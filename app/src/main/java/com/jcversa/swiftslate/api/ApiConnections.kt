package com.jcversa.swiftslate.api

import com.jcversa.swiftslate.provider.EndpointValidator
import java.net.HttpURLConnection
import java.net.URL

/**
 * Single choke point for opening HTTP connections.
 *
 * Android's network security config cannot express IP ranges, so the platform layer permits
 * cleartext globally and the http://-only-for-private-LAN rule lives in [EndpointValidator].
 * Every client previously had to remember to call that validator itself; this object makes the
 * check unavoidable by performing it inside the `openConnection()` call every request path
 * already funnels through. Automatic redirects are disabled: following a Location response
 * inside HttpURLConnection could forward a provider key to another host or downgrade HTTPS.
 */
internal object ApiConnections {

    /**
     * Opens [urlString], requiring an `https://` URL with a non-blank host. For the
     * hardcoded provider/GitHub URLs, where cleartext must never occur.
     * @throws IllegalArgumentException when [urlString] is not an https URL with a host.
     */
    fun openHttps(urlString: String): HttpURLConnection =
        openWithoutRedirects(checkHttps(urlString))

    /**
     * Opens [urlString], requiring [EndpointValidator] acceptance (`https://` anywhere,
     * `http://` only for private-LAN hosts). For user-configured Custom endpoints.
     * @throws IllegalArgumentException when [urlString] fails validation.
     */
    fun openProviderUrl(urlString: String): HttpURLConnection =
        openWithoutRedirects(checkProviderUrl(urlString))

    private fun openWithoutRedirects(urlString: String): HttpURLConnection {
        val connection = URL(urlString).openConnection()
        require(connection is HttpURLConnection) { "HTTP connection required" }
        connection.instanceFollowRedirects = false
        return connection
    }

    /**
     * Pure policy behind [openHttps]: returns [urlString] with a normalized scheme, or
     * throws. Exposed for unit tests — the socket half is not JVM-testable.
     */
    fun checkHttps(urlString: String): String {
        val normalized = EndpointValidator.withNormalizedScheme(urlString)
        require(normalized.startsWith("https://")) { "https:// URL required" }
        val host = try { java.net.URI(normalized).host } catch (_: Exception) { null }
        require(!host.isNullOrBlank()) { "https:// URL must have a host" }
        return normalized
    }

    /**
     * Pure policy behind [openProviderUrl]: returns [urlString] with a normalized scheme,
     * or throws. Exposed for unit tests — the socket half is not JVM-testable.
     */
    fun checkProviderUrl(urlString: String): String {
        require(EndpointValidator.validate(urlString) == EndpointValidator.Error.NONE) {
            "Endpoint must be https:// or an http:// private-LAN address"
        }
        return EndpointValidator.withNormalizedScheme(urlString)
    }
}
