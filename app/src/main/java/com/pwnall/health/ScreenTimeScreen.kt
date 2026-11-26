package com.pwnall.health

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.time.format.TextStyle

/**
 * Time in the feed apps — TikTok, Instagram, YouTube, Reddit and their kind — read from Android's own
 * usage log, and the two reminders it feeds: the eyes, after a long sitting without a break, and the
 * head, after a long day of feed. Beside them, and only counted, video, messengers and games.
 *
 * Nothing is written to the journal and nothing leaves the phone. Without Usage access — the same
 * one the sleep estimate asks for — the page says what the access is for and opens its screen.
 */
@Composable
fun ScreenTimeScreen(modifier: Modifier, onBack: () -> Unit, onBreathing: () -> Unit) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(hasUsageStatsAccess(context)) }
    var days by remember { mutableStateOf<List<FeedDay>?>(null) }
    var settings by remember { mutableStateOf(ScreenTime.settings(context)) }
    // Bumped on every return to the page, so the figures are as fresh as the phone's log. Opening
    // the page is such a return too, so nothing is read before that first bump: read at once as
    // well, the week's log would be read twice, the first reading dropped as the bump came after it.
    var reads by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        access = hasUsageStatsAccess(context)
        reads++
        onPauseOrDispose { }
    }
    LaunchedEffect(reads, access) {
        if (reads > 0) days = if (access) ScreenTime.read(context) else emptyList()
    }

    val usageAccess = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        access = hasUsageStatsAccess(context)
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun change(next: ScreenTimeSettings) {
        val turnedOn = next.anyOn && !settings.anyOn
        settings = next
        ScreenTime.save(context, next)
        val granted =
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (turnedOn && !granted) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Column(modifier.fillMaxSize()) {
        val title = stringResource(R.string.screen_time_title)
        PwnTopBar(title, onBack) { HelpDot(title) { Text(stringResource(R.string.screen_time_help)) } }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().panelVerticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                if (!access) {
                    AccessCard { usageAccess.openUsageAccess(context) }
                } else {
                    days?.let { read ->
                        read.lastOrNull()?.let { TodayCard(it, settings) }
                        if (read.size > 1) WeekCard(read)
                        // Only the kinds the week has any time in: a card of zeroes for games
                        // nobody plays is a page longer and says nothing.
                        COUNTED_KINDS.forEach { kind ->
                            if (read.any { it.msOf(kind) > 0 }) TrackedCard(read, kind)
                        }
                    }
                }
                RemindersCard(settings, enabled = access, onChange = ::change)
                TipsCard(onBreathing)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** The kinds counted and shown without a reminder, in the order their cards come. */
private val COUNTED_KINDS = listOf(AppKind.VIDEO, AppKind.MESSENGER, AppKind.GAME)

/** An app is listed on a card from this much time in it: the rows are for where the time went. */
private const val SHOWN_FROM_MS = 60_000L

private val AppKind.titleRes: Int
    get() = when (this) {
        AppKind.FEED -> R.string.screen_time_feeds_title
        AppKind.VIDEO -> R.string.screen_time_video_title
        AppKind.MESSENGER -> R.string.screen_time_messengers_title
        AppKind.GAME -> R.string.screen_time_games_title
    }

/** The colour of a kind's bars. */
private val AppKind.color: Color
    @Composable get() = when (this) {
        AppKind.FEED -> PwnIndigo
        AppKind.VIDEO -> PwnBlue
        AppKind.MESSENGER -> PwnGreen
        AppKind.GAME -> PwnPurple
    }

/** [ms] as the page says every figure: "1 h 5 min", in whole minutes. */
@Composable
private fun minutesText(ms: Long): String = workDurationText(LocalResources.current, minutesOf(ms))

/** A card's heading, announced as one by a screen reader. */
@Composable
private fun CardTitle(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun AccessCard(onOpen: () -> Unit) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.screen_time_access_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.screen_time_access_body), color = PwnMuted)
            Button(onClick = onOpen) { Text(stringResource(R.string.screen_time_access_button)) }
        }
    }
}

@Composable
private fun TodayCard(today: FeedDay, settings: ScreenTimeSettings) {
    val limitMs = settings.brainMinutes * 60_000L
    val overDay = today.totalMs >= limitMs
    val overSitting = today.longestSessionMs >= settings.eyesMinutes * 60_000L
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle(stringResource(AppKind.FEED.titleRes))
            Text(
                minutesText(today.totalMs),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (overDay) PwnWarn else MaterialTheme.colorScheme.onSurface,
            )
            if (overDay) OverLine(settings.brainMinutes)
            UsedApps(today, AppKind.FEED) { ms -> ms.toFloat() / limitMs }
            Text(
                stringResource(R.string.screen_time_longest, minutesText(today.longestSessionMs)),
                style = MaterialTheme.typography.bodyMedium,
                color = if (overSitting) PwnWarn else PwnMuted,
            )
            if (overSitting) OverLine(settings.eyesMinutes)
        }
    }
}

/**
 * "Over 2 h", under a figure drawn in the warning colour: the step it has passed said in words as
 * well, since a colour alone is lost on anybody who cannot tell it from the others.
 */
