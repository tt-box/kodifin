package io.github.ttbox.kodifin.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import io.github.ttbox.kodifin.R

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    keyboardType: KeyboardType,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val submit = { if (value.isNotBlank()) onConfirm(value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value, { value = it },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = submit, enabled = value.isNotBlank()) { Text(stringResource(R.string.remote_send)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.remote_cancel)) } },
    )
}

/** Message à afficher sur l'écran piloté : titre et texte. */
@Composable
fun NotifyDialog(onDismiss: () -> Unit, onConfirm: (title: String, text: String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("Kodifin") }
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_notify_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.remote_notify_title_label)) }, singleLine = true)
                OutlinedTextField(
                    text, { text = it },
                    label = { Text(stringResource(R.string.remote_text_label)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (valid) onConfirm(title, text) }),
                    modifier = Modifier.focusRequester(focus),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(title, text) }, enabled = valid) { Text(stringResource(R.string.remote_notify_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.remote_cancel)) } },
    )
}

/** Confirmation d'une action irréversible ou dérangeante (alimentation, vidage de file). */
@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onConfirm()
            }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.remote_cancel)) } },
    )
}
