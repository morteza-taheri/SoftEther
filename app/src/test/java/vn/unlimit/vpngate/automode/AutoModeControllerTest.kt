package vn.unlimit.vpngate.automode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the Auto Mode orchestration core, covering the ten
 * product scenarios (§29).
 */
class AutoModeControllerTest {

    private class FakeAdapter : AutoModeController.ConnectionAdapter {
        val logs = mutableListOf<String>()
        val failing = mutableSetOf<String>()
        var delayMs: Long = 0

        override suspend fun connect(candidate: AutoModeCandidate, protocol: AutoModeProtocol) {
            logs += "connect:${candidate.hostname}"
        }

        override suspend fun disconnect() {
            logs += "disconnect"
        }

        override suspend fun awaitTunnel(protocol: AutoModeProtocol, timeoutMs: Long): Boolean {
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            val last = logs.lastOrNull { it.startsWith("connect:") } ?: return false
            return last.removePrefix("connect:") !in failing
        }

        override fun log(message: String) {
            logs += message
        }
    }

    private lateinit var scope: CoroutineScope
    private lateinit var adapter: FakeAdapter

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        adapter = FakeAdapter()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun controller(
        protocol: AutoModeProtocol = AutoModeProtocol.SOFTETHER_UDP,
        servers: List<AutoModeCandidate>,
        onSuccess: (AutoModeCandidate, AutoModeProtocol) -> Unit = { _, _ -> },
        timeout: Long = 500,
    ) = AutoModeController(
        scope = scope,
        adapter = adapter,
        protocolProvider = { protocol },
        serverProvider = { servers },
        onSuccess = onSuccess,
        attemptTimeoutMs = timeout,
    )

    private fun server(
        host: String,
        speed: Long = 10_000_000,
        ping: Int = 50,
        sessions: Int = 100,
        score: Int = 1000,
        sources: Int = 2,
        seTcp: Int = 443,
        seUdp: Boolean = true,
    ) = AutoModeCandidate(
        hostname = host, ip = "10.0.0.${host.length}", speed = speed, ping = ping,
        sessions = sessions, score = score, sources = sources,
        seTcpPort = seTcp, seUdpPort = 0, seUdpSupported = seUdp,
        openVpnTcpPort = 0, openVpnUdpPort = 0,
        l2tpSupported = false, sstpSupported = false,
    )

    private suspend fun AutoModeController.awaitTerminal(): AutoModeState {
        while (true) {
            val s = state.value
            // Wait until we hit a terminal state AND the run has fully completed
            // (job == null or job not active), so a subsequent start() launches a fresh run.
            if ((s is AutoModeState.Connected || s is AutoModeState.Error) && !isRunning) {
                return s
            }
            kotlinx.coroutines.delay(25)
        }
    }

    // Test 1: initial state is Disconnected
    @Test
    fun initialStateIsDisconnected() {
        val c = controller(servers = emptyList())
        assertTrue(c.state.value is AutoModeState.Disconnected)
        assertTrue(!c.isRunning)
    }

    // Test 2 + 3: press → Connecting → first server succeeds → Connected
    @Test
    fun firstServerSuccess() = runBlocking {
        val c = controller(servers = listOf(server("s1"), server("s2")))
        c.start()
        val final = c.awaitTerminal()
        assertTrue(final is AutoModeState.Connected)
        assertEquals("s1", (final as AutoModeState.Connected).hostname)
        assertTrue(adapter.logs.contains("connect:s1"))
        assertTrue(!adapter.logs.contains("disconnect"))
    }

    // Test 4: sequential retries until server 3 succeeds
    @Test
    fun retriesUntilSuccess() = runBlocking {
        adapter.failing.addAll(listOf("s1", "s2"))
        val c = controller(servers = listOf(server("s1"), server("s2"), server("s3")))
        c.start()
        val final = c.awaitTerminal()
        assertTrue(final is AutoModeState.Connected)
        assertEquals("s3", (final as AutoModeState.Connected).hostname)
        assertTrue(adapter.logs.contains("connect:s1"))
        assertTrue(adapter.logs.contains("connect:s2"))
        assertTrue(adapter.logs.contains("connect:s3"))
    }

    // Test 5: user stop during connecting — no further attempts
    @Test
    fun stopDuringConnectingStopsEverything() = runBlocking {
        adapter.delayMs = 10_000
        val c = controller(servers = listOf(server("s1"), server("s2")))
        c.start()
        repeat(100) {
            if (c.state.value is AutoModeState.Connecting) return@repeat
            kotlinx.coroutines.delay(10)
        }
        c.stop()
        assertEquals(AutoModeState.Disconnected, c.state.value)
        assertTrue(!c.isRunning)
        kotlinx.coroutines.delay(100)
        assertTrue(adapter.logs.none { it == "connect:s2" })
    }

    // Test 6: all servers fail → Error
    @Test
    fun allServersFailYieldsError() = runBlocking {
        adapter.failing.addAll(listOf("s1", "s2"))
        val c = controller(servers = listOf(server("s1"), server("s2")))
        c.start()
        assertTrue(c.awaitTerminal() is AutoModeState.Error)
    }

