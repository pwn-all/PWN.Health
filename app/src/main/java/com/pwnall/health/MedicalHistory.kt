package com.pwnall.health

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * The five things a patient summary asks about that no measurement can answer.
 *
 * They are one table because they are one shape — a name, a date or two, a note — and because a
 * doctor reads them as one page of history rather than as five features. What differs is only what
 * the columns of [MedicalHistoryRow] mean:
 *
 * | kind | title | startDay | endDay | detail |
 * | --- | --- | --- | --- | --- |
 * | [CONDITION] | what it is called | when it began | when it ended, empty while it goes on | who diagnosed it |
 * | [PROCEDURE] | what was done | when | — | where, and by whom |
 * | [IMMUNISATION] | the vaccine | when it was given | — | which dose, which product |
 * | [PREGNANCY] | a [HistoryOption] code | since when | until when | anything worth adding |
 * | [CONTRACEPTION] | a [HistoryOption] code | started | stopped | the product, the dose |
 *
 * Nothing here is a diagnosis this app made, and nothing here is coded. A person writes down what
 * they were told, in the words they were told it in; the app neither checks it nor grades it nor
 * attaches an ICD or SNOMED code to it, and every screen and the report say so. Free text is the
 * honest form for that. The two kinds that are a choice are a choice because they are a state with
 * a small, published vocabulary rather than a name — and even there the code stored is this app's
 * own, not a terminology's.
 */
enum class HistoryKind(
    /** The heading over the group: "Diagnoses". */
    val titleRes: Int,
    /** One of them, which is the label of the field that names it: "Diagnosis". */
    val oneRes: Int,
    /** The vocabulary, where the answer is a state rather than a name. Empty means free text. */
    val options: List<HistoryOption> = emptyList(),
    /** Whether it can end. A vaccination happened on a day; a diagnosis lasts until it does not. */
    val hasEnd: Boolean = false,
    /**
     * Whether the newest row is the answer rather than one of a list.
     *
     * Pregnancy and contraception are states: "not pregnant since March" does not stand beside
     * "pregnant since January", it replaces it. The older rows are kept — a history of methods is
     * worth something to a doctor — but only the newest is the current answer, and only until it
     * ends: see [currentAnswer].
     */
    val current: Boolean = false,
    /** Null for everyone. Pregnancy follows the app's own rule for who is asked about a cycle. */
    val sex: String? = null,
) {
    CONDITION(
        titleRes = R.string.history_conditions,
        oneRes = R.string.history_condition,
        hasEnd = true,
    ),
    PROCEDURE(
        titleRes = R.string.history_procedures,
        oneRes = R.string.history_procedure,
    ),
    IMMUNISATION(
        titleRes = R.string.history_immunisations,
        oneRes = R.string.history_immunisation,
    ),
    PREGNANCY(
        titleRes = R.string.history_pregnancy,
        oneRes = R.string.history_pregnancy_status,
        options = PREGNANCY_OPTIONS,
        hasEnd = true,
        current = true,
        sex = "FEMALE",
    ),
    CONTRACEPTION(
        titleRes = R.string.history_contraception,
        oneRes = R.string.history_contraception_method,
        options = CONTRACEPTION_OPTIONS,
        hasEnd = true,
        current = true,
    ),
}

/**
 * One answer out of a fixed list, as it is stored and as it is shown.
 *
 * [code] is this app's own and travels in [MedicalHistoryRow.title]; it is deliberately not a
 * clinical code. A method or a pregnancy state does have codes in SNOMED CT, which this app may not
 * redistribute, so what the report carries is the word, not a pretended identifier.
 */
data class HistoryOption(val code: String, val titleRes: Int)

/**
 * Pregnancy as the four answers a person can give about themselves.
 *
 * "Not sure" is one of them on purpose: it is a real answer, and a question with only yes and no
 * forces somebody early enough not to know into a claim.
 */
val PREGNANCY_OPTIONS: List<HistoryOption> = listOf(
    HistoryOption("NOT_PREGNANT", R.string.pregnancy_not),
    HistoryOption("PREGNANT", R.string.pregnancy_yes),
    HistoryOption("BREASTFEEDING", R.string.pregnancy_breastfeeding),
    HistoryOption("UNSURE", R.string.pregnancy_unsure),
)

