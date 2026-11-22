package com.b3ther.app

import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.b3ther.app.core.AetherController
import com.b3ther.app.core.PingMonitor
import com.b3ther.app.data.ProfileStore
import com.b3ther.app.model.ConnectionProfile
import com.b3ther.app.model.ConnectionState
import com.b3ther.app.model.Protocol
import com.b3ther.app.model.ScanMode
import com.b3ther.app.model.TransportBackend
import com.b3ther.app.model.isBusy
import com.b3ther.app.model.isConnected
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

// ---------------------------------------------------------------------------
// B3THER design tokens (extracted from the approved preview artifact).
// ---------------------------------------------------------------------------

private object B3 {
    // Day theme
    val dayGradient = listOf(Color(0xFFA9D4FB), Color(0xFFCFE8FD), Color(0xFFEEF7FF), Color(0xFFFFFFFF))
    val dayText = Color(0xFF16324F)
    val dayAccent = Color(0xFF1D6FD1)
    val dayGlassBg = Color.White.copy(alpha = 0.50f)
    val dayGlassBorder = Color.White.copy(alpha = 0.50f)
    val dayDrawerBg = Color.White.copy(alpha = 0.42f)
    val dayDrawerBorder = Color.White.copy(alpha = 0.55f)

    // Night theme
    val nightGradient = listOf(Color(0xFF14467F), Color(0xFF0C2A52), Color(0xFF061426), Color(0xFF000000))
    val nightText = Color(0xFFE9F2FC)
    val nightAccent = Color(0xFF5EA8F5)
    val nightGlassBg = Color.White.copy(alpha = 0.11f)
    val nightGlassBorder = Color.White.copy(alpha = 0.24f)
    val nightDrawerBg = Color(0xFF0A1A30).copy(alpha = 0.48f)
    val nightDrawerBorder = Color.White.copy(alpha = 0.22f)
}

private val Vazirmatn = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
    Font(R.font.vazirmatn_bold, FontWeight.ExtraBold),
    Font(R.font.vazirmatn_bold, FontWeight.Black),
)

private data class ModeUi(
    val backend: TransportBackend,
    val title: String,
    val desc: String,
    val badge: String,
)

private val MODES = listOf(
    ModeUi(TransportBackend.AETHER, "Aether", "فقط Aether — خروجی ایران از WARP", "🇮🇷 ایران"),
    ModeUi(TransportBackend.AETHER_PSIPHON, "Aether → Psiphon", "زنجیرهٔ Aether و سایفون — خروجی خارجی", "🇳🇱 خارجی"),
    ModeUi(TransportBackend.TOR, "Tor", "فقط شبکهٔ تور — ناشناس و کندتر", "تور"),
    ModeUi(TransportBackend.AETHER_TOR, "Aether → Tor", "زنجیرهٔ Aether و تور — خروجی تور", "تور"),
    ModeUi(TransportBackend.TOR_PSIPHON, "Tor → Psiphon", "زنجیرهٔ تور و سایفون — خروجی خارجی", "🇳🇱 خارجی"),
    ModeUi(TransportBackend.TOR_AETHER, "Tor → Aether", "زنجیرهٔ تور و Aether — خروجی ایران، شبکهٔ محلی فقط تور می‌بیند", "🇮🇷 ایران"),
)

private data class ProtoUi(val protocol: Protocol, val title: String, val desc: String)

private val PROTOS = listOf(
    ProtoUi(Protocol.AUTO, "Smart", "هوشمند — انتخاب خودکار بهترین پروتکل"),
    ProtoUi(Protocol.MASQUE, "MASQUE", "تونل MASQUE روی QUIC"),
    ProtoUi(Protocol.WIREGUARD, "WireGuard", "سبک و سریع — اتصال از WARP"),
    ProtoUi(Protocol.GOOL, "Gool", "مسیر Gool برای عبور پایدار"),
    ProtoUi(Protocol.MIM, "MASQUE ×2", "دو لایهٔ MASQUE — پوشش بیشتر، کمی کندتر"),
)

private data class ScanUi(val scan: ScanMode, val title: String, val desc: String)

