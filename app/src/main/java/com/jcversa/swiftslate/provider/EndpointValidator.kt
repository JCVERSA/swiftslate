package com.jcversa.swiftslate.provider

import java.net.URI

/**
 * Endpoint validation shared by the Settings screen and the OpenAI-compatible
 * client. https:// is unrestricted (but must carry a host); cleartext http:// is
 * permitted only for private-LAN hosts (loopback, the Android emulator alias,
 * RFC1918 private ranges, link-local, unique-local IPv6, and mDNS hostnames).
 *
 * Android's network security config cannot express IP ranges, so the platform
 * layer allows cleartext while this guard enforces the private-LAN rule at the
 * app level. [com.jcversa.swiftslate.api.ApiConnections] re-checks every URL
 * through this validator at connection time, so a call site cannot bypass it.
 */
object EndpointValidator {

    enum class Error { NONE, INVALID }

    /**
     * Validates [endpoint]: `Error.NONE` if it is a usable https:// URL or an
     * http:// URL pointing at a private-LAN host; `Error.INVALID` otherwise
     * (including blank, schemeless, hostless, or malformed values). The scheme
     * match is case-insensitive (`HTTPS://host` is accepted); host matching was
     * already case-insensitive via lowercase normalization in [isPrivateHost].
     */
    fun validate(endpoint: String): Error {
        if (endpoint.isBlank()) return Error.INVALID
        val normalized = withNormalizedScheme(endpoint)
        if (normalized.startsWith("https://")) {
            // A scheme alone is not an endpoint: bare "https://" previously passed
            // validation and failed later at the network layer with a generic error.
            val host = try { URI(normalized).host } catch (_: Exception) { null }
            return if (host.isNullOrBlank()) Error.INVALID else Error.NONE
        }
        if (!normalized.startsWith("http://")) return Error.INVALID
        val host = try { URI(normalized).host } catch (_: Exception) { return Error.INVALID }
        if (host.isNullOrEmpty() || !isPrivateHost(host)) return Error.INVALID
        return Error.NONE
    }

    /**
     * Lowercases the URL scheme (`HTTPS://x` -> `https://x`), leaving the remainder
     * byte-identical: hosts are case-insensitive but paths may not be. Applied before
     * every check and before `java.net.URL` construction, whose handler lookup expects
     * a lowercase protocol.
     */
    internal fun withNormalizedScheme(url: String): String = when {
        url.startsWith("https://", ignoreCase = true) -> "https://" + url.substring(8)
        url.startsWith("http://", ignoreCase = true) -> "http://" + url.substring(7)
        else -> url
    }

    /** Whether [host] is a private-LAN hostname or IP address. */
    fun isPrivateHost(host: String): Boolean {
        // URI.getHost() returns IPv6 literals with brackets ([::1]) — strip them.
        val h = host.trim().lowercase().removeSurrounding("[", "]").removeSuffix(".")
        if (h == "localhost" || h == "::1") return true
        if (h.endsWith(".local") || h.endsWith(".lan")) return true
        // A colon can only appear in an IPv6 literal (URI.getHost strips the port),
        // so anything colon-shaped that is not private-IPv6 is rejected, never
        // passed on to the IPv4 check.
        if (':' in h) return isPrivateIPv6Literal(h)
        val ip = h.toIPv4OrNull() ?: return false
        return isPrivateIPv4(ip)
    }

    private fun isPrivateIPv4(ip: IntArray): Boolean {
        if (ip[0] == 127) return true // loopback 127.0.0.0/8
        if (ip[0] == 10) return true // RFC1918 10.0.0.0/8 (also the Android emulator alias 10.0.2.2)
        if (ip[0] == 172 && ip[1] in 16..31) return true // RFC1918 172.16.0.0/12
        if (ip[0] == 192 && ip[1] == 168) return true // RFC1918 192.168.0.0/16
        if (ip[0] == 169 && ip[1] == 254) return true // link-local 169.254.0.0/16
        if (ip[0] == 100 && ip[1] in 64..127) return true // CGNAT 100.64.0.0/10 (Tailscale/ZeroTier)
        return false
    }

    /**
     * Whether a bracket-stripped, lowercased IPv6 literal is link-local (fe80::/10)
     * or unique-local (fc00::/7). Judged from the first hextet, which survives `::`
     * compression everywhere except a leading one — and a leading `::` means the
     * address is `::`, `::1` (handled by the caller) or an embedded-IPv4 form, of
     * which only the mapped `::ffff:a.b.c.d` shape is judged by its IPv4 tail.
     * Anything else (zone ids with `%`, multicast, global unicast, garbage) fails
     * closed. Deliberately textual: `InetAddress.getByName` can hit DNS, which this
     * validator — called on the UI thread from Settings — must never do.
     */
    private fun isPrivateIPv6Literal(h: String): Boolean {
        if (h.any { it !in '0'..'9' && it !in 'a'..'f' && it != ':' && it != '.' }) return false
        val first = h.split(':').firstOrNull { it.isNotEmpty() } ?: return false
        if (first == "ffff" && '.' in h) {
            val tail = h.substringAfterLast(':').toIPv4OrNull() ?: return false
            return isPrivateIPv4(tail)
        }
        if ('.' in first) return false // "::a.b.c.d" compat form — deprecated, fail closed
        val group = first.toIntOrNull(16) ?: return false
        if (group !in 0..0xFFFF) return false
        return group in 0xFE80..0xFEBF || group in 0xFC00..0xFDFF
    }

    private fun String.toIPv4OrNull(): IntArray? {
        val parts = split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in parts.indices) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            out[i] = n
        }
        return out
    }
}
