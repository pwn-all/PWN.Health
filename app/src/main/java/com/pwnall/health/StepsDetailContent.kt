package com.pwnall.health

import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun StepsDetailContent(face: MetricFace, imperial: Boolean, motion: StepsMotion) {
    val resources = LocalResources.current
    val prefix = if (motion.estimated) "≈ " else ""
    val time = if (motion.seconds > 0) {
        prefix + formatActivityClock(resources, (motion.seconds / 60.0).roundToInt())
    } else {
        "—"
    }
    val paceSeconds = motion.paceSeconds(imperial)
    val pace = paceSeconds?.let {
        prefix + stringResource(
            if (imperial) R.string.track_split_pace_miles else R.string.track_split_pace,
            formatPace(it.toLong()),
        )
    } ?: "—"
    // Hours, because hours are what the app holds: hourly_steps is the finest grain a step total
    // ever has. The clock range is written out rather than a count of hours so it reads as a
    // stretch of the day that happened, not as a score.
    val now = java.time.LocalTime.now()
    // Isolated left to right inside the row's value: two clock times and a dash hold no letter to
    // set a direction, and in an Arabic paragraph the stretch would read from its end to its start.
    val still = longestStillHours(face.hours, now.hour)?.let {
        val end = if (it.last == now.hour) clockText(now.hour * 60 + now.minute) else hourText(it.last + 1)
        "\u2066${hourText(it.first)}–$end\u2069"
    } ?: "—"
    MetricRing(face)
    StatRow(listOf(time to stringResource(R.string.steps_activity_label)) + face.stats.drop(1))
    // A phone with no pedometer counts nothing, and the card above simply reads zero for ever. The
    // service already declines to start in that case (HomeScreen's PermissionGate); this is the
    // only place that says so, instead of leaving the figure looking like a fault.
    val context = LocalContext.current
    val hasCounter = remember(context) {
        context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
    }
    if (!hasCounter) {
        Text(
            stringResource(R.string.steps_no_sensor),
            color = PwnMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )
    }
    HorizontalDivider(color = PwnBorder)
    StepsActivityHistory(face)
    StepDetailRow(
        Icons.Outlined.LocationOn,
        stringResource(R.string.steps_distance_title),
        face.detail,
        stringResource(R.string.steps_distance_note),
    )
    StepDetailRow(
        Icons.Outlined.Speed,
        stringResource(R.string.steps_pace_title),
        pace,
        stringResource(R.string.steps_route_note),
    )
    StepDetailRow(
        Icons.Outlined.AccessTime,
        stringResource(R.string.steps_time_title),
        time,
        stringResource(R.string.steps_route_note),
    )
    // Silent on a day the phone did not count: a band's or Health Connect's daily total has no
    // hours inside it, so every hour would read as still and the row would assert a ten-hour sit
    // that never happened.
    if (!face.hoursPartial) {
        StepDetailRow(
            Icons.Outlined.Chair,
            stringResource(R.string.steps_still_title),
            still,
            stringResource(R.string.steps_still_note),
        )
    }
    StepsActivityIntervals(face)
}

/**
 * What a screen reader needs from a control that folds something away: the state in words, and the
 * standard expand and collapse actions beside the tap. The chevron that draws the state is left
 * unlabelled, so this is the only place the state is said.
 */
internal fun Modifier.disclosureSemantics(expanded: Boolean, state: String, onChange: (Boolean) -> Unit) =
    semantics {
        stateDescription = state
        if (expanded) {
            collapse {
                onChange(false)
                true
            }
        } else {
            expand {
                onChange(true)
                true
            }
        }
    }

/** "Expanded" or "Collapsed", for [disclosureSemantics]. */
@Composable
internal fun disclosureState(expanded: Boolean): String =
    stringResource(if (expanded) R.string.a11y_state_expanded else R.string.a11y_state_collapsed)

@Composable
private fun StepDetailRow(icon: ImageVector, title: String, value: String, explanation: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    PwnCard(
        Modifier.fillMaxWidth().animateContentSize()
            .disclosureSemantics(expanded, disclosureState(expanded)) { expanded = it },
        onClick = { expanded = !expanded },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(36.dp).background(PwnBlueSoft, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = PwnInk, modifier = Modifier.size(24.dp))
                }
                Text(title, Modifier.weight(1f), color = PwnMuted, style = MaterialTheme.typography.bodyMedium)
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                // The mirrored chevron: shut, it points the way the language reads, which in Arabic
                // is to the left.
                Icon(
                    if (expanded) Icons.Outlined.ExpandMore else Icons.AutoMirrored.Outlined.NavigateNext,
                    contentDescription = null,
                    tint = PwnMuted,
                )
            }
            if (expanded) Text(explanation, color = PwnMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
