package io.github.ttbox.kodifin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import io.github.ttbox.kodifin.ui.KodifinNavHost
import io.github.ttbox.kodifin.ui.theme.KodifinTheme

class MainActivity : ComponentActivity() {

    /** Lien reçu via « Partager » : proposé à l'envoi sur l'appareil choisi. */
    private val sharedUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Après une rotation ou une mort de processus, l'intent d'origine est toujours là :
        // le relire reproposerait un lien déjà envoyé. On restaure l'état sauvegardé à la place.
        sharedUrl.value = if (savedInstanceState == null) {
            extractUrl(intent)
        } else {
            savedInstanceState.getString(KEY_SHARED_URL)
        }
        setContent {
            KodifinTheme {
                KodifinNavHost(sharedUrl = sharedUrl.value, onSharedUrlConsumed = { sharedUrl.value = null })
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SHARED_URL, sharedUrl.value)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractUrl(intent)?.let { sharedUrl.value = it }
    }

    private fun extractUrl(intent: Intent?): String? = SharedLink.extract(
        action = intent?.action,
        text = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT),
        launchedFromHistory = intent != null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0,
    )

    private companion object {
        const val KEY_SHARED_URL = "shared_url"
    }
}