    // Test 7: restart from Error works from scratch
    @Test
    fun restartAfterError() = runBlocking {
        adapter.failing.add("s1")
        val c = controller(servers = listOf(server("s1")))
        c.start()
        assertTrue(c.awaitTerminal() is AutoModeState.Error)
        adapter.failing.clear()
        c.onButtonPressed()
        assertTrue(c.awaitTerminal() is AutoModeState.Connected)
    }

    // Test 8: protocol filter — only OpenVPN-UDP-capable servers tried
    @Test
    fun protocolFilterOpenVpnUdp() = runBlocking {
        val se = server("se-server")
        val ovpn = AutoModeCandidate(
            hostname = "ovpn-server", ip = "10.0.0.9", speed = 5_000_000, ping = 60,
            sessions = 10, score = 900, sources = 1,
            seTcpPort = 0, seUdpPort = 0, seUdpSupported = false,
            openVpnTcpPort = 0, openVpnUdpPort = 1194,
            l2tpSupported = false, sstpSupported = false,
        )
        val c = controller(protocol = AutoModeProtocol.OPENVPN_UDP, servers = listOf(se, ovpn))
        val filtered = c.compatibleServers(listOf(se, ovpn), AutoModeProtocol.OPENVPN_UDP)
        assertEquals(listOf("ovpn-server"), filtered.map { it.hostname })
        c.start()
        assertTrue(c.awaitTerminal() is AutoModeState.Connected)
        assertTrue(adapter.logs.contains("connect:ovpn-server"))
        assertTrue(adapter.logs.none { it == "connect:se-server" })
    }

    // Test 9: rapid double press does not create two jobs
    @Test
    fun doublePressSingleJob() = runBlocking {
        adapter.delayMs = 300
        val c = controller(servers = listOf(server("s1")))
        c.start()
        c.start()
        kotlinx.coroutines.delay(50)
        assertEquals(1, adapter.logs.count { it.startsWith("connect:") })
        c.stop()
    }

    // Test 10: pressing while Connected goes back to Disconnected
    @Test
    fun pressWhileConnectedDisconnects() = runBlocking {
        val c = controller(servers = listOf(server("s1")))
        c.start()
        assertTrue(c.awaitTerminal() is AutoModeState.Connected)
        c.onButtonPressed()
        assertEquals(AutoModeState.Disconnected, c.state.value)
        assertTrue(!c.isRunning)
    }

    // §7 per-protocol capability rules
    @Test
    fun capabilityRules() {
        val c = controller(servers = emptyList())
        val seTcpOnly = server("a", seTcp = 443, seUdp = false)
        val seUdpOnly = AutoModeCandidate(
            "b", "ip", 1_000, 10, 5, 500, 1,
            seTcpPort = 0, seUdpPort = 0, seUdpSupported = true,
            openVpnTcpPort = 0, openVpnUdpPort = 0, l2tpSupported = false, sstpSupported = false,
        )
        val l2tp = AutoModeCandidate(
            "c", "ip", 1_000, 10, 5, 500, 1,
            seTcpPort = 0, seUdpPort = 0, seUdpSupported = false,
            openVpnTcpPort = 0, openVpnUdpPort = 0, l2tpSupported = true, sstpSupported = false,
        )
        val sstp = AutoModeCandidate(
            "d", "ip", 1_000, 10, 5, 500, 1,
            seTcpPort = 0, seUdpPort = 0, seUdpSupported = false,
            openVpnTcpPort = 0, openVpnUdpPort = 0, l2tpSupported = false, sstpSupported = true,
        )
        val all = listOf(seTcpOnly, seUdpOnly, l2tp, sstp)
        assertEquals(listOf("a"), c.compatibleServers(all, AutoModeProtocol.SOFTETHER_TCP).map { it.hostname })
        assertEquals(listOf("b"), c.compatibleServers(all, AutoModeProtocol.SOFTETHER_UDP).map { it.hostname })
        assertEquals(listOf("c"), c.compatibleServers(all, AutoModeProtocol.L2TP_IPSEC).map { it.hostname })
        assertEquals(listOf("d"), c.compatibleServers(all, AutoModeProtocol.MS_SSTP).map { it.hostname })
    }

    // §8 ordering: speed desc, tie-breakers, zero-speed demoted (§23)
    @Test
    fun orderingByQuality() {
        val c = controller(servers = emptyList())
        val fast = server("fast", speed = 90_000_000, ping = 100)
        val fastLowPing = server("fastLowPing", speed = 90_000_000, ping = 20)
        val slow = server("slow", speed = 1_000_000)
        val zero = server("zero", speed = 0)
        val sorted = c.compatibleServers(listOf(zero, slow, fast, fastLowPing), AutoModeProtocol.SOFTETHER_UDP)
        assertEquals(listOf("fastLowPing", "fast", "slow", "zero"), sorted.map { it.hostname })
    }
}
