package com.example.clashforlsposed

import org.junit.Test

import org.junit.Assert.*
import com.example.clashforlsposed.proxy.ProxyConfigStore
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun shareLinksBecomeNativeMihomoProxies() {
        val parsed = ProxyConfigStore.parseSource(
            "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@example.com:443#demo\n" +
                "vless://uuid@example.org:443?security=tls#second"
        )

        assertTrue(parsed.config.isEmpty())
        assertEquals(2, parsed.shareLinks.size)
        val proxies = ProxyConfigStore.parseShareLinks(parsed.shareLinks)
        assertEquals(2, proxies.size)
        assertEquals("ss", proxies[0]["type"])
        assertEquals("vless", proxies[1]["type"])
        assertEquals("uuid", proxies[1]["uuid"])
    }

    @Test
    fun vmessAndTrojanMapCredentialsWithoutLeakingLinkText() {
        val vmessPayload = java.util.Base64.getEncoder().encodeToString(
            "{\"v\":\"2\",\"ps\":\"vmess-node\",\"add\":\"vmess.example\",\"port\":443,\"id\":\"uuid-1\",\"aid\":0,\"scy\":\"auto\",\"net\":\"ws\",\"path\":\"/proxy\",\"host\":\"cdn.example\",\"tls\":\"tls\"}"
                .toByteArray()
        )
        val proxies = ProxyConfigStore.parseShareLinks(
            listOf(
                "vmess://$vmessPayload",
                "trojan://secret%3Apass@trojan.example:443?sni=trojan.example#trojan-node"
            )
        )

        assertEquals("vmess-node", proxies[0]["name"])
        assertEquals("ws", proxies[0]["network"])
        assertEquals("secret:pass", proxies[1]["password"])
        assertTrue(proxies[1]["uuid"] == null)
    }

    @Test
    fun modernVmessAndHysteriaLinksMatchFlClashOutput() {
        val proxies = ProxyConfigStore.parseShareLinks(
            listOf(
                "vmess://u@c.example:443?security=tls&sni=sni.example&fp=chrome&type=ws&path=%2Fray&host=host.example&ed=2048#vmess-aead",
                "hysteria://hy.example:443?auth=token&peer=hy.example&protocol=udp&up=30+Mbps&down=200+Mbps&insecure=1&obfs=obfs#hy"
            )
        )

        assertEquals("u", proxies[0]["uuid"])
        assertEquals(0, proxies[0]["alterId"])
        assertEquals(true, proxies[0]["tls"])
        assertEquals("chrome", proxies[0]["client-fingerprint"])
        assertEquals(2048, (proxies[0]["ws-opts"] as Map<*, *>)["max-early-data"])
        assertEquals("token", proxies[1]["auth-str"])
        assertEquals("hy.example", proxies[1]["sni"])
        assertEquals("30 Mbps", proxies[1]["up"])
        assertEquals(true, proxies[1]["skip-cert-verify"])
    }

    @Test
    fun base64MultiNodeSubscriptionsSupportDuplicateNamesAndSsr() {
        val encodedPassword = java.util.Base64.getEncoder().encodeToString("secret".toByteArray())
        val encodedRemark = java.util.Base64.getEncoder().encodeToString("same".toByteArray())
        val ssrPayload = java.util.Base64.getEncoder().encodeToString(
            "ssr.example:443:auth_sha1_v4:aes-128-ctr:tls1.2_ticket_auth:$encodedPassword/?remarks=$encodedRemark"
                .toByteArray()
        )
        val content = "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@ss.example:443#same\n" +
            "ssr://$ssrPayload\n" +
            "socks5://user:p%40ss@socks.example:1080#same"
        val subscription = java.util.Base64.getEncoder().encodeToString(content.toByteArray())

        val parsed = ProxyConfigStore.parseSource(subscription)
        val proxies = ProxyConfigStore.parseShareLinks(parsed.shareLinks)

        assertEquals(3, proxies.size)
        assertEquals(listOf("same", "same 2", "same 3"), proxies.map { it["name"] })
        assertEquals("ssr", proxies[1]["type"])
        assertEquals("secret", proxies[1]["password"])
        assertEquals("socks5", proxies[2]["type"])
        assertEquals("p@ss", proxies[2]["password"])
    }

    @Test
    fun urlSchemeDetectionDoesNotParseTheWholeSubscriptionUrl() {
        assertEquals(
            "https",
            ProxyConfigStore.schemeOf("https://订阅.example/config?token=a|b")
        )
        assertEquals("vless", ProxyConfigStore.schemeOf("vless://uuid@example.com:443"))
        assertEquals("proxies", ProxyConfigStore.schemeOf("proxies:\n  - name: node"))
    }

    @Test
    fun yamlParserAcceptsConfigsUpToTheImportSizeLimit() {
        val value = "x".repeat(3 * 1024 * 1024 + 32)
        val parsed = ProxyConfigStore.parseSource("payload: $value")

        assertEquals(value.length, parsed.config["payload"].toString().length)
    }

    @Test
    fun htmlMimeDoesNotRejectBase64SubscriptionBody() {
        val links = "vless://uuid@example.com:443#node"
        val encoded = java.util.Base64.getEncoder().encodeToString(links.toByteArray())

        assertFalse(ProxyConfigStore.looksLikeHtml(encoded))
        assertEquals(1, ProxyConfigStore.parseSource(encoded).shareLinks.size)
        assertTrue(ProxyConfigStore.looksLikeHtml("<!doctype html><html></html>"))
    }

    @Test
    fun flClashLegacyShareSchemesAndWhitespaceBatchesAreAccepted() {
        val links = listOf(
            "hysteria://secret@hysteria.example:443#old-hy",
            "socks5h://user:pass@socks.example:1080#remote-dns",
            "anytls://password@anytls.example:443?sni=anytls.example#anytls",
            "mierus://user:pass@mieru.example:443#mieru",
            "hysteria2+realm://secret@realm.example:443#realm"
        )
        val parsed = ProxyConfigStore.parseSource(links.joinToString(" "))
        val proxies = ProxyConfigStore.parseShareLinks(parsed.shareLinks)

        assertEquals(5, proxies.size)
        assertEquals("hysteria", proxies[0]["type"])
        assertEquals("socks5", proxies[1]["type"])
        assertEquals("anytls", proxies[2]["type"])
        assertEquals("mieru", proxies[3]["type"])
        assertEquals("hysteria2", proxies[4]["type"])
    }

    @Test
    fun shareListWithDescriptionAndInvalidLinesKeepsValidNodes() {
        val parsed = ProxyConfigStore.parseSource(
            "My subscription\n" +
                "this line has no scheme\n" +
                "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@example.com:443#node\n"
        )

        assertEquals(1, ProxyConfigStore.parseShareLinks(parsed.shareLinks).size)
    }

    @Test
    fun subscriptionResponseCompressionIsDecoded() {
        val source = "proxies:\n  - name: node\n    type: ss\n"
        val gzip = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(source.toByteArray()) }
        }.toByteArray()
        val zlib = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write(source.toByteArray()) }
        }.toByteArray()

        assertEquals(source, ProxyConfigStore.decodeSubscriptionBytes(gzip).toString(Charsets.UTF_8))
        assertEquals(source, ProxyConfigStore.decodeSubscriptionBytes(zlib).toString(Charsets.UTF_8))
    }

    @Test
    fun normalizedConfigPinsGoogleGroupAndLocalPorts() {
        val input = mapOf(
            "proxies" to listOf(mapOf("name" to "node-a", "type" to "ss")),
            "rules" to listOf("DOMAIN-SUFFIX,example.com,DIRECT"),
            "dns" to mapOf(
                "fallback" to listOf("https://dns.example/dns-query"),
                "fallback-filter" to mapOf(
                    "geoip" to true,
                    "geoip-code" to "CN",
                    "geosite" to listOf("gfw"),
                    "ipcidr" to listOf("240.0.0.0/4"),
                    "domain" to listOf("+.example.com")
                )
            )
        )

        val normalized = ProxyConfigStore.normalize(input, null)
        val groups = normalized["proxy-groups"] as List<*>
        val googleGroup = groups.first() as Map<*, *>
        val autoGroup = groups[1] as Map<*, *>

        assertEquals("Google Proxy", googleGroup["name"])
        assertEquals("select", googleGroup["type"])
        assertEquals(listOf("Google Proxy Auto", "node-a"), googleGroup["proxies"])
        assertEquals("url-test", autoGroup["type"])
        assertEquals(listOf("node-a"), autoGroup["proxies"])
        assertEquals(listOf("MATCH,Google Proxy"), normalized["rules"])
        assertEquals(17890, normalized["mixed-port"])
        assertEquals(17892, normalized["redir-port"])
        val dns = normalized["dns"] as Map<*, *>
        val fallback = dns["fallback-filter"] as Map<*, *>
        assertEquals(false, fallback["geoip"])
        assertEquals(
            mapOf("geoip" to false, "ipcidr" to listOf("240.0.0.0/4"), "domain" to listOf("+.example.com")),
            fallback
        )
    }

    @Test
    fun fallbackWithoutFilterExplicitlyDisablesDefaultGeoIpDownload() {
        val config = ProxyConfigStore.normalize(
            mapOf(
                "proxies" to listOf(mapOf("name" to "node-a", "type" to "ss")),
                "dns" to mapOf("fallback" to listOf("https://dns.example/dns-query"))
            ),
            null
        )
        val dns = config["dns"] as Map<*, *>
        val fallback = dns["fallback-filter"] as Map<*, *>

        assertEquals(false, fallback["geoip"])
    }

    @Test
    fun startupMigrationRemovesRemoteGeodataFiltersAndKeepsSelectedProxy() {
        val config = """
            proxies:
              - name: node-a
                type: ss
                server: example.com
                port: 443
            proxy-groups:
              - name: Google Proxy
                type: select
                proxies:
                  - node-a
            dns:
              fallback:
                - https://dns.example/dns-query
              fallback-filter:
                geoip: true
                geoip-code: CN
                geosite:
                  - gfw
                ipcidr:
                  - 240.0.0.0/4
                domain:
                  - +.example.com
              nameserver-policy:
                geosite:cn: https://dns.example/dns-query
                example.com: https://dns.example/dns-query
              fake-ip-filter:
                - GEOSITE,cn
                - DOMAIN-SUFFIX,example.com
            sniffer:
              enable: true
              force-domain:
                - geosite:cn
                - example.com
              skip-src-address:
                - geoip:cn
                - 192.168.0.0/16
            sub-rules:
              geo-routing:
                - GEOSITE,cn,Google Proxy
                - DOMAIN-SUFFIX,example.com,Google Proxy
        """.trimIndent()

        val migrated = ProxyConfigStore.sanitizeConfigForStartup(config)
        val root = org.yaml.snakeyaml.Yaml().load<Map<*, *>>(migrated)
        val group = (root["proxy-groups"] as List<*>).first() as Map<*, *>
        val dns = root["dns"] as Map<*, *>
        val fallback = dns["fallback-filter"] as Map<*, *>
        val policies = dns["nameserver-policy"] as Map<*, *>
        val fakeIpFilter = dns["fake-ip-filter"] as List<*>
        val sniffer = root["sniffer"] as Map<*, *>

        assertEquals(listOf("node-a"), group["proxies"])
        assertFalse(root.containsKey("sub-rules"))
        assertEquals(false, fallback["geoip"])
        assertEquals("https://dns.example/dns-query", policies["example.com"])
        assertEquals(listOf("DOMAIN-SUFFIX,example.com"), fakeIpFilter)
        assertEquals(listOf("example.com"), sniffer["force-domain"])
        assertEquals(listOf("192.168.0.0/16"), sniffer["skip-src-address"])
    }
}
