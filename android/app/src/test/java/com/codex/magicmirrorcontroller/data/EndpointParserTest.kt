package com.codex.magicmirrorcontroller.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointParserTest {
    @Test
    fun parsesBareIpv4WithFallbackPort() {
        val endpoint = parseEndpointInput("192.168.1.75", "8080")

        assertEquals("192.168.1.75", endpoint?.host)
        assertEquals(8080, endpoint?.port)
    }

    @Test
    fun parsesUrlWithPort() {
        val endpoint = parseEndpointInput("http://192.168.1.75:9090/", "8080")

        assertEquals("192.168.1.75", endpoint?.host)
        assertEquals(9090, endpoint?.port)
    }

    @Test
    fun parsesBracketedIpv6WithPort() {
        val endpoint = parseEndpointInput("[fe80::1]:8080", "9090")

        assertEquals("fe80::1", endpoint?.host)
        assertEquals(8080, endpoint?.port)
    }

    @Test
    fun rejectsBlankHost() {
        assertNull(parseEndpointInput("", "8080"))
    }
}
