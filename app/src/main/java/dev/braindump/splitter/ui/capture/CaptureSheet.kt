package dev.braindump.splitter.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.braindump.splitter.R
import kotlinx.coroutines.launch

/**
 * One-screen capture: type or dictate, hit Split. Used by the dashboard FAB and by the
 * transparent [CaptureActivity] behind the Quick Settings tile and launcher shortcuts.
 *
 * @param splitting shows the in-sheet progress state while Gemini Nano works.
 * @param hideOnSubmit close the sheet as soon as the idea is handed off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureSheet(
    startWithVoice: Boolean,
    splitting: Boolean,
    hideOnSubmit: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }
    val transcriber = rememberVoiceTranscriber()
    var text by rememberSaveable { mutableStateOf("") }
    var voiceAutoStarted by rememberSaveable { mutableStateOf(false) }

    val showMessage: (Int) -> Unit = { message ->
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
    val startListening: () -> Unit = {
        transcriber.start(
            onFinal = { spoken -> text = if (text.isBlank()) spoken else "${text.trimEnd()} $spoken" },
            onMessage = showMessage,
        )
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else showMessage(R.string.voice_permission_denied)
    }
    val toggleVoice: () -> Unit = {
        when {
            transcriber.isListening -> transcriber.stop()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> startListening()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val submit: () -> Unit = {
        val idea = text.trim()
        if (idea.isNotEmpty()) {
            if (transcriber.isListening) transcriber.release()
            onSubmit(idea)
            if (hideOnSubmit) {
                text = ""
                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.capture_title), style = MaterialTheme.typography.titleLarge)

            val listening = transcriber.isListening
            val partial = transcriber.partialText
            OutlinedTextField(
                // While dictating, show the live words after what's already committed.
                value = if (listening && partial.isNotEmpty()) "${text.trimEnd()} $partial".trimStart() else text,
                onValueChange = { text = it },
                readOnly = listening || splitting,
                placeholder = {
                    Text(stringResource(if (listening) R.string.capture_listening else R.string.capture_hint))
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                maxLines = 8,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .focusRequester(focusRequester),
            )

            if (splitting) {
                Text(
                    text = stringResource(R.string.capture_splitting),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (listening) {
                        FilledIconButton(
                            onClick = toggleVoice,
                            // Read in the draw phase so mic level changes never recompose the sheet.
                            modifier = Modifier.graphicsLayer {
                                val pulse = 1f + transcriber.level * 0.25f
                                scaleX = pulse
                                scaleY = pulse
                            },
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.capture_voice_stop))
                        }
                    } else {
                        FilledTonalIconButton(onClick = toggleVoice) {
                            Icon(
                                painter = painterResource(R.drawable.ic_mic),
                                contentDescription = stringResource(R.string.capture_voice),
                            )
                        }
                    }
                    if (listening) {
                        Text(
                            text = stringResource(R.string.capture_listening),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = submit, enabled = text.isNotBlank() && !listening) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.capture_submit))
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            if (startWithVoice && !voiceAutoStarted) {
                voiceAutoStarted = true
                toggleVoice()
            } else {
                // The field may not be attached on the very first frame of the sheet.
                runCatching { focusRequester.requestFocus() }
            }
        }
    }
}
