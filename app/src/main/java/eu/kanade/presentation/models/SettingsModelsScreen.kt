package eu.kanade.presentation.models

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.translation.models.DeleteResult
import eu.kanade.translation.models.InstallResult
import eu.kanade.translation.models.ModelPackCatalog
import eu.kanade.translation.models.ModelPackManagerProvider
import eu.kanade.translation.models.PackFailure
import eu.kanade.translation.models.VerifyResult
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlinx.coroutines.launch

/**
 * Settings > Model management, backed by the real ModelPackManager. Reachable
 * from Settings > Translations; reflects disk-as-truth state on entry.
 */
class SettingsModelsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val manager = remember(context) { ModelPackManagerProvider.get(context) }
        val descriptors = remember { ModelPackCatalog.defaultEmbedded().associateBy { it.id } }
        val statuses by manager.statuses.collectAsState()
        val scope = rememberCoroutineScope()

        var deleteAckPack by remember { mutableStateOf<ModelPackUi?>(null) }
        var meteredPack by remember { mutableStateOf<ModelPackUi?>(null) }
        var notice by remember { mutableStateOf<String?>(null) }

        // Pre-resolve strings at composition; coroutine callbacks only format.
        val verifyOkText = stringResource(ATMR.strings.models_verify_ok)
        val verifyCorruptTemplate = stringResource(ATMR.strings.models_verify_corrupt, "%1\$s")
        val deletedTemplate = stringResource(ATMR.strings.models_deleted, "%1\$s")
        val blockedLeasesText = stringResource(ATMR.strings.models_delete_blocked_leases)

        LaunchedEffect(Unit) { manager.refresh() }

        ModelManagementScreenContent(
            packs = statuses.map { modelPackUi(it, descriptors[it.id]) },
            onBack = navigator::pop,
            onInstall = { pack ->
                scope.launch {
                    when (val result = manager.install(pack.id)) {
                        is InstallResult.Failed -> if (result.failure == PackFailure.MeteredConfirmationRequired) {
                            meteredPack = pack
                        } else {
                            notice = result.failure.message
                        }
                        else -> Unit
                    }
                }
            },
            onCancel = { pack ->
                scope.launch { manager.cancel(pack.id) }
            },
            onVerify = { pack ->
                scope.launch {
                    notice = when (val result = manager.verify(pack.id)) {
                        VerifyResult.Verified -> verifyOkText
                        is VerifyResult.Corrupt -> verifyCorruptTemplate.format(result.problems.joinToString("; "))
                        VerifyResult.NotInstalled -> null
                    }
                }
            },
            onDelete = { pack ->
                scope.launch {
                    when (val result = manager.delete(pack.id)) {
                        is DeleteResult.Deleted -> notice = deletedTemplate.format(formatBytesForUi(result.freedBytes))
                        is DeleteResult.Blocked ->
                            when (deleteBlockUi(result.reason)) {
                                DeleteBlockUi.LIVE_LEASES -> notice = blockedLeasesText
                                DeleteBlockUi.ESSENTIAL_ACK -> deleteAckPack = pack
                                DeleteBlockUi.NOT_INSTALLED -> Unit
                            }
                    }
                }
            },
        )

        meteredPack?.let { pack ->
            AlertDialog(
                onDismissRequest = { meteredPack = null },
                title = { Text(stringResource(ATMR.strings.models_metered_title)) },
                text = { Text(stringResource(ATMR.strings.models_metered_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        meteredPack = null
                        scope.launch {
                            val result = manager.install(pack.id, confirmMetered = true)
                            if (result is InstallResult.Failed) notice = result.failure.message
                        }
                    }) { Text(stringResource(ATMR.strings.models_action_install)) }
                },
                dismissButton = {
                    TextButton(onClick = { meteredPack = null }) {
                        Text(stringResource(ATMR.strings.models_action_cancel))
                    }
                },
            )
        }

        deleteAckPack?.let { pack ->
            AlertDialog(
                onDismissRequest = { deleteAckPack = null },
                title = { Text(stringResource(ATMR.strings.models_delete_essential_title)) },
                text = { Text(stringResource(ATMR.strings.models_delete_essential_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        deleteAckPack = null
                        scope.launch {
                            when (val result = manager.delete(pack.id, acknowledgeEssential = true)) {
                                is DeleteResult.Deleted -> notice = deletedTemplate.format(formatBytesForUi(result.freedBytes))
                                is DeleteResult.Blocked -> notice = blockedLeasesText
                            }
                        }
                    }) { Text(stringResource(ATMR.strings.models_delete_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteAckPack = null }) {
                        Text(stringResource(ATMR.strings.models_action_dismiss))
                    }
                },
            )
        }

        notice?.let { message ->
            AlertDialog(
                onDismissRequest = { notice = null },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { notice = null }) {
                        Text(stringResource(ATMR.strings.models_action_dismiss))
                    }
                },
            )
        }
    }
}
