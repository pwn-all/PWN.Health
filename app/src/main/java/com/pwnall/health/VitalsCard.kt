package com.pwnall.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The measurements a person's own cuff, oximeter, thermometer and scale have been writing into the
 * journal, shown to the person they were taken from.
 *
 * Nothing here reads the database and nothing here decides what a reading means: [Vital] settles
 * the number and which side of its published band it fell on, and these cards print that and the
 * band it was read against, naming the body that published it so a threshold can be checked rather
 * than believed.
 *
 * A vital with no reading in the range gets no card. An empty row saying "—" for a cuff nobody owns
 * is furniture: the section is a list of what was actually measured, not a catalogue of devices.
 *
 * Three things the cards print that the model alone could not. The top of every range is
 * [Vital.insideTop] — the largest reading still inside — because [LabBand.high] is the first value
 * OUTSIDE, and a card printing the edge itself would name 140/90 as the top of normal while
 * colouring a 140/90 reading as raised. A reading with no verdict at all — [VitalReading.status]
 * null — is printed as its own third thing: not coloured, not counted as outside, and never shaded
 * green, with one line saying which silence it is. And a band written from one edge alone is drawn
 * and spoken as the one-sided thing it is: no strip closing on the frame edge, no colour on its
 * NORMAL, and a sentence saying what was tested rather than one claiming a pass on both sides.
 *
 * The verdict is in words as well as in colour. PwnIndigo and PwnBlueInk are one blue apart, which is
 * no difference at all to a person who cannot separate them and nothing whatsoever to a screen
 * reader, so every graded figure carries "above the band" or "inside the band" beside it.
 *
 * The trend is drawn the way [LabsScreen]'s history rows draw theirs — a shaded strip for the band,
 * a smoothed line over it, the same three status colours — so a reading looked at here and the same
 * kind of reading looked at there do not appear to be graded by two different apps.
 */
@Composable
internal fun VitalCards(entries: List<JournalEntry>, profile: UserProfile, modifier: Modifier = Modifier) {
    // Sorting the journal and reading a payload out of every row is not composition work, and the
    // range behind these cards is a month or a year of them. Keyed on the rows themselves it
    // happens when the range changes rather than every time a day is tapped.
    val history = remember(entries, profile) { vitalHistory(entries, profile) }
    // The heading belongs to the cards rather than to the page above them: a section title standing
    // over nothing is the same furniture as an empty row, and this way both leave together.
    if (history.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val title = stringResource(R.string.vitals_title)
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Said as a heading, so a screen reader can jump to the section by it.
            Box(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
                PwnSectionTitle(title)
            }
            // The bands below are published guidance, and the app grades against them without
            // ever saying what a grade is not. That is said here, beside the section it covers,
            // behind the question mark every explanation in the app stands behind.
            HelpDot(title) { Text(stringResource(R.string.vitals_disclaimer)) }
        }
        // Vital's own order rather than the map's: what a person reads first should not depend on
        // which device happened to connect first.
        Vital.entries.forEach { vital ->
            history[vital]?.let { series -> VitalCard(series, profile) }
        }
    }
}

/**
 * One vital: the latest reading and when it was taken, the spread across the range, and the series
 * drawn against the band.
 *
 * [readings] is one vital's series, oldest first — a value out of [vitalHistory], or a
 * [vitalSeries] call. Stateless: the caller owns the rows and the range they came from.
 *
 * [profile] is read for the band and, on the six vitals stored in a unit it may not read, for the
 * scale the figure is printed on. The band is taken at the age of the newest reading the way
 * [LabsScreen]'s history rows take theirs: a person who turned eighteen inside the range is shown
 * the band they have now, while the older readings keep the verdict — none — they were graded with
 * on the day they were taken.
 *
 * What wrote the latest reading is that reading's own [VitalReading.source]; a row that named none
 * simply says nothing rather than guessing at one. Looking it up by the instant in a map of every
 * journal row would let a weight imported for 08:00 sharp put its device's name under a pressure
 * typed in for the same minute.
 */
