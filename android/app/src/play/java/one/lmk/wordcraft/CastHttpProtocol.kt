package one.lmk.wordcraft

import java.net.Inet4Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

internal enum class CastHttpAction {
    GET_PNG,
    HEAD_PNG,
    OPTIONS,
    NOT_FOUND,
    BAD_REQUEST,
}

/**
 * Pure JVM-testable HTTP request validation and LAN IPv4 selection helpers for the `play`
 * Google Cast local image server.
 */
internal object CastHttpProtocol {
    private const val HEX_DIGITS = "0123456789abcdef"

    fun generateToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_DIGITS[v ushr 4]
            out[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
        }
        return String(out)
    }

    fun isUsableLanIpv4(addr: InetAddress): Boolean =
        addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress && !addr.isMulticastAddress

    fun selectPreferredLanIpv4(candidates: Iterable<InetAddress>): String? {
        var fallback: InetAddress? = null
        for (addr in candidates) {
            if (!isUsableLanIpv4(addr)) continue
            if (addr.isSiteLocalAddress) {
                return addr.hostAddress
            }
            if (fallback == null) {
                fallback = addr
            }
        }
        return fallback?.hostAddress
    }

    fun buildFrameUrl(hostIp: String, port: Int, token: String, seq: Long): String? {
        if (hostIp.isBlank() || port !in 1..65535 || token.isEmpty() || seq < 0L) {
            return null
        }
        return "http://$hostIp:$port/cast/$token/$seq.png"
    }

    fun parseRequestLine(requestLine: String?, expectedToken: String): CastHttpAction {
        if (requestLine.isNullOrBlank() || expectedToken.isEmpty()) {
            return CastHttpAction.BAD_REQUEST
        }
        val parts = requestLine.trim().split(' ')
        if (parts.size < 3 || !parts[2].startsWith("HTTP/")) {
            return CastHttpAction.BAD_REQUEST
        }
        val method = parts[0]
        val rawTarget = parts[1]
        if (method == "OPTIONS") {
            return if (rawTarget == "*" || isAuthorizedCastPath(rawTarget, expectedToken)) {
                CastHttpAction.OPTIONS
            } else {
                CastHttpAction.NOT_FOUND
            }
        }
        if (method != "GET" && method != "HEAD") {
            return CastHttpAction.BAD_REQUEST
        }
        if (!isAuthorizedCastPath(rawTarget, expectedToken)) {
            return CastHttpAction.NOT_FOUND
        }
        return if (method == "GET") CastHttpAction.GET_PNG else CastHttpAction.HEAD_PNG
    }

    private fun isAuthorizedCastPath(rawTarget: String, expectedToken: String): Boolean {
        val path = rawTarget.substringBefore('?')
        if (!path.startsWith("/cast/") || !path.endsWith(".png") || path.contains("..") || path.contains('%') || path.contains('\\')) {
            return false
        }
        val trimmed = path.removePrefix("/cast/")
        val slash = trimmed.indexOf('/')
        if (slash <= 0 || slash == trimmed.lastIndex) {
            return false
        }
        val providedToken = trimmed.substring(0, slash)
        val fileName = trimmed.substring(slash + 1)
        if (!fileName.removeSuffix(".png").all { it in '0'..'9' } || fileName.length > 24) {
            return false
        }
        val expectedBytes = expectedToken.toByteArray(StandardCharsets.UTF_8)
        val providedBytes = providedToken.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(expectedBytes, providedBytes)
    }
}
