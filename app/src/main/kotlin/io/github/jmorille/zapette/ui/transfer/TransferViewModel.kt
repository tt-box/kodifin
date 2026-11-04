package io.github.jmorille.zapette.ui.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jmorille.zapette.AppContainer
import io.github.jmorille.zapette.data.DeviceExport
import io.github.jmorille.zapette.data.ImportPolicy
import io.github.jmorille.zapette.data.ImportReport
import io.github.jmorille.zapette.data.InvalidExportException
import io.github.jmorille.zapette.data.QrPayload
import io.github.jmorille.zapette.model.Device
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

/** Dépendances du ViewModel, isolées pour les tests JVM (ni DataStore ni Android). */
interface TransferDependencies {
    val devices: Flow<List<Device>>
    suspend fun export(ids: Collection<String>?, prettyPrint: Boolean): String
    suspend fun import(text: String, policy: ImportPolicy): ImportReport
}

fun AppContainer.transferDependencies(): TransferDependencies = object : TransferDependencies {
    override val devices: Flow<List<Device>> get() = this@transferDependencies.devices.saved
    override suspend fun export(ids: Collection<String>?, prettyPrint: Boolean) =
        this@transferDependencies.devices.exportDevices(ids, prettyPrint)
    override suspend fun import(text: String, policy: ImportPolicy) =
        this@transferDependencies.devices.importDevices(text, policy)
}

/** QR code de la sélection : contenu prêt à afficher, ou `null` s'il serait trop dense. */
data class QrState(val payload: String?, val count: Int)

data class TransferUiState(
    val devices: List<Device> = emptyList(),
    /** Appareil choisi pour le QR code ; `null` : tous les appareils. */
    val selectedId: String? = null,
    val replaceExisting: Boolean = false,
    val qr: QrState? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

class TransferViewModel(
    private val deps: TransferDependencies,
    initialDeviceId: String? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(TransferUiState(selectedId = initialDeviceId))
    val state: StateFlow<TransferUiState> = _state

    init {
        viewModelScope.launch {
            deps.devices.collect { list ->
                _state.update { s -> s.copy(devices = list, selectedId = s.selectedId?.takeIf { id -> list.any { it.id == id } }) }
                refreshQr()
            }
        }
    }

    fun select(id: String?) {
        _state.update { it.copy(selectedId = id) }
        viewModelScope.launch { refreshQr() }
    }

    fun setReplaceExisting(replace: Boolean) = _state.update { it.copy(replaceExisting = replace) }

    fun dismissMessage() = _state.update { it.copy(message = null, isError = false) }

    private suspend fun refreshQr() {
        val s = _state.value
        val ids = s.selectedId?.let { listOf(it) }
        val count = if (ids == null) s.devices.size else 1
        val qr = if (count == 0) {
            null
        } else {
            val payload = QrPayload.encode(deps.export(ids, prettyPrint = false))
            QrState(payload.takeIf(QrPayload::fits), count)
        }
        _state.update { if (it.selectedId == s.selectedId) it.copy(qr = qr) else it }
    }

    /** Export complet (lisible, indenté) écrit par [write] (document SAF) hors du thread principal. */
    fun exportTo(write: suspend (String) -> Unit) = run("Écriture impossible") {
        val json = deps.export(null, prettyPrint = true)
        withContext(io) { write(json) }
        val n = _state.value.devices.size
        "$n appareil(s) exporté(s), sans mot de passe ni jeton."
    }

    /** Texte à partager : export complet, compact. */
    suspend fun shareText(): String = deps.export(null, prettyPrint = false)

    /** Import d'un texte lu par [read] (fichier, presse-papiers, QR code scanné). */
    fun importFrom(read: suspend () -> String?) = run("Lecture impossible") {
        val text = withContext(io) { read() }
        if (text.isNullOrBlank()) return@run null
        val policy = if (_state.value.replaceExisting) ImportPolicy.REPLACE_EXISTING else ImportPolicy.SKIP_EXISTING
        "Import : " + deps.import(text, policy).summary()
    }

    fun importText(text: String) = importFrom { text }

    /** [block] renvoie le message de réussite (`null` : rien à signaler). */
    private fun run(ioError: String, block: suspend () -> String?) = viewModelScope.launch {
        _state.update { it.copy(busy = true, message = null, isError = false) }
        try {
            val message = block()
            _state.update { it.copy(busy = false, message = message, isError = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidExportException) {
            _state.update { it.copy(busy = false, message = e.message, isError = true) }
        } catch (e: IOException) {
            _state.update { it.copy(busy = false, message = "$ioError : ${e.message ?: "erreur d'entrée/sortie"}", isError = true) }
        } catch (e: SecurityException) {
            _state.update { it.copy(busy = false, message = "$ioError : accès refusé", isError = true) }
        }
    }
}

/**
 * Lit au plus [maxBytes] octets UTF-8 : un fichier plus gros est refusé sans être chargé
 * entièrement en mémoire.
 */
fun InputStream.readTextLimited(maxBytes: Int = DeviceExport.MAX_CHARS * 4): String {
    // Boucle manuelle : InputStream.readNBytes n'existe qu'à partir de l'API 33.
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > maxBytes) throw InvalidExportException("Fichier trop volumineux")
    }
    return out.toString(Charsets.UTF_8.name())
}