@Composable
internal fun VitalCard(
    readings: List<VitalReading>,
    profile: UserProfile,
    modifier: Modifier = Modifier,
) {
    val stats = vitalStats(readings) ?: return
    val vital = stats.vital
    val band = vital.bandFor(profile.sex, ageOn(stats.latest.day, profile.birthDate))
    val source = vital.sourceRes
    // Which scale this profile reads on. The thermometer, the scale's two masses and the tape's three
    // girths are the figures on these cards that are not printed exactly as they were stored — see
    // [Vital.displayValue].
    val imperial = profile.imperial
    val unit = stringResource(vital.displayUnitRes(imperial))
    val locale = LocalLocale.current.platformLocale
    val dayFormat = remember(locale) { localizedDatePattern(locale, "dMMM", "d MMM") }
    // Three answers, not two. A reading nobody could grade is not a normal one: it colours nothing,
    // it is not counted outside, and it says for itself which of the two silences it is — a band
    // written for a site this reading was not taken at, or an adult band on somebody younger.
    //
    // Which way a reading went is kept apart from whether it went anywhere, because "outside" is
    // the one word that tells a person nothing they could act on.
    val above = readings.any { it.status == LabStatus.ABOVE || it.status == LabStatus.FAR_ABOVE }
    val below = readings.any { it.status == LabStatus.BELOW || it.status == LabStatus.FAR_BELOW }
    val outside = above || below
    val ungraded = if (vital.banded) readings.filter { it.status == null } else emptyList()
    val siteless = ungraded.any { !temperatureBandApplies(it.siteName) }
    val ageless = ungraded.any { temperatureBandApplies(it.siteName) }
    val trendRes = when {
        band == null -> R.string.vitals_trend_plain

        above && below -> R.string.vitals_trend_outside

        above -> R.string.vitals_trend_above

        below -> R.string.vitals_trend_below

        // A fortnight of armpit temperatures graded nothing, and reading it out as a fortnight
        // inside the band would be the screen reader saying what the card refused to say.
        ungraded.isNotEmpty() -> R.string.vitals_trend_plain

        band.twoSided -> R.string.vitals_trend_inside

        // Body fat has a ceiling and no floor. "Every reading inside its band" over a band with one
        // edge would be a pass on a side nobody ever published an edge for.
        band.hasHigh -> R.string.vitals_trend_not_above

        else -> R.string.vitals_trend_not_below
    }
    // The strip and the line are the card's whole answer about where the readings sat, and a
    // drawing publishes neither. Spoken from the very figures it was drawn from — but not from all
    // of them: a year of daily samples read out one at a time is the series again rather than a
    // description of it. First, last and the two extremes, with the count line below carrying how
    // many there were in between.
    val spokenReadings = listOf(readings.first(), stats.lowest, stats.highest, stats.latest)
        .distinct()
        .sortedBy(VitalReading::occurredAt)
    val spoken = stringResource(
        trendRes,
        stringResource(vital.titleRes),
        unit,
        spokenReadings.joinToString {
            "${dayLabel(it.day, dayFormat)}: ${readingText(vital, it.value, it.secondary, imperial, locale)}"
        },
    )

    val explained = vitalExplanations(vital, graded = band != null)
    val baseline = remember(readings) { if (vital in BASELINE_VITALS) vitalBaseline(readings) else null }
    PwnCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val title = stringResource(vital.titleRes)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (explained.isNotEmpty()) {
                            HelpDot(title) { explained.forEach { Text(stringResource(it)) } }
                        }
                    }
                    // The instant rather than the day: two pressures taken the same morning are two
                    // readings, and which of them is the latest is the whole of what this line says.
                    Text(
                        dayMonthClock(
                            Instant.ofEpochMilli(stats.latest.occurredAt).atZone(ZoneId.systemDefault()),
                            dayFormat,
                        ),
                        color = PwnMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            readingText(vital, stats.latest.value, stats.latest.secondary, imperial, locale),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = stats.latest.status.vitalTint(band),
                        )
                        Text(
                            unit,
                            Modifier.padding(start = 4.dp),
                            color = PwnMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    // The verdict said rather than only tinted. Two of the three tints are the same
                    // blue to most eyes and none of them is anything at all to a screen reader, so
                    // the word is what a person actually reads off this card.
                    stats.latest.status.vitalVerdictRes(band)?.let { verdict ->
                        Text(
                            stringResource(verdict),
                            color = PwnMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // Whose reading it is. A pulse typed into the editor, a chest strap's, a cuff's and a
            // wearable's one sample of the day are four different measurements of four different
            // things, and without this line the card would draw all four identically.
            stats.latest.source?.takeIf(String::isNotBlank)?.let { wroteIt ->
                VitalNote(stringResource(R.string.measurement_source_value, vitalSourceLabel(wroteIt)))
            }
            // Where the thermometer says it read, on the reading printed above it: 37.6 at an
            // armpit and 37.6 in the mouth are not the same measurement, and the site is the whole
            // of the difference.
            stats.latest.site?.let { site ->
                VitalNote(stringResource(R.string.vitals_site_measured_at, stringResource(site.labelRes)))
            }

            VitalTrend(readings, band, outside, ungraded.isNotEmpty(), spoken)

            if (siteless) {
                VitalNote(stringResource(R.string.vitals_no_verdict_site))
            }
            if (ageless) {
                // Withheld for want of an age and withheld because the age is under eighteen are
                // not the same silence, and only one of them is a statement about the person.
                VitalNote(
                    stringResource(
                        if (birthDateKnown(profile.birthDate)) {
                            R.string.vitals_no_verdict_age
                        } else {
                            R.string.vitals_no_verdict_birthdate
                        },
                    ),
                )
            }

            // One reading has no spread, and printing it three times under three different words
            // would dress a single measurement up as a statistic.
            if (stats.count > 1) {
                VitalFigures(
                    listOf(
                        R.string.vitals_lowest to
                            readingText(vital, stats.lowest.value, stats.lowest.secondary, imperial, locale),
                        R.string.vitals_mean to readingText(vital, stats.mean, stats.meanSecondary, imperial, locale),
                        R.string.vitals_highest to
                            readingText(vital, stats.highest.value, stats.highest.secondary, imperial, locale),
                    ),
                )
            }

            // The person's own usual figure and where the latest sits against it — for the two
            // vitals whose meaning is that comparison. Both are counted in whole beats and whole
            // milliseconds, so a difference that rounds to nothing is said to be nothing.
            baseline?.let { usual ->
                VitalNote(
                    pluralStringResource(
                        R.plurals.vitals_baseline,
                        usual.count,
                        readingText(vital, usual.median, null, imperial, locale),
                        unit,
                        usual.count,
                    ),
                )
                val shift = abs(usual.delta).roundToInt()
                VitalNote(
                    when {
                        shift == 0 -> stringResource(R.string.vitals_baseline_same)
                        usual.delta > 0 -> stringResource(R.string.vitals_baseline_above, shift, unit)
                        else -> stringResource(R.string.vitals_baseline_below, shift, unit)
                    },
                )
            }

            VitalNote(pluralStringResource(R.plurals.count_readings, stats.count, stats.count))
            // Where an adult band was withheld the card says nothing here at all: the line above
            // has already said why, and "nobody published a band" would be untrue of a figure whose
            // band exists and is simply not this person's yet.
            val bandLine = when {
                band != null && source != null ->
                    stringResource(
                        R.string.vitals_band,
                        vitalRangeText(vital, band, imperial),
                        stringResource(source),
                    )

                vital.banded -> null

                else -> stringResource(R.string.vitals_no_band)
            }
            bandLine?.let { line -> VitalNote(line) }
            // The card changes colour twice and only one of the two thresholds was printed. A
            // person can check a number; they cannot check a shade of orange they have to guess at.
            band?.veryHigh?.let { edge ->
                VitalNote(
                    stringResource(
                        R.string.vitals_band_far_above,
                        vital.printed(edge, vital.secondaryBand?.veryHigh, imperial, locale),
                    ),
                )
            }
            band?.veryLow?.let { edge ->
                VitalNote(
                    stringResource(
                        R.string.vitals_band_far_below,
                        vital.printed(edge, vital.secondaryBand?.veryLow, imperial, locale),
                    ),
                )
            }
            // Body fat is the one band that genuinely differs by sex, so the card that prints 25 to
            // one person and 35 to another has to say which of the two cut-offs it just printed.
            if (band != null && vital.femaleBand != null) {
                VitalNote(
                    stringResource(
                        if (band == vital.femaleBand) R.string.vitals_band_women else R.string.vitals_band_men,
                    ),
                )
            }
            if (band != null && vital.adultOnly) {
                VitalNote(stringResource(R.string.vitals_note_adult_band))
            }
        }
    }
}

