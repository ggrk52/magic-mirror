package com.codex.magicmirrorcontroller.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingPayloadParserTest {
    @Test
    fun parsesValidPairingPayload() {
        val payload = parsePairingPayload(
            """
            {
              "type": "magic-mirror-pair",
              "version": 1,
              "token": "secret-token",
              "port": 8080,
              "hosts": ["192.168.1.75", "[fe80::1]"],
              "service": "Magic Mirror"
            }
            """.trimIndent(),
        )

        assertEquals("secret-token", payload?.token)
        assertEquals(8080, payload?.port)
        assertEquals(listOf("192.168.1.75", "fe80::1"), payload?.hosts)
        assertEquals("Magic Mirror", payload?.service)
    }

    @Test
    fun rejectsNonMagicMirrorPayload() {
        assertNull(parsePairingPayload("""{"type":"other","version":1,"token":"secret"}"""))
    }

    @Test
    fun rejectsPayloadWithoutToken() {
        assertNull(parsePairingPayload("""{"type":"magic-mirror-pair","version":1}"""))
    }
}
