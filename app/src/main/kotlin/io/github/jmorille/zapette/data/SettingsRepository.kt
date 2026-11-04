package io.github.jmorille.zapette.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jmorille.zapette.cast.CastSettings
import io.github.jmorille.zapette.cast.CastSubtitleStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException

/** Réglages globaux de l'application. */
data class AppSettings(
    /** Garder l'écran allumé tant que la télécommande est affichée. */
    val keepScreenOn: Boolean = false,
    val cast: CastSettings = CastSettings(),
)

/** Accès aux réglages, remplaçable par un faux dans les tests du ViewModel. */
interface SettingsStore {
    val settings: Flow<AppSettings>

    /** Applique [transform] aux réglages courants de façon atomique. */
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

/**
 * Réglages globaux dans un fichier DataStore distinct (`zapette_settings`) de celui des
 * appareils. Les énumérations sont stockées par nom : une valeur inconnue (version plus
 * récente, fichier altéré) retombe sur la valeur par défaut.
 */
class SettingsRepository(private val store: DataStore<Preferences>) : SettingsStore {

    private val data: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    override val settings: Flow<AppSettings> = data.map(::read).distinctUntilChanged()

    /** Flux à passer à la télécommande (`RemoteScreen(keepScreenOn = …)`). */
    val keepScreenOn: Flow<Boolean> = settings.map { it.keepScreenOn }.distinctUntilChanged()

    /** Réglages Chromecast, à fournir au `CastController`. */
    val cast: Flow<CastSettings> = settings.map { it.cast }.distinctUntilChanged()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs -> write(prefs, transform(read(prefs))) }
    }

    companion object {
        internal val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        internal val CAST_MAX_BITRATE = stringPreferencesKey("cast_max_bitrate")
        internal val CAST_SUBTITLE_SIZE = stringPreferencesKey("cast_subtitle_size")
        internal val CAST_SUBTITLE_COLOR = stringPreferencesKey("cast_subtitle_color")
        internal val CAST_SUBTITLE_BACKGROUND = stringPreferencesKey("cast_subtitle_background")
        internal val CAST_AUTO_DISCONNECT = booleanPreferencesKey("cast_auto_disconnect")

        /** Nom du fichier (`preferencesDataStoreFile(FILE_NAME)`). */
        const val FILE_NAME = "zapette_settings"

        private inline fun <reified E : Enum<E>> Preferences.enumOr(key: Preferences.Key<String>, default: E): E =
            this[key]?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

        internal fun read(prefs: Preferences): AppSettings {
            val defaults = AppSettings()
            return AppSettings(
                keepScreenOn = prefs[KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
                cast = CastSettings(
                    maxBitrate = prefs.enumOr(CAST_MAX_BITRATE, defaults.cast.maxBitrate),
                    subtitles = CastSubtitleStyle(
                        size = prefs.enumOr(CAST_SUBTITLE_SIZE, defaults.cast.subtitles.size),
                        textColor = prefs.enumOr(CAST_SUBTITLE_COLOR, defaults.cast.subtitles.textColor),
                        background = prefs.enumOr(CAST_SUBTITLE_BACKGROUND, defaults.cast.subtitles.background),
                    ),
                    autoDisconnect = prefs[CAST_AUTO_DISCONNECT] ?: defaults.cast.autoDisconnect,
                ),
            )
        }

        private fun write(prefs: MutablePreferences, s: AppSettings) {
            prefs[KEEP_SCREEN_ON] = s.keepScreenOn
            prefs[CAST_MAX_BITRATE] = s.cast.maxBitrate.name
            prefs[CAST_SUBTITLE_SIZE] = s.cast.subtitles.size.name
            prefs[CAST_SUBTITLE_COLOR] = s.cast.subtitles.textColor.name
            prefs[CAST_SUBTITLE_BACKGROUND] = s.cast.subtitles.background.name
            prefs[CAST_AUTO_DISCONNECT] = s.cast.autoDisconnect
        }

        /** Magasin DataStore des réglages (un seul par processus), fichier corrompu remplacé. */
        fun createStore(
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile: () -> File,
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            corruptionHandler = DeviceRepository.corruptionHandler(),
            scope = scope,
            produceFile = produceFile,
        )
    }
}
