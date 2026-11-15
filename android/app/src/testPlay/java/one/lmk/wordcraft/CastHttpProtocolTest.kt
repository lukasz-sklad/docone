package one.lmk.wordcraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class CastHttpProtocolTest {

    @Test
    fun generatedTokensAreHexAndUnique() {
        val a = CastHttpProtocol.generateToken()
        val b = CastHttpProtocol.generateToken()
        assertEquals(32, a.length)
        assertEquals(32, b.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(a != b)
    }

    @Test
    fun parseRequestLineAcceptsOnlyValidTokenAndSafeCastPath() {
        val token = "0123456789abcdef0123456789abcdef"
        assertEquals(
            CastHttpAction.GET_PNG,
            CastHttpProtocol.parseRequestLine("GET /cast/$token/42.png HTTP/1.1", token),
        )
        assertEquals(
            CastHttpAction.HEAD_PNG,
            CastHttpProtocol.parseRequestLine("HEAD /cast/$token/42.png?t=1 HTTP/1.1", token),
        )
        assertEquals(
            CastHttpAction.OPTIONS,
            CastHttpProtocol.parseRequestLine("OPTIONS /cast/$token/42.png HTTP/1.1", token),
        )
        assertEquals(
            CastHttpAction.NOT_FOUND,
            CastHttpProtocol.parseRequestLine("GET /cast/wrongtoken/42.png HTTP/1.1", token),
        )
        assertEquals(
            CastHttpAction.NOT_FOUND,
            CastHttpProtocol.parseRequestLine("GET /cast/$token/../etc/passwd.png HTTP/1.1", token),
        )
        assertEquals(
            CastHttpAction.NOT_FOUND,
            CastHttpProtocol.parseRequestLine("GET /cast/$token/abc.png HTTP/1.1", token),
        )
        assertEquals(
            CastHttpAction.BAD_REQUEST,
            CastHttpProtocol.parseRequestLine("POST /cast/$token/42.png HTTP/1.1", token),
        )
        assertEquals(
            CastHttpAction.BAD_REQUEST,
            CastHttpProtocol.parseRequestLine(null, token),
        )
    }

    @Test
    fun selectPreferredLanIpv4SkipsLoopbackLinkLocalAndIpv6() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val linkLocal = InetAddress.getByName("169.254.1.5")
        val ipv6 = InetAddress.getByName("fe80::1")
        val lan = InetAddress.getByName("192.168.1.13")

        assertFalse(CastHttpProtocol.isUsableLanIpv4(loopback))
        assertFalse(CastHttpProtocol.isUsableLanIpv4(linkLocal))
        assertFalse(CastHttpProtocol.isUsableLanIpv4(ipv6))
        assertTrue(CastHttpProtocol.isUsableLanIpv4(lan))

        assertEquals(
            "192.168.1.13",
            CastHttpProtocol.selectPreferredLanIpv4(listOf(loopback, linkLocal, ipv6, lan)),
        )
        assertNull(CastHttpProtocol.selectPreferredLanIpv4(listOf(loopback, linkLocal, ipv6)))
    }
}