private val SCANS = listOf(
    ScanUi(ScanMode.BALANCED, "اسکن خودکار", "قبل از هر اتصال، بهترین اندپوینت WARP را خودش پیدا می‌کند"),
    ScanUi(ScanMode.TURBO, "اسکن سریع", "چند اندپوینت اصلی را در چند ثانیه می‌سنجد"),
    ScanUi(ScanMode.THOROUGH, "اسکن عمیق", "همهٔ اندپوینت‌ها را کامل می‌سنجد — دقیق‌تر، کمی دیرتر"),
)

private const val ABOUT_TEXT =
    "B3THER یک VPN سبک و مینیمال بر پایهٔ هستهٔ Aether است؛ بدون لیست کشور و سرور. " +
        "اتصال از مسیر WARP خروجی ایران می‌دهد و خروجی خارجی فقط از مسیر سایفون ممکن است. " +
        "حالت‌های موتور و پروتکل اتصال از منوی کشویی (فلش لبهٔ صفحه) انتخاب می‌شوند، نه از اینجا."

private fun flagEmoji(countryCode: String?): String {
    val cc = countryCode?.uppercase()?.takeIf { it.length == 2 } ?: return "🌐"
    val first = Character.codePointAt(cc, 0) - 'A'.code + 0x1F1E6
    val second = Character.codePointAt(cc, 1) - 'A'.code + 0x1F1E6
    return String(Character.toChars(first)) + String(Character.toChars(second))
}

private fun fmtBytes(bytes: Long): String = when {
    bytes < 0 -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

private fun fmtSpeed(bytesPerSec: Long): String = when {
    bytesPerSec < 0 -> "—"
    bytesPerSec < 1024 -> "$bytesPerSec B/s"
    bytesPerSec < 1024 * 1024 -> "%.1f KB/s".format(bytesPerSec / 1024.0)
    else -> "%.1f MB/s".format(bytesPerSec / (1024.0 * 1024))
}

private fun fmtTimer(sinceMs: Long?): String {
    if (sinceMs == null) return "00:00"
    val totalSec = ((System.currentTimeMillis() - sinceMs) / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CONNECT_ON_LAUNCH = "com.b3ther.app.EXTRA_CONNECT_ON_LAUNCH"
    }

    private lateinit var profileStore: ProfileStore
    private var pendingProfile: ConnectionProfile? = null

    private val vpnConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingProfile?.let { AetherController.connect(this, it) }
        }
        pendingProfile = null
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base?.let { com.b3ther.app.data.LanguagePrefs.wrap(it) } ?: base)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.b3ther.app.data.LanguagePrefs.applyLayoutDirection(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleConnectOnLaunch(intent)
    }

    private fun handleConnectOnLaunch(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_CONNECT_ON_LAUNCH, false) == true) {
            intent.removeExtra(EXTRA_CONNECT_ON_LAUNCH)
            lifecycleScope.launch {
                val profile = profileStore.profile.first()
                startConnect(profile)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.b3ther.app.data.LanguagePrefs.applyLayoutDirection(this)
        profileStore = ProfileStore(applicationContext)

        // Crash report from a previous run: open it once, then continue.
        val crashFile = File(filesDir, B3hApp.CRASH_FILE)
        if (crashFile.exists() && crashFile.length() > 0) {
            startActivity(Intent(this, CrashReportActivity::class.java))
        }

        handleConnectOnLaunch(intent)

        setContent {
            MaterialTheme {
                B3therHome(
                    profileStore = profileStore,
                    onConnect = { profile -> startConnect(profile) },
                    onDisconnect = { AetherController.disconnect(this) },
                )
            }
        }
    }

    private fun startConnect(profile: ConnectionProfile) {
        lifecycleScope.launch { profileStore.save(profile) }
        val consent = AetherController.prepare(this)
        if (consent != null) {
            pendingProfile = profile
            vpnConsent.launch(consent)
        } else {
            AetherController.connect(this, profile)
        }
    }
}

