package com.pwnall.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.text.Collator
import java.time.Instant
import java.time.ZoneId

internal fun completedVisits(
    appointments: List<Appointment>,
    specialty: String?,
    doctor: String?,
    nowMs: Long,
): List<Appointment> = appointments.filter {
    it.doneAt != null && it.atMs <= nowMs &&
        (specialty == null || it.specialty == specialty) && (doctor == null || it.doctor == doctor)
}.sortedByDescending { it.atMs }

/**
 * The doctors the filter offers: those seen for [specialty], or for anything where it is null, in
 * [order] of the person's language — the code points put "Борис" before "анна" and "Ёлкин" before
 * both. The [doctor] chosen stays in the list even where the record no longer holds them, so the
 * field goes on saying what the list below is narrowed by.
 */
internal fun visitDoctors(
    visits: List<Appointment>,
    specialty: String?,
    doctor: String?,
    order: Comparator<in String>,
): List<String> = visits.filter { specialty == null || it.specialty == specialty }
    .map { it.doctor }.filter { it.isNotBlank() }.distinct().sortedWith(order)
    .plus(listOfNotNull(doctor)).distinct()

/**
 * The visits that happened, newest first, narrowed by who was seen.
 *
 * Laid out as the other medicine pages are — the top bar first and full width, the page's own
 * margin only under it — so the arrow does not jump sideways between this list and the appointment
 * it opens. The two filters are the app's choice field, which says what it filters and what it is
 * set to, and marks the chosen line in its list, where two identical outlined buttons would say neither.
 */
@Composable
internal fun MedicalVisitHistory(
    modifier: Modifier,
    appointments: List<Appointment>,
    specialty: String?,
    doctor: String?,
    nowMs: Long,
    zone: ZoneId,
    onSpecialty: (String?) -> Unit,
    onDoctor: (String?) -> Unit,
    onBack: () -> Unit,
    onOpen: (Appointment) -> Unit,
) {
    val all = completedVisits(appointments, null, null, nowMs)
    val visits = completedVisits(all, specialty, doctor, nowMs)
    val locale = LocalLocale.current.platformLocale
    val order = remember(locale) { Collator.getInstance(locale) }
    Column(
        modifier.panelVerticalScroll(rememberScrollState()).padding(bottom = LocalBottomBarInset.current),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PwnTopBar(stringResource(R.string.visit_history), onBack)
        Column(
            Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val specialties = (all.map { it.specialty } + listOfNotNull(specialty)).distinct()
            VisitHistoryFilter(
                label = stringResource(R.string.visit_specialty),
                allLabel = stringResource(R.string.visit_all_specialties),
                options = specialties,
                optionLabels = specialties.map { specialtyLabel(it) },
                selected = specialty,
                onSelect = onSpecialty,
            )
            val doctors = visitDoctors(all, specialty, doctor, order)
            VisitHistoryFilter(
                label = stringResource(R.string.visit_doctor),
                allLabel = stringResource(R.string.visit_all_doctors),
                options = doctors,
                optionLabels = doctors,
                selected = doctor,
                onSelect = onDoctor,
            )
            Text(stringResource(R.string.visit_all_time), color = PwnMuted)
            if (visits.isEmpty()) Text(stringResource(R.string.visit_history_empty), color = PwnMuted)
            visits.groupBy { Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate() }.forEach { (date, dayVisits) ->
                Text(
                    date.formatLongDay(),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
                dayVisits.forEach { appointment ->
                    PwnCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) { VisitRow(appointment, zone, onOpen) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** One filter: "all" first, then each value on record, with [optionLabels] saying each as shown. */
@Composable
private fun VisitHistoryFilter(
    label: String,
    allLabel: String,
    options: List<String>,
    optionLabels: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    PwnChoiceField(
        label = label,
        options = listOf(allLabel) + optionLabels,
        selected = selected?.let { options.indexOf(it) + 1 } ?: 0,
        onSelected = { index -> onSelect(options.getOrNull(index - 1)) },
    )
}
