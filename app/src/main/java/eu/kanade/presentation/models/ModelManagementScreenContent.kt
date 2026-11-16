package eu.kanade.presentation.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Settings > Model management. Renders ONLY manager-backed state: every
 * enabled action maps to a real manager operation, and unwired capabilities
 * (BYOM import, runtime validation, remote catalog) are shown disabled with
 * the reason, never as available features.
 */
@Composable
fun ModelManagementScreenContent(
    packs: List<ModelPackUi>,
    onBack: () -> Unit,
    onInstall: (ModelPackUi) -> Unit,
    onCancel: (ModelPackUi) -> Unit,
    onVerify: (ModelPackUi) -> Unit,
    onDelete: (ModelPackUi) -> Unit,
) {
    Scaffold(
        topBar = { AppBar(title = stringResource(ATMR.strings.pref_models_title), navigateUp = onBack) },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(paddingValues),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(ATMR.strings.models_proto_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(packs, key = { it.id }) { pack ->
                PackCard(
                    pack = pack,
                    onInstall = { onInstall(pack) },
                    onCancel = { onCancel(pack) },
                    onVerify = { onVerify(pack) },
                    onDelete = { onDelete(pack) },
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(ATMR.strings.models_import_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(ATMR.strings.models_import_disabled),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = {}, enabled = false) {
                            Text(stringResource(ATMR.strings.models_import_action))
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(ATMR.strings.models_trust_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        listOf(
                            ATMR.strings.models_trust_unqualified,
                            ATMR.strings.models_trust_l2,
                            ATMR.strings.models_trust_leases,
                        ).forEach { res ->
                            Text(
                                text = stringResource(res),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PackCard(
    pack: ModelPackUi,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onVerify: () -> Unit,
    onDelete: () -> Unit,
) {
    val actions = modelPackActions(pack)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = pack.title, style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = packStateLabel(pack),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (pack.deviceLocked) {
                    Text(
                        text = stringResource(ATMR.strings.models_badge_device_locked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (!pack.qualified) {
                    Text(
                        text = stringResource(ATMR.strings.models_badge_unqualified),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            pack.version?.let { version ->
                Text(
                    text = stringResource(ATMR.strings.models_version_line, version),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            pack.previousVersionKept?.let { previous ->
                Text(
                    text = stringResource(ATMR.strings.models_previous_kept, previous),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (pack.storageBytes > 0) {
                Text(
                    text = stringResource(ATMR.strings.models_storage_installed, formatBytesForUi(pack.storageBytes)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (pack.state == PackUiState.INSTALLABLE && pack.downloadBytes > 0) {
                Text(
                    text = stringResource(ATMR.strings.models_download_size, formatBytesForUi(pack.downloadBytes)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            pack.progress?.let { progress ->
                LinearProgressIndicator(
                    progress = {
                        if (progress.totalBytes > 0) progress.doneBytes.toFloat() / progress.totalBytes else 0f
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(
                        ATMR.strings.models_install_progress,
                        progress.filesDone,
                        progress.filesTotal,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            pack.failureMessage?.let { failure ->
                Text(
                    text = stringResource(ATMR.strings.models_failure, failure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (actions.install) {
                    Button(onClick = onInstall) { Text(stringResource(ATMR.strings.models_action_install)) }
                }
                if (actions.cancel) {
                    TextButton(onClick = onCancel) { Text(stringResource(ATMR.strings.models_action_cancel)) }
                }
                if (actions.verify) {
                    TextButton(onClick = onVerify) { Text(stringResource(ATMR.strings.models_action_verify)) }
                }
                if (actions.delete) {
                    TextButton(onClick = onDelete) { Text(stringResource(ATMR.strings.models_action_delete)) }
                }
                if (pack.state == PackUiState.NOT_INSTALLABLE) {
                    TextButton(onClick = {}, enabled = false) {
                        Text(stringResource(ATMR.strings.models_action_unavailable))
                    }
                }
            }
        }
    }
}

@Composable
private fun packStateLabel(pack: ModelPackUi): String = when (pack.state) {
    PackUiState.NOT_INSTALLABLE -> stringResource(ATMR.strings.models_state_not_installable)
    PackUiState.INSTALLABLE -> stringResource(ATMR.strings.models_state_installable)
    PackUiState.INSTALLING -> stringResource(ATMR.strings.models_state_installing)
    PackUiState.INSTALLED -> stringResource(ATMR.strings.models_state_installed)
    PackUiState.FAILED -> stringResource(ATMR.strings.models_state_failed)
}