@Composable
private fun B3therHome(
    profileStore: ProfileStore,
    onConnect: (ConnectionProfile) -> Unit,
    onDisconnect: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("b3ther_ui", Context.MODE_PRIVATE) }
    var night by remember { mutableStateOf(prefs.getBoolean("night", false)) }

    val state by AetherController.state.collectAsState()
    val connectedSince by AetherController.connectedSince.collectAsState()
    val ipInfo by AetherController.ipInfo.collectAsState()
    val ping by PingMonitor.state.collectAsState()

    var profile by remember { mutableStateOf(ConnectionProfile()) }
    LaunchedEffect(Unit) {
        profile = profileStore.profile.first()
    }

    var drawerOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var drawerTab by remember { mutableStateOf(0) } // 0 = engine modes, 1 = protocols
    var scanStatus by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }

    val connected = state.isConnected
    val busy = state.isBusy

    // Live ping loop while connected.
    LaunchedEffect(connected) {
        if (connected) {
            while (true) {
                PingMonitor.pingOnce(viaTunnel = true)
                delay(2_000)
            }
        }
    }

    // Traffic sampling (device totals; deltas per second).
    var downSpeed by remember { mutableLongStateOf(0L) }
    var upSpeed by remember { mutableLongStateOf(0L) }
    var usageDown by remember { mutableLongStateOf(0L) }
    var usageUp by remember { mutableLongStateOf(0L) }
    LaunchedEffect(connected) {
        if (!connected) {
            downSpeed = 0; upSpeed = 0; usageDown = 0; usageUp = 0
            return@LaunchedEffect
        }
        var lastRx = TrafficStats.getTotalRxBytes()
        var lastTx = TrafficStats.getTotalTxBytes()
        val baseRx = lastRx
        val baseTx = lastTx
        while (true) {
            delay(1_000)
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            if (rx >= 0 && lastRx >= 0) downSpeed = (rx - lastRx).coerceAtLeast(0)
            if (tx >= 0 && lastTx >= 0) upSpeed = (tx - lastTx).coerceAtLeast(0)
            if (rx >= 0 && baseRx >= 0) usageDown = (rx - baseRx).coerceAtLeast(0)
            if (tx >= 0 && baseTx >= 0) usageUp = (tx - baseTx).coerceAtLeast(0)
            lastRx = rx; lastTx = tx
        }
    }

    // Connection timer ticker.
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(connected) {
        if (connected) {
            while (true) {
                tick = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }

    val textColor = if (night) B3.nightText else B3.dayText
    val accent = if (night) B3.nightAccent else B3.dayAccent
    val glassBg = if (night) B3.nightGlassBg else B3.dayGlassBg
    val glassBorder = if (night) B3.nightGlassBorder else B3.dayGlassBorder
    val drawerBg = if (night) B3.nightDrawerBg else B3.dayDrawerBg
    val drawerBorder = if (night) B3.nightDrawerBorder else B3.dayDrawerBorder

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = if (night) B3.nightGradient else B3.dayGradient,
                    start = Offset(0f, 0f),
                    end = Offset(300f, 1600f),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---- Top bar: hamburger (left) + B3THER (center) ----
            Box(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                // Hamburger button, top-left, lines level with the title.
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (night) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.34f),
                        )
                        .border(
                            1.5.dp,
                            if (night) Color.White.copy(alpha = 0.26f) else B3.dayText.copy(alpha = 0.22f),
                            RoundedCornerShape(10.dp),
                        )
                        .clickable { menuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        repeat(3) {
                            Box(
                                modifier = Modifier
                                    .width(18.dp)
                                    .height(2.dp)
                                    .background(textColor, RoundedCornerShape(1.dp)),
                            )
                        }
                    }
                }
                Text(
                    text = "B3THER",
                    modifier = Modifier.align(Alignment.Center),
                    fontFamily = Vazirmatn,
                    fontWeight = FontWeight.Black,
                    fontSize = 25.sp,
                    letterSpacing = 4.sp,
                    color = textColor,
                )
            }

            Spacer(Modifier.height(56.dp))

            // ---- Connect button (176dp frosted circle, 3 states) ----
            val iconSize by animateDpAsState(
                targetValue = when {
                    connected -> 32.dp
                    busy -> 32.dp
                    else -> 68.dp
                },
                label = "powerIcon",
            )
            Box(
                modifier = Modifier
                    .size(176.dp)
                    .clip(CircleShape)
                    .background(glassBg)
                    .border(1.dp, glassBorder, CircleShape)
                    .clickable {
                        when {
                            connected || busy -> onDisconnect()
                            else -> onConnect(profile)
                        }
                    }
                    .animateContentSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PowerIcon(size = iconSize, color = accent)
                    when {
                        connected -> {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "متصل است",
                                fontFamily = Vazirmatn,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.sp,
                                color = textColor,
                            )
                            Text(
                                text = fmtTimer(connectedSince) + if (tick == 0L) "" else "",
                                fontFamily = Vazirmatn,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                letterSpacing = 2.sp,
                                color = textColor.copy(alpha = 0.85f),
                            )
                        }
                        busy -> {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "در حال اتصال",
                                fontFamily = Vazirmatn,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.sp,
                                color = textColor,
                            )
                        }
                        else -> Unit // disconnected: big power icon only, no text
                    }
                }
            }

            if (state is ConnectionState.Error) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = (state as ConnectionState.Error).message,
                    fontFamily = Vazirmatn,
                    fontSize = 13.sp,
                    color = Color(0xFFE05252),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            // ---- Bottom block (sample 4): wave chart + glass cards ----
            WaveChart(
                pingMs = if (connected && ping.ms > 0) ping.ms else -1L,
                accent = accent,
                night = night,
                modifier = Modifier.fillMaxWidth().height(84.dp),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassCard(
                    modifier = Modifier.weight(1.2f),
                    bg = glassBg, border = glassBorder,
                ) {
                    Text("آی‌پی خروجی", fontFamily = Vazirmatn, fontSize = 11.sp, color = textColor.copy(alpha = 0.7f))
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = ipInfo?.ip ?: "—",
                            fontFamily = Vazirmatn,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = textColor,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(text = flagEmoji(ipInfo?.countryCode), fontSize = 15.sp)
                    }
                }
                GlassCard(
                    modifier = Modifier.weight(0.8f),
                    bg = glassBg, border = glassBorder,
                ) {
                    Text("پینگ", fontFamily = Vazirmatn, fontSize = 11.sp, color = textColor.copy(alpha = 0.7f))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (connected && ping.ms > 0) "${ping.ms} ms" else "—",
                        fontFamily = Vazirmatn,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = textColor,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassCard(
                    modifier = Modifier.weight(1f),
                    bg = glassBg, border = glassBorder,
                ) {
                    Text("سرعت", fontFamily = Vazirmatn, fontSize = 11.sp, color = textColor.copy(alpha = 0.7f))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "↓ ${fmtSpeed(downSpeed)}   ↑ ${fmtSpeed(upSpeed)}",
                        fontFamily = Vazirmatn,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = textColor,
                    )
                }
                GlassCard(
                    modifier = Modifier.weight(1f),
                    bg = glassBg, border = glassBorder,
                ) {
                    Text("مصرف اینترنت", fontFamily = Vazirmatn, fontSize = 11.sp, color = textColor.copy(alpha = 0.7f))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "↓ ${fmtBytes(usageDown)}   ↑ ${fmtBytes(usageUp)}",
                        fontFamily = Vazirmatn,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = textColor,
                    )
                }
            }
        }

        // ---- Drawer edge arrow (right edge, ~34% height) ----
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(y = (-90).dp)
                .size(width = 20.dp, height = 62.dp)
                .clip(RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp))
                .background(
                    if (night) Color.White.copy(alpha = 0.16f) else B3.dayText.copy(alpha = 0.16f),
                )
                .clickable { drawerOpen = true },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "‹", fontSize = 18.sp, color = textColor, fontWeight = FontWeight.Bold)
        }

        // ---- Side drawer (frosted glass, 2 tabs) ----
        if (drawerOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f))
                    .clickable { drawerOpen = false },
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(0.84f),
                color = drawerBg,
                shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.dp, drawerBorder, RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                        .padding(horizontal = 16.dp)
                        .padding(top = 52.dp, bottom = 16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DrawerTab(
                            label = "حالت‌های موتور",
                            selected = drawerTab == 0,
                            accent = accent, textColor = textColor,
                            modifier = Modifier.weight(1f),
                        ) { drawerTab = 0 }
                        DrawerTab(
                            label = "پروتکل اتصال",
                            selected = drawerTab == 1,
                            accent = accent, textColor = textColor,
                            modifier = Modifier.weight(1f),
                        ) { drawerTab = 1 }
                    }
                    Spacer(Modifier.height(14.dp))
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (drawerTab == 0) {
                            MODES.forEach { mode ->
                                SelectRow(
                                    title = mode.title,
                                    desc = mode.desc,
                                    badge = mode.badge,
                                    selected = profile.backend == mode.backend,
                                    accent = accent, textColor = textColor,
                                    glassBg = glassBg, glassBorder = glassBorder,
                                ) {
                                    profile = profile.copy(backend = mode.backend)
                                }
                            }
                        } else {
                            PROTOS.forEach { proto ->
                                SelectRow(
                                    title = proto.title,
                                    desc = proto.desc,
                                    badge = null,
                                    selected = profile.protocol == proto.protocol,
                                    accent = accent, textColor = textColor,
                                    glassBg = glassBg, glassBorder = glassBorder,
                                ) {
                                    profile = profile.copy(protocol = proto.protocol)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- Hamburger menu (frosted glass, separate from drawer) ----
        if (menuOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f))
                    .clickable { menuOpen = false },
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(0.84f),
                color = drawerBg,
                shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.dp, drawerBorder, RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp))
                        .padding(horizontal = 16.dp)
                        .padding(top = 52.dp, bottom = 16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "حالت‌های اسکن — اندپوینت WARP",
                        fontFamily = Vazirmatn,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                        color = textColor,
                    )
                    SCANS.forEach { scan ->
                        SelectRow(
                            title = scan.title,
                            desc = scan.desc,
                            badge = null,
                            selected = profile.scanMode == scan.scan,
                            accent = accent, textColor = textColor,
                            glassBg = glassBg, glassBorder = glassBorder,
                        ) {
                            profile = profile.copy(scanMode = scan.scan)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(accent)
                            .clickable(enabled = !scanning) {
                                scanning = true
                                scanStatus = "در حال اسکن…"
                                // Real WARP endpoint scan with the chosen scan
                                // mode; the best gateway is applied through
                                // the engine's lastconn cache (EndpointScanner).
                                scope.launch {
                                    val result = com.b3ther.app.core.EndpointScanner.scan(
                                        context = context,
                                        mode = profile.scanMode,
                                        backend = profile.backend,
                                        protocol = profile.protocol,
                                        ipVersion = profile.ipVersion,
                                    )
                                    scanning = false
                                    scanStatus = if (result.bestPeer != null) {
                                        "آخرین اسکن: بهترین اندپوینت ${result.bestPeer} (${result.bestMs}ms) — در اتصال بعدی اعمال می‌شود ✓"
                                    } else {
                                        "آخرین اسکن: هیچ اندپوینتی پاسخ نداد — هنگام اتصال دوباره سنجیده می‌شود"
                                    }
                                }
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "شروع اسکن",
                            fontFamily = Vazirmatn,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color.White,
                        )
                    }
                    scanStatus?.let {
                        Text(
                            text = it,
                            fontFamily = Vazirmatn,
                            fontSize = 12.sp,
                            color = textColor.copy(alpha = 0.8f),
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "حالت شب",
                            fontFamily = Vazirmatn,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = textColor,
                        )
                        Switch(
                            checked = night,
                            onCheckedChange = {
                                night = it
                                prefs.edit().putBoolean("night", it).apply()
                            },
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "انتخاب فعلی",
                        fontFamily = Vazirmatn,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                        color = textColor,
                    )
                    val curMode = MODES.firstOrNull { it.backend == profile.backend }
                    val curProto = PROTOS.firstOrNull { it.protocol == profile.protocol }
                    val curScan = SCANS.firstOrNull { it.scan == profile.scanMode }
                    Text(
                        text = "موتور: ${curMode?.title ?: "—"}\nپروتکل: ${curProto?.title ?: "—"}\nاسکن: ${curScan?.title ?: "—"}",
                        fontFamily = Vazirmatn,
                        fontSize = 13.sp,
                        color = textColor.copy(alpha = 0.85f),
                    )

                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "دربارهٔ برنامه",
                        fontFamily = Vazirmatn,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                        color = textColor,
                    )
                    Text(
                        text = ABOUT_TEXT,
                        fontFamily = Vazirmatn,
                        fontSize = 13.sp,
                        color = textColor.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PowerIcon(size: androidx.compose.ui.unit.Dp, color: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val stroke = size.toPx() * 0.09f
        val r = size.toPx() / 2f
        // Arc (open at top) + vertical line = standard power symbol.
        drawArc(
            color = color,
            startAngle = -55f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = androidx.compose.ui.geometry.Size(size.toPx() - stroke, size.toPx() - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = Offset(r, stroke * 0.4f),
            end = Offset(r, r * 1.05f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    bg: Color,
    border: Color,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column { content() }
    }
}

@Composable
private fun DrawerTab(
    label: String,
    selected: Boolean,
    accent: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent else Color.Transparent)
            .border(1.dp, if (selected) accent else textColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontFamily = Vazirmatn,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = if (selected) Color.White else textColor,
        )
    }
}

@Composable
private fun SelectRow(
    title: String,
    desc: String,
    badge: String?,
    selected: Boolean,
    accent: Color,
    textColor: Color,
    glassBg: Color,
    glassBorder: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(glassBg)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) accent else glassBorder,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontFamily = Vazirmatn,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = textColor,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = desc,
                fontFamily = Vazirmatn,
                fontSize = 12.sp,
                color = textColor.copy(alpha = 0.75f),
            )
        }
        if (badge != null) {
            Spacer(Modifier.width(8.dp))
            Text(text = badge, fontFamily = Vazirmatn, fontSize = 12.sp, color = textColor.copy(alpha = 0.85f))
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Text(text = "✓", fontSize = 16.sp, color = accent, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Sea-wave traffic chart: calm when ping is low, taller and more agitated
 * when ping is high. No caption inside.
 */
@Composable
private fun WaveChart(pingMs: Long, accent: Color, night: Boolean, modifier: Modifier = Modifier) {
    var phase by remember { mutableStateOf(0f) }
    val targetAmp by animateFloatAsState(
        targetValue = when {
            pingMs < 0 -> 0.25f
            pingMs < 100 -> 0.3f
            pingMs < 300 -> 0.55f
            pingMs < 600 -> 0.8f
            else -> 1f
        },
        label = "waveAmp",
    )
    LaunchedEffect(pingMs) {
        while (true) {
            val speed = when {
                pingMs < 0 -> 0.02f
                pingMs < 100 -> 0.03f
                pingMs < 300 -> 0.05f
                else -> 0.09f
            }
            phase += speed
            delay(50)
        }
    }
    val frontFill = accent.copy(alpha = if (night) 0.28f else 0.24f)
    val backFill = accent.copy(alpha = if (night) 0.14f else 0.13f)
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val midY = h * 0.55f
        fun wavePath(ampScale: Float, phaseOff: Float, freq: Float): Path {
            val p = Path()
            p.moveTo(0f, midY)
            val steps = 60
            for (i in 0..steps) {
                val x = w * i / steps
                val y = midY + sin((x / w * freq * 2 * PI) + phase + phaseOff).toFloat() * h * 0.32f * targetAmp * ampScale
                p.lineTo(x, y)
            }
            p.lineTo(w, h)
            p.lineTo(0f, h)
            p.close()
            return p
        }
        // Back wave
        drawPath(wavePath(0.6f, 1.7f, 1.6f), color = backFill)
        // Front wave with stroke on top edge
        val front = wavePath(1f, 0f, 2.2f)
        drawPath(front, color = frontFill)
        val strokePath = Path()
        val steps = 60
        for (i in 0..steps) {
            val x = w * i / steps
            val y = midY + sin((x / w * 2.2f * 2 * PI) + phase).toFloat() * h * 0.32f * targetAmp
            if (i == 0) strokePath.moveTo(x, y) else strokePath.lineTo(x, y)
        }
        drawPath(strokePath, color = accent, style = Stroke(width = 2.4f, cap = StrokeCap.Round))
    }
}
