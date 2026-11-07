package io.github.ttbox.kodifin.ui.transfer

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ttbox.kodifin.AppContainer
import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.data.DeviceExport
import io.github.ttbox.kodifin.data.ImportPolicy
import io.github.ttbox.kodifin.data.ImportReport
import io.github.ttbox.kodifin.data.InvalidExportException
import io.github.ttbox.kodifin.data.QrPayload
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.text.UiText
import io.github.ttbox.kodifin.text.toUiText
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
    val message: UiText? = null,
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
    fun exportTo(write: suspend (String) -> Unit) = run(R.string.transfer_error_write) {
        val json = deps.export(null, prettyPrint = true)
        withContext(io) { write(json) }
        val n = _state.value.devices.size
        UiText.Plural(R.plurals.transfer_exported, n)
    }

    /** Texte à partager : export complet, compact. */
    suspend fun shareText(): String = deps.export(null, prettyPrint = false)

    /** Import d'un texte lu par [read] (fichier, presse-papiers, QR code scanné). */
    fun importFrom(read: suspend () -> String?) = run(R.string.transfer_error_read) {
        val text = withContext(io) { read() }
        if (text.isNullOrBlank()) return@run null
        val policy = if (_state.value.replaceExisting) ImportPolicy.REPLACE_EXISTING else ImportPolicy.SKIP_EXISTING
        importSummary(deps.import(text, policy))
    }

    fun importText(text: String) = importFrom { text }

    /**
     * [block] renvoie le message de réussite (`null` : rien à signaler). [ioError] : format
     * `… : %1$s` du message d'échec d'entrée/sortie.
     */
    private fun run(@StringRes ioError: Int, block: suspend () -> UiText?) = viewModelScope.launch {
        _state.update { it.copy(busy = true, message = null, isError = false) }
        try {
            val message = block()
            _state.update { it.copy(busy = false, message = message, isError = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidExportException) {
            // Raison du refus fournie par le format d'export (data/).
            _state.update { it.copy(busy = false, message = e.text, isError = true) }
        } catch (e: IOException) {
            val detail = e.toUiText(UiText.Res(R.string.transfer_error_io_generic))
            _state.update { it.copy(busy = false, message = UiText.Res(ioError, detail), isError = true) }
        } catch (e: SecurityException) {
            _state.update {
                it.copy(busy = false, message = UiText.Res(ioError, UiText.Res(R.string.transfer_error_access_denied)), isError = true)
            }
        }
    }
}

/** Bilan d'import affiché : « Import : 1 ajouté, 0 mis à jour, 2 ignorés, 0 invalide ». */
fun importSummary(report: ImportReport): UiText = UiText.Res(
    R.string.transfer_import_summary,
    UiText.Plural(R.plurals.transfer_import_added, report.added),
    UiText.Plural(R.plurals.transfer_import_updated, report.updated),
    UiText.Plural(R.plurals.transfer_import_skipped, report.skipped),
    UiText.Plural(R.plurals.transfer_import_invalid, report.invalid),
)

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
        if (out.size() > maxBytes) throw InvalidExportException.fileTooLarge()
    }
    return out.toString(Charsets.UTF_8.name())
}
