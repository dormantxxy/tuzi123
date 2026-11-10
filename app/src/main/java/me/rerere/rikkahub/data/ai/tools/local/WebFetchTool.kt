package me.rerere.rikkahub.data.ai.tools.local

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * 网页抓取（增强版）：支持重定向、gzip 解压、charset 自适应、标题/正文/链接提取、超时与大小限制。
 */
internal fun buildWebFetchTool(): Tool = Tool(
    name = "web_fetch",
    description = "Fetch a web page/API and return readable content. Handles redirects, gzip, charset. " +
        "Params: url(required), max_chars(optional default 8000), include_links(optional default false), raw(optional default false to strip HTML).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject { put("type", "string"); put("description", "http(s) URL") })
                put("max_chars", buildJsonObject { put("type", "number"); put("description", "Max chars to return (default 8000)") })
                put("include_links", buildJsonObject { put("type", "boolean"); put("description", "Include extracted links (default false)") })
                put("raw", buildJsonObject { put("type", "boolean"); put("description", "Return raw body without HTML stripping") })
            },
            required = listOf("url"),
        )
    },
    execute = {
        val p = it.jsonObject
        val url = p["url"]?.jsonPrimitive?.contentOrNull ?: error("url is required")
        // 只允许 http(s)：file:// / ftp:// 会在 `as HttpURLConnection` 处抛 ClassCastException，
        // 报错信息对模型毫无意义，直接给出明确拒绝。
        require(url.startsWith("http://", true) || url.startsWith("https://", true)) {
            "url 必须以 http:// 或 https:// 开头"
        }
        val maxChars = (p["max_chars"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 8000).coerceIn(200, 200_000)
        val includeLinks = p["include_links"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val raw = p["raw"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

        val payload = withContext(Dispatchers.IO) {
            runCatching {
                var current = url
                var conn: HttpURLConnection
                var code: Int
                var redirects = 0
                while (true) {
                    conn = (URL(current).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20_000; readTimeout = 40_000
                        instanceFollowRedirects = false
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) RikkaHub")
                        setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                        setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                        setRequestProperty("Accept-Encoding", "gzip")
                    }
                    code = conn.responseCode
                    val loc = conn.getHeaderField("Location")
                    if (code in 300..399 && loc != null && redirects < 8) {
                        current = URL(URL(current), loc).toString()
                        conn.disconnect(); redirects++; continue
                    }
                    break
                }
                val stream0 = if (code in 200..299) conn.inputStream else conn.errorStream
                val stream = if ((conn.contentEncoding ?: "").contains("gzip", true) && stream0 != null)
                    GZIPInputStream(stream0) else stream0
                val bytes = stream?.use { s ->
                    val bos = ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
                    var total = 0
                    while (true) { val n = s.read(buf); if (n < 0) break; total += n; if (total > 5_000_000) break; bos.write(buf, 0, n) }
                    bos.toByteArray()
                } ?: ByteArray(0)
                val contentType = conn.contentType ?: ""
                var charset = Regex("charset=([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.getOrNull(1)
                conn.disconnect()
                val rawText = decode(bytes, charset)
                val isHtml = contentType.contains("html", true) || rawText.trimStart().startsWith("<")
                val title = if (isHtml) Regex("(?is)<title[^>]*>(.*?)</title>").find(rawText)?.groupValues?.getOrNull(1)?.trim() else null
                val text = if (raw || !isHtml) rawText else stripHtml(rawText)
                val links = if (includeLinks && isHtml) extractLinks(rawText) else emptyList()
                Fetched(code, contentType, current, title, text, links)
            }
        }

        val out = payload.map { f ->
            buildJsonObject {
                put("status", f.status); put("content_type", f.contentType); put("final_url", f.finalUrl)
                f.title?.let { put("title", it) }
                put("text", f.text.take(maxChars))
                if (includeLinks) {
                    put("links", buildJsonArray { f.links.take(200).forEach { add(it) } })
                }
            }
        }.getOrElse { e -> buildJsonObject { put("error", e.message ?: e.javaClass.simpleName) } }
        listOf(UIMessagePart.Text(out.toString()))
    }
)

private data class Fetched(
    val status: Int,
    val contentType: String,
    val finalUrl: String,
    val title: String?,
    val text: String,
    val links: List<String>,
)

private fun decode(bytes: ByteArray, charsetName: String?): String {
    val candidates = listOfNotNull(charsetName, "UTF-8", "GBK").distinct()
    for (cs in candidates) {
        runCatching { return String(bytes, charset(cs)) }
    }
    return String(bytes, Charsets.UTF_8)
}

private fun extractLinks(html: String): List<String> =
    Regex("(?is)href\\s*=\\s*[\"']([^\"'#]+)[\"']").findAll(html)
        .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
        .filter { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("/") }
        .distinct().take(200).toList()

private fun stripHtml(html: String): String {
    var s = html.replace(Regex("(?is)<script.*?</script>"), " ")
        .replace(Regex("(?is)<style.*?</style>"), " ")
        .replace(Regex("(?is)<noscript.*?</noscript>"), " ")
        .replace(Regex("(?is)<head.*?</head>"), " ")
        .replace(Regex("(?s)<[^>]+>"), " ")
    s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
    s = s.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n").trim()
    return s
}
