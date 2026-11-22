package com.b3ther.app.core

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Real delay of a custom config, measured THROUGH the config.
 *
 * ## Why the old probe reported -1 / "Filtered" for configs that work
 *
 * The 1.5.0 probe opened its own TCP socket to the server and, for TLS and
 * REALITY configs, ran the platform's TLS handshake against it. That measures
 * something the session never does:
 *
 *  * A REALITY server only completes a handshake for a client that carries the
 *    config's REALITY key. A plain ClientHello is relayed to the camouflage site
 *    or held open, so the probe hit its timeout and reported "Filtered" for a
 *    perfectly healthy server.
 *  * The platform ClientHello (Conscrypt) is not the config's uTLS fingerprint,
 *    and filtering middleboxes treat the two differently.
 *  * The server name was resolved with the network's resolver, while the session
 *    resolves it with Xray's own DNS; a poisoned answer made the probe dial an
 *    address the session never uses.
 *  * A TCP connect proves little: transparent middleboxes accept every SYN and
 *    then drop the stream.
 *
 * ## What is measured now
 *
 * The same kind of end-to-end HTTPS request as v2rayNG's "real delay": the config's own outbound, built
 * by the same code as a direct session (the probe path), runs
 * in the bundled Xray core, and one HTTP request for `generate_204` is sent
 * through it. The clock includes remote CONNECT/TLS on a cold attempt and the
 * complete validated 204 response headers; a reused attempt measures a warm request.
 * Two attempts are taken on the same reusable channel and the faster successful one is reported.
 * A transient failure of the first attempt is retried once; startup and scheduling are not timed.
 *
 * Many configs share one Xray process (one SOCKS inbound per config, routed to
 * its own outbound). A config the core refuses would stop the whole batch from
 * starting, so a batch that does not start is retried one config per process.
 *
 * Nothing here has Android types, so the whole path runs in JVM tests against a
 * real Xray binary.
 */
object RealDelay {

    /** Explicit HTTPS generate_204 target (v2rayNG supports a configurable test URL). */
    internal val TARGETS = listOf("www.gstatic.com")
    internal const val TARGET_PATH = "/generate_204"

    /** Budget for one request through the config, handshake included. */
    const val REQUEST_TIMEOUT_MS = 12_000

    sealed class Result {
        /** The config carried a request; [ms] is the faster of the rounds. */
        data class Ok(val ms: Long) : Result()

        /** The core refused this config (it cannot run at all). */
        data class Invalid(val reason: String) : Result()

        /** The core ran the config, and nothing came back through it. */
        data class NoReply(val reason: String) : Result()
    }


    internal class PartialResponse(message: String) : IOException(message)

    /** A well-formed HTTP response with a status other than the expected 204. */
    internal class UnexpectedStatus(val code: Int) : IOException("http $code")


    internal fun measureSession(session: ProbeSession): Result {
        var best = -1L
        var lastError = "no reply"
        repeat(ROUNDS) {
            try {
                val ms = session.request()
                best = if (best < 0) ms else minOf(best, ms)
            } catch (e: IOException) {
                lastError = describe(e)
                session.close() // request can reopen on the next attempt
            }
        }
        return if (best >= 0) Result.Ok(best.coerceAtLeast(1L)) else Result.NoReply(lastError)
    }


    /**
     * Parse an HTTP/1.x status line using an explicit version allowlist. A null
     * result is malformed (including unsupported/non-numeric versions), not a
     * successful 204. The reason phrase is optional; the status code is exactly
     * three ASCII digits in the HTTP status-code range.
     */
    internal fun parseStatusLine(line: String): Int? {
        if (line.any { it == '\r' || it == '\n' || (it.code < 0x20 && it != '\t') }) return null
        val versionEnd = line.indexOf(' ')
        if (versionEnd <= 0) return null
        val version = line.substring(0, versionEnd)
        if (version != "HTTP/1.0" && version != "HTTP/1.1") return null
        val rest = line.substring(versionEnd + 1)
        if (rest.length < 3) return null
        val digits = rest.substring(0, 3)
        if (!digits.all { it in '0'..'9' }) return null
        if (rest.length > 3 && rest[3] != ' ') return null
        val status = digits.toIntOrNull() ?: return null
        return status.takeIf { it in 100..599 }
    }

