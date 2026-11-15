package one.lmk.wordcraft

import android.content.Context
import android.net.ConnectivityManager
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Lightweight local-LAN-only HTTP PNG server used exclusively during an active Google Cast session
 * in the `play` flavor. Keeps only the latest rendered page PNG in memory behind an ephemeral
 * random token URL (`/cast/<token>/<seq>.png`).
 */
internal class LocalCastHttpServer(private val appContext: Context) {
    companion object {
        private const val MAX_REQUEST_LINE_BYTES = 2048
        private const val MAX_HEADER_LINES = 32
        private const val SOCKET_TIMEOUT_MS = 3000
        private const val MAX_PNG_BYTES = 16 * 1024 * 1024
    }

    private val lock = Any()
    private val latestFrame = AtomicReference<Pair<Long, ByteArray>?>(null)
    private val seqCounter = AtomicLong(0L)

    @Volatile
    private var token: String = CastHttpProtocol.generateToken()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false
    private var acceptThread: Thread? = null
    private var workerPool: ExecutorService? = null

    fun updateFrame(pngBytes: ByteArray): String? {
        if (pngBytes.isEmpty() || pngBytes.size > MAX_PNG_BYTES) {
            return null
        }
        val port = ensureStarted() ?: return null
        val hostIp = resolveLanIpv4() ?: return null
        val seq = seqCounter.incrementAndGet()
        latestFrame.set(seq to pngBytes)
        return CastHttpProtocol.buildFrameUrl(hostIp, port, token, seq)
    }

    fun clearFrame() {
        latestFrame.set(null)
    }

    fun ensureStarted(): Int? {
        synchronized(lock) {
            val existing = serverSocket
            if (running && existing != null && !existing.isClosed) {
                return existing.localPort
            }
            val socket = try {
                ServerSocket(0).apply {
                    reuseAddress = true
                }
            } catch (_: Throwable) {
                return null
            }
            token = CastHttpProtocol.generateToken()
            serverSocket = socket
            running = true
            val pool = Executors.newFixedThreadPool(2) { runnable ->
                Thread(runnable, "docone-cast-worker").apply { isDaemon = true }
            }
            workerPool = pool
            acceptThread = Thread({ acceptLoop(socket, pool) }, "docone-cast-accept").apply {
                isDaemon = true
                start()
            }
            return socket.localPort
        }
    }

    fun stop() {
        val socketToClose: ServerSocket?
        val poolToShutdown: ExecutorService?
        synchronized(lock) {
            running = false
            socketToClose = serverSocket
            serverSocket = null
            acceptThread = null
            poolToShutdown = workerPool
            workerPool = null
        }
        latestFrame.set(null)
        try {
            socketToClose?.close()
        } catch (_: Throwable) {
        }
        try {
            poolToShutdown?.shutdownNow()
        } catch (_: Throwable) {
        }
    }

    private fun acceptLoop(socket: ServerSocket, pool: ExecutorService) {
        while (running && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Throwable) {
                break
            }
            try {
                pool.execute { handleClient(client) }
            } catch (_: Throwable) {
                try {
                    client.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = SOCKET_TIMEOUT_MS
                val input = BufferedInputStream(sock.getInputStream(), 2048)
                val requestLine = readLineBounded(input)
                var headerCount = 0
                while (headerCount < MAX_HEADER_LINES) {
                    val hdr = readLineBounded(input) ?: break
                    if (hdr.isEmpty()) break
                    headerCount++
                }
                val action = CastHttpProtocol.parseRequestLine(requestLine, token)
                val out = sock.getOutputStream()
                when (action) {
                    CastHttpAction.OPTIONS -> writeResponse(out, "204 No Content", null, includeBody = false)
                    CastHttpAction.GET_PNG, CastHttpAction.HEAD_PNG -> {
                        val png = latestFrame.get()?.second
                        if (png == null) {
                            writeResponse(out, "404 Not Found", null, includeBody = false)
                        } else {
                            writeResponse(out, "200 OK", png, includeBody = action == CastHttpAction.GET_PNG)
                        }
                    }
                    CastHttpAction.NOT_FOUND -> writeResponse(out, "404 Not Found", null, includeBody = false)
                    CastHttpAction.BAD_REQUEST -> writeResponse(out, "400 Bad Request", null, includeBody = false)
                }
                out.flush()
            } catch (_: Throwable) {
            }
        }
    }

    private fun writeResponse(out: OutputStream, status: String, body: ByteArray?, includeBody: Boolean) {
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
            append("Access-Control-Allow-Private-Network: true\r\n")
            append("Cache-Control: no-store\r\n")
            if (body != null) {
                append("Content-Type: image/png\r\n")
                append("Content-Length: ").append(body.size).append("\r\n")
            } else {
                append("Content-Length: 0\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray(StandardCharsets.US_ASCII))
        if (includeBody && body != null) {
            out.write(body)
        }
    }

    private fun readLineBounded(input: BufferedInputStream): String? {
        val baos = ByteArrayOutputStream(128)
        while (baos.size() < MAX_REQUEST_LINE_BYTES) {
            val b = input.read()
            if (b < 0) {
                return if (baos.size() == 0) null else baos.toString(StandardCharsets.US_ASCII.name())
            }
            if (b == '\n'.code) {
                val raw = baos.toByteArray()
                val len = if (raw.isNotEmpty() && raw[raw.lastIndex] == '\r'.code.toByte()) raw.size - 1 else raw.size
                return String(raw, 0, len, StandardCharsets.US_ASCII)
            }
            baos.write(b)
        }
        return null
    }

    private fun resolveLanIpv4(): String? {
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val linkProps = if (network != null) cm.getLinkProperties(network) else null
            val activeAddrs = linkProps?.linkAddresses?.map { it.address }.orEmpty()
            CastHttpProtocol.selectPreferredLanIpv4(activeAddrs)?.let { return it }
        } catch (_: Throwable) {
        }
        return try {
            val candidates = mutableListOf<InetAddress>()
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces != null && ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    candidates.add(addrs.nextElement())
                }
            }
            CastHttpProtocol.selectPreferredLanIpv4(candidates)
        } catch (_: Throwable) {
            null
        }
    }
}
