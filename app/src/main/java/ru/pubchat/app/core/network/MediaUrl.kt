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