/**
 * The methods, in the order a family-planning leaflet lists them: nothing, barrier, hormonal,
 * long-acting, permanent, and the two that are neither a product nor nothing.
 *
 * "None" is a method — the answer a doctor about to prescribe something teratogenic needs — and
 * "Other" is here so that a method this list does not name can still be recorded rather than
 * squeezed into the nearest wrong one. The detail field is where its name goes.
 */
val CONTRACEPTION_OPTIONS: List<HistoryOption> = listOf(
    HistoryOption("NONE", R.string.contraception_none),
    HistoryOption("CONDOM", R.string.contraception_condom),
    HistoryOption("COMBINED_PILL", R.string.contraception_combined_pill),
    HistoryOption("PROGESTOGEN_PILL", R.string.contraception_progestogen_pill),
    HistoryOption("IUD_HORMONAL", R.string.contraception_iud_hormonal),
    HistoryOption("IUD_COPPER", R.string.contraception_iud_copper),
    HistoryOption("IMPLANT", R.string.contraception_implant),
    HistoryOption("INJECTION", R.string.contraception_injection),
    HistoryOption("PATCH", R.string.contraception_patch),
    HistoryOption("RING", R.string.contraception_ring),
    HistoryOption("STERILISATION", R.string.contraception_sterilisation),
    HistoryOption("AWARENESS", R.string.contraception_awareness),
    HistoryOption("OTHER", R.string.contraception_other),
)

/** The kind this row belongs to, or null for a kind written by a newer version of the app. */
fun MedicalHistoryRow.historyKind(): HistoryKind? = HistoryKind.entries.firstOrNull { it.name == kind }

/** The option this row names, where its kind is a choice and the code is one this version knows. */
fun MedicalHistoryRow.historyOption(): HistoryOption? =
    historyKind()?.options?.firstOrNull { it.code == title }

/** Which kinds this person is asked about, on the app's own rule for who has a cycle. */
fun historyKindsFor(sex: String): List<HistoryKind> =
    HistoryKind.entries.filter { it.sex == null || it.sex == sex }

/**
 * The rows of one kind, newest first.
 *
 * Sorted here rather than trusted from the query. The DAO orders by the same two columns, and the
 * report is built from a list that has been through a backup import, a test fixture or a flow that
 * merged two sources — none of which owes anything to that ORDER BY. In a list, a row nobody dated
 * sorts below every dated one, because an empty day is the smallest string there is.
 *
 * The first row of a [HistoryKind.current] kind is that kind's current answer for as long as it
 * has not ended ([currentAnswer]), and there an empty day must not mean the oldest: the form opens
 * a new answer on its first option with no date, so "not pregnant" saved today would sort under
 * "pregnant since January", and the card, the page and the doctor's sheet would go on giving the
 * earlier answer as the current one. An answer with no date is placed by the day it was written down
 * instead — see [standingFrom].
 */
fun List<MedicalHistoryRow>.ofHistoryKind(kind: HistoryKind): List<MedicalHistoryRow> =
    filter { it.kind == kind.name }
        .sortedWith(
            compareByDescending<MedicalHistoryRow> { if (kind.current) it.standingFrom() else it.startDay }
                .thenByDescending { it.updatedAt },
        )

/**
 * The day an answer of a [HistoryKind.current] kind is ordered by: its own start, or, for one saved
 * with no date at all, the day it was given — the one day it is known to have been true on.
 * Nothing is written back: the row stays undated and prints as undated.
 *
 * An answer that carries only an end keeps its empty start and stays behind the dated ones: it
 * says when it stopped being true, which is not a day it began to be.
 *
 * Changing an undated answer is giving it again: it moves to that day, and stands in front of an
 * answer dated before it — the one row there is, changed from one option to another. Correcting
 * its note is not: the row keeps the day its answer was given ([MedicalHistoryRow.answeredAt]),
 * rather than moving to the day of the correction and becoming the current answer over a newer one.
 */
private fun MedicalHistoryRow.standingFrom(): String =
    if (startDay.isBlank() && endDay.isBlank()) {
        answeredAt.localDate(ZoneId.systemDefault()).toString()
    } else {
        startDay
    }

