package vn.unlimit.vpngate.automode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Auto Mode orchestration core (§27): a thin layer on top of the app's
 * existing connection stack. It never opens tunnels itself — the
 * [ConnectionAdapter] drives the real services — it only filters and
 * orders candidates, tries them one by one (§9), enforces one-attempt-
 * per-server (§10), waits for a *verified tunnel* (§14) with a
 * timeout (§11) and exposes a StateFlow for the UI.
 *
 * Pure JVM: unit-testable without Android.
 */
class AutoModeController(
    private val scope: CoroutineScope,
    private val adapter: ConnectionAdapter,
    /** §24 protocol is read from Settings at the moment of start. */
    private val protocolProvider: () -> AutoModeProtocol,
    /** Candidate list source (the in-app collector repository cache). */
    private val serverProvider: suspend () -> List<AutoModeCandidate>,
    /** Called when a tunnel is verified so the caller can persist it (§19). */
    private val onSuccess: (AutoModeCandidate, AutoModeProtocol) -> Unit = { _, _ -> },
    /** Per-attempt timeout in ms (§11). */
    private val attemptTimeoutMs: Long = DEFAULT_ATTEMPT_TIMEOUT_MS,
) {
    interface ConnectionAdapter {
        /** Initiate the connection for [candidate] via [protocol]. */
        suspend fun connect(candidate: AutoModeCandidate, protocol: AutoModeProtocol)

        /** Clean up any half-open tunnel (§15). */
        suspend fun disconnect()

        /**
         * Wait until the VPN tunnel is genuinely up (§14) or the
         * timeout elapses. Returns true only for a verified tunnel.
         * Must be cancellation-cooperative (§12).
         */
        suspend fun awaitTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean

        /** Debug logging sink (§26). */
        fun log(message: String)
    }

    companion object {
        const val DEFAULT_ATTEMPT_TIMEOUT_MS = 25_000L
        const val ERROR_NO_SERVER = "no_compatible_server"
        const val ERROR_VPN_PERMISSION = "vpn_permission_missing"
    }

    /**
     * Raised by the connection adapter when the OS-level VPN permission
     * is not granted — retrying other servers is pointless, the run must
     * stop with a dedicated error (Auto Mode §12/§18).
     */
    class VpnPermissionMissingException : RuntimeException("VPN permission not granted")

    private val _state = MutableStateFlow<AutoModeState>(AutoModeState.Disconnected)
    val state: StateFlow<AutoModeState> = _state

    /** §13 single-job guard. */
    var job: Job? = null
        private set

    val isRunning: Boolean
        get() = job?.isActive == true

    /**
     * User-requested skip of the current server (Try next server button):
     * aborts the in-flight attempt and lets the run continue with the next
     * candidate. Idempotent and safe in every state — outside an active
     * Connecting attempt it just logs and returns.
     */
    private val skipRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    fun skipToNextServer() {
        if (!isRunning || _state.value !is AutoModeState.Connecting) {
            adapter.log("[AUTO] Skip requested but no attempt in flight")
            return
        }
        adapter.log("[AUTO] Skipping current server; trying next")
        skipRequested.set(true)
        adapterSkipSignal?.invoke()
    }

    /** Lets the adapter interrupt a blocked connect/await (e.g. cancel the service attempt). */
    private var adapterSkipSignal: (() -> Unit)? = null

    fun setSkipSignal(signal: (() -> Unit)?) {
        adapterSkipSignal = signal
    }

    fun start() {
        if (isRunning) {
            adapter.log("[AUTO] start ignored: already running")
            return
        }
        job = scope.launch { runAutoMode() }
    }

    /** §12: cancel everything; the loop stops at the next suspension point. */
    fun stop() {
        if (!isRunning) return
        adapter.log("[AUTO] User requested stop")
        job?.cancel()
        job = null
        _state.value = AutoModeState.Disconnected
    }

    /** §3 button semantics across the four states. */
    fun onButtonPressed() {
        when (_state.value) {
            is AutoModeState.Disconnected, is AutoModeState.Error -> start()
            is AutoModeState.Connecting -> stop()
            is AutoModeState.Connected -> disconnectNow()
        }
    }

        /** §3/§29-Test10: pressing while Connected disconnects via the normal flow. */
    fun disconnectNow() {
        if (isRunning) {
            // Cancel the active run, then clean up the tunnel.
            job?.cancel()
            job = null
            scope.launch { adapter.disconnect() }
        } else {
            scope.launch { adapter.disconnect() }
        }
        _state.value = AutoModeState.Disconnected
    }

    /** §7 filter + §8 ordering. Visible for tests. */
    fun compatibleServers(
        all: List<AutoModeCandidate>,
        protocol: AutoModeProtocol,
    ): List<AutoModeCandidate> = all
        .filter { protocol.supports(it) }
        .sortedWith(byQuality)

            private suspend fun runAutoMode() {
        val protocol = protocolProvider()
        adapter.log("[AUTO] Started")
        adapter.log("[AUTO] Protocol = ${protocol.id}")
        adapter.log("[AUTO] Connection timeout = ${attemptTimeoutMs / 1000} seconds")

        val all = serverProvider()
        val servers = compatibleServers(all, protocol)
        adapter.log("[AUTO] Servers available = ${all.size}")
        adapter.log("[AUTO] Compatible servers = ${servers.size}")
        adapter.log("[AUTO] Sorted by quality")

        if (servers.isEmpty()) {
            adapter.log("[AUTO] No compatible server found")
            _state.value = AutoModeState.Error(ERROR_NO_SERVER)
            return
        }

        val attempted = mutableSetOf<String>() // §10
        var attempt = 0

        for (server in servers) {
            if (!currentCoroutineContext().isActive) return // §12 stop

            val key = "${server.ip}|${server.hostname ?: ""}"
            if (!attempted.add(key)) continue
            attempt++

            adapter.log("[AUTO] Trying #$attempt ${server.hostname ?: server.ip}")
            skipRequested.set(false)
            _state.value = AutoModeState.Connecting(
                hostname = server.hostname,
                ip = server.ip,
                protocol = protocol,
                speed = server.speed,
                ping = server.ping,
                attempt = attempt,
                total = servers.size,
            )

            try {
                adapter.connect(server, protocol)
            } catch (e: VpnPermissionMissingException) {
                // Without the OS VPN permission no server can ever connect.
                adapter.log("[AUTO] VPN permission missing; stopping Auto Mode")
                adapter.disconnect()
                _state.value = AutoModeState.Error(ERROR_VPN_PERMISSION)
                job = null
                return
            } catch (e: Exception) {
                adapter.log("[AUTO] Connect threw ${e.javaClass.simpleName}: ${e.message}")
                adapter.disconnect() // §15 cleanup before next attempt
                continue
            }
            val connected = adapter.awaitTunnel(protocol, attemptTimeoutMs)

            if (!currentCoroutineContext().isActive) return // §12 stop

            if (skipRequested.get()) {
                // User pressed Try next server: clean up and move on without
                // counting this as a terminal outcome.
                adapter.log("[AUTO] Server skipped by user")
                skipRequested.set(false)
                adapter.disconnect() // §15 cleanup before next attempt
                continue
            }

            if (connected) {
                adapter.log("[AUTO] Tunnel established")
                adapter.log("[AUTO] Connected successfully")
                onSuccess(server, protocol)
                _state.value = AutoModeState.Connected(
                    hostname = server.hostname,
                    ip = server.ip,
                    protocol = protocol,
                    speed = server.speed,
                    ping = server.ping,
                )
                return
            }

            adapter.log("[AUTO] Connection failed: ${server.hostname ?: server.ip}")
            adapter.disconnect() // §15 cleanup before next attempt
        }

        adapter.log("[AUTO] All compatible servers failed")
        _state.value = AutoModeState.Error(ERROR_NO_SERVER) // §18
        job = null // terminal state: the run is over; next press restarts cleanly
    }

    /** §8: Speed DESC first, ping/sessions/score/sources as tie-breakers; §23 zero-speed demoted. */
    private val byQuality =
        compareByDescending<AutoModeCandidate> { it.speed > 0 }
            .thenByDescending { it.speed }
            .thenBy { it.ping }
            .thenBy { it.sessions }
            .thenByDescending { it.score }
            .thenByDescending { it.sources }

    /** §7 capability rules. */
    private fun AutoModeProtocol.supports(c: AutoModeCandidate): Boolean = when (this) {
        AutoModeProtocol.SOFTETHER_TCP -> c.seTcpPort > 0
        AutoModeProtocol.SOFTETHER_UDP -> c.seUdpSupported || c.seUdpPort > 0
        AutoModeProtocol.OPENVPN_TCP -> c.openVpnTcpPort > 0
        AutoModeProtocol.OPENVPN_UDP -> c.openVpnUdpPort > 0
        AutoModeProtocol.L2TP_IPSEC -> c.l2tpSupported
        AutoModeProtocol.MS_SSTP -> c.sstpSupported
    }
}

