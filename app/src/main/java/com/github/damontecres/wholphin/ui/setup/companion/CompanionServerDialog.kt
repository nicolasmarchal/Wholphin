package com.github.damontecres.wholphin.ui.setup.companion

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.components.EditTextBox
import com.github.damontecres.wholphin.ui.components.TextButton
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.LoadingState

@Composable
fun CompanionServerDialog(
    initialUrl: String,
    status: LoadingState,
    onSubmit: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    BasicDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var url by remember(initialUrl) { mutableStateOf(initialUrl) }
        val urlFocusRequester = remember { FocusRequester() }
        val cancelFocusRequester = remember { FocusRequester() }
        val saveFocusRequester = remember { FocusRequester() }
        val loading = status == LoadingState.Loading
        val submit = { if (!loading && url.isNotBlank()) onSubmit(url) }

        LaunchedEffect(Unit) { urlFocusRequester.tryRequestFocus() }

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier
                    .widthIn(min = 560.dp)
                    .focusGroup()
                    .padding(24.dp)
                    .wrapContentSize(),
        ) {
            Text(
                text = stringResource(R.string.companion_server_url),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.url),
                    modifier = Modifier.width(72.dp),
                )
                EditTextBox(
                    value = url,
                    onValueChange = { url = it },
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    isInputValid = { true },
                    modifier =
                        Modifier
                            .weight(1f)
                            .focusRequester(urlFocusRequester)
                            .focusProperties { down = cancelFocusRequester }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                    cancelFocusRequester.requestFocus()
                                    true
                                } else {
                                    false
                                }
                            },
                )
            }

            when (status) {
                LoadingState.Loading -> {
                    Text(
                        text = stringResource(R.string.loading),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is LoadingState.Error -> {
                    Text(
                        text = status.localizedMessage,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                else -> {}
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(
                    stringRes = R.string.cancel,
                    onClick = onDismissRequest,
                    modifier =
                        Modifier
                            .focusRequester(cancelFocusRequester)
                            .focusProperties {
                                up = urlFocusRequester
                                right = saveFocusRequester
                            },
                )
                TextButton(
                    stringRes = R.string.save,
                    onClick = submit,
                    enabled = !loading && url.isNotBlank(),
                    modifier =
                        Modifier
                            .focusRequester(saveFocusRequester)
                            .focusProperties {
                                up = urlFocusRequester
                                left = cancelFocusRequester
                            },
                )
            }
        }
    }
}
