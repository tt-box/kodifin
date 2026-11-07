package io.github.ttbox.kodifin.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import kotlinx.coroutines.CoroutineScope
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * DataStore réel pour les tests JVM, avec le gestionnaire de corruption de l'application.
 * Stockage Okio plutôt que `PreferenceDataStoreFactory.create` : la variante Android de ce
 * dernier, hors appareil, renomme le fichier temporaire avec `File.renameTo`, qui échoue sous
 * Windows dès que le fichier cible existe (deuxième écriture). Même format de fichier.
 */
fun testStore(scope: CoroutineScope, file: File): DataStore<Preferences> = DataStoreFactory.create(
    storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer, producePath = { file.toOkioPath() }),
    corruptionHandler = DeviceRepository.corruptionHandler(),
    scope = scope,
)