@Composable
private fun OverLine(minutes: Int) {
    Text(
        stringResource(R.string.screen_time_over, durationLabel(LocalResources.current, minutes)),
        style = MaterialTheme.typography.bodyMedium,
        color = PwnWarn,
    )
}

@Composable
private fun WeekCard(days: List<FeedDay>) {
    val most = days.maxOf(FeedDay::totalMs).coerceAtLeast(1L)
    val average = days.sumOf(FeedDay::totalMs) / days.size
    val locale = LocalLocale.current.platformLocale
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle(stringResource(R.string.screen_time_week))
            days.forEach { day ->
                AppRow(
                    day.day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                    minutesText(day.totalMs),
                    day.totalMs.toFloat() / most,
                    PwnBlue,
                )
            }
            Text(
                stringResource(R.string.screen_time_week_average, minutesText(average)),
                style = MaterialTheme.typography.bodySmall,
                color = PwnMuted,
            )
        }
    }
}

/**
 * One counted-only kind — video, messengers, games — today per app, its longest sitting and the
 * week. No reminder reads it, so the bars are against the busiest app rather than a limit.
 */
@Composable
private fun TrackedCard(days: List<FeedDay>, kind: AppKind) {
    val today = days.last()
    val most = today.byApp.filterKeys { it.kind == kind }.values.fold(1L, ::maxOf)
    val week = days.sumOf { it.msOf(kind) }
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle(stringResource(kind.titleRes))
            Text(
                minutesText(today.msOf(kind)),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            UsedApps(today, kind) { ms -> ms.toFloat() / most }
            if (today.longestOf(kind) > 0) {
                Text(
                    stringResource(R.string.screen_time_category_longest, minutesText(today.longestOf(kind))),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PwnMuted,
                )
            }
            Text(
                stringResource(R.string.screen_time_category_week, minutesText(week), minutesText(week / days.size)),
                style = MaterialTheme.typography.bodySmall,
                color = PwnMuted,
            )
            Text(
                stringResource(R.string.screen_time_tracked_note),
                style = MaterialTheme.typography.bodySmall,
                color = PwnMuted,
            )
        }
    }
}

/**
 * The apps of [kind] open [SHOWN_FROM_MS] or more [today], most time first, each bar the [share] of
 * its time. With nearly sixty apps listed, a row for each would bury the three that matter under
 * rows of zeroes; a day with none says so instead.
 */
@Composable
private fun UsedApps(today: FeedDay, kind: AppKind, share: (Long) -> Float) {
    val used = today.byApp
        .filter { (app, ms) -> app.kind == kind && ms >= SHOWN_FROM_MS }
        .toList()
        .sortedByDescending { it.second }
    if (used.isEmpty()) {
        Text(
            stringResource(R.string.screen_time_none_today),
            style = MaterialTheme.typography.bodyMedium,
            color = PwnMuted,
        )
    }
    // Only the feeds' bars stand against a limit, the head's step, and their warning is said in
    // words on the card as well ([OverLine]). The other kinds' stand against the busiest app, whose
    // full bar is no warning — nor is the busiest day's on the week's card.
    used.forEach { (app, ms) ->
        val over = kind == AppKind.FEED && share(ms) >= 1f
        AppRow(app.label, minutesText(ms), share(ms), if (over) PwnWarn else kind.color)
    }
}

@Composable
private fun AppRow(name: String, value: String, share: Float, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(8.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = PwnMuted)
        }
        LinearProgressIndicator(
            progress = { share.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun RemindersCard(settings: ScreenTimeSettings, enabled: Boolean, onChange: (ScreenTimeSettings) -> Unit) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            PwnSwitchRow(
                title = stringResource(R.string.screen_time_eyes_switch),
                body = stringResource(R.string.screen_time_eyes_switch_body),
                checked = settings.eyesOn,
                enabled = enabled,
                onCheckedChange = { onChange(settings.copy(eyesOn = it)) },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            )
            if (settings.eyesOn) {
                MinuteChoices(EYES_MINUTE_CHOICES, settings.eyesMinutes, enabled) {
                    onChange(settings.copy(eyesMinutes = it))
                }
            }
            PwnSwitchRow(
                title = stringResource(R.string.screen_time_brain_switch),
                body = stringResource(R.string.screen_time_brain_switch_body),
                checked = settings.brainOn,
                enabled = enabled,
                onCheckedChange = { onChange(settings.copy(brainOn = it)) },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            )
            if (settings.brainOn) {
                MinuteChoices(BRAIN_MINUTE_CHOICES, settings.brainMinutes, enabled) {
                    onChange(settings.copy(brainMinutes = it))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MinuteChoices(choices: List<Int>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    val resources = LocalResources.current
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.forEach { minutes ->
            PwnChoiceChip(
                selected = minutes == selected,
                onClick = { onSelect(minutes) },
                label = durationLabel(resources, minutes),
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun TipsCard(onBreathing: () -> Unit) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle(stringResource(R.string.screen_time_tips_title))
            listOf(
                R.string.screen_time_tip_eyes,
                R.string.screen_time_tip_blink,
                R.string.screen_time_tip_brain,
                R.string.screen_time_tip_night,
            ).forEach { Text("• " + stringResource(it), style = MaterialTheme.typography.bodyMedium) }
            OutlinedButton(onClick = onBreathing) { Text(stringResource(R.string.screen_time_breathing)) }
        }
    }
}
