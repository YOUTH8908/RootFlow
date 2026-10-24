package com.example.clashforlsposed.proxy

import android.content.Context
import android.net.Uri
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.UnknownHostException
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Base64
import java.util.LinkedHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import org.json.JSONObject

object ProxyConfigStore {
    private const val PREFS = "proxy_config"
    private const val CONFIG = "normalized_config"
    private const val SOURCE = "source"
    private const val SELECTED_PROXY = "selected_proxy"
    private const val NODE_PROVIDER = "Imported Nodes"
    private const val CONTROLLER_SECRET = "controller_secret"
    private const val CONTROLLER_PORT = 17891
    private const val GOOGLE_AUTO_GROUP = "Google Proxy Auto"

    fun hasConfig(context: Context): Boolean = readConfig(context) != null

    fun source(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SOURCE, "") ?: ""

    fun readConfig(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CONFIG, null)

    fun configForStartup(context: Context): String? {
        val config = readConfig(context) ?: return null
        val startupConfig = sanitizeConfigForStartup(config)
        if (startupConfig != config) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(CONFIG, startupConfig)
                .apply()
        }
        return startupConfig
    }

    data class ProxyNode(
        val name: String,
        val type: String,
        val server: String,
        val port: Int?
    )

    data class ProxyGroup(
        val name: String,
        val type: String,
        val members: List<String>
    )

    data class ConfigSnapshot(
        val source: String,
        val nodes: List<ProxyNode>,
        val groups: List<ProxyGroup>,
        val selectedProxy: String?
    )

    fun snapshot(context: Context): ConfigSnapshot {
        val source = source(context)
        val text = readConfig(context) ?: return ConfigSnapshot(source, emptyList(), emptyList(), null)
        val root = runCatching { Yaml(SafeConstructor(loaderOptions())).load<Any?>(text) as? Map<*, *> }
            .getOrNull() ?: return ConfigSnapshot(source, emptyList(), emptyList(), null)
        val nodes = (root["proxies"] as? List<*>)?.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val name = map["name"]?.toString().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            ProxyNode(name, map["type"]?.toString().orEmpty(), map["server"]?.toString().orEmpty(), map["port"]?.toString()?.toIntOrNull())
        }.orEmpty()
        val groups = (root["proxy-groups"] as? List<*>)?.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val name = map["name"]?.toString().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            val members = (map["proxies"] as? List<*>)?.map { it.toString() }.orEmpty() +
                (map["use"] as? List<*>)?.map { "provider: ${it}" }.orEmpty()
            ProxyGroup(name, map["type"]?.toString().orEmpty(), members)
        }.orEmpty()
        val selected = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(SELECTED_PROXY, null)
        return ConfigSnapshot(source, nodes, groups, selected)
    }

    data class SelectionResult(val saved: Boolean, val applied: Boolean, val message: String)

    fun selectProxy(context: Context, name: String?): SelectionResult {
        val text = readConfig(context) ?: return SelectionResult(false, false, "暂无可用配置")
        val root = runCatching { Yaml(SafeConstructor(loaderOptions())).load<Any?>(text) as? Map<*, *> }
            .getOrNull() ?: return SelectionResult(false, false, "当前配置无法读取")
        val groups = (root["proxy-groups"] as? List<*>)?.mapNotNull { item ->
            val group = (item as? Map<*, *>)?.let { LinkedHashMap<String, Any?>().apply { it.forEach { (key, value) -> put(key.toString(), value) } } }
            group
        }.orEmpty().toMutableList()
        val selector = groups.firstOrNull { it["name"] == "Google Proxy" }
            ?: return SelectionResult(false, false, "配置中没有 Google Proxy 代理组")
        val auto = groups.firstOrNull { it["name"] == GOOGLE_AUTO_GROUP }
        val proxyNames = (root["proxies"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("name")?.toString() }.orEmpty()
        val groupNames = groups.mapNotNull { it["name"]?.toString() }.filter { it != "Google Proxy" }
        val selectorMembers = ((selector["proxies"] as? List<*>)?.map { it.toString() }.orEmpty() +
            proxyNames + groupNames + listOfNotNull(auto?.get("name")?.toString()))
            .filter { it != "Google Proxy" && it.isNotBlank() }.distinct()
        val autoMembers = ((auto?.get("proxies") as? List<*>)?.map { it.toString() }
            ?: (selector["proxies"] as? List<*>)?.map { it.toString() }.orEmpty())
            .filter { it != "Google Proxy" && it != GOOGLE_AUTO_GROUP && it.isNotBlank() }
            .distinct()
        if (auto == null && autoMembers.isNotEmpty()) {
            groups.add(linkedMapOf(
                "name" to GOOGLE_AUTO_GROUP,
                "type" to "url-test",
                "url" to "https://www.gstatic.com/generate_204",
                "interval" to 300,
                "proxies" to autoMembers
            ))
        }
        val candidates = (listOf(GOOGLE_AUTO_GROUP) + selectorMembers + proxyNames + groupNames)
            .filter { it != "Google Proxy" }.distinct()
        val chosen = name?.takeIf { it in candidates }
        if (name != null && chosen == null) return SelectionResult(false, false, "找不到节点：$name")
        val selectedName = chosen ?: GOOGLE_AUTO_GROUP
        selector["type"] = "select"
        selector["proxies"] = listOf(selectedName) + candidates.filter { it != selectedName }
        val updated = LinkedHashMap<String, Any?>().apply {
            root.forEach { (key, value) -> put(key.toString(), value) }
            put("proxy-groups", groups)
        }
        val updatedText = dumpYaml(updated)
        val selected = name?.takeIf { it == selectedName }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CONFIG, updatedText)
            .putString(SELECTED_PROXY, selected)
            .apply()
        val applied = !ProxyRuntimeState.running || applyRunningSelection(context, selectedName)
        val message = when {
            !ProxyRuntimeState.running -> "已保存选择：${selectedName}，启动代理后生效"
            applied -> "当前节点已切换为：${selectedName}"
            else -> "已保存选择：${selectedName}，当前切换失败，请重启代理后生效"
        }
        return SelectionResult(true, applied, message)
    }

    private fun applyRunningSelection(context: Context, name: String): Boolean {
        if (!ProxyRuntimeState.running) return false
        return runCatching {
            val secret = controllerSecret(context)
            val connection = (URL("http://127.0.0.1:$CONTROLLER_PORT/proxies/${Uri.encode("Google Proxy")}")
                .openConnection() as HttpURLConnection).apply {
                connectTimeout = 2_000
                readTimeout = 3_000
                requestMethod = "PUT"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $secret")
                setRequestProperty("Content-Type", "application/json")
            }
            connection.outputStream.use { it.write(JSONObject().put("name", name).toString().toByteArray()) }
            val status = connection.responseCode
            connection.disconnect()
            status in 200..299
        }.getOrDefault(false)
    }

    data class ImportSummary(
        val nodeCount: Int,
        val groupCount: Int,
        val providerCount: Int
    ) {
        fun successMessage(): String {
            val counts = buildList {
                if (nodeCount > 0) add("$nodeCount 个节点")
                if (groupCount > 0) add("$groupCount 个代理组")
                if (providerCount > 0) add("$providerCount 个节点 provider")
            }
            return if (counts.isEmpty()) "配置导入成功" else "导入成功：${counts.joinToString("，")}"
        }
    }

    fun importUri(context: Context, uri: Uri): ImportSummary {
        val source = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytesLimited(MAX_CONFIG_BYTES).toString(Charsets.UTF_8)
        } ?: throw IOException("无法读取配置文件")
        return importText(context, source, "配置文件")
    }

    fun importUrl(context: Context, rawUrl: String): ImportSummary {
        val pasted = rawUrl.trim()
        val url = if (pasted.startsWith("clash://install-config", ignoreCase = true) ||
            pasted.startsWith("clashmeta://install-config", ignoreCase = true)
        ) {
            Uri.parse(pasted).getQueryParameter("url")
                ?: throw IOException("导入链接缺少 url 参数")
        } else pasted

        val scheme = schemeOf(url)
        if (scheme !in setOf("https", "http")) {
            return importText(context, url, "节点分享链接")
        }

        val parsedUrl = try {
            URL(url)
        } catch (error: Exception) {
            throw IOException("订阅链接格式无效", error)
        }
        val source = downloadSubscription(parsedUrl)
        return importText(context, source.first, "订阅 (${source.second})")
    }

    private fun downloadSubscription(initialUrl: URL): Pair<String, String> {
        var currentUrl = initialUrl
        val proxy = if (ProxyRuntimeState.running) {
            Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 17890))
        } else Proxy.NO_PROXY

        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = try {
                (currentUrl.openConnection(proxy) as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("User-Agent", SUBSCRIPTION_USER_AGENT)
                    setRequestProperty("Accept", "application/yaml, text/yaml, text/plain, */*")
                    setRequestProperty("Accept-Encoding", "identity")
                    if (sameOrigin(initialUrl, currentUrl)) {
                        currentUrl.userInfo?.let { userInfo ->
                            val credentials = decodeUriComponent(userInfo)
                            val value = Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))
                            setRequestProperty("Authorization", "Basic $value")
                        }
                    }
                }
            } catch (error: UnknownHostException) {
                throw unknownHostError(currentUrl, error)
            }

            try {
                val status = connection.responseCode
                if (status in REDIRECT_CODES) {
                    if (redirectCount == MAX_REDIRECTS) throw IOException("订阅重定向次数过多")
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("订阅服务器返回了无效重定向")
                    currentUrl = try {
                        URL(currentUrl, location)
                    } catch (error: Exception) {
                        throw IOException("订阅服务器返回了无效重定向", error)
                    }
                    return@repeat
                }
                if (status !in 200..299) throw IOException("订阅请求失败：HTTP $status")

                val bytes = connection.inputStream.use { it.readBytesLimited(MAX_CONFIG_BYTES) }
                val decoded = decodeSubscriptionBytes(bytes, connection.contentEncoding)
                val text = decoded.toString(Charsets.UTF_8)
                if (looksLikeHtml(text)) {
                    throw IOException("订阅服务器返回网页或验证页面，不是 Clash 配置")
                }
                return text to currentUrl.host
            } catch (error: UnknownHostException) {
                throw unknownHostError(currentUrl, error)
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("订阅重定向次数过多")
    }

    private fun sameOrigin(first: URL, second: URL): Boolean =
        first.protocol.equals(second.protocol, ignoreCase = true) &&
            first.host.equals(second.host, ignoreCase = true) &&
            effectivePort(first) == effectivePort(second)

    private fun effectivePort(url: URL): Int = if (url.port >= 0) url.port else url.defaultPort

    private fun decodeUriComponent(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name()) }
            .getOrDefault(value)

    internal fun schemeOf(value: String): String? {
        val separator = value.indexOf(':')
        if (separator <= 0) return null
        val candidate = value.substring(0, separator)
        if (!candidate.first().isLetter() || candidate.drop(1).any { !it.isLetterOrDigit() && it !in "+.-" }) return null
        return candidate.lowercase()
    }

    fun importText(
        context: Context,
        source: String,
        sourceLabel: String = "粘贴的 Clash 配置"
    ): ImportSummary {
        val parsed = parseSource(source)
        val root = LinkedHashMap<String, Any?>().apply {
            parsed.config.forEach { (key, value) -> put(key.toString(), value) }
            if (parsed.shareLinks.isNotEmpty()) {
                put("proxies", ShareLinkParser.parseAll(parsed.shareLinks))
            }
        }
        val summary = ImportSummary(
            nodeCount = (root["proxies"] as? List<*>)?.size ?: 0,
            groupCount = (root["proxy-groups"] as? List<*>)?.size ?: 0,
            providerCount = (root["proxy-providers"] as? Map<*, *>)?.size ?: 0
        )
        val normalized = normalize(root, null)
        normalized["external-controller"] = "127.0.0.1:$CONTROLLER_PORT"
        normalized["secret"] = controllerSecret(context)
        val text = dumpYaml(normalized)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CONFIG, text)
            .putString(SOURCE, sourceLabel)
            .remove(SELECTED_PROXY)
            .apply()
        return summary
    }

    internal fun parseSource(source: String): ParsedSource {
        val text = source.removePrefix("\uFEFF").trim()
        if (text.isEmpty()) throw IOException("配置内容为空")
        val lines = meaningfulLines(text)
        val shareLinks = lines.flatMap { it.split(Regex("\\s+")) }
            .filter(String::isNotBlank)
            .filter(::isShareLink)
        if (shareLinks.isNotEmpty()) {
            return ParsedSource(linkedMapOf<String, Any?>(), shareLinks)
        }

        val decoded = decodeBase64(text)
        if (decoded != null && decoded != text) {
            val decodedLines = meaningfulLines(decoded).flatMap { it.split(Regex("\\s+")) }
                .filter(String::isNotBlank)
                .filter(::isShareLink)
            if (decodedLines.isNotEmpty()) {
                return ParsedSource(linkedMapOf<String, Any?>(), decodedLines)
            }
            val decodedYaml = runCatching {
                Yaml(SafeConstructor(loaderOptions())).load<Any?>(decoded)
            }.getOrNull()
            if (decodedYaml is Map<*, *>) return ParsedSource(decodedYaml, emptyList())
        }

        val parsed = runCatching { Yaml(SafeConstructor(loaderOptions())).load<Any?>(text) }
            .getOrElse { throw IOException("无法解析配置：YAML 格式错误", it) }
        val root = parsed as? Map<*, *> ?: throw IOException("配置不是 YAML 对象，也不是受支持的节点链接")
        return ParsedSource(root, emptyList())
    }

    private fun meaningfulLines(text: String): List<String> = text.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .filterNot { it.startsWith("#") }
        .toList()

    private fun loaderOptions() = LoaderOptions().apply {
        codePointLimit = MAX_CONFIG_BYTES
    }

    private fun isShareLink(value: String): Boolean =
        listOf("ss://", "ssr://", "vmess://", "vless://", "trojan://", "hysteria://", "hysteria2://", "hysteria2+realm://", "hy2://", "hy2+realm://", "tuic://", "socks://", "socks5://", "socks5h://", "anytls://", "mieru://", "mierus://")
            .any { value.startsWith(it, ignoreCase = true) }

    private fun unknownHostError(url: URL, cause: UnknownHostException) =
        IOException("无法解析订阅服务器 ${url.host}，请检查手机网络或 DNS 后重试", cause)

    /** Converts common subscription share links to Mihomo's native proxy objects. */
    private object ShareLinkParser {
        fun parseAll(links: List<String>): List<Map<String, Any?>> {
            val names = HashSet<String>()
            var firstError: IOException? = null
            val parsed = links.mapIndexedNotNull { index, link ->
                val node = runCatching { parse(link, index + 1) }.getOrElse { error ->
                    if (firstError == null) {
                        firstError = IOException("第 ${index + 1} 个节点链接无法解析：${error.message ?: "链接格式无效"}", error)
                    }
                    return@mapIndexedNotNull null
                }.toMutableMap()
                val originalName = node["name"]?.toString().orEmpty().ifBlank { "Imported Node ${index + 1}" }
                var uniqueName = originalName
                var suffix = 2
                while (!names.add(uniqueName)) {
                    uniqueName = "$originalName $suffix"
                    suffix++
                }
                node["name"] = uniqueName
                node
            }
            if (parsed.isEmpty()) throw (firstError ?: IOException("没有可导入的节点链接"))
            return parsed
        }

        private fun parse(raw: String, index: Int): Map<String, Any?> {
            val value = raw.trim()
            val uri = runCatching { URI(value) }.getOrElse {
                throw IOException("链接格式无效")
            }
            val scheme = uri.scheme?.lowercase() ?: throw IOException("缺少协议")
            val name = decodeUri(uri.rawFragment).ifBlank { "Imported Node $index" }
            return when (scheme) {
                "ss" -> parseShadowsocks(uri, name)
                "ssr" -> parseShadowsocksR(uri, name)
                "vmess" -> parseVmess(uri, name)
                "vless" -> parseVless(uri, name)
                "trojan" -> parseTrojan(uri, name)
                "hysteria", "hysteria2", "hysteria2+realm", "hy2", "hy2+realm" -> parseHysteria(uri, name, scheme)
                "tuic" -> parseTuic(uri, name)
                "socks", "socks5", "socks5h" -> parseSocks(uri, name)
                "anytls" -> parseAnyTls(uri, name)
                "mieru", "mierus" -> parseMieru(uri, name)
                else -> throw IOException("不支持的节点协议 $scheme")
            }
        }

        private fun parseShadowsocks(uri: URI, name: String): Map<String, Any?> {
            val authority = uri.rawAuthority ?: throw IOException("缺少服务器地址")
            val at = authority.lastIndexOf('@')
            val decodedPayload = if (at < 0) {
                decodeBase64Payload(uri.rawSchemeSpecificPart.removePrefix("//").substringBefore('#'))
            } else null
            val payload = decodedPayload ?: authority
            val payloadAt = payload.lastIndexOf('@')
            val credentials = if (payloadAt >= 0) {
                decodeBase64Payload(payload.substring(0, payloadAt)) ?: decodeUri(payload.substring(0, payloadAt))
            } else ""
            val userAndHost = if (payloadAt >= 0) payload.substring(payloadAt + 1) else payload.substringAfterLast('@', payload)
            val methodPassword = credentials.substringBefore(':', "") to credentials.substringAfter(':', "")
            val (host, port) = hostPort(userAndHost)
            if (methodPassword.first.isBlank() || methodPassword.second.isBlank()) {
                throw IOException("SS 链接缺少加密方式或密码")
            }
            return linkedMapOf(
                "name" to name,
                "type" to "ss",
                "server" to host,
                "port" to port,
                "cipher" to methodPassword.first,
                "password" to methodPassword.second,
                "udp" to true
            )
        }

        private fun parseVmess(uri: URI, name: String): Map<String, Any?> {
            val payload = uri.rawSchemeSpecificPart.removePrefix("//").substringBefore('#')
            if (uri.rawUserInfo != null) return parseModernVmess(uri, name)
            val json = decodeBase64Payload(payload) ?: throw IOException("VMess 数据无效")
            val values = runCatching {
                Yaml(SafeConstructor(loaderOptions())).load<Any?>(json) as? Map<*, *>
            }.getOrNull() ?: throw IOException("VMess 数据格式无效")
            fun field(key: String): String = values[key]?.toString().orEmpty()
            val server = field("add")
            val port = field("port").toIntOrNull() ?: throw IOException("VMess 端口无效")
            val uuid = field("id").ifBlank { field("uuid") }
            if (server.isBlank() || uuid.isBlank()) throw IOException("VMess 缺少服务器或 UUID")
            val nodeName = decodeUri(uri.rawFragment).ifBlank { field("ps").ifBlank { name } }
            val node = linkedMapOf<String, Any?>(
                "name" to nodeName,
                "type" to "vmess",
                "server" to server,
                "port" to port,
                "uuid" to uuid,
                "alterId" to (field("aid").toIntOrNull() ?: 0),
                "cipher" to field("scy").ifBlank { "auto" },
                "udp" to true
            )
            if (field("net").isNotBlank()) node["network"] = field("net")
            if (field("tls").equals("tls", true)) node["tls"] = true
            field("sni").takeIf(String::isNotBlank)?.let { node["servername"] = it }
            field("fp").takeIf(String::isNotBlank)?.let { node["client-fingerprint"] = it }
            if (field("net").equals("ws", true)) {
                val ws = linkedMapOf<String, Any?>()
                field("path").takeIf(String::isNotBlank)?.let { ws["path"] = it }
                field("host").takeIf(String::isNotBlank)?.let { ws["headers"] = linkedMapOf("Host" to it) }
                node["ws-opts"] = ws
            }
            return node
        }

        private fun parseModernVmess(uri: URI, name: String): Map<String, Any?> {
            val uuid = decodeUri(uri.rawUserInfo).ifBlank { throw IOException("VMess 缺少 UUID") }
            val (server, port) = hostPort(uri.rawAuthority?.substringAfterLast('@') ?: "")
            val query = query(uri.rawQuery)
            val node = linkedMapOf<String, Any?>(
                "name" to name,
                "type" to "vmess",
                "server" to server,
                "port" to port,
                "uuid" to uuid,
                "alterId" to 0,
                "cipher" to (query["encryption"] ?: "auto"),
                "udp" to true
            )
            val security = query["security"].orEmpty()
            if (security.equals("tls", true) || security.equals("reality", true)) {
                node["tls"] = true
                node["client-fingerprint"] = query["fp"]?.takeIf(String::isNotBlank) ?: "chrome"
            }
            query["sni"]?.takeIf(String::isNotBlank)?.let { node["servername"] = it }
            query["alpn"]?.takeIf(String::isNotBlank)?.let { node["alpn"] = it.split(',').filter(String::isNotBlank) }
            query["pbk"]?.takeIf(String::isNotBlank)?.let { publicKey ->
                node["reality-opts"] = linkedMapOf(
                    "public-key" to publicKey,
                    "short-id" to query["sid"].orEmpty()
                )
            }
            when (query["packetEncoding"]) {
                "packet" -> node["packet-addr"] = true
                "none" -> node["xudp"] = false
                else -> node["xudp"] = true
            }
            var network = query["type"].orEmpty().ifBlank { "tcp" }.lowercase()
            if (network == "tcp" && query["headerType"].equals("http", true)) network = "http"
            else if (network == "http") network = "h2"
            node["network"] = network
            if (network == "h2") {
                val options = linkedMapOf<String, Any?>("path" to (query["path"] ?: "/"))
                query["host"]?.takeIf(String::isNotBlank)?.let { options["host"] = listOf(it) }
                node["h2-opts"] = options
            }
            if (network == "http") {
                val options = linkedMapOf<String, Any?>("path" to listOf(query["path"] ?: "/"))
                val headers = linkedMapOf<String, Any?>()
                query["host"]?.takeIf(String::isNotBlank)?.let { headers["Host"] = listOf(it) }
                query["method"]?.takeIf(String::isNotBlank)?.let { options["method"] = it }
                options["headers"] = headers
                node["http-opts"] = options
            }
            if (network.equals("ws", true)) {
                val ws = linkedMapOf<String, Any?>()
                query["path"]?.takeIf(String::isNotBlank)?.let { ws["path"] = it }
                query["host"]?.takeIf(String::isNotBlank)?.let { ws["headers"] = linkedMapOf("Host" to it) }
                query["ed"]?.toIntOrNull()?.takeIf { it > 0 }?.let {
                    ws["max-early-data"] = it
                    ws["early-data-header-name"] = "Sec-WebSocket-Protocol"
                }
                query["eh"]?.takeIf(String::isNotBlank)?.let { ws["early-data-header-name"] = it }
                node["ws-opts"] = ws
            }
            if (network.equals("httpupgrade", true)) {
                val ws = linkedMapOf<String, Any?>()
                query["path"]?.takeIf(String::isNotBlank)?.let { ws["path"] = it }
                query["host"]?.takeIf(String::isNotBlank)?.let { ws["headers"] = linkedMapOf("Host" to it) }
                if (query["ed"] == "0") ws["v2ray-http-upgrade-fast-open"] = true
                query["eh"]?.takeIf(String::isNotBlank)?.let { ws["early-data-header-name"] = it }
                node["ws-opts"] = ws
            }
            if (network.equals("grpc", true)) {
                query["serviceName"]?.takeIf(String::isNotBlank)?.let {
                    node["grpc-opts"] = linkedMapOf("grpc-service-name" to it)
                }
            }
            if (network.equals("xhttp", true)) {
                val options = linkedMapOf<String, Any?>()
                query["path"]?.takeIf(String::isNotBlank)?.let { options["path"] = it }
                query["host"]?.takeIf(String::isNotBlank)?.let { options["host"] = it }
                query["mode"]?.takeIf(String::isNotBlank)?.let { options["mode"] = it }
                node["xhttp-opts"] = options
            }
            return node
        }

        private fun parseShadowsocksR(uri: URI, name: String): Map<String, Any?> {
            val encoded = uri.rawSchemeSpecificPart.removePrefix("//").substringBefore('#')
            val payload = decodeBase64Payload(encoded) ?: throw IOException("SSR 数据无效")
            val main = payload.substringBefore("/?")
            val fields = main.split(':')
            if (fields.size < 6) throw IOException("SSR 节点字段不完整")
            val query = query(payload.substringAfter("/?", ""))
            val password = decodeBase64Payload(fields[5]) ?: decodeUri(fields[5])
            if (password.isBlank()) throw IOException("SSR 缺少密码")
            val node = linkedMapOf<String, Any?>(
                "name" to (decodeBase64Payload(query["remarks"].orEmpty())?.takeIf(String::isNotBlank) ?: name),
                "type" to "ssr",
                "server" to fields[0],
                "port" to (fields[1].toIntOrNull() ?: throw IOException("SSR 端口无效")),
                "protocol" to fields[2],
                "cipher" to fields[3],
                "obfs" to fields[4],
                "password" to password,
                "udp" to true
            )
            query["obfsparam"]?.let { decodeBase64Payload(it) }?.takeIf(String::isNotBlank)
                ?.let { node["obfs-param"] = it }
            query["protoparam"]?.let { decodeBase64Payload(it) }?.takeIf(String::isNotBlank)
                ?.let { node["protocol-param"] = it }
            return node
        }

        private fun parseVless(uri: URI, name: String): Map<String, Any?> = parseUriNode(uri, name, "vless")

        private fun parseTrojan(uri: URI, name: String): Map<String, Any?> = parseUriNode(uri, name, "trojan")

        private fun parseUriNode(uri: URI, name: String, type: String): Map<String, Any?> {
            val user = decodeUri(uri.rawUserInfo).ifBlank { throw IOException("缺少认证信息") }
            val (host, port) = hostPort(uri.rawAuthority?.substringAfterLast('@') ?: "")
            val query = query(uri.rawQuery)
            val node = linkedMapOf<String, Any?>(
                "name" to name,
                "type" to type,
                "server" to host,
                "port" to port,
                "udp" to true
            )
            if (type == "trojan") node["password"] = user else node["uuid"] = user
            val security = query["security"].orEmpty()
            if (security.equals("tls", true) || security.equals("reality", true)) node["tls"] = true
            query["sni"]?.takeIf(String::isNotBlank)?.let { node["servername"] = it }
            if (security.equals("reality", true)) {
                val realityOptions = linkedMapOf<String, String>()
                query["pbk"]?.takeIf(String::isNotBlank)?.let { realityOptions["public-key"] = it }
                query["sid"]?.takeIf(String::isNotBlank)?.let { realityOptions["short-id"] = it }
                if (realityOptions.isNotEmpty()) node["reality-opts"] = realityOptions
            }
            query["fp"]?.takeIf(String::isNotBlank)?.let { node["client-fingerprint"] = it }
            query["type"]?.takeIf(String::isNotBlank)?.let { network ->
                node["network"] = network
                if (network == "ws") {
                    val ws = linkedMapOf<String, Any?>()
                    query["path"]?.let { ws["path"] = it }
                    query["host"]?.let { ws["headers"] = linkedMapOf("Host" to it) }
                    node["ws-opts"] = ws
                }
            }
            if (query["type"].equals("grpc", true)) {
                query["serviceName"]?.takeIf(String::isNotBlank)?.let {
                    node["grpc-opts"] = linkedMapOf("grpc-service-name" to it)
                }
            }
            query["flow"]?.takeIf(String::isNotBlank)?.let { node["flow"] = it }
            query["encryption"]?.takeIf(String::isNotBlank)?.let { node["encryption"] = it }
            return node
        }

        private fun parseHysteria(uri: URI, name: String, scheme: String): Map<String, Any?> {
            val (host, port) = hostPort(uri.rawAuthority?.substringAfterLast('@') ?: "")
            val query = query(uri.rawQuery)
            val type = if (scheme == "hysteria") "hysteria" else "hysteria2"
            val password = decodeUri(uri.rawUserInfo)
            val auth = query["auth"] ?: query["auth-str"] ?: query["auth_str"] ?: password
            if (type == "hysteria" && auth.isNullOrBlank()) throw IOException("缺少认证信息")
            if (type == "hysteria2" && password.isBlank()) throw IOException("缺少认证信息")
            val node = linkedMapOf<String, Any?>(
                "name" to name,
                "type" to type,
                "server" to host,
                "port" to port,
                "sni" to (query["sni"] ?: query["peer"] ?: ""),
                "skip-cert-verify" to (query["insecure"] == "1" || query["insecure"] == "true"),
                "udp" to true
            )
            if (type == "hysteria") node["auth-str"] = auth
            else node["password"] = password
            query["peer"]?.takeIf(String::isNotBlank)?.let { node["sni"] = it }
            query["protocol"]?.takeIf(String::isNotBlank)?.let { node["protocol"] = it }
            (query["up"] ?: query["upmbps"])?.takeIf(String::isNotBlank)?.let { node["up"] = it }
            (query["down"] ?: query["downmbps"])?.takeIf(String::isNotBlank)?.let { node["down"] = it }
            query["alpn"]?.takeIf(String::isNotBlank)?.let { node["alpn"] = it.split(',').filter(String::isNotBlank) }
            query["obfs"]?.takeIf(String::isNotBlank)?.let { node["obfs"] = it }
            query["obfs-password"]?.takeIf(String::isNotBlank)?.let { node["obfs-password"] = it }
            return node
        }

        private fun parseAnyTls(uri: URI, name: String): Map<String, Any?> {
            val (host, port) = hostPort(uri.rawAuthority?.substringAfterLast('@') ?: "")
            val query = query(uri.rawQuery)
            return linkedMapOf<String, Any?>(
                "name" to name,
                "type" to "anytls",
                "server" to host,
                "port" to port,
                "password" to decodeUri(uri.rawUserInfo),
                "sni" to (query["sni"] ?: query["peer"] ?: host),
                "udp" to true
            )
        }

        private fun parseMieru(uri: URI, name: String): Map<String, Any?> {
            val (host, port) = hostPort(uri.rawAuthority?.substringAfterLast('@') ?: "")
            val user = decodeUri(uri.rawUserInfo)
            return linkedMapOf<String, Any?>(
                "name" to name,
                "type" to "mieru",
                "server" to host,
                "port" to port,
                "username" to user.substringBefore(':').takeIf(String::isNotBlank),
                "password" to user.substringAfter(':', "").takeIf(String::isNotBlank)
            )
        }

        private fun parseSocks(uri: URI, name: String): Map<String, Any?> {
            val (host, port) = hostPort(uri.rawAuthority?.substringAfterLast('@') ?: "")
            val userInfo = decodeUri(uri.rawUserInfo)
            val node = linkedMapOf<String, Any?>(
                "name" to name,
                "type" to "socks5",
                "server" to host,
                "port" to port,
                "udp" to true
            )
            if (userInfo.isNotBlank()) {
                node["username"] = userInfo.substringBefore(':')
                node["password"] = userInfo.substringAfter(':', "")
            }
            return node
        }

        private fun parseTuic(uri: URI, name: String): Map<String, Any?> {
            val credentials = decodeUri(uri.rawUserInfo)
            val uuid = credentials.substringBefore(':')
            val password = credentials.substringAfter(':', "")
            val (host, port) = hostPort(uri.rawAuthority?.substringAfterLast('@') ?: "")
            val query = query(uri.rawQuery)
            if (uuid.isBlank() || password.isBlank()) throw IOException("TUIC 链接缺少 UUID 或密码")
            return linkedMapOf(
                "name" to name,
                "type" to "tuic",
                "server" to host,
                "port" to port,
                "uuid" to uuid,
                "password" to password,
                "congestion-controller" to (query["congestion_control"] ?: "cubic"),
                "udp-relay-mode" to (query["udp_relay_mode"] ?: "native"),
                "udp" to true,
                "sni" to (query["sni"] ?: "")
            )
        }

        private fun hostPort(authority: String): Pair<String, Int> {
            val value = authority.substringAfterLast('@')
            val separator = value.lastIndexOf(':')
            if (separator <= 0) throw IOException("服务器地址或端口缺失")
            val host = value.substring(0, separator).removePrefix("[").removeSuffix("]")
            val port = value.substring(separator + 1).toIntOrNull()
                ?: throw IOException("端口无效")
            if (host.isBlank() || port !in 1..65535) throw IOException("服务器地址或端口无效")
            return host to port
        }

        private fun query(raw: String?): Map<String, String> = raw.orEmpty()
            .split('&')
            .mapNotNull { part ->
                if (part.isBlank()) return@mapNotNull null
                val key = part.substringBefore('=').let(::decodeQueryComponent)
                val value = part.substringAfter('=', "").let(::decodeQueryComponent)
                key to value
            }.toMap()

        private fun decodeQueryComponent(value: String): String =
            runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)

        private fun decodeUri(value: String?): String = value?.let {
            // URI query values may legitimately contain '+', so protect it from form decoding.
            runCatching { URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8.name()) }
                .getOrDefault(it)
        }.orEmpty()

        private fun decodeBase64Payload(value: String): String? {
            val compact = value.filterNot(Char::isWhitespace)
            if (compact.isEmpty() || compact.any { !(it.isLetterOrDigit() || it in "+/_=-") }) return null
            val normalized = compact.replace('-', '+').replace('_', '/')
                .let { it + "=".repeat((4 - it.length % 4) % 4) }
            return runCatching {
                String(java.util.Base64.getDecoder().decode(normalized), Charsets.UTF_8)
            }.getOrNull()
        }
    }

    private fun decodeBase64(value: String): String? {
        val compact = value.filterNot(Char::isWhitespace)
        if (compact.length < 12 || compact.any { !(it.isLetterOrDigit() || it in "+/_=-") }) return null
        val normalized = compact.replace('-', '+').replace('_', '/')
            .let { it + "=".repeat((4 - it.length % 4) % 4) }
        return runCatching {
            String(java.util.Base64.getDecoder().decode(normalized), Charsets.UTF_8)
        }.getOrNull()
    }

    internal fun decodeSubscriptionBytes(bytes: ByteArray, contentEncoding: String? = null): ByteArray {
        val encoding = contentEncoding.orEmpty().lowercase()
        val gzip = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        val zlib = bytes.size >= 2 && bytes[0].toInt() and 0x0f == 8 &&
            ((bytes[0].toInt() and 0xff) * 256 + (bytes[1].toInt() and 0xff)) % 31 == 0
        val stream = when {
            encoding.contains("gzip") || gzip -> GZIPInputStream(ByteArrayInputStream(bytes))
            encoding.contains("deflate") || zlib -> InflaterInputStream(ByteArrayInputStream(bytes))
            encoding.isBlank() || encoding == "identity" -> return bytes
            else -> throw IOException("暂不支持订阅响应压缩格式")
        }
        return try {
            stream.use { it.readBytesLimited(MAX_CONFIG_BYTES) }
        } catch (error: Exception) {
            throw IOException("订阅响应压缩数据无效", error)
        }
    }

    internal fun looksLikeHtml(text: String): Boolean =
        HTML_START.containsMatchIn(text.removePrefix("\uFEFF").trimStart())

    internal fun parseShareLinks(links: List<String>): List<Map<String, Any?>> =
        ShareLinkParser.parseAll(links)

    internal fun normalize(input: Map<*, *>, shareProviderPath: String?): LinkedHashMap<String, Any?> {
        val root = LinkedHashMap<String, Any?>()
        input.forEach { (key, value) -> root[key.toString()] = value }

        val proxies = root["proxies"] as? List<*> ?: emptyList<Any?>()
        val proxyNames = proxies.mapNotNull { proxy ->
            (proxy as? Map<*, *>)?.get("name")?.toString()
        }.filter(String::isNotBlank)
        val proxyGroups = root["proxy-groups"] as? List<*> ?: emptyList<Any?>()
        val selectableGroups = proxyGroups.mapNotNull { group ->
            (group as? Map<*, *>)?.get("name")?.toString()
        }.filter { it.isNotBlank() && it != "Google Proxy" }

        val providers = LinkedHashMap<String, Any?>()
        (root["proxy-providers"] as? Map<*, *>)?.forEach { (key, value) ->
            providers[key.toString()] = value
        }
        if (shareProviderPath != null) {
            providers[NODE_PROVIDER] = linkedMapOf(
                "type" to "file",
                "path" to shareProviderPath,
                "health-check" to linkedMapOf(
                    "enable" to true,
                    "url" to "https://www.gstatic.com/generate_204",
                    "interval" to 300
                )
            )
        }

        val autoGroup = linkedMapOf<String, Any?>(
            "name" to GOOGLE_AUTO_GROUP,
            "type" to "url-test",
            "url" to "https://www.gstatic.com/generate_204",
            "interval" to 300
        )
        when {
            proxyNames.isNotEmpty() -> autoGroup["proxies"] = proxyNames
            selectableGroups.isNotEmpty() -> autoGroup["proxies"] = selectableGroups
        }
        if (providers.isNotEmpty()) autoGroup["use"] = providers.keys.toList()
        if (autoGroup["proxies"] == null && autoGroup["use"] == null) {
            throw IOException("配置中没有可用的代理节点、代理组或 provider")
        }
        val selectorMembers = (listOf(GOOGLE_AUTO_GROUP) + proxyNames + selectableGroups).distinct()
        val group = linkedMapOf<String, Any?>(
            "name" to "Google Proxy",
            "type" to "select",
            "proxies" to selectorMembers
        )
        if (providers.isNotEmpty()) group["use"] = providers.keys.toList()
        if (providers.isNotEmpty()) root["proxy-providers"] = providers
        else root.remove("proxy-providers")

        val configuredGroups = proxyGroups.toMutableList()
        configuredGroups.removeAll {
            val name = (it as? Map<*, *>)?.get("name")
            name == "Google Proxy" || name == GOOGLE_AUTO_GROUP
        }
        configuredGroups.add(0, group)
        configuredGroups.add(1, autoGroup)
        root["proxy-groups"] = configuredGroups
        root["rules"] = listOf("MATCH,Google Proxy")

        root["allow-lan"] = false
        root["bind-address"] = "127.0.0.1"
        root["mixed-port"] = 17890
        root["redir-port"] = 17892
        root["external-controller"] = "127.0.0.1:$CONTROLLER_PORT"
        root["mode"] = "rule"
        root["ipv6"] = false
        root["tun"] = linkedMapOf("enable" to false)
        root.remove("sub-rules")

        val dns = LinkedHashMap<String, Any?>()
        (root["dns"] as? Map<*, *>)?.forEach { (key, value) -> dns[key.toString()] = value }
        removeRemoteGeodataFilters(dns)
        dns["enable"] = true
        dns["listen"] = "127.0.0.1:17953"
        dns["ipv6"] = false
        if ((dns["nameserver"] as? List<*>)?.isEmpty() != false) {
            dns["nameserver"] = listOf("223.5.5.5", "119.29.29.29")
        }
        if ((dns["default-nameserver"] as? List<*>)?.isEmpty() != false) {
            dns["default-nameserver"] = listOf("223.5.5.5", "119.29.29.29")
        }
        root["dns"] = dns
        sanitizeSnifferGeodata(root)
        return root
    }

    internal fun sanitizeConfigForStartup(config: String): String {
        val parsed = try {
            Yaml(SafeConstructor(loaderOptions())).load<Any?>(config) as? Map<*, *>
        } catch (error: Exception) {
            throw IOException("无法读取已保存的 Clash 配置", error)
        } ?: throw IOException("已保存的 Clash 配置不是 YAML 对象")
        val root = LinkedHashMap<String, Any?>().apply {
            parsed.forEach { (key, value) -> put(key.toString(), value) }
        }
        var changed = root.remove("sub-rules") != null
        val dns = LinkedHashMap<String, Any?>()
        (root["dns"] as? Map<*, *>)?.forEach { (key, value) -> dns[key.toString()] = value }
        val dnsChanged = removeRemoteGeodataFilters(dns)
        if (dnsChanged) {
            root["dns"] = dns
            changed = true
        }
        if (sanitizeSnifferGeodata(root)) changed = true
        if (!changed) return config
        return dumpYaml(root)
    }

    private fun removeRemoteGeodataFilters(dns: MutableMap<String, Any?>): Boolean {
        val original = dns["fallback-filter"] as? Map<*, *>
        val hasFallback = (dns["fallback"] as? List<*>)?.isNotEmpty() == true
        val fallbackFilter = LinkedHashMap<String, Any?>().apply {
            original?.forEach { (key, value) -> put(key.toString(), value) }
        }
        var changed = false
        var fallbackFilterChanged = false
        if (original != null || hasFallback) {
            if (fallbackFilter["geoip"] != false) {
                fallbackFilter["geoip"] = false
                changed = true
                fallbackFilterChanged = true
            }
            listOf("geoip-code", "geosite").forEach { key ->
                if (fallbackFilter.containsKey(key)) {
                    fallbackFilter.remove(key)
                    changed = true
                    fallbackFilterChanged = true
                }
            }
        }
        listOf("nameserver-policy", "proxy-server-nameserver-policy").forEach { key ->
            val policies = dns[key] as? Map<*, *> ?: return@forEach
            val keptPolicies = LinkedHashMap<String, Any?>().apply {
                policies.forEach { (policy, value) ->
                    val selector = policy.toString()
                    if (!selector.startsWith("geosite:", ignoreCase = true)) put(selector, value)
                    else changed = true
                }
            }
            if (keptPolicies.size != policies.size) dns[key] = keptPolicies
        }
        val fakeIpFilter = dns["fake-ip-filter"] as? List<*>
        if (fakeIpFilter != null) {
            val keptRules = fakeIpFilter.filterNot { isGeodataSelector(it?.toString().orEmpty()) }
            if (keptRules.size != fakeIpFilter.size) {
                dns["fake-ip-filter"] = keptRules
                changed = true
            }
        }
        if (fallbackFilterChanged) dns["fallback-filter"] = fallbackFilter
        return changed
    }

    private fun sanitizeSnifferGeodata(root: MutableMap<String, Any?>): Boolean {
        val original = root["sniffer"] as? Map<*, *> ?: return false
        val sniffer = LinkedHashMap<String, Any?>().apply {
            original.forEach { (key, value) -> put(key.toString(), value) }
        }
        var changed = false
        listOf("force-domain", "skip-domain").forEach { key ->
            val values = sniffer[key] as? List<*> ?: return@forEach
            val kept = values.filterNot { isGeoSiteSelector(it?.toString().orEmpty()) }
            if (kept.size != values.size) {
                sniffer[key] = kept
                changed = true
            }
        }
        listOf("skip-src-address", "skip-dst-address").forEach { key ->
            val values = sniffer[key] as? List<*> ?: return@forEach
            val kept = values.filterNot { isGeoIpSelector(it?.toString().orEmpty()) }
            if (kept.size != values.size) {
                sniffer[key] = kept
                changed = true
            }
        }
        if (changed) root["sniffer"] = sniffer
        return changed
    }

    private fun isGeoSiteSelector(value: String): Boolean {
        val selector = value.trim()
        return selector.startsWith("geosite:", ignoreCase = true) ||
            selector.startsWith("GEOSITE,", ignoreCase = true)
    }

    private fun isGeoIpSelector(value: String): Boolean {
        val selector = value.trim()
        return selector.startsWith("geoip:", ignoreCase = true) ||
            selector.startsWith("GEOIP,", ignoreCase = true)
    }

    private fun isGeodataSelector(value: String): Boolean =
        isGeoSiteSelector(value) || isGeoIpSelector(value)

    private fun dumpYaml(root: Map<String, Any?>): String {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2
        }
        return Yaml(options).dump(root)
    }

    private fun controllerSecret(context: Context): String {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        preferences.getString(CONTROLLER_SECRET, null)?.let { return it }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        preferences.edit().putString(CONTROLLER_SECRET, secret).commit()
        return secret
    }

    internal data class ParsedSource(
        val config: Map<*, *>,
        val shareLinks: List<String>
    )

    private fun java.io.InputStream.readBytesLimited(maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IOException("配置文件超过 ${maxBytes / 1024 / 1024} MB")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private const val MAX_CONFIG_BYTES = 4 * 1024 * 1024
    private const val MAX_REDIRECTS = 5
    private const val SUBSCRIPTION_USER_AGENT = "ClashMeta/1.19.10"
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    private val HTML_START = Regex("<(?:!doctype\\s+html|html(?:\\s|>))", RegexOption.IGNORE_CASE)
}
