package com.pwnall.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

// The parts every pattern card on the Pain, Work and Intimate screens is built from, in the house
// style of the Mood screen's "Weather and mood" card: a title, the counts in words, one line saying
// what the app's own rule makes of them, a line that a correlation is not a cause, and the research
// behind the question with its source.
//
// The cards draw what the statistics files worked out ([PainDiary], [workSummary],
// [morningShares] and the rest) and never count anything themselves: the doctor's sheet reads
// the same functions, and a card and the sheet must not disagree about the same days. Which verdict
// line a comparison gets is [contrastLine]'s.

/**
 * A card: its title over whatever it says, in the Mood screen's card. The title and the headings
 * inside are headings to a screen reader too, so a card of five areas can be walked area by area.
 * [help], where a card has it, is what the card's notes, rules and research say, behind a
 * [HelpDot] beside the title rather than printed under the figures.
 */
@Composable
internal fun PatternCard(
    title: String,
    modifier: Modifier = Modifier,
    help: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    PwnCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
                help?.let { HelpDot(title, it) }
            }
            content()
        }
    }
}

/** A heading inside a card: the area, the muscle group or the item a few lines are about. */
@Composable
internal fun PatternHeading(text: String) {
    Text(
        text,
        Modifier.padding(top = 6.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

/** A line of counts. */
@Composable
internal fun PatternLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

/** A line about how the counts were drawn: small and muted, like the Mood card's own. */
@Composable
internal fun PatternNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = PwnMuted)
}

/** Research about other people, with its source under it. */
@Composable
internal fun PatternResearch(text: String, source: String) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = PwnMuted)
        Text(source, style = MaterialTheme.typography.labelSmall, color = PwnMuted)
    }
}

/** The app's own rule for two shares of days, stated as the app's own under the cards that apply it. */
@Composable
internal fun PatternRule() {
    PatternNote(pluralStringResource(R.plurals.insight_rule_shares, CONTRAST_MIN_DAYS, CONTRAST_MIN_DAYS, CARD_POINTS))
}

/** The one line every comparing card ends on: two things side by side are not a cause. */
@Composable
internal fun PatternDisclaimer() {
    PatternNote(stringResource(R.string.insight_disclaimer))
}

/**
 * The verdict under a comparison, in the card's own words for a notable one: [notable] for one in
 * no order, [daysAfter] for one "on the days after". [mornings] counts the floor in mornings;
 * [floor], when given, is the floor line's own words — for a group of pain starts, which are counted
 * as starts rather than as days.
 */
@Composable
internal fun VerdictLine(
    contrast: DayContrast,
    line: ContrastLine,
    notable: Int = R.string.insight_notable,
    daysAfter: Int = R.string.insight_notable_days_after,
    mornings: Boolean = false,
    floor: Int? = null,
) {
    if (line == ContrastLine.NOT_ENOUGH) {
        val (exposed, other) = contrast.groupSizes
        val words = floor ?: if (mornings) R.string.insight_not_enough_mornings else R.string.insight_not_enough
        PatternNote(stringResource(words, exposed, other, contrast.minDays))
        return
    }
    val text = when (line) {
        ContrastLine.NOTABLE -> notable
        ContrastLine.NOTABLE_DAYS_AFTER -> daysAfter
        ContrastLine.NOTABLE_NO_ORDER -> R.string.insight_notable_no_order
        ContrastLine.NOTABLE_REVERSE_UNCHECKED -> R.string.insight_notable_reverse_unchecked
        else -> R.string.insight_no_clear
    }
    val found = line != ContrastLine.NO_CLEAR_DIFFERENCE
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        color = if (found) PwnBlueInk else PwnMuted,
    )
}

/** [pretty] in the language on screen: "72,4" where that language writes a decimal comma. */
@Composable
internal fun Double.shown(): String = pretty(LocalLocale.current.platformLocale)

/** [shownFigure] in the language on screen: a lab value printed for the sheet, "0,035" in Russian. */
@Composable
internal fun shownFigure(figure: String): String = shownFigure(figure, LocalLocale.current.platformLocale)

/** "3,912": steps as the locale writes a whole number. */
internal fun wholeNumber(value: Double, locale: Locale): String =
    NumberFormat.getIntegerInstance(locale).format(value.roundToInt())

/** "3.5", "4": one decimal where there is one, as the locale writes it. */
internal fun oneDecimal(value: Double, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(value)

/** "12 Sep", or "12 Sep – 14 Sep" for a stretch of days. */
@Composable
internal fun dayRangeText(from: LocalDate, to: LocalDate, locale: Locale): String {
    val format = remember(locale) { localizedDatePattern(locale, "dMMM", "d MMM") }
    return if (from == to) {
        from.format(format)
    } else {
        stringResource(R.string.insight_day_range, from.format(format), to.format(format))
    }
}

/** The analysis groups' names, for the cards' headings. */
internal fun PainGroup.labelRes(): Int = when (this) {
    PainGroup.HEAD -> R.string.pain_group_head
    PainGroup.NECK_SHOULDERS -> R.string.pain_group_neck_shoulders
    PainGroup.LOWER_BACK -> R.string.pain_group_lower_back
    PainGroup.ARMS_HANDS -> R.string.pain_group_arms_hands
    PainGroup.CHEST_ABDOMEN -> R.string.pain_group_chest_abdomen
    PainGroup.HIPS_LEGS -> R.string.pain_group_hips_legs
}
