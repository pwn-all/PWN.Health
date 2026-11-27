package com.pwnall.health

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

/**
 * Who writes each number in the record, how much each of them gave, and whose figure is the one
 * being shown.
 *
 * [dataSources] settles all of that; this prints it. The only thing decided here is what a source
 * is called: an app that wrote through Health Connect is named by the package manager, so the row
 * says "Fitbit" rather than a package name — and says the package name when the system will not
 * name it, which is what a source no longer installed looks like.
 *
 * [entries] is the journal the caller already holds. The four step tables are not in it and are
 * read here, off the main thread with the sources themselves: a year of days is a thousand rows
 * and the sum over them is not composition work.
 */
@Composable
internal fun DataSourceCards(entries: List<JournalEntry>, profile: UserProfile, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dao = remember { StepsDb.get(context).dao() }
    val metrics by produceState<List<MetricSources>?>(null, entries, profile) {
        value = withContext(Dispatchers.IO) {
            dataSources(
                entries = entries,
                localDays = dao.allLocalDays(),
                externalDays = dao.allExternalDays(),
                externalOriginDays = dao.allExternalOriginDays(),
                bleDays = dao.allDirectBleDays(),
                profile = profile,
            )
        }
    }
    val found = metrics ?: return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (found.isEmpty()) {
            PwnCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sources_empty), Modifier.padding(18.dp), color = PwnMuted)
            }
        }
        found.forEach { MetricCard(it) }
    }
}

@Composable
private fun MetricCard(metric: MetricSources) {
    val locale = LocalLocale.current.platformLocale
    val format = remember(locale) { localizedDatePattern(locale, "yMMMd", "d MMM yyyy") }
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PwnGlyph(metric.art, Icons.Outlined.Storage, PwnBlueSoft, null)
                Text(
                    stringResource(metric.titleRes),
                    Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            metric.shares.forEach { ShareRow(it, metric.inSteps, format) }
        }
    }
}

/**
 * One writer: what it is called, what it gave, and whether this is the one being believed.
 *
 * The winner is said in a word as well as in weight, because a bold line and a plain one are the
 * same line to a screen reader — and to anybody reading two sources that gave nearly the same
 * number, which is exactly the case this screen exists for.
 */
@Composable
private fun ShareRow(share: SourceShare, inSteps: Boolean, format: DateTimeFormatter) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                sourceName(share),
                fontWeight = if (share.winner) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                stringResource(R.string.sources_last, dayLabel(share.lastDay, format)),
                style = MaterialTheme.typography.bodySmall,
                color = PwnMuted,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (inSteps) {
                    stringResource(R.string.sources_steps_value, share.amount.formatted())
                } else {
                    pluralStringResource(R.plurals.backup_records, share.amount.toInt(), share.amount.toInt())
                },
                fontWeight = if (share.winner) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (share.winner) {
                Text(
                    stringResource(R.string.sources_shown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * A device's own name, the writing app's name from the system, or the token's own words.
 *
 * The system's name is asked for once a source, not at every recomposition: it is a call into the
 * package manager and a load of the other app's resources, on the main thread.
 */
@Composable
private fun sourceName(share: SourceShare): String {
    val context = LocalContext.current
    val appName = remember(share.source, share.installedApp) {
        if (share.installedApp) installedAppName(context, share.source) else null
    }
    return share.label?.takeIf { it.isNotBlank() } ?: appName ?: vitalSourceLabel(share.source)
}

/**
 * What the system calls the app behind a package name, or the package name itself.
 *
 * The manifest's `<queries>` makes the apps that integrate with Health Connect visible, which is
 * every app that can appear here; one that has since been uninstalled falls back to its package
 * name rather than disappearing, because "something called com.example.health wrote 400 of these"
 * is the answer, and a blank row is not.
 */
internal fun installedAppName(context: Context, packageName: String): String = runCatching {
    context.packageManager.getApplicationLabel(
        context.packageManager.getApplicationInfo(packageName, 0),
    ).toString()
}.getOrDefault(packageName)