    /**
     * One certificate-validated HTTPS `GET /generate_204` through the SOCKS5 inbound at [port].
     *
     * The clock starts after the local SOCKS handshake (Xray answers that itself,

     * Local process startup/queue time is outside this clock. SOCKS mode sends the
     * domain remotely; direct mode necessarily uses the platform resolver.
     */
    internal class ProbeSession(
        private val port: Int, private val host: String, private val timeoutMs: Int,
        private val user: String, private val pass: String, private val useTls: Boolean,
        private val authenticated: Boolean,
    ) : AutoCloseable {
        @Volatile private var plain: Socket? = null
        @Volatile private var transport: Socket? = null

        fun request(): Long {
            // The timeout owns only THIS attempt's sockets. A cancelled timer that
            // has already started must never close a subsequent retry/session.
            val attempt = AttemptDeadline()
            plain?.let(attempt::watch)
            transport?.let(attempt::watch)
            val expiry = deadlines.schedule({ attempt.expire() }, timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            try {
                var started = System.nanoTime()
                var http = transport
                if (http == null) {
                    val socket = Socket().also { plain = it; attempt.watch(it) }
                    socket.tcpNoDelay = true
                    socket.soTimeout = timeoutMs
                    if (port > 0) socket.connect(InetSocketAddress(LOOPBACK, port), LOCAL_CONNECT_MS)
                    else socket.connect(InetSocketAddress(host, if (useTls) 443 else 80), timeoutMs)
                    if (port > 0) {
                    val out = socket.getOutputStream(); val input = socket.getInputStream()
                    out.write(byteArrayOf(5, 1, if (authenticated) 2 else 0)); out.flush()
                    val greeting = input.readExactly(2)
                    if (greeting[0].toInt() != 5 || greeting[1].toInt() != (if (authenticated) 2 else 0))
                        throw IOException("socks greeting refused")
                    if (authenticated) {
                        val u = user.toByteArray(Charsets.US_ASCII); val p = pass.toByteArray(Charsets.US_ASCII)
                        out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p); out.flush()
                        val auth = input.readExactly(2)
                        if (auth[0].toInt() != 1 || auth[1].toInt() != 0) throw IOException("socks authentication refused")
                    }
                    started = System.nanoTime() // exclude only loopback authentication, never remote CONNECT.
                    val name = host.toByteArray(Charsets.US_ASCII)
                    out.write(byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name +
                        byteArrayOf(if (useTls) 1 else 0, if (useTls) 187.toByte() else 80)); out.flush()
                    val head = input.readExactly(4)
                    if (head[0].toInt() != 5 || head[1].toInt() != 0 || head[2].toInt() != 0)
                        throw IOException("socks connect refused")
                    when (head[3].toInt()) {
                        1 -> input.readExactly(6)
                        4 -> input.readExactly(18)
                        3 -> input.readExactly((input.read().also { if (it < 0) throw EOFException() }) + 2)
                        else -> throw IOException("socks reply malformed")
                    }
                    }
                    http = if (useTls) {
                        (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory)
                            .createSocket(socket, host, 443, true).let { it as javax.net.ssl.SSLSocket }.apply {
                                attempt.watch(this)
                                soTimeout = timeoutMs
                                sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                                startHandshake(); soTimeout = timeoutMs
                            }
                    } else socket
                    transport = http
                }
                val output = http.getOutputStream(); val input = http.getInputStream()
                output.write(("GET $TARGET_PATH HTTP/1.1\r\nHost: $host\r\nUser-Agent: Mozilla/5.0\r\n" +
                    "Accept: */*\r\nConnection: keep-alive\r\n\r\n").toByteArray(Charsets.US_ASCII)); output.flush()
                val code = parseStatusLine(input.readCrlfLine(MAX_STATUS_LINE)) ?: throw PartialResponse("malformed HTTP status line")
                if (code != 204) throw UnexpectedStatus(code)
                // Consume the COMPLETE response headers; otherwise the next round can read
                // leftover bytes and mistake them for a new response. 204 cannot carry a body.
                var bytes = 0
                while (true) {
                    val line = input.readCrlfLine(8192); bytes += line.length + 2
                    if (bytes > 16_384) throw PartialResponse("oversized HTTP headers")
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon <= 0 || line.substring(0, colon).any {
                            !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9') && it !in "!#$%&'*+-.^_`|~"
                        } || line.any { it.code < 0x20 && it != '\t' })
                        throw PartialResponse("malformed HTTP header")
                    val key = line.substringBefore(':').lowercase(java.util.Locale.ROOT)
                    val value = line.substringAfter(':', "").trim()
                    if ((key == "content-length" && value.toLongOrNull() != 0L) || key == "transfer-encoding")
                        throw PartialResponse("unexpected 204 body framing")
                    if (key == "connection" && value.equals("close", true)) transport = null
                }
                val elapsed = (System.nanoTime() - started) / 1_000_000L
                if (transport == null) { runCatching { http.close() }; plain = null }
                return elapsed
            } catch (e: IOException) { close(); throw e
            } finally { attempt.finish(); expiry.cancel(false) }
        }

        /** A finished attempt can no longer close a reused keep-alive socket. */
        internal class AttemptDeadline {
            private var finished = false
            private var expired = false
            private val sockets = mutableSetOf<Socket>()
            @Synchronized fun watch(socket: Socket) {
                if (expired) { runCatching { socket.close() }; throw java.net.SocketTimeoutException("probe deadline") }
                sockets += socket
            }
            @Synchronized fun expire() {
                if (finished) return
                expired = true
                sockets.forEach { runCatching { it.close() } }
            }
            @Synchronized fun finish() { finished = true; sockets.clear() }
        }

        override fun close() {
            runCatching { transport?.close() }; runCatching { plain?.close() }
            transport = null; plain = null
        }
    }

    private fun describe(e: IOException): String = when (e) {
        is UnexpectedStatus -> "http ${e.code}"
        is PartialResponse -> "partial response"
        is java.net.SocketTimeoutException -> "timed out"
        is EOFException -> "closed by the server"
        else -> e.message?.take(80) ?: e.javaClass.simpleName
    }

    private fun InputStream.readExactly(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(buf, off, n - off)
            if (r < 0) throw EOFException()
            off += r
        }
        return buf
    }

    /** Read one complete CRLF-terminated status line; EOF/overflow is never success. */
    private fun InputStream.readCrlfLine(max: Int): String {
        val sb = StringBuilder()
        while (true) {
            val c = read()
            if (c < 0) throw PartialResponse("EOF before HTTP status line terminator")
            if (c == '\n'.code) {
                if (sb.isEmpty() || sb.last() != '\r') {
                    throw PartialResponse("HTTP status line was not CRLF terminated")
                }
                sb.setLength(sb.length - 1)
                if (sb.length > max || sb.indexOf("\r") >= 0) {
                    throw PartialResponse("malformed or oversized HTTP status line")
                }
                return sb.toString()
            }
            sb.append(c.toChar())
            if (sb.length > max + 1) throw PartialResponse("HTTP status line exceeds limit")
        }
    }

    private val deadlines = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "probe-deadline").apply { isDaemon = true }
    }
    private const val ROUNDS = 2
    private const val LOOPBACK = "127.0.0.1"
    private const val LOCAL_CONNECT_MS = 1_000
    private const val MAX_STATUS_LINE = 256
}