package io.github.ttbox.kodifin.ui.transfer

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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.container
import io.github.ttbox.kodifin.ui.text.asString
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Export et import des appareils : fichier (Storage Access Framework, aucune permission de
 * stockage), texte (partage, presse-papiers) et QR code (affichage, scan). Les mots de passe
 * et jetons ne sont jamais exportés. [deviceId] : appareil présélectionné pour le QR code.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(deviceId: String? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val vm: TransferViewModel = viewModel(
        factory = viewModelFactory { initializer { TransferViewModel(context.container.transferDependencies(), deviceId) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val resolver = context.contentResolver
    val scanPrompt = stringResource(R.string.transfer_scan_prompt)
    // Nom proposé pour le fichier d'export (Storage Access Framework), dans la langue de l'utilisateur.
    val exportFileName = stringResource(R.string.transfer_export_file_name)

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            vm.exportTo { json ->
                val out = resolver.openOutputStream(uri, "wt")
                    ?: throw IOException(resources.getString(R.string.transfer_error_document_unavailable))
                out.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }
        }
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.importFrom {
                val input = resolver.openInputStream(uri)
                    ?: throw IOException(resources.getString(R.string.transfer_error_document_unavailable))
                input.use { it.readTextLimited() }
            }
        }
    }
    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(vm::importText)
    }
    // CAMERA n'est demandée qu'ici, au moment du scan.
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scan.launch(scanOptions(scanPrompt)) else toast(context, resources.getString(R.string.transfer_camera_denied))
    }
    val hasCamera = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.transfer_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.transfer_back)) }
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
                stringResource(R.string.transfer_no_secrets),
                style = MaterialTheme.typography.bodyMedium,
            )
            state.message?.let {
                Text(it.asString(), color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }

            Text(stringResource(R.string.transfer_section_file), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { createDocument.launch(exportFileName) }, enabled = state.devices.isNotEmpty() && !state.busy) {
                    Icon(Icons.Filled.FileUpload, contentDescription = null)
                    Text(stringResource(R.string.transfer_export), Modifier.padding(start = 8.dp))
                }
                OutlinedButton(
                    onClick = { openDocument.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                    enabled = !state.busy,
                ) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null)
                    Text(stringResource(R.string.transfer_import), Modifier.padding(start = 8.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.transfer_replace_existing), Modifier.weight(1f))
                Switch(checked = state.replaceExisting, onCheckedChange = vm::setReplaceExisting)
            }
            Text(
                if (state.replaceExisting) {
                    stringResource(R.string.transfer_replace_existing_on)
                } else {
                    stringResource(R.string.transfer_replace_existing_off)
                },
                style = MaterialTheme.typography.bodySmall,
            )

            Text(stringResource(R.string.transfer_section_text), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { scope.launch { share(context, vm.shareText()) } },
                    enabled = state.devices.isNotEmpty() && !state.busy,
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Text(stringResource(R.string.transfer_share), Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = { vm.importFrom { clipboardText(context) } }, enabled = !state.busy) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null)
                    Text(stringResource(R.string.transfer_paste), Modifier.padding(start = 8.dp))
                }
            }

            HorizontalDivider()
            Text(stringResource(R.string.transfer_section_qr), style = MaterialTheme.typography.titleMedium)
            if (hasCamera) {
                OutlinedButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            scan.launch(scanOptions(scanPrompt))
                        } else {
                            cameraPermission.launch(Manifest.permission.CAMERA)
                        }
                    },
                    enabled = !state.busy,
                ) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                    Text(stringResource(R.string.transfer_scan), Modifier.padding(start = 8.dp))
                }
            }
            if (state.devices.isEmpty()) {
                Text(stringResource(R.string.transfer_no_devices), style = MaterialTheme.typography.bodyMedium)
            } else {
                DeviceChoice(label = stringResource(R.string.transfer_all_devices), selected = state.selectedId == null) { vm.select(null) }
                state.devices.forEach { d ->
                    DeviceChoice(label = "${d.name} (${d.kind.label})", selected = state.selectedId == d.id) { vm.select(d.id) }
                }
                val qr = state.qr
                when {
                    qr == null -> Unit
                    qr.payload == null -> Text(
                        stringResource(R.string.transfer_qr_too_dense),
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        QrCodeImage(
                            qr.payload,
                            contentDescription = pluralStringResource(R.plurals.transfer_qr_description, qr.count, qr.count),
                        )
                        Text(
                            stringResource(R.string.transfer_qr_hint),
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
private fun scanOptions(prompt: String): ScanOptions = ScanOptions()
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setPrompt(prompt)
    .setBeepEnabled(false)

private fun clipboardText(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    return clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

private fun share(context: Context, json: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.transfer_share_subject))
        .putExtra(Intent.EXTRA_TEXT, json)
    try {
        context.startActivity(Intent.createChooser(send, context.getString(R.string.transfer_share_chooser)))
    } catch (_: ActivityNotFoundException) {
        toast(context, context.getString(R.string.transfer_no_share_app))
    }
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