/**
 * The answer of a [HistoryKind.current] kind that stands on [today]: its newest row, unless that
 * row has ended. Null for a kind that is a list, which has no such row.
 *
 * An end before today is the person saying the answer stopped being true, and the card and the
 * page would otherwise go on giving "combined pill, until last June" as the method in use. Nothing takes its
 * place: the rows under it are answers it replaced, not answers waiting to come back, so until a
 * new one is written down the kind has a history and no current answer. An end on [today] still
 * stands, the day not being over.
 */
internal fun List<MedicalHistoryRow>.currentAnswer(kind: HistoryKind, today: LocalDate): MedicalHistoryRow? =
    ofHistoryKind(kind).firstOrNull()?.takeIf { kind.current && it.endDay.asHistoryDay()?.isBefore(today) != true }

/** A day column that may be empty, as the day it holds or as nothing at all. */
internal fun String.asHistoryDay(): LocalDate? =
    takeIf { it.isNotBlank() }?.let { it.toLocalDateOrNull() }

/**
 * The card in the medicine tab: what is written down, counted, and a line saying what this is.
 *
 * Counts rather than the records themselves. The tab is already four cards long, and a person who
 * wants to read their operations is a tap away from the page that lists them — whereas the one
 * thing everybody needs to see from here is whether there is anything to read at all. A state is
 * the exception while it has an answer standing ([currentAnswer]): that one word is the record.
 * Once its newest answer has ended it is counted like the rest.
 */
