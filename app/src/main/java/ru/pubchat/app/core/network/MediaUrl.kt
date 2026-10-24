package ru.pubchat.app.core.network

import java.net.URI

internal fun resolveMediaUrl(baseUrl: String, value: String?): String? {
    if (value.isNullOrBlank()) return null
    return runCatching {
        val base = URI(baseUrl.trimEnd('/'))
        val supplied = URI(value)
        val uri = if (supplied.isAbsolute) supplied else {
            require(!value.startsWith("//") && supplied.rawPath?.split('/')?.none { it == ".." } != false)
            URI(base.toString() + "/" + value.trimStart('/'))
        }
        uri.takeIf { it.scheme in setOf("https", "http") && it.host != null }?.toString()
    }.getOrNull()
}

/**
 * Only same-origin media can receive the signed-in account's bearer token.
 * Never attach credentials to third-party or redirect targets.
 */
internal fun authorizedMediaUrl(baseUrl: String, value: String?): String? {
    val candidate = resolveMediaUrl(baseUrl, value) ?: return null
    return runCatching {
        val base = URI(baseUrl)
        val target = URI(candidate)
        fun effectivePort(uri: URI): Int = when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals("https", true) -> 443
            uri.scheme.equals("http", true) -> 80
            else -> -1
        }
        candidate.takeIf {
            target.userInfo == null && target.fragment == null &&
                target.scheme.equals(base.scheme, ignoreCase = true) &&
                target.host.equals(base.host, ignoreCase = true) &&
                effectivePort(target) == effectivePort(base)
        }
    }.getOrNull()
}
