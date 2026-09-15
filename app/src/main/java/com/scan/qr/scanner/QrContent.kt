package com.scan.qr.scanner

import java.net.URLDecoder

/** 二维码内容类型 */
enum class QrType(val label: String) {
    URL("网页链接"),
    TEL("电话号码"),
    SMS("短信"),
    EMAIL("Email"),
    WIFI("Wi-Fi"),
    GEO("地理位置"),
    APP("App 链接"),
    TEXT("文本"),
    UNKNOWN("未知链接类型")
}

/** Wi-Fi 二维码解析结果 */
data class WifiInfo(
    val ssid: String,
    val security: String,
    val password: String?,
    val hidden: Boolean
)

/**
 * 二维码内容（不可信输入）。
 * 只做解析与分类，不执行任何动作。
 */
data class QrContent(
    val raw: String,
    val type: QrType,
    val display: String,
    val detail: String? = null,
    val wifi: WifiInfo? = null
) {
    /** 复制/分享用的纯文本 */
    val copyText: String
        get() = if (wifi != null) {
            buildString {
                appendLine("Wi-Fi")
                appendLine("网络名称：${wifi.ssid}")
                appendLine("安全类型：${wifi.security}")
                wifi.password?.let { appendLine("密码：$it") }
            }.trim()
        } else raw
}

/** 明确禁止的 scheme，避免恶意二维码触发危险行为 */
private val BLOCKED_SCHEMES = setOf(
    "javascript", "data", "file", "content", "intent", "blob", "about", "vbscript"
)

/** 合法 scheme：字母开头，后跟字母/数字/+/-/. */
private val SCHEME_REGEX = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*):(.*)$", RegexOption.DOT_MATCHES_ALL)

/**
 * 规范解析二维码文本：先抽取 scheme，再按类型分别解析，不依赖 contains() 粗暴判断。
 * 纯 Kotlin 实现（不依赖 android.net.Uri），便于单元测试。
 */
fun parseQrContent(raw: String): QrContent {
    val text = raw.trim()
    if (text.isEmpty()) return QrContent(raw, QrType.UNKNOWN, "")
    if (text.length > MAX_PARSE_LENGTH) return QrContent(raw, QrType.TEXT, text)

    if (text.startsWith("WIFI:", ignoreCase = true)) return parseWifi(text, raw)

    val match = SCHEME_REGEX.find(text)
        ?: return QrContent(raw, QrType.TEXT, text) // 无 scheme → 纯文本

    val scheme = match.groupValues[1].lowercase()
    val body = match.groupValues[2]
    if (scheme in BLOCKED_SCHEMES) return QrContent(raw, QrType.UNKNOWN, text)

    return when (scheme) {
        "http", "https" -> {
            val rest = body.removePrefix("//")
            val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
            if (host.isBlank()) QrContent(raw, QrType.UNKNOWN, text)
            else QrContent(raw, QrType.URL, text)
        }

        "tel" -> {
            val number = body.removePrefix("//").substringBefore('?').trim()
            if (number.isBlank()) QrContent(raw, QrType.UNKNOWN, text)
            else QrContent(raw, QrType.TEL, decode(number))
        }

        "sms", "smsto", "mms", "mmsto" -> {
            val params = parseQuery(body)
            val number = body.removePrefix("//").substringBefore('?').trim()
            val message = params["body"]
            if (number.isBlank() && message.isNullOrBlank()) QrContent(raw, QrType.UNKNOWN, text)
            else QrContent(raw, QrType.SMS, decode(number), message)
        }

        "mailto" -> {
            val params = parseQuery(body)
            val address = body.removePrefix("//").substringBefore('?').trim()
            if (address.isBlank()) QrContent(raw, QrType.UNKNOWN, text)
            else QrContent(
                raw,
                QrType.EMAIL,
                decode(address),
                listOfNotNull(
                    params["subject"]?.takeIf { it.isNotBlank() }?.let { "主题：$it" },
                    params["body"]?.takeIf { it.isNotBlank() }
                ).takeIf { it.isNotEmpty() }?.joinToString("\n")
            )
        }

        "geo" -> {
            val coords = body.substringBefore('?').trim()
            val parts = coords.split(',')
            val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
            val lon = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
            if (lat == null || lon == null) QrContent(raw, QrType.UNKNOWN, text)
            else QrContent(raw, QrType.GEO, "${parts[0].trim()}, ${parts[1].trim()}")
        }

        else -> {
            val specific = body.removePrefix("//")
            if (specific.isBlank()) QrContent(raw, QrType.UNKNOWN, text)
            else QrContent(raw, QrType.APP, text)
        }
    }
}

private const val MAX_PARSE_LENGTH = 8000

/** 解析 ?a=1&b=2 形式的查询参数（值做 percent 解码） */
private fun parseQuery(body: String): Map<String, String> {
    val idx = body.indexOf('?')
    if (idx < 0 || idx == body.lastIndex) return emptyMap()
    return body.substring(idx + 1)
        .split('&')
        .mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val key = pair.substringBefore('=').lowercase()
            val value = pair.substringAfter('=', "")
            key to decode(value)
        }
        .toMap()
}

private fun decode(value: String): String = try {
    URLDecoder.decode(value, "UTF-8")
} catch (e: Exception) {
    value
}

private fun parseWifi(text: String, raw: String): QrContent {
    val body = text.substring(5)
    var ssid = ""
    var security = ""
    var password: String? = null
    var hidden = false

    for (field in splitWifiFields(body)) {
        if (field.length < 2 || field[1] != ':') continue
        val value = field.substring(2)
        when (field[0].uppercaseChar()) {
            'S' -> ssid = value
            'T' -> security = value
            'P' -> password = value
            'H' -> hidden = value.equals("true", ignoreCase = true)
        }
    }

    if (ssid.isBlank()) return QrContent(raw, QrType.UNKNOWN, text)
    val secLabel = if (security.isBlank() || security.equals("nopass", ignoreCase = true)) {
        "无"
    } else {
        security.uppercase()
    }
    return QrContent(raw, QrType.WIFI, ssid, wifi = WifiInfo(ssid, secLabel, password, hidden))
}

/** 按未转义的 ';' 切分 Wi-Fi 字段，并处理 \; \, \: \\ 转义 */
private fun splitWifiFields(body: String): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var escaped = false
    for (ch in body) {
        when {
            escaped -> {
                sb.append(ch)
                escaped = false
            }

            ch == '\\' -> escaped = true
            ch == ';' -> {
                out.add(sb.toString())
                sb.clear()
            }

            else -> sb.append(ch)
        }
    }
    if (sb.isNotEmpty()) out.add(sb.toString())
    return out
}
