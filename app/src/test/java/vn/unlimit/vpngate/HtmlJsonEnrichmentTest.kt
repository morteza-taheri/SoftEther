package vn.unlimit.vpngate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.models.VPNGateConnection
import vn.unlimit.vpngate.models.VPNGateHtmlServer

class HtmlJsonEnrichmentTest {

    private val sampleJson = """
    {
      "generatedAt": "2026-08-26T22:55:02Z",
      "source": "VPN Gate multi-source collector",
      "count": 3,
      "servers": [
        {
          "hostname": "us123",
          "ip": "1.2.3.4",
          "country": "US",
          "countryLong": "United States",
          "sessions": 5,
          "uptime": 93,
          "totalUsers": 1000,
          "score": 7500000,
          "ping": 45,
          "speed": 100000000,
          "softEther": { "tcp": 443, "udp": true },
          "openVPN": { "tcp": 443, "udp": 443 },
          "l2tp": true,
          "sstp": { "host": "us123.opengw.net", "port": 443 },
          "sources": ["html", "api"],
          "sourceCount": 2,
          "valid": true,
          "qualityScore": 85
        },
        {
          "hostname": "jp456",
          "ip": "5.6.7.8",
          "country": "JP",
          "countryLong": "Japan",
          "sessions": 3,
          "uptime": 50,
          "totalUsers": 500,
          "score": 5000000,
          "ping": 120,
          "speed": 50000000,
          "softEther": { "tcp": 555, "udp": false },
          "openVPN": { "tcp": 0, "udp": 1194 },
          "l2tp": false,
          "sstp": null,
          "sources": ["html"],
          "sourceCount": 1,
          "valid": true,
          "qualityScore": 60
        },
        {
          "hostname": "de789",
          "ip": "9.10.11.12",
          "country": "DE",
          "countryLong": "Germany",
          "sessions": 10,
          "uptime": 200,
          "totalUsers": 2000,
          "score": 9000000,
          "ping": 30,
          "speed": 200000000,
          "softEther": { "tcp": 0, "udp": false },
          "openVPN": { "tcp": 0, "udp": 0 },
          "l2tp": false,
          "sstp": { "host": "", "port": 0 },
          "sources": ["api"],
          "sourceCount": 1,
          "valid": false,
          "qualityScore": 40
        }
      ]
    }
    """.trimIndent()

    @Test
    fun parseHtmlJson_parsesValidServersOnly() {
        val servers = VPNGateHtmlServer.parseHtmlJson(sampleJson)
        assertEquals(2, servers.size)
        assertEquals("us123", servers[0].hostname)
        assertEquals("jp456", servers[1].hostname)
        assertEquals(443, servers[0].softEther?.tcp)
        assertEquals(true, servers[0].softEther?.udp)
        assertEquals(true, servers[0].l2tp)
        assertEquals(443, servers[0].sstp?.port)
    }

    @Test
    fun enrichFromHtmlServer_setsAllPortsAndFlags() {
        val servers = VPNGateHtmlServer.parseHtmlJson(sampleJson)
        val html = servers[0]

        val conn = VPNGateConnection()
        conn.hostName = "us123"
        conn.ip = "1.2.3.4"

        conn.enrichFromHtmlServer(html)

        assertEquals(443, conn.seTcpPort)
        assertEquals(443, conn.seUdpPort)
        assertEquals(443, conn.tcpPort)
        assertEquals(443, conn.udpPort)
        assertEquals(1, conn.isL2TPSupport)
        assertEquals(1, conn.isSSTPSupport)
    }

    @Test
    fun enrichFromHtmlServer_partialData_onlySetsAvailableFields() {
        val servers = VPNGateHtmlServer.parseHtmlJson(sampleJson)
        val html = servers[1]

        val conn = VPNGateConnection()
        conn.hostName = "jp456"
        conn.ip = "5.6.7.8"

        conn.enrichFromHtmlServer(html)

        assertEquals(555, conn.seTcpPort)
        assertEquals(0, conn.seUdpPort)
        assertEquals(0, conn.tcpPort)
        assertEquals(1194, conn.udpPort)
        assertEquals(0, conn.isL2TPSupport)
        assertEquals(0, conn.isSSTPSupport)
    }

    @Test
    fun enrichFromHtmlServer_doesNotOverrideExistingValues() {
        val servers = VPNGateHtmlServer.parseHtmlJson(sampleJson)
        val html = servers[0]

        val conn = VPNGateConnection()
        conn.hostName = "us123"
        conn.ip = "1.2.3.4"
        conn.tcpPort = 500
        conn.isL2TPSupport = 1

        conn.enrichFromHtmlServer(html)

        assertEquals(500, conn.tcpPort)
        assertEquals(443, conn.udpPort)
        assertEquals(1, conn.isL2TPSupport)
    }

    @Test
    fun parseHtmlJson_invalidJson_returnsEmpty() {
        val servers = VPNGateHtmlServer.parseHtmlJson("not valid json")
        assertTrue(servers.isEmpty())
    }
}
