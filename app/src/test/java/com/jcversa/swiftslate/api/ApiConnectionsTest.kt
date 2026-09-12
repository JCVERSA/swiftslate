package com.jcversa.swiftslate.api

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure unit tests for the connection policy. No sockets are opened. */
class ApiConnectionsTest {

    @Test
    fun checkHttps_acceptsHttpsWithHost() {
        assertEquals(
            "https://api.example.com/v1",
            ApiConnections.checkHttps("https://api.example.com/v1")
        )
    }

    @Test
    fun checkHttps_normalizesUppercaseSchemeWithoutTouchingTheRest() {
        assertEquals(
            "https://Example.COM/Path",
            ApiConnections.checkHttps("HTTPS://Example.COM/Path")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun checkHttps_rejectsPrivateLanHttp() {
        ApiConnections.checkHttps("http://192.168.1.5:8080/v1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun checkHttps_rejectsHostlessHttps() {
        ApiConnections.checkHttps("https://")
    }

    @Test
    fun checkProviderUrl_acceptsHttpsAndPrivateLanHttp() {
        assertEquals(
            "https://api.example.com/v1",
            ApiConnections.checkProviderUrl("https://api.example.com/v1")
        )
        assertEquals(
            "http://192.168.1.5:8080/v1",
            ApiConnections.checkProviderUrl("http://192.168.1.5:8080/v1")
        )
        assertEquals(
            "http://[fe80::1]:8080/v1",
            ApiConnections.checkProviderUrl("http://[fe80::1]:8080/v1")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun checkProviderUrl_rejectsPublicHttp() {
        ApiConnections.checkProviderUrl("http://api.example.com/v1")
    }
}
