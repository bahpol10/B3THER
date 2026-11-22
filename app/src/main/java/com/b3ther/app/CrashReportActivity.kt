package com.b3ther.app

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Shows the fatal JVM exception saved by [B3hApp]'s crash handler on the
 * previous run. Kept as a separate Activity (launched on the next cold start)
 * so the report is reachable even when the crash broke the home UI's state.
 */
class CrashReportActivity : ComponentActivity() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(
            base?.let { com.b3ther.app.data.LanguagePrefs.wrap(it) } ?: base,
        )
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.b3ther.app.data.LanguagePrefs.applyLayoutDirection(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A crash dump names classes, endpoints and whatever the engine was
        // doing when it died; keep it out of screenshots and recents previews.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        com.b3ther.app.data.LanguagePrefs.applyLayoutDirection(this)
        val crashFile = File(filesDir, "last_crash.txt")
        val details = runCatching { crashFile.readText() }.getOrDefault("")

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.crash_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = stringResource(R.string.crash_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            Text(
                                text = details.ifBlank { "—" },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp),
                            )
                        }
                        val context = LocalContext.current
                        var copied by remember { mutableStateOf(false) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = {
                                    copySensitive(context, details, "b3ther-crash")
                                    copied = true
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    stringResource(
                                        if (copied) R.string.crash_copied else R.string.crash_copy,
                                    ),
                                )
                            }
                            Button(
                                onClick = {
                                    runCatching { crashFile.delete() }
                                    finish()
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.crash_dismiss))
                            }
                        }
                    }
                }
            }
        }
    }

    /** Copies [text] flagged sensitive so it does not show in the clipboard preview. */
    private fun copySensitive(context: Context, text: String, label: String) {
        val clip = ClipData.newPlainText(label, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(clip)
    }
}
