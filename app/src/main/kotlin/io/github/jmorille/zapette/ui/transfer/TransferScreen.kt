package io.github.jmorille.zapette.ui.transfer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.jmorille.zapette.container
import kotlinx.coroutines.launch
import java.io.IOException

/** Nom proposé pour le fichier d'export (Storage Access Framework). */
const val EXPORT_FILE_NAME = "zapette-appareils.json"

/**
 * Export et import des appareils : fichier (Storage Access Framework, aucune permission de
 * stockage), texte (partage, presse-papiers) et QR code (affichage, scan). Les mots de passe
 * et jetons ne sont jamais exportés. [deviceId] : appareil présélectionné pour le QR code.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(deviceId: String? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: TransferViewModel = viewModel(
        factory = viewModelFactory { initializer { TransferViewModel(context.container.transferDependencies(), deviceId) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val resolver = context.contentResolver

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            vm.exportTo { json ->
                val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("document inaccessible")
                out.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }
        }
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.importFrom {
                val input = resolver.openInputStream(uri) ?: throw IOException("document inaccessible")
                input.use { it.readTextLimited() }
            }
        }
    }
    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(vm::importText)
    }
    // CAMERA n'est demandée qu'ici, au moment du scan.
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scan.launch(scanOptions()) else toast(context, "Accès à la caméra refusé : collez le texte à la place.")
    }
    val hasCamera = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Importer / exporter") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                "Les mots de passe et jetons ne sont jamais exportés : ressaisissez-les après l'import.",
                style = MaterialTheme.typography.bodyMedium,
            )
            state.message?.let {
                Text(it, color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }

            Text("Fichier", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { createDocument.launch(EXPORT_FILE_NAME) }, enabled = state.devices.isNotEmpty() && !state.busy) {
                    Icon(Icons.Filled.FileUpload, contentDescription = null)
                    Text("Exporter", Modifier.padding(start = 8.dp))
                }
                OutlinedButton(
                    onClick = { openDocument.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                    enabled = !state.busy,
                ) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null)
                    Text("Importer", Modifier.padding(start = 8.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Remplacer les appareils existants", Modifier.weight(1f))
                Switch(checked = state.replaceExisting, onCheckedChange = vm::setReplaceExisting)
            }
            Text(
                if (state.replaceExisting) {
                    "Un appareil déjà présent (même identifiant) est mis à jour ; ses secrets sont gardés si l'adresse ne change pas."
                } else {
                    "Un appareil déjà présent (même identifiant) est laissé tel quel."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Texte", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { scope.launch { share(context, vm.shareText()) } },
                    enabled = state.devices.isNotEmpty() && !state.busy,
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Text("Partager", Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = { vm.importFrom { clipboardText(context) } }, enabled = !state.busy) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null)
                    Text("Coller", Modifier.padding(start = 8.dp))
                }
            }

            HorizontalDivider()
            Text("QR code", style = MaterialTheme.typography.titleMedium)
            if (hasCamera) {
                OutlinedButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            scan.launch(scanOptions())
                        } else {
                            cameraPermission.launch(Manifest.permission.CAMERA)
                        }
                    },
                    enabled = !state.busy,
                ) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                    Text("Scanner un QR code", Modifier.padding(start = 8.dp))
                }
            }
            if (state.devices.isEmpty()) {
                Text("Aucun appareil enregistré à afficher.", style = MaterialTheme.typography.bodyMedium)
            } else {
                DeviceChoice(label = "Tous les appareils", selected = state.selectedId == null) { vm.select(null) }
                state.devices.forEach { d ->
                    DeviceChoice(label = "${d.name} (${d.kind.label})", selected = state.selectedId == d.id) { vm.select(d.id) }
                }
                val qr = state.qr
                when {
                    qr == null -> Unit
                    qr.payload == null -> Text(
                        "Trop de données pour un seul QR code lisible : choisissez un appareil, ou utilisez le fichier.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        QrCodeImage(qr.payload, contentDescription = "QR code de ${qr.count} appareil(s)")
                        Text(
                            "À scanner depuis Zapette sur l'autre téléphone (Importer / exporter → Scanner).",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

/** Scanner ZXing embarqué : QR code seulement, sans bip, entièrement hors ligne. */
private fun scanOptions(): ScanOptions = ScanOptions()
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setPrompt("Visez le QR code affiché par Zapette")
    .setBeepEnabled(false)

private fun clipboardText(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    return clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

private fun share(context: Context, json: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Appareils Zapette")
        .putExtra(Intent.EXTRA_TEXT, json)
    try {
        context.startActivity(Intent.createChooser(send, "Partager les appareils"))
    } catch (_: ActivityNotFoundException) {
        toast(context, "Aucune application de partage disponible.")
    }
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
