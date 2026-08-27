package vn.unlimit.vpngate.models

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

data class VPNGateHtmlResponse(
    @SerializedName("generatedAt") val generatedAt: String? = null,
    @SerializedName("source") val source: String? = null,
    @SerializedName("count") val count: Int = 0,
    @SerializedName("servers") val servers: List<VPNGateHtmlServer> = emptyList()
)

data class VPNGateHtmlServer(
    @SerializedName("hostname") val hostname: String? = null,
    @SerializedName("ip") val ip: String? = null,
    @SerializedName("country") val country: String? = null,
    @SerializedName("countryLong") val countryLong: String? = null,
    @SerializedName("sessions") val sessions: Int = 0,
    @SerializedName("uptime") val uptime: Int = 0,
    @SerializedName("totalUsers") val totalUsers: Int = 0,
    @SerializedName("score") val score: Int = 0,
    @SerializedName("ping") val ping: Int = 0,
    @SerializedName("speed") val speed: Int = 0,
    @SerializedName("softEther") val softEther: SoftEtherPorts? = null,
    @SerializedName("openVPN") val openVPN: OpenVPNPorts? = null,
    @SerializedName("l2tp") val l2tp: Boolean = false,
    @SerializedName("sstp") val sstp: SstpInfo? = null,
    @SerializedName("sources") val sources: List<String> = emptyList(),
    @SerializedName("sourceCount") val sourceCount: Int = 0,
    @SerializedName("valid") val valid: Boolean = false,
    @SerializedName("qualityScore") val qualityScore: Int = 0
) {
    companion object {
        fun parseHtmlJson(json: String): List<VPNGateHtmlServer> {
            return try {
                val response = Gson().fromJson(json, VPNGateHtmlResponse::class.java)
                response.servers.filter { it.valid }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

data class SoftEtherPorts(
    @SerializedName("tcp") val tcp: Int = 0,
    @SerializedName("udp") val udp: Boolean = false
)

data class OpenVPNPorts(
    @SerializedName("tcp") val tcp: Int = 0,
    @SerializedName("udp") val udp: Int = 0
)

data class SstpInfo(
    @SerializedName("host") val host: String? = null,
    @SerializedName("port") val port: Int = 0
)
