package com.pwnall.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.WeekFields

/**
 * The calendar: a month at a time, with what was recorded on it and what is expected.
 *
 * Its own page rather than a card on the screen, because a month is the one view the week strip
 * cannot be: the strip answers "where am I", and this answers "what happened, and when is it due".
 * The marks are the two circles and the three small figures — a ring for a period she wrote down,
 * a dashed ring for one the average expects, drops for how heavy it was, an open dot for spotting
 * between periods and a heart for an act — and nothing is drawn that the record does not carry.
 *
 * The heart is there only where [acts] says the profile is offered the sexual-activity log at all
 * ([sexLogOffered]): under the age gate it is off the squares, out of what they speak and out of
 * the key, which would otherwise name the thing the gate removed.
 *
 * [onAddPast] is the way to a period that was never logged: this is the page a missing one is
 * noticed on, and the record button on the page behind it only ever writes today.
 */
@Composable
internal fun CycleCalendarPage(
    modifier: Modifier,
    facts: CycleFacts,
    today: LocalDate,
    acts: Boolean,
    onBack: () -> Unit,
    onAddPast: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    val locale = LocalLocale.current.platformLocale
    val firstDay = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    var month by remember(today) { mutableStateOf(YearMonth.from(today)) }
    val days = remember(facts, month, firstDay, acts) { facts.calendarMonth(month, firstDay, acts) }
    val title = remember(locale) { localizedDatePattern(locale, "yMMMM", "LLLL yyyy") }
    val fullDate = remember(locale) { localizedDatePattern(locale, "yMMMMEEEEd", "EEEE d MMMM yyyy") }

    Column(modifier.fillMaxSize()) {
        PwnTopBar(stringResource(R.string.cycle_calendar_title), onBack)
        // A narrow side inset, because seven columns share the width and each day is a finger's
        // target; and a ceiling on the width, because the cells keep their shape and on a wide pane
        // six weeks of them would grow taller than the screen.
        Column(
            Modifier.weight(1f).fillMaxWidth().panelVerticalScroll(rememberScrollState())
                .padding(horizontal = CALENDAR_INSET),
        ) {
            Column(Modifier.align(Alignment.CenterHorizontally).widthIn(max = CALENDAR_MAX_WIDTH).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month = month.minusMonths(1) }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                            stringResource(R.string.m3_cal_previous_month),
                            tint = CycleAccent,
                        )
                    }
                    Text(
                        month.atDay(1).format(title).replaceFirstChar { it.uppercase(locale) },
                        Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    IconButton(onClick = { month = month.plusMonths(1) }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            stringResource(R.string.m3_cal_next_month),
                            tint = CycleAccent,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    repeat(CALENDAR_WEEK) { offset ->
                        Text(
                            firstDay.plus(offset.toLong()).getDisplayName(TextStyle.SHORT, locale),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = PwnMuted,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                days.chunked(CALENDAR_WEEK).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { day ->
                            CycleCalendarCell(
                                day = day,
                                today = day.date == today,
                                inMonth = YearMonth.from(day.date) == month,
                                spokenDate = day.date.format(fullDate),
                                modifier = Modifier.weight(1f),
                                onClick = { onPick(day.date) }.takeIf { !day.date.isAfter(today) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                CycleCalendarKey(acts, Modifier.padding(horizontal = CALENDAR_INSET))
                Spacer(Modifier.height(14.dp))
                // Under the key that names them, and not only behind the "?" on the page before: the
                // fertile days and the ovulation ring are counted from dates, and this is the sentence
                // that says what that is worth.
                Text(
                    stringResource(R.string.cycle_fertile_note),
                    Modifier.padding(horizontal = CALENDAR_INSET),
                    style = MaterialTheme.typography.bodySmall,
                    color = PwnMuted,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onAddPast, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.cycle_past_title))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** The page's side inset. The key and the note under the grid add it again, for a wider text margin. */
private val CALENDAR_INSET = 8.dp

/** The widest the month is drawn, so its cells stop growing on a wide pane. */
private val CALENDAR_MAX_WIDTH = 480.dp

/**
 * One square: the number, the ring round it, and the small figures under it.
 *
 * A recorded day of a period is a filled ring in the bleeding colour, one the average expects is the
 * same ring dashed — the app's standing rule that what was written down and what was worked out
 * must differ in shape and not only in strength. A fertile day keeps its pale fill and adds a dotted
 * ring in the accent: the fill alone is within a shade of the page and cannot be the only mark of
 * the day. Today keeps its own outline whatever else it is.
 *
 * Spotting between periods keeps the bleeding colour of its number and takes no ring: the ring is
 * the one the key calls "Period", and a spotting day is not a day of a period. Its mark is an open
 * dot where a period day's drops stand.
 */
@Composable
private fun CycleCalendarCell(
    day: CycleCalendarDay,
    today: Boolean,
    inMonth: Boolean,
    spokenDate: String,
    modifier: Modifier,
    onClick: (() -> Unit)?,
) {
    val bleed = PwnBleed
    val ovulation = CycleAccent
    val fertile = CycleCell
    val outline = MaterialTheme.colorScheme.onSurface
    val spoken = cycleCalendarSpoken(day, spokenDate, today)
    val period = day.bleeding && !day.spotting

    Box(
        modifier.aspectRatio(0.82f)
            .then(if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick))
            .semantics { contentDescription = spoken },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(CELL_RING), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val radius = size.minDimension / 2f - 1.dp.toPx()
                    // A bleeding ring, solid or dashed, stands on the same circle the fertile dots
                    // would; where a day is both, the fill stays and the bleeding ring is drawn.
                    if (day.fertile && (period || day.predicted)) drawCircle(fertile, radius)
                    when {
                        period -> drawCircle(bleed, radius, style = Stroke(2.dp.toPx()))

                        day.predicted -> drawCircle(
                            color = bleed.copy(alpha = 0.7f),
                            radius = radius,
                            style = Stroke(
                                width = 2.dp.toPx(),
                                pathEffect = androidx.compose.ui.graphics.PathEffect
                                    .dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                            ),
                        )

                        day.fertile -> drawFertileMark(fertile, ovulation, radius)
                    }
                    if (day.ovulation) {
                        drawCircle(ovulation, radius - 3.dp.toPx(), style = Stroke(2.dp.toPx()))
                    }
                    if (today) {
                        drawCircle(outline, radius + 2.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                    }
                }
                Text(
                    day.date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (today) FontWeight.Bold else FontWeight.Normal,
                    // A neighbouring month's day is the secondary ink at full strength: it can still
                    // be opened, so it is not disabled text, and thinned it would fall under the 4.5:1
                    // that text owes.
                    color = when {
                        !inMonth -> PwnMuted
                        day.bleeding -> bleed
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Spacer(Modifier.height(3.dp))
            Canvas(Modifier.height(MARK_ROW).fillMaxWidth()) {
                val unit = MARK_SIZE.toPx()
                val marks = (day.flow ?: 0) + (if (day.spotting) 1 else 0) + if (day.intimacy) 1 else 0
                if (marks == 0) return@Canvas
                var x = size.width / 2f - (marks - 1) * unit * 0.7f
                repeat(day.flow ?: 0) {
                    drawDroplet(Offset(x, size.height / 2f), unit, bleed)
                    x += unit * 1.4f
                }
                if (day.spotting) {
                    drawSpot(Offset(x, size.height / 2f), unit, bleed)
                    x += unit * 1.4f
                }
                if (day.intimacy) drawHeart(Offset(x, size.height / 2f), unit, ovulation)
            }
        }
    }
}

private val CELL_RING = 36.dp
private val MARK_ROW = 12.dp
private val MARK_SIZE = 7.dp

/**
 * A fertile day: the pale fill, and round it a ring of dots in the accent — dots, so it is neither
 * of the bleeding days' rings, the solid or the dashed.
 */
private fun DrawScope.drawFertileMark(fill: Color, ring: Color, radius: Float) {
    drawCircle(fill, radius)
    val dot = 2.dp.toPx()
    drawCircle(
        color = ring,
        radius = radius - dot / 2f,
        style = Stroke(
            width = dot,
            cap = StrokeCap.Round,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(0f, dot * 2.5f)),
        ),
    )
}

/** A drop: a circle with a point pulled out of the top, the same shape the log-period page draws. */
internal fun DrawScope.drawDroplet(centre: Offset, unit: Float, colour: Color) {
    val r = unit / 2f
    val tip = centre.y - r * 1.9f
    val path = Path().apply {
        moveTo(centre.x, tip)
        cubicTo(centre.x + r * 0.5f, tip + r * 0.8f, centre.x + r, centre.y - r * 0.6f, centre.x + r, centre.y)
        arcTo(Rect(centre.x - r, centre.y - r, centre.x + r, centre.y + r), 0f, 180f, false)
        cubicTo(centre.x - r, centre.y - r * 0.6f, centre.x - r * 0.5f, tip + r * 0.8f, centre.x, tip)
        close()
    }
    drawPath(path, colour)
}

/**
 * Spotting: an open dot the width of a drop. Open, so it is told from a drop by its shape — a drop
 * is an amount of flow, and spotting is the bleeding that has none.
 */
internal fun DrawScope.drawSpot(centre: Offset, unit: Float, colour: Color) {
    val line = 1.5.dp.toPx()
    drawCircle(colour, (unit - line) / 2f, centre, style = Stroke(line))
}

/** And a heart, for an act. Two arcs off a point, which is the whole of the shape. */
private fun DrawScope.drawHeart(centre: Offset, unit: Float, colour: Color) {
    val r = unit / 2f
    val path = Path().apply {
        moveTo(centre.x, centre.y + r)
        cubicTo(
            centre.x - r * 2f,
            centre.y - r * 0.4f,
            centre.x - r * 0.6f,
            centre.y - r * 1.8f,
            centre.x,
            centre.y - r * 0.5f,
        )
        cubicTo(
            centre.x + r * 0.6f,
            centre.y - r * 1.8f,
            centre.x + r * 2f,
            centre.y - r * 0.4f,
            centre.x,
            centre.y + r,
        )
        close()
    }
    drawPath(path, colour)
}

/** What the marks mean, said once under the grid rather than guessed at — the heart only where [acts]. */
@Composable
private fun CycleCalendarKey(acts: Boolean, modifier: Modifier = Modifier) {
    val fertileFill = CycleCell
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CycleCalendarKeyRow(stringResource(R.string.cycle_legend_period)) {
            drawCircle(it, size.minDimension / 2f - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
        }
        CycleCalendarKeyRow(stringResource(R.string.cycle_legend_predicted)) {
            drawCircle(
                color = it.copy(alpha = 0.7f),
                radius = size.minDimension / 2f - 1.dp.toPx(),
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect
                        .dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                ),
            )
        }
        CycleCalendarKeyRow(stringResource(R.string.cycle_legend_ovulation), accent = true) {
            drawCircle(it, size.minDimension / 2f - 4.dp.toPx(), style = Stroke(2.dp.toPx()))
        }
        CycleCalendarKeyRow(stringResource(R.string.cycle_legend_fertile), accent = true) {
            drawFertileMark(fertileFill, it, size.minDimension / 2f - 1.dp.toPx())
        }
        CycleCalendarKeyRow(stringResource(R.string.cycle_section_flow)) { colour ->
            var x = size.width / 2f - MARK_SIZE.toPx() * 1.4f
            repeat(3) {
                drawDroplet(Offset(x, size.height / 2f), MARK_SIZE.toPx(), colour)
                x += MARK_SIZE.toPx() * 1.4f
            }
        }
        CycleCalendarKeyRow(stringResource(R.string.cycle_spotting)) {
            drawSpot(Offset(size.width / 2f, size.height / 2f), MARK_SIZE.toPx(), it)
        }
        if (acts) {
            CycleCalendarKeyRow(stringResource(R.string.cycle_intimate_title), accent = true) {
                drawHeart(Offset(size.width / 2f, size.height / 2f), MARK_SIZE.toPx() * 1.4f, it)
            }
        }
    }
}

@Composable
private fun CycleCalendarKeyRow(
    label: String,
    accent: Boolean = false,
    draw: DrawScope.(Color) -> Unit,
) {
    val colour = if (accent) CycleAccent else PwnBleed
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(CELL_RING)) { draw(colour) }
        Spacer(Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = PwnMuted)
    }
}

/**
 * One square in words. A grid of forty-two clickable cells is forty-two things a screen reader has
 * to announce, and "18" on its own is not one of them — nor is "18" of which month, when the grid
 * carries the edges of the months either side. So the full date first, then today if it is, then
 * what the record holds.
 */
@Composable
private fun cycleCalendarSpoken(day: CycleCalendarDay, spokenDate: String, today: Boolean): String {
    val parts = buildList {
        add(spokenDate)
        if (today) add(stringResource(R.string.home_today))
        bleedingWord(day.bleeding, day.spotting)?.let { add(stringResource(it)) }
        if (day.predicted) add(stringResource(R.string.cycle_legend_predicted))
        if (day.ovulation) add(stringResource(R.string.cycle_legend_ovulation))
        if (day.fertile && !day.ovulation) add(stringResource(R.string.cycle_legend_fertile))
        day.flow?.let { add(stringResource(CycleScale.FLOW.logs[it - 1].titleRes)) }
        if (day.intimacy) add(stringResource(R.string.cycle_intimate_title))
    }
    return parts.joinToString(stringResource(R.string.list_comma))
}
