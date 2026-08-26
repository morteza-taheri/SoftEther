package vn.unlimit.vpngate.utils

/**
 * Local configuration values for the app.
 *
 * These values are used instead of a cloud/remote config service so the app
 * works fully offline without any external dependency.
 */
object AppConfig {
    /**
     * Returns the string value for the given key.
     */
    fun getString(key: String): String {
        return when (key) {
            "vpn_dns_block_ads_primary" -> "176.103.130.130"
            "vpn_dns_block_ads_alternative" -> "176.103.130.131"
            "vpn_detail_open_ads_interval" -> "30"
            "vpn_alternative_api" -> "https://www.vpngate.net"
            "vpn_udp_api_v2" -> "https://www.vpngate.net/api/iphone/"
            "vpn_check_ip_url" -> "https://whatismyipaddress.com/"
            "vpn_paid_server_api" -> "https://www.vpngate.net/api/"
            "vpn_import_open_vpn" -> "false"
            "vpn_header_session_name" -> "vpn_header_session_name"
            "vpn_paid_skus" ->
                "[\"vn.unlimit.vpngate.2gb\",\"vn.unlimit.vpngate.5gb\"," +
                    "\"vn.unlimit.vpngate.10gb\",\"vn.unlimit.vpngate.15gb\"]"
            "vpn_paid_skus_pro_ver" ->
                "[\"vn.unlimit.vpngatepro.2gb\",\"vn.unlimit.vpngatepro.5gb\"," +
                    "\"vn.unlimit.vpngatepro.10gb\",\"vn.unlimit.vpngatepro.15gb\"]"
            else -> ""
        }
    }

    fun getBoolean(key: String): Boolean {
        return when (key) {
            "invite_paid_server" -> true
            "vpn_import_open_vpn" -> false
            else -> false
        }
    }
}