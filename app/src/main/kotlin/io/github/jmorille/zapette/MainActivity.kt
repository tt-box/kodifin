package io.github.jmorille.zapette

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import io.github.jmorille.zapette.ui.ZapetteNavHost
import io.github.jmorille.zapette.ui.theme.ZapetteTheme

class MainActivity : ComponentActivity() {

    /** Lien reçu via « Partager » : proposé à l'envoi sur l'appareil choisi. */
    private val sharedUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        sharedUrl.value = extractUrl(intent)
        setContent {
            ZapetteTheme {
                ZapetteNavHost(sharedUrl = sharedUrl.value, onSharedUrlConsumed = { sharedUrl.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractUrl(intent)?.let { sharedUrl.value = it }
    }

    private fun extractUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return URL_REGEX.find(text)?.value
    }

    private companion object {
        val URL_REGEX = Regex("""https?://\S+""")
    }
}
