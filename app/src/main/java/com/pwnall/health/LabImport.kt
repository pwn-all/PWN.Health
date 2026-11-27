package com.pwnall.health

import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import kotlin.math.abs

internal data class LabImportPage(val source: String, val scan: LabSheetScan)

/** One collection date, ready to populate the editable form without writing a journal entry. */
internal data class LabImportDraft(val scan: LabSheetScan, val sources: List<String>) {
    fun canApply(existing: Set<LabMarker>, day: LocalDate): Boolean =
        existing.isEmpty() || scan.day == null || scan.day == day
}

/** Merge a batch by collection date; contradictory duplicates stay blank for manual review. */
internal fun labImportDrafts(pages: List<LabImportPage>): List<LabImportDraft> {
    val knownDays = pages.mapNotNull { it.scan.day }.distinct()
    return pages.groupBy { it.scan.day ?: knownDays.singleOrNull() }.map { (day, group) ->
        val human = group.map { it.scan }.filterNot { it.veterinary }
        val readings = human.flatMap { it.readings }.groupBy { it.marker }
        val agreed = readings.values.mapNotNull { copies ->
            copies.first().takeIf { first ->
                copies.all { other ->
                    abs(other.value - first.value) <= maxOf(abs(first.value) * 0.001, 1e-9)
                }
            }?.let { first ->
                val ranges = copies.map { it.range }.filterNot { it.empty }.distinct()
                // The value may agree while laboratories use different ranges. Keep neither range.
                first.copy(range = ranges.singleOrNull() ?: ReportedRange())
            }
        }
        LabImportDraft(
            scan = LabSheetScan(
                readings = agreed,
                day = day,
                veterinary = human.isEmpty(),
                unread = human.sumOf { it.unread } + readings.size - agreed.size,
            ),
            sources = group.map { it.source }.distinct(),
        )
    }
}

/**
 * Whether a page that opens takes up the reading its saved state lists ([pending]). It does where
 * nothing in this process ever started one for it ([started]): the process was ended under a reading,
 * or the page was handed a sheet and is opening for the first time. A page rebuilt by a turn of the
 * phone finds its reading still under way, or its pages waiting to be placed, and starts nothing:
 * reading again from the first page would ask a locked PDF for its password a second time.
 */
internal fun resumesLabReading(pending: List<String>, started: Boolean): Boolean = pending.isNotEmpty() && !started

/** Keep conversion precision; display rounding can change a result near a reference limit. */
internal fun labImportNumber(value: Double, unit: LabImperial?): String =
    BigDecimal.valueOf(unit?.of(value) ?: value).round(MathContext(10)).stripTrailingZeros().toPlainString()
