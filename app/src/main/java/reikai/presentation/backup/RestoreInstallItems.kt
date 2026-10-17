package reikai.presentation.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import reikai.data.backup.BackupRepo
import reikai.data.backup.RestoreInstallPlan
import reikai.domain.extension.NO_SIGNING_KEY
import reikai.presentation.browse.repos.DetailLine
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The Restore screen's install cards: what installs from the device's own repos, then each backup repo
 * this device lacks, with what it would install and a box to trust it. Trusting is also how a repo comes
 * back, so a repo is offered with Missing extensions off. With it off the device's repos install nothing, and
 * their items are still named for the user to install by hand.
 */
fun LazyListScope.restoreInstallItems(
    plan: RestoreInstallPlan,
    installs: Boolean,
    trusted: Set<String>,
    onTrust: (BackupRepo, Boolean) -> Unit,
) {
    if (plan.ready.isNotEmpty()) {
        item(key = "restore-ready") {
            SectionCard(if (installs) MR.strings.restore_installs_ready else MR.strings.restore_installs_off) {
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
                DetailLine(stringResource(MR.strings.repo_address), repo.url)
                // A keyless store's sentinel is no key, as the Repositories screen shows it.
                repo.signingKey?.takeIf { it != NO_SIGNING_KEY }?.let {
                    DetailLine(stringResource(MR.strings.repo_signing_key), it)
                }
                DetailLine(
                    stringResource(MR.strings.restore_repo_installs),
                    repo.items.joinToString(separator = "\n").ifEmpty {
                        stringResource(MR.strings.restore_repo_nothing)
                    },
                )
                LabeledCheckbox(
                    label = stringResource(MR.strings.restore_repo_trust),
                    checked = repo.url in trusted,
                    onCheckedChange = { onTrust(repo, it) },
                )
            }
        }
    }
}
