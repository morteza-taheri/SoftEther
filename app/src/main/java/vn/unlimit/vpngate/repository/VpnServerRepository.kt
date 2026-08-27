package vn.unlimit.vpngate.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import vn.unlimit.vpngate.data.model.CollectorLog
import vn.unlimit.vpngate.data.model.VpnServerRecord
import vn.unlimit.vpngate.data.remote.HttpFetcher
import vn.unlimit.vpngate.data.remote.OkHttpFetcher
import vn.unlimit.vpngate.data.remote.VpnGateApiSource
import vn.unlimit.vpngate.data.remote.VpnGateHtmlSource
import vn.unlimit.vpngate.data.remote.VpnGateMirrorSource
import vn.unlimit.vpngate.merger.VpnServerMerger
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.models.VPNGateConnectionList
import vn.unlimit.vpngate.parser.VpnGateApiParser
import vn.unlimit.vpngate.parser.VpnGateHtmlParser
import vn.unlimit.vpngate.ranking.ServerQualityCalculator
import java.io.File

/**
 * In-app multi-source collector (Mode B): the app collects server
 * data itself from the VPN Gate main HTML + official API + official
 * mirrors, then merges/validates/scores with the oracle-ported
 * engine. Replaces the former GitHub-hosted JSON enrichment.
 *
 * All networking runs via coroutines on IO (never the main thread);
 * each request has its own timeout; mirrors are fetched sequentially
 * (the fetcher rate-limits); a timestamped JSON snapshot provides a
 * last-known-good fallback.
 */
class VpnServerRepository(
    private val fetcher: HttpFetcher = OkHttpFetcher(),
    private val cacheDir: File? = null,
) {
    companion object {
        private const val CACHE_FILE = "collector_vpn_servers.json"
        private const val MAIN_TIMEOUT_MS = 45_000L
        private const val MIRROR_TIMEOUT_MS = 30_000L
    }

    data class CollectResult(
        val connectionList: VPNGateConnectionList,
        val serverCount: Int,
        val fromCache: Boolean,
    )

    private val gson = Gson()
    private val htmlSource = VpnGateHtmlSource(fetcher)
    private val apiSource = VpnGateApiSource(fetcher)
    private val mirrorSource = VpnGateMirrorSource(fetcher)

    /**
     * Network-first collection: main HTML + API in parallel, mirrors
     * sequentially, then merge -> validate -> score -> map. On total
     * failure falls back to the last-known-good snapshot (if any).
     */
    suspend fun refresh(): CollectResult? = withContext(Dispatchers.IO) {
        val records = coroutineScope {
            val htmlDeferred = async {
                withTimeoutOrNull(MAIN_TIMEOUT_MS) { htmlSource.fetch() }
            }
            val apiDeferred = async {
                withTimeoutOrNull(MAIN_TIMEOUT_MS) { apiSource.fetch() }
            }
            val mirrorsDeferred = async {
                withTimeoutOrNull(MAIN_TIMEOUT_MS) { mirrorSource.discoverMirrors() }
            }

            val html = htmlDeferred.await()
            val apiText = apiDeferred.await()
            val mirrors = mirrorsDeferred.await().orEmpty()

            CollectorLog.d(
                "Sources: html=${html != null} api=${apiText != null} mirrors=${mirrors.size}"
            )

            val collected = mutableListOf<VpnServerRecord>()

            html?.let {
                runCatching { collected.addAll(VpnGateHtmlParser.parseHtml(it, "html")) }
                    .onFailure { e -> CollectorLog.d("HTML parse failed: ${e.message}") }
            }
            apiText?.let {
                runCatching { collected.addAll(VpnGateApiParser.parseApi(it, "api")) }
                    .onFailure { e -> CollectorLog.d("API parse failed: ${e.message}") }
            }

            // Mirrors fetched one by one (rate-limited by the fetcher).
            for ((index, mirrorUrl) in mirrors.withIndex()) {
                val mirrorHtml = withTimeoutOrNull(MIRROR_TIMEOUT_MS) {
                    mirrorSource.fetchMirror(mirrorUrl)
                } ?: continue

                runCatching {
                    collected.addAll(VpnGateHtmlParser.parseHtml(mirrorHtml, "mirror_${index + 1}"))
                }
            }

            collected
        }

        if (records.isEmpty()) {
            CollectorLog.d("No records collected; trying last-known-good snapshot")
            return@withContext loadSnapshot()
        }

        val merged = VpnServerMerger.mergeRecords(records)
        val valid = VpnServerMerger.validateServers(merged)

        if (valid.isEmpty()) {
            CollectorLog.d("No valid servers after merge; trying last-known-good snapshot")
            return@withContext loadSnapshot()
        }

        ServerQualityCalculator.scoreAll(valid)

        val connectionList = VpnConnectionMapper.toConnectionList(valid)
        if (connectionList.size() == 0) {
            return@withContext loadSnapshot()
        }

        saveSnapshot(connectionList)
        CollectResult(connectionList, valid.size, fromCache = false)
    }

    private fun cacheFile(): File? = cacheDir?.let { File(it, CACHE_FILE) }

    private fun saveSnapshot(list: VPNGateConnectionList) {
        val file = cacheFile() ?: return
        try {
            val servers = (0 until list.size()).map { list.get(it) }
            val payload = linkedMapOf(
                "savedAt" to System.currentTimeMillis(),
                "count" to servers.size,
                "servers" to servers,
            )
            file.writeText(gson.toJson(payload), Charsets.UTF_8)
            CollectorLog.d("Snapshot saved: ${servers.size} servers")
        } catch (e: Exception) {
            CollectorLog.d("Snapshot save failed: ${e.message}")
        }
    }

    private fun loadSnapshot(): CollectResult? {
        val file = cacheFile() ?: return null
        if (!file.isFile) return null

        return try {
            val json = file.readText(Charsets.UTF_8)
            val savedAt = runCatching {
                gson.fromJson<Map<String, Any?>>(
                    json,
                    object : TypeToken<Map<String, Any?>>() {}.type,
                )["savedAt"] as? Number
            }.getOrNull()?.toLong() ?: 0L

            val servers: List<VPNGateConnection> = run {
                val element = com.google.gson.JsonParser.parseString(json).asJsonObject
                val array = element.getAsJsonArray("servers") ?: return null
                gson.fromJson(
                    array,
                    object : TypeToken<List<VPNGateConnection>>() {}.type,
                )
            }

            if (servers.isEmpty()) return null

            val list = VPNGateConnectionList()
            servers.forEach { list.add(it) }

            CollectorLog.d("Loaded last-known-good snapshot (savedAt=$savedAt): ${servers.size}")
            CollectResult(list, servers.size, fromCache = true)
        } catch (e: Exception) {
            CollectorLog.d("Snapshot load failed: ${e.message}")
            null
        }
    }
}