@Composable
internal fun MedicalHistoryCard(rows: List<MedicalHistoryRow>, sex: String, onOpen: () -> Unit) {
    val kinds = remember(sex) { historyKindsFor(sex) }
    val today = rememberToday()
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // A heading, as the tab's other cards have, so a screen reader can skim them by it.
            Text(
                stringResource(R.string.history_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.history_card_body),
                style = MaterialTheme.typography.bodySmall,
                color = PwnMuted,
            )
            kinds.forEach { kind ->
                val held = rows.ofHistoryKind(kind)
                if (held.isEmpty()) return@forEach
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(kind.titleRes),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        // In the digits of the app's language, as the screen's other figures are.
                        held.currentAnswer(kind, today)?.let { historyLabel(it) } ?: held.size.toDouble().shown(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (rows.none { it.historyKind() in kinds }) {
                Text(
                    stringResource(R.string.history_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PwnMuted,
                )
            }
            // White with an outline on the tinted card, as the booking button above it is: the
            // medicine page's buttons look alike.
            OutlinedButton(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Text(stringResource(R.string.history_open))
            }
        }
    }
}

/** What a row is called on screen: the option's own word, or the words the person typed. */
@Composable
internal fun historyLabel(row: MedicalHistoryRow): String =
    row.historyOption()?.let { stringResource(it.titleRes) } ?: row.title

/**
 * The page: every kind, everything written under it, and a way to add one more.
 *
 * Grouped by kind rather than by date. A list of one person's medical history sorted by when it
 * happened reads as a diary; a doctor asks "what are you being treated for", "what have you had
 * done", "what have you had", and the page answers in those three piles.
 */
@Composable
internal fun MedicalHistoryPage(
    modifier: Modifier,
    rows: List<MedicalHistoryRow>,
    sex: String,
    onBack: () -> Unit,
    onAdd: (HistoryKind) -> Unit,
    onOpen: (MedicalHistoryRow) -> Unit,
) {
    val kinds = remember(sex) { historyKindsFor(sex) }
    val today = rememberToday()
    Column(
        modifier.panelVerticalScroll(rememberScrollState()).padding(bottom = LocalBottomBarInset.current),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val title = stringResource(R.string.history_title)
        // What this record is — what the person was told, as typed — is said once, in the bar.
        PwnTopBar(title = title, onBack = onBack) { HelpDot(title) { Text(stringResource(R.string.history_caveat)) } }
        Column(
            Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            kinds.forEach { kind ->
                PwnCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(kind.titleRes),
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val held = rows.ofHistoryKind(kind)
                        if (held.isEmpty()) {
                            Text(
                                stringResource(R.string.history_none),
                                style = MaterialTheme.typography.bodySmall,
                                color = PwnMuted,
                            )
                        }
                        val standing = held.currentAnswer(kind, today)
                        held.forEach { row -> HistoryRow(row, current = row === standing) { onOpen(row) } }
                        // White with an outline, as the medicine page's own buttons are: a tonal fill
                        // would make it the one button here unlike them.
                        OutlinedButton(
                            onClick = { onAdd(kind) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(kind.oneRes))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * One written-down record: what it is, when, and — for a state — whether it is the one standing now.
 *
 * The floor sits before the click so the whole minimum target is the pointer area: a record with no
 * date or detail under its name is a single line and would fall short of it. The answer standing now
 * is told by more than its weight — a tag beside it says so in words, and a screen reader hears it
 * as the row's state, so the current and the earlier answers do not sound the same.
 */
@Composable
private fun HistoryRow(row: MedicalHistoryRow, current: Boolean, onOpen: () -> Unit) {
    val currentLabel = stringResource(R.string.med_history_current)
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = PwnDimensions.minimumTarget)
            .clickable(role = Role.Button, onClick = onOpen)
            .then(if (current) Modifier.semantics { stateDescription = currentLabel } else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    historyLabel(row),
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                )
                if (current) {
                    // Said once, by the row's state; the tag is for the eye.
                    Surface(
                        Modifier.clearAndSetSemantics {},
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            currentLabel,
                            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            val dates = historyDates(row)
            val detail = listOfNotNull(dates, row.detail.takeIf(String::isNotBlank))
                .joinToString(" · ")
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = PwnMuted)
            }
        }
    }
}

/** The dates of a record as one phrase, or nothing where none were given. */
@Composable
internal fun historyDates(row: MedicalHistoryRow): String? {
    val start = row.startDay.asHistoryDay()
    val end = row.endDay.asHistoryDay()
    val kind = row.historyKind()
    return when {
        start != null && end != null -> stringResource(
            R.string.history_between,
            start.formatLongDay(),
            end.formatLongDay(),
        )

        start != null && kind?.hasEnd == true ->
            stringResource(R.string.history_since, start.formatLongDay())

        start != null -> start.formatLongDay()

        end != null -> stringResource(R.string.history_until, end.formatLongDay())

        else -> null
    }
}

/**
 * The form, which is the same five fields for all five kinds.
 *
 * The name is a text field or a list of options depending on the kind, and the second date exists
 * only where the kind can end. Everything else — the detail, the note, the dates as they are
 * picked — is shared, because a form per kind would be five forms to keep in step and the
 * difference between them is one field.
 *
 * A date that has been picked can be taken away again, by the button under its field, as the end
 * of a medicine course can: the picker has only OK and Cancel, and an "until" chosen by mistake
 * could otherwise be put right only by deleting the record. Which days each picker offers is
 * [historyDayAllowed].
 */
@Composable
internal fun MedicalHistoryFormPage(
    modifier: Modifier,
    form: HistoryFormState,
    onBack: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: () -> Unit,
) {
    var pickingStart by rememberSaveable { mutableStateOf(false) }
    var pickingEnd by rememberSaveable { mutableStateOf(false) }
    val today = rememberToday()
    Column(
        modifier.panelVerticalScroll(rememberScrollState()).padding(bottom = LocalBottomBarInset.current),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PwnTopBar(
            title = stringResource(form.kind.oneRes),
            onBack = onBack,
            content = medDeleteAction(onDelete),
        )
        Column(
            Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StepCard(1, stringResource(form.kind.oneRes)) {
                if (form.kind.options.isEmpty()) {
                    // No label inside the field: the card above it already says what it is, and
                    // with one the word would stand three times on one screen.
                    CountedField(
                        value = form.title,
                        onValue = { form.title = it.take(HISTORY_NAME_MAX) },
                        label = null,
                        max = HISTORY_NAME_MAX,
                        description = stringResource(form.kind.oneRes),
                    )
                } else {
                    PwnChoiceField(
                        label = stringResource(form.kind.oneRes),
                        options = form.kind.options.map { stringResource(it.titleRes) },
                        selected = form.kind.options
                            .indexOfFirst { it.code == form.title }
                            .coerceAtLeast(0),
                        onSelected = { form.title = form.kind.options[it].code },
                    )
                }
            }
            StepCard(2, stringResource(R.string.history_when)) {
                FieldButton(
                    label = stringResource(
                        if (form.kind.hasEnd) R.string.history_from else R.string.history_on,
                    ),
                    value = form.start?.formatLongDay() ?: stringResource(R.string.history_no_date),
                    icon = Icons.Outlined.CalendarMonth,
                    onClick = { pickingStart = true },
                )
                if (form.start != null) {
                    TextButton(onClick = { form.start = null }) {
                        Text(stringResource(R.string.history_date_clear))
                    }
                }
                if (form.kind.hasEnd) {
                    FieldButton(
                        label = stringResource(R.string.history_to),
                        value = form.end?.formatLongDay()
                            ?: stringResource(R.string.history_ongoing),
                        icon = Icons.Outlined.CalendarMonth,
                        onClick = { pickingEnd = true },
                    )
                    if (form.end != null) {
                        TextButton(onClick = { form.end = null }) {
                            Text(stringResource(R.string.med_end_clear))
                        }
                    }
                }
            }
            StepCard(3, stringResource(R.string.history_detail)) {
                CountedField(
                    value = form.detail,
                    onValue = { form.detail = it.take(HISTORY_NAME_MAX) },
                    label = stringResource(R.string.history_detail_hint),
                    max = HISTORY_NAME_MAX,
                )
                NoteField(form.note, onNote = { form.note = it.take(NOTE_MAX_CHARS) })
            }
            Button(
                onClick = onSave,
                enabled = form.canSave,
                modifier = Modifier.fillMaxWidth().heightIn(min = PwnDimensions.mediumButtonHeight),
                shape = CircleShape,
            ) { Text(stringResource(R.string.common_save)) }
            Spacer(Modifier.height(16.dp))
        }
    }
    // Each picker opens on a day it offers: the start on the end where only the end is known, the
    // end on the start, and either on today where the form has no date yet.
    if (pickingStart) {
        HistoryDayDialog(
            initial = form.start ?: form.end ?: today,
            allowed = { historyDayAllowed(it, today, notAfter = form.end) },
            onDismiss = { pickingStart = false },
        ) {
            form.start = it
            pickingStart = false
        }
    }
    if (pickingEnd) {
        HistoryDayDialog(
            initial = form.end ?: form.start ?: today,
            allowed = { historyDayAllowed(it, today, notBefore = form.start) },
            onDismiss = { pickingEnd = false },
        ) {
            form.end = it
            pickingEnd = false
        }
    }
}

/**
 * A day of somebody's history, which is as likely to be decades back as last week, and is one of
 * the days [allowed]. The day a file's examination was made is asked with it too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryDayDialog(
    initial: LocalDate,
    allowed: (LocalDate) -> Boolean,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    val today = rememberToday()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.toPickerMillis(),
        yearRange = historyYearRange(initial, today),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) =
                allowed(Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate())
        },
    )
    // OK lights on whatever day stands chosen, the one the dialog opened on included, and a row
    // from a backup can open it on a day that is not offered: that day is turned away here too.
    LocalDateDialog(state, onDismiss, onPick = { if (allowed(it)) onPick(it) else onDismiss() })
}

/**
 * Whether [day] may be one of the form's two dates.
 *
 * Not after [today]: a history says what has been. A vaccination dated 2030 would be taken and
 * printed on the doctor's sheet, and an answer that has not ended yet has no end to give — it is "ongoing".
 * And on its own side of the form's other date — a start [notAfter] the end, an end [notBefore]
 * the start — so the two cannot be picked the wrong way round and printed as "10 March 2025 –
 * 1 February 2025". A day the picker does not offer is greyed out in its calendar and refused when
 * typed, which says why without a line of the form's own.
 */
internal fun historyDayAllowed(
    day: LocalDate,
    today: LocalDate,
    notBefore: LocalDate? = null,
    notAfter: LocalDate? = null,
): Boolean = !day.isAfter(today) && notBefore?.isAfter(day) != true && notAfter?.isBefore(day) != true

/**
 * The years the form's date picker offers.
 *
 * A history is mostly old: a diagnosis made in childhood, an operation twenty years ago, a
 * vaccination copied out of a school record. The medicine courses' picker, whose years begin two
 * before this one, would not do: Material's picker takes no date outside its years, tapped or typed,
 * so those records could only be saved undated. Nothing in a history is older than the person,
 * hence [HISTORY_EARLIEST_YEAR], and nothing in it is still to come ([historyDayAllowed]), so the
 * years end with this one.
 * The year the dialog opens on is inside the range whatever it is: handed a date outside it the
 * picker shows nothing chosen and OK never lights, and a row restored from a backup owes this
 * range nothing.
 */
internal fun historyYearRange(initial: LocalDate, today: LocalDate): IntRange =
    minOf(initial.year, HISTORY_EARLIEST_YEAR)..maxOf(initial.year, today.year)

/** As far back as a history's dates go: the earliest year of birth the profile's own wheel offers. */
private const val HISTORY_EARLIEST_YEAR = 1900

/** As long as a diagnosis ever needs to be, and short enough to print on one line of the report. */
private const val HISTORY_NAME_MAX = 80

/** The form's fields, held across a rotation and a trip to the date picker. */
internal class HistoryFormState(kind: HistoryKind) {
    var editingKey by mutableStateOf("")
    var kind by mutableStateOf(kind)
    var title by mutableStateOf("")
    var detail by mutableStateOf("")
    var start by mutableStateOf<LocalDate?>(null)
    var end by mutableStateOf<LocalDate?>(null)
    var note by mutableStateOf("")

    // When the answer of the row being corrected was given, and what that answer was; see [toRow].
    private var answeredAt = 0L
    private var openedAnswer = ""

    /** What the form answers: the name or the option, and the two days. The rest is commentary on it. */
    private fun answer(): String =
        listOf(title.trim(), start?.toString().orEmpty(), end?.toString().orEmpty()).toString()

    /**
     * Whether there is a record to save: it has a name, and does not end before it began. The
     * pickers offer no such pair of dates ([historyDayAllowed]); this holds the row itself to the
     * same order, whatever the form was opened on.
     */
    val canSave: Boolean get() = title.isNotBlank() && end?.let { start?.isAfter(it) } != true

    /** Opening the form: on a row to correct it, or on a kind to write a new one down. */
    fun show(kind: HistoryKind, row: MedicalHistoryRow?) {
        this.kind = row?.historyKind() ?: kind
        editingKey = row?.key.orEmpty()
        // A state answers itself by default — the first option — so that the commonest record, "I
        // am not pregnant", is two taps rather than a choice made from nothing. A name has no
        // default it could honestly carry.
        title = row?.title ?: this.kind.options.firstOrNull()?.code.orEmpty()
        detail = row?.detail.orEmpty()
        start = row?.startDay?.asHistoryDay()
        end = row?.endDay?.asHistoryDay()
        note = row?.note.orEmpty()
        answeredAt = row?.answeredAt ?: 0L
        openedAnswer = if (row == null) "" else answer()
    }

    /**
     * The row to write.
     *
     * A new record gets a key made here rather than by the database, so that the same record
     * carried to another phone in a backup is recognised as the same record. Editing keeps the key
     * it already had, which is what makes a correction a correction.
     *
     * And it keeps the moment its answer was given for as long as the answer is the one the form
     * was opened on: a note corrected is not an answer given again ([MedicalHistoryRow.answeredAt]).
     */
    fun toRow(nowMs: Long) = MedicalHistoryRow(
        key = editingKey.ifBlank { UUID.randomUUID().toString() },
        kind = kind.name,
        title = title.trim(),
        detail = detail.trim(),
        startDay = start?.toString().orEmpty(),
        endDay = end?.toString().orEmpty(),
        note = note.trim(),
        updatedAt = nowMs,
        answeredAt = answeredAt.takeIf { editingKey.isNotBlank() && answer() == openedAnswer } ?: nowMs,
    )

    companion object {
        val Saver: Saver<HistoryFormState, Any> = listSaver(
            save = {
                listOf(
                    it.editingKey,
                    it.kind.name,
                    it.title,
                    it.detail,
                    it.start?.toString().orEmpty(),
                    it.end?.toString().orEmpty(),
                    it.note,
                    it.answeredAt.toString(),
                    it.openedAnswer,
                )
            },
            restore = { saved ->
                HistoryFormState(HistoryKind.valueOf(saved[1])).apply {
                    editingKey = saved[0]
                    title = saved[2]
                    detail = saved[3]
                    start = saved[4].asHistoryDay()
                    end = saved[5].asHistoryDay()
                    note = saved[6]
                    answeredAt = saved[7].toLong()
                    openedAnswer = saved[8]
                }
            },
        )
    }
}

@Composable
internal fun rememberHistoryForm() =
    rememberSaveable(saver = HistoryFormState.Saver) { HistoryFormState(HistoryKind.CONDITION) }
