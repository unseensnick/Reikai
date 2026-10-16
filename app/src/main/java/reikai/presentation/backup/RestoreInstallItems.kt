package reikai.presentation.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.icerock.moko.resources.StringResource
import reikai.data.backup.BackupRepo
import reikai.data.backup.RestoreInstallPlan
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The Restore screen's install cards: what installs from the device's own repos, then each backup repo
 * this device lacks, with what it would install and a box to trust it. Trusting is also how a repo comes
 * back, so a repo is offered with Missing extensions off; only the installs follow that option.
 */
fun LazyListScope.restoreInstallItems(
    plan: RestoreInstallPlan,
    installs: Boolean,
    trusted: Set<String>,
    onTrust: (BackupRepo, Boolean) -> Unit,
) {
    if (installs && plan.ready.isNotEmpty()) {
        item(key = "restore-ready") {
            SectionCard(MR.strings.restore_installs_ready) {
                plan.ready.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
    items(plan.repos, key = { "restore-repo-${it.url}" }) { repo ->
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                Text(repo.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (repo.isPluginRepo) MR.strings.repo_kind_plugins else MR.strings.repo_kind_store),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(MR.strings.restore_repo_untrusted), style = MaterialTheme.typography.bodyMedium)
                RepoDetail(MR.strings.repo_address, repo.url)
                repo.signingKey?.let { RepoDetail(MR.strings.repo_signing_key, it) }
                if (repo.items.isEmpty()) {
                    RepoDetail(MR.strings.restore_repo_installs, stringResource(MR.strings.restore_repo_nothing))
                } else {
                    RepoDetail(MR.strings.restore_repo_installs, repo.items.joinToString(separator = "\n"))
                }
                LabeledCheckbox(
                    label = stringResource(MR.strings.restore_repo_trust),
                    checked = repo.url in trusted,
                    onCheckedChange = { onTrust(repo, it) },
                )
            }
        }
    }
}

@Composable
private fun RepoDetail(label: StringResource, value: String) {
    Column(Modifier.padding(top = MaterialTheme.padding.extraSmall)) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