/** One of the card's grey qualifying lines: what the band is, whose it is, or why there is none. */
@Composable
private fun VitalNote(text: String) {
    Text(text, color = PwnMuted, style = MaterialTheme.typography.bodySmall)
}

/**
 * The three figures under the trend: side by side while each fits its third whole, one under another
 * when one does not.
 *
 * A pressure is a pair joined by a slash, and a line break has nowhere to fall inside it: squeezed
 * into a third of the card at a large text size it would be cut through the middle of a number.
 * Measured in the type the figure is printed in, so the row stays side by side wherever it fits.
 */
@Composable
private fun VitalFigures(figures: List<Pair<Int, String>>) {
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.copy(fontWeight = FontWeight.SemiBold)
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val third = with(density) { ((maxWidth - VITAL_FIGURE_GAP * (figures.size - 1)) / figures.size).roundToPx() }
        val fits = figures.all { (_, figure) -> measurer.measure(figure, style).size.width <= third }
        if (fits) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(VITAL_FIGURE_GAP)) {
                figures.forEach { (label, figure) -> VitalFigure(label, figure, Modifier.weight(1f)) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                figures.forEach { (label, figure) -> VitalFigure(label, figure, Modifier.fillMaxWidth()) }
            }
        }
    }
}

/** One of the three figures under the trend: what it is, and the reading itself. */
@Composable
private fun VitalFigure(labelRes: Int, figure: String, modifier: Modifier) {
    Column(modifier) {
        Text(
            stringResource(labelRes),
            color = PwnMuted,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(figure, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The series over the band it is read against, drawn the way [LabsScreen] draws a marker's years.
 *
 * The band is a shaded strip rather than a pair of labelled lines, because the question a glance
 * asks is "was it inside", and a strip answers it without reading an axis. The line is smoothed
 * through the midpoints between readings, which is decoration and admits as much: what happened
 * between two squeezes of a cuff is not recorded anywhere.
 *
 * The strip is drawn only where the band closed both sides. A band with one edge — body fat's
 * ceiling, saturation's floor — has nothing to close the other end of the strip on but the frame
 * itself, and a strip run down to the bottom of the frame says a body fat of 4% is inside the
 * band it was never measured against. So that half stays empty and the dashed edge is left to say
 * the whole of what such a band has to say.
 *
 * A reading with no verdict is still drawn — it is a measurement of somebody, and dropping it would
 * leave the line disagreeing with the lowest, the mean and the highest printed under it — but with
 * [ungraded] set the strip loses its colour. Shading it in-band green under a temperature nobody
 * could grade would be the drawing handing back the verdict the card has just refused to give.
 *
 * Limitation: on a blood pressure only the systolic is drawn. Both halves on one axis would put the
 * diastolic underneath a strip written for the systolic, where a diastolic of 100 — above its own
 * band — would sit inside the shading and read as normal. The pair is printed in every figure on
 * the card, [outside] already accounts for whichever half was worse, and the caption says which
 * number the line is; drawing both honestly would mean two charts and two axes.
 */
@Composable
private fun VitalTrend(
    readings: List<VitalReading>,
    band: LabBand?,
    outside: Boolean,
    ungraded: Boolean,
    description: String,
) {
    val low = band?.low ?: band?.veryLow
    val high = band?.high ?: band?.veryHigh
    // The band's own edges are in the frame whether or not a reading came near them: a fortnight of
    // pressures all at 120 should be seen sitting well inside 90 to 140, not filling the card.
    val values = readings.map(VitalReading::value) + listOfNotNull(low, high)
    val floor = values.min()
    val ceiling = values.max()
    val span = (ceiling - floor).takeIf { it > 0.0 } ?: 1.0
    val top = ceiling + span * 0.2
    val bottom = floor - span * 0.2

    val line = PwnIndigo
    // Three tones for the three answers this card can give — the warning ink, the app's green, and
    // the grey that is the answer saying nothing — the ones the lab cards grade in. The edges are
    // drawn in them at full strength and in dp: they are the only drawing of which readings fell
    // outside, so they owe the 3:1 of a graphic, which a thin half-transparent line would not reach.
    // The fill under them is decoration, and inside the band it is the shared [IN_BAND_TINT].
    val edge = when {
        outside -> PwnWarn
        ungraded -> PwnMuted
        else -> PwnGreen
    }
    val fill = (if (outside || ungraded) edge else IN_BAND_TINT).copy(alpha = 0.14f)
    Canvas(
        Modifier.fillMaxWidth()
            .height(VITAL_TREND_HEIGHT)
            .semantics { contentDescription = description },
    ) {
        fun y(value: Double) = (((top - value) / (top - bottom)) * size.height).toFloat()
        fun x(index: Int) = (index + 0.5f) * size.width / readings.size

        // No band, no strip. Shading the whole card green because there are no edges to fall
        // outside of would be the drawing telling a person their figure is fine.
        if (band != null) {
            // The strip stops at the edge itself rather than at [Vital.insideTop]: the boundary is
            // where the guideline put it, and the tenth of a degree between them is a third of a
            // pixel here. It is the printed range, not the drawing, that has to name a real value.
            //
            // And it is drawn at all only between two real edges — see the note above about the
            // half a one-sided band has nothing to say about.
            if (low != null && high != null) {
                val bandTop = y(high)
                val bandBottom = y(low)
                drawRect(fill, Offset(0f, bandTop), Size(size.width, (bandBottom - bandTop).coerceAtLeast(0f)))
            }
            val dash = VITAL_EDGE_DASH.toPx()
            val dashes = PathEffect.dashPathEffect(floatArrayOf(dash, dash))
            listOfNotNull(high?.let(::y), low?.let(::y)).forEach { edgeY ->
                drawLine(edge, Offset(0f, edgeY), Offset(size.width, edgeY), 1.dp.toPx(), pathEffect = dashes)
            }
        }

        val path = Path()
        readings.forEachIndexed { index, reading ->
            val pointX = x(index)
            val pointY = y(reading.value)
            if (index == 0) {
                path.moveTo(pointX, pointY)
            } else {
                val midX = (x(index - 1) + pointX) / 2f
                path.cubicTo(midX, y(readings[index - 1].value), midX, pointY, pointX, pointY)
            }
        }
        // In dp, and the same 2dp and 3dp [CalendarScreen]'s chart already draws its series with.
        // A DrawScope's floats are device pixels, so a raw 2.5f would be a line 0.83dp wide at
        // density 3.0 and 0.95dp at 2.625 — a mark whose width changes from phone to phone, and
        // thinner than the 1dp Material draws a divider at. At 24 rings — [VITAL_TREND_DOTS], the
        // most this ever draws — a 320dp card leaves 13.3dp between centres against a 7.5dp outer
        // ring, so they still read apart.
        drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        // A ring per reading while the readings are countable. A month of pressures taken twice a
        // day is sixty of them, and sixty rings on this width is a bead curtain, not a series.
        if (readings.size <= VITAL_TREND_DOTS) {
            readings.forEachIndexed { index, reading ->
                drawCircle(
                    line,
                    3.dp.toPx(),
                    Offset(x(index), y(reading.value)),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
    }
}

/**
 * The band as the card prints it: `60 – 100`, `from 95`, `91 – 139/89`.
 *
 * The top is [Vital.insideTop] and never the edge, because [LabBand.high] is the first value the
 * guideline puts outside — the card would otherwise print 100 bpm's band as ending at 101, and a
 * pressure's as ending at the very 140/90 it colours as raised. The bottom is [Vital.insideBottom]
 * for the same reason: the pressure's low edge is the NHS's "90 or lower", which is itself low.
 *
 * The two halves of a pressure are not symmetrical here, and the print says so by being asymmetric:
 * the high side is a pair because both halves carry an edge, and the low side is one number because
 * one number is all it is read on. Printing the NHS's "90/60" whole would name a diastolic
 * threshold nothing in this app reads, and an ordinary 118/58 would be left wondering why it was
 * not coloured.
 *
 * A band written from one edge alone still has an edge worth printing — oxygen saturation has a
 * floor and no ceiling, body fat a ceiling and no floor, and "from 95" and "up to 25" are exactly
 * what those two mean.
 */
@Composable
private fun vitalRangeText(vital: Vital, band: LabBand, imperial: Boolean): String {
    val locale = LocalLocale.current.platformLocale
    val pair = vital.secondaryBand
    val pairLow = pair?.let { it.low ?: it.veryLow }
    val pairTop = (pair?.high ?: pair?.veryHigh)?.let { edge -> vital.insideTop(edge) }
    val low = (band.low ?: band.veryLow)?.let { edge ->
        vital.printed(vital.insideBottom(edge, band), pairLow, imperial, locale)
    }
    val top = (band.high ?: band.veryHigh)?.let { edge ->
        vital.printed(vital.insideTop(edge), pairTop, imperial, locale)
    }
    return when {
        low != null && top != null -> stringResource(R.string.labs_norm_range, low, top)

        top != null -> stringResource(R.string.labs_norm_up_to, top)

        low != null -> stringResource(R.string.labs_norm_from, low)

        // Unreachable while [Vital.bandFor] returns null rather than an empty band; if that ever
        // stops being true, the card says there is no band rather than printing an empty range.
        else -> stringResource(R.string.vitals_no_band)
    }
}

/**
 * Where the row says the reading came from, in the words the editor already uses for it.
 *
 * [measurementSourceLabel] is called only for the sources it actually names: its `else` branch is
 * "Manual", and a chest strap's pulse or a cuff's pressure labelled as typed by hand would make
 * this line worse than no line at all. Every Bluetooth source that function has no case for is
 * named as a Bluetooth device here instead, which is the least a row of that kind can be said to
 * prove — and it keeps a service added tomorrow out of the "Manual" branch by construction.
 */
@Composable
internal fun vitalSourceLabel(source: String): String = when (source) {
    "BLUETOOTH_BPS" -> stringResource(R.string.measurement_source_cuff)

    "BLUETOOTH_PLXS" -> stringResource(R.string.measurement_source_oximeter)

    "BLUETOOTH_HTS" -> stringResource(R.string.measurement_source_thermometer)

    "BLUETOOTH_BCS" -> stringResource(R.string.measurement_source_body_composition)

    "BLUETOOTH_STRAP" -> stringResource(R.string.measurement_source_strap)

    "BLUETOOTH_COLMI" -> stringResource(R.string.measurement_source_ring)

    else -> if (source.startsWith(BLUETOOTH_SOURCE_PREFIX)) {
        stringResource(R.string.measurement_source_bluetooth)
    } else {
        measurementSourceLabel(source)
    }
}

/**
 * The three colours [LabsScreen] grades a result with, so one body and one blood test agree — and
 * no colour at all where there was no verdict, or where the verdict covered one side only.
 *
 * [Color.Unspecified] is what Compose reads as "the colour this text would have had", so a reading
 * nobody could grade is left looking like ordinary text. Any of the three below would be a verdict.
 *
 * A NORMAL under a one-sided band is left uncoloured for the same reason. Body fat's band is a
 * ceiling with no floor, so its NORMAL means "not above 25" and nothing whatever about 4%; painting
 * that the same indigo as a pulse that came in between 60 and 100 would be the card answering a
 * question [Vital] refused. The word beside the figure says which of the two it is.
 */
@Composable
private fun LabStatus?.vitalTint(band: LabBand?) = when {
    this == LabStatus.FAR_ABOVE || this == LabStatus.FAR_BELOW -> PwnWarn

    // The text-weight blue: the fill blue would be the faintest figure on the card in daylight, on
    // the very reading that most needs reading, and under the 3:1 even a large figure owes.
    this == LabStatus.ABOVE || this == LabStatus.BELOW -> PwnBlueInk

    this == LabStatus.NORMAL && band != null && band.twoSided -> PwnIndigo

    else -> Color.Unspecified
}

/**
 * The verdict in words, or null where there is none to give.
 *
 * The four directions say themselves. NORMAL does not: under a band with both edges it is "inside
 * the band", and under a band with one it is only "not above" or "not below" — the same silence
 * about the missing side that [LabBand] keeps and that the strip above keeps too.
 */
private fun LabStatus?.vitalVerdictRes(band: LabBand?): Int? = when (this) {
    LabStatus.FAR_ABOVE -> R.string.vitals_status_far_above

    LabStatus.FAR_BELOW -> R.string.vitals_status_far_below

    LabStatus.ABOVE -> R.string.vitals_status_above

    LabStatus.BELOW -> R.string.vitals_status_below

    LabStatus.NORMAL -> when {
        band == null -> null
        band.twoSided -> R.string.vitals_status_inside
        band.hasHigh -> R.string.vitals_status_not_above
        else -> R.string.vitals_status_not_below
    }

    null -> null
}

/** Whether this band has a ceiling at all — see [twoSided] for why the card keeps asking. */
private val LabBand.hasHigh: Boolean get() = (high ?: veryHigh) != null

/**
 * Whether the band closed both sides, and so whether its NORMAL is a pass or half of one.
 *
 * An absent edge is silence about that side, everywhere else in this app. A one-sided band's NORMAL
 * has to be printed, drawn and spoken as the half-answer it is, and this is the one question all
 * three ask.
 */
private val LabBand.twoSided: Boolean get() = (low ?: veryLow) != null && hasHigh

/**
 * The four figures a body composition scale estimates rather than measures.
 *
 * A small current through both feet, an impedance, and the manufacturer's own regression on it.
 * None of the four was read off anything the way a cuff reads a pressure, and the card says so.
 */
private val IMPEDANCE_VITALS = setOf(
    Vital.BODY_FAT,
    Vital.MUSCLE_MASS,
    Vital.BODY_WATER,
    Vital.BASAL_METABOLISM,
)

/**
 * What this kind of figure is and is not, behind the question mark beside its name: nothing in a
 * body composition figure was measured, a pressure is graded by its worse half and on the
 * thresholds written for a clinic, and a pulse is read against a resting band that a wearable's one
 * sample of the day may not have been.
 *
 * [graded] is whether a band is applied to this person at all. The two notes about what a band was
 * written for are not said over readings nobody graded.
 */
internal fun vitalExplanations(vital: Vital, graded: Boolean): List<Int> = buildList {
    if (vital in IMPEDANCE_VITALS) add(R.string.vitals_note_impedance)
    if (vital.paired) add(R.string.vitals_pair_note)
    if (vital == Vital.BLOOD_PRESSURE && graded) add(R.string.vitals_note_pressure_office)
    if (vital == Vital.HEART_RATE) {
        if (graded) add(R.string.vitals_note_resting)
        add(R.string.vitals_note_workout_excluded)
    }
    if (vital == Vital.RESTING_HEART_RATE) add(R.string.vitals_note_resting_measured)
    if (vital in BASELINE_VITALS) add(R.string.vitals_note_baseline)
}

/** How every direct-connection source in this app is spelled — see [vitalSourceLabel]. */
private const val BLUETOOTH_SOURCE_PREFIX = "BLUETOOTH_"

private val VITAL_TREND_HEIGHT = 56.dp

/** The gap between the three figures under the trend. */
private val VITAL_FIGURE_GAP = 10.dp

/** The dash and the gap of a band's edge, in dp so the edge reads the same on every screen. */
private val VITAL_EDGE_DASH = 4.dp

/** Above this many readings the rings crowd into a solid band and the line alone is clearer. */
private const val VITAL_TREND_DOTS = 24
