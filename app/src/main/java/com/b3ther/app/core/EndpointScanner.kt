package com.b3ther.app.core

import android.content.Context
import com.b3ther.app.model.IpVersion
import com.b3ther.app.model.Protocol
import com.b3ther.app.model.ScanMode
import com.b3ther.app.model.TransportBackend
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App-side WARP endpoint scanner behind the hamburger menu's "شروع اسکن".
 *
 * It mirrors the engine's own prober (native/aether/aether/src/prober.rs):
 * for regular (non-team) WARP on IPv4 the scan candidates are the
 * MASQUE_GATEWAYS_V4 addresses — the 162.159.198.0/24 and 162.159.199.0/24
 * gateways that completed a TCP handshake on port 443 — tried fastest-first
 * on the primary MASQUE port. The scan depth follows the chosen [ScanMode]:
 *  - TURBO    : the first handful of gateways (a few seconds),
 *  - BALANCED : a wider slice,
 *  - THOROUGH : the full gateway list.
 *
 * When a gateway answers, it is written to the engine's `lastconn` cache
 * (aether-masque-lastconn.toml / aether-lastconn.toml, same TOML shape the
 * engine itself writes: peer/profile/carrier/recent), so the next connect
 * takes the engine's cached-gateway path ("[+] cached gateway ... still
 * works; skipping scan") and applies this endpoint instead of rescanning.
 * The engine re-validates the cached peer on connect, so a stale entry can
 * never break a connection — it just falls back to a normal scan.
 *
 * IPv6 gateways are not probed here: the app profile pins IPv4 (see
 * ConnectionProfile.ipVersion default), and the engine's IPv6 path is only
 * reached when a profile asks for it.
 */
object EndpointScanner {

    /** Outcome of one scan run. */
    data class Result(
        /** Best answering gateway as "ip:port", or null when none answered. */
        val bestPeer: String?,
        /** Round-trip of the best gateway in ms, or -1 when none answered. */
        val bestMs: Long,
        /** How many candidates were actually probed. */
        val probed: Int,
    )

    private const val PRIMARY_PORT = 443
    private const val CONNECT_TIMEOUT_MS = 1_500
    private const val PARALLELISM = 16

    // The engine's regular-WARP IPv4 gateway list (prober.rs
    // MASQUE_GATEWAYS_V4, fastest-first as measured by the engine authors).
    // Kept in sync with the vendored core; only the first entries are needed
    // for TURBO/BALANCED, the full list backs THOROUGH.
    private val GATEWAYS_V4 = listOf(
        "162.159.198.183", "162.159.198.206", "162.159.198.226", "162.159.198.125",
        "162.159.198.72", "162.159.198.89", "162.159.198.198", "162.159.198.131",
        "162.159.198.245", "162.159.198.114", "162.159.198.31", "162.159.198.60",
        "162.159.198.4", "162.159.198.65", "162.159.198.33", "162.159.198.86",
        "162.159.198.244", "162.159.198.123", "162.159.198.233", "162.159.198.69",
        "162.159.198.255", "162.159.198.176", "162.159.198.186", "162.159.198.63",
        "162.159.198.37", "162.159.198.169", "162.159.198.147", "162.159.198.120",
        "162.159.198.135", "162.159.198.54", "162.159.198.234", "162.159.198.109",
        "162.159.198.175", "162.159.198.231", "162.159.198.219", "162.159.198.216",
        "162.159.198.64", "162.159.198.250", "162.159.198.184", "162.159.198.74",
        "162.159.198.241", "162.159.198.213", "162.159.198.232", "162.159.198.75",
        "162.159.198.77", "162.159.198.172", "162.159.198.145", "162.159.198.36",
        "162.159.198.124", "162.159.198.80", "162.159.198.192", "162.159.198.82",
        "162.159.198.137", "162.159.198.141", "162.159.198.48", "162.159.198.71",
        "162.159.198.227", "162.159.198.207", "162.159.198.49", "162.159.198.178",
        "162.159.198.197", "162.159.198.203", "162.159.198.204", "162.159.198.205",
        "162.159.198.195", "162.159.198.230", "162.159.198.202", "162.159.198.53",
        "162.159.198.79", "162.159.198.58", "162.159.198.35", "162.159.198.239",
        "162.159.198.116", "162.159.198.127", "162.159.198.240", "162.159.199.87",
        "162.159.199.117", "162.159.199.48", "162.159.199.217", "162.159.199.65",
    )

    private fun candidatesFor(mode: ScanMode): List<String> = when (mode) {
        ScanMode.TURBO -> GATEWAYS_V4.take(8)
        ScanMode.BALANCED -> GATEWAYS_V4.take(24)
        ScanMode.THOROUGH -> GATEWAYS_V4
        // STEALTH / IRONCLAD are not offered in B3THER's menu; scan wide.
        ScanMode.STEALTH, ScanMode.IRONCLAD -> GATEWAYS_V4
    }

    /**
     * Probes the WARP gateways for [mode] and, when one answers, applies it
     * by writing the engine's lastconn cache for [backend]/[protocol].
     * Runs on IO; safe to call from a coroutine.
     */
    suspend fun scan(
        context: Context,
        mode: ScanMode,
        backend: TransportBackend,
        protocol: Protocol,
        ipVersion: IpVersion,
    ): Result = withContext(Dispatchers.IO) {
        if (ipVersion == IpVersion.V6) {
            // The app-side prober is IPv4-only (the profile default); the
            // engine still scans IPv6 itself at connect when asked.
            return@withContext Result(bestPeer = null, bestMs = -1, probed = 0)
        }
        val candidates = candidatesFor(mode)
        val pool = Executors.newFixedThreadPool(PARALLELISM)
        try {
            val tasks = candidates.map { ip ->
                Callable<Pair<String, Long>?> {
                    val started = System.nanoTime()
                    runCatching {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(ip, PRIMARY_PORT), CONNECT_TIMEOUT_MS)
                        }
                        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                        "$ip:$PRIMARY_PORT" to ms
                    }.getOrNull()
                }
            }
            val answers = pool.invokeAll(tasks)
                .mapNotNull { runCatching { it.get() }.getOrNull() }
                .sortedBy { it.second }
            val best = answers.firstOrNull()
            if (best != null) {
                applyBest(context, best.first, answers.map { it.first }, backend, protocol)
            }
            Result(bestPeer = best?.first, bestMs = best?.second ?: -1, probed = candidates.size)
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Writes the engine's lastconn cache in the engine's own TOML shape
     * (lastconn.rs: peer / profile / carrier / recent, recent capped at 8).
     * The MASQUE carrier name matches the engine's default MASQUE run
     * (masque-h3); the WireGuard file uses the wireguard carrier.
     */
    private fun applyBest(
        context: Context,
        peer: String,
        ranked: List<String>,
        backend: TransportBackend,
        protocol: Protocol,
    ) {
        val wireGuard = protocol == Protocol.WIREGUARD || backend == TransportBackend.TOR_AETHER
        val fileName = if (wireGuard) "aether-lastconn.toml" else "aether-masque-lastconn.toml"
        val carrier = if (wireGuard) "wireguard" else "masque-h3"
        val recent = ranked.distinct().take(8)
        val text = buildString {
            append("peer = \"").append(peer).append("\"\n")
            append("profile = \"\"\n")
            append("carrier = \"").append(carrier).append("\"\n")
            append("recent = [")
            append(recent.joinToString(", ") { "\"$it\"" })
            append("]\n")
        }
        runCatching { File(context.filesDir, fileName).writeText(text) }
    }
}
