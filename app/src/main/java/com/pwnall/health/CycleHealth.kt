package com.pwnall.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.CervicalMucusRecord
import androidx.health.connect.client.records.IntermenstrualBleedingRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.OvulationTestRecord
import androidx.health.connect.client.records.SexualActivityRecord
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

internal const val PERIOD_CLIENT_ID_PREFIX = "pwnfit-journal-period:"
internal const val CYCLE_CLIENT_ID_PREFIX = "pwnfit-journal-cycle:"

/**
 * What [MenstruationPeriodRecord] refuses to be constructed above. Measured on the clock rather
 * than in calendar days, which is why this is the interval below and not a day count: the day the
 * clocks go back is twenty-five hours long, so a span that counts as thirty-one days can still be
 * an hour over the cap.
 */
private val MAX_PERIOD_LENGTH: Duration = Duration.ofDays(31)

/**
 * The instants a period is published as. Health Connect wants the moment the period stopped; the
 * journal only knows the day, so the interval runs to the end of it.
 */
private fun CyclePeriod.exportInterval(zone: ZoneId): Pair<Instant, Instant> =
    start.atStartOfDay(zone).toInstant() to end.plusDays(1).atStartOfDay(zone).toInstant()

/**
 * The interval as it is published at [now], or null while there is none to publish yet.
 *
 * A period closed today runs to tomorrow's midnight, which has not happened: it goes out as running
 * to now, the way a session still under way is cut off at the export, and the export after midnight
 * publishes the whole day: the record has changed, so it is written again ([insertChanged]). Read
 * back, an end inside its last day is that day ([importedPeriodDays]).
 */
internal fun CyclePeriod.publishedInterval(zone: ZoneId, now: Instant): Pair<Instant, Instant>? {
    val (start, end) = exportInterval(zone)
    return (start to minOf(end, now)).takeIf { it.second > start }
}

/**
 * The periods Health Connect will take, measured as the interval this export actually publishes.
 * Over the cap the record constructor throws, which would abort this export and every one queued
 * behind it; a start and an end that far apart are a forgotten "period ended" tap rather than a
 * measurement, so the pair is left out instead of being cut to fit.
 */
internal fun exportablePeriods(entries: List<JournalEntry>, zone: ZoneId): List<CyclePeriod> =
    cyclePeriods(entries).filter { period ->
        val (start, end) = period.exportInterval(zone)
        Duration.between(start, end) <= MAX_PERIOD_LENGTH
    }

/**
 * Mirrors the cycle to Health Connect: the period as an interval, everything else as the day it was
 * recorded on. Kept apart from the main export because it is six record types that nothing else
 * touches, and because a phone whose owner never opens the cycle screen never builds a single record
 * here.
 *
 * Period pain and desire are deliberately not among them, and the silence below is a decision rather
 * than an oversight: Health Connect publishes no record type for either, and the nearest thing —
 * filing a desire level as a sexual-activity record — would publish a claim that record cannot
 * qualify, which is the same reason [KIND_MASTURBATION] stays off it. Both are carried by this app's
 * own backup instead, so the one way to lose them is to reinstall without taking one. A [CycleLog]
 * absent from every table ([HC_FLOW] and its siblings) is dropped here without a word, so this note
 * is the only thing standing between that choice and a later reader reading it as a bug.
 */
// One branch per Health Connect record type, like the main export.
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal suspend fun exportCycleRecords(
    context: Context,
    client: HealthConnectClient,
    now: Instant,
    localEvents: List<JournalEntry>,
    zone: ZoneId,
    version: Long,
) {
    val periodRecords = exportablePeriods(localEvents, zone).mapNotNull { period ->
        val (start, end) = period.publishedInterval(zone, now) ?: return@mapNotNull null
        MenstruationPeriodRecord(
            startTime = start,
            startZoneOffset = zone.rules.getOffset(start),
            endTime = end,
            endZoneOffset = zone.rules.getOffset(end),
            metadata = Metadata.manualEntry(
                clientRecordId = PERIOD_CLIENT_ID_PREFIX + period.start,
                clientRecordVersion = version,
            ),
        )
    }

    fun metadata(entry: JournalEntry) = Metadata.manualEntry(
        clientRecordId = publishedClientId(context, CYCLE_CLIENT_ID_PREFIX, entry.id),
        clientRecordVersion = version,
    )

    fun instant(entry: JournalEntry) = Instant.ofEpochMilli(entry.occurredAt)

    // Each payload is read once, here, rather than once per record type: the five builders below all
    // switch on the same value, and a long-running cycle log is thousands of rows on every sync.
    val cycleLogs = localEvents.mapNotNull { entry -> entry.cycleLog()?.let { entry to it } }

    val flowRecords = cycleLogs.healthRecords { (entry, log) ->
        val flow = HC_FLOW[log] ?: return@healthRecords null
        val time = instant(entry)
        MenstruationFlowRecord(
            time = time,
            zoneOffset = entry.offsetAt(time, zone),
            flow = flow,
            metadata = metadata(entry),
        )
    }
    val spottingRecords = cycleLogs.healthRecords { (entry, log) ->
        if (log != CycleLog.SPOTTING) return@healthRecords null
        val time = instant(entry)
        IntermenstrualBleedingRecord(
            time = time,
            zoneOffset = entry.offsetAt(time, zone),
            metadata = metadata(entry),
        )
    }
    val ovulationRecords = cycleLogs.healthRecords { (entry, log) ->
        val result = HC_OVULATION_RESULT[log] ?: return@healthRecords null
        val time = instant(entry)
        OvulationTestRecord(
            time = time,
            zoneOffset = entry.offsetAt(time, zone),
            result = result,
            metadata = metadata(entry),
        )
    }
    val mucusRecords = cycleLogs.healthRecords { (entry, log) ->
        val appearance = HC_MUCUS_APPEARANCE[log] ?: return@healthRecords null
        val time = instant(entry)
        CervicalMucusRecord(
            time = time,
            zoneOffset = entry.offsetAt(time, zone),
            appearance = appearance,
            metadata = metadata(entry),
        )
    }
    val sexRecords = cycleLogs.healthRecords { (entry, log) ->
        val protection = HC_PROTECTION[log] ?: return@healthRecords null
        val time = instant(entry)
        SexualActivityRecord(
            time = time,
            zoneOffset = entry.offsetAt(time, zone),
            protectionUsed = protection,
            metadata = metadata(entry),
        )
    }

    client.insertChanged(periodRecords)
    client.insertChanged(flowRecords)
    client.insertChanged(spottingRecords)
    client.insertChanged(ovulationRecords)
    client.insertChanged(mucusRecords)
    client.insertChanged(sexRecords)

    removeStalePwnFitRecords(
        context,
        client,
        now,
        MenstruationPeriodRecord::class,
        PERIOD_CLIENT_ID_PREFIX,
        periodRecords.mapNotNull { it.metadata.clientRecordId }.toSet(),
    )
    // One prefix for the five instantaneous types: the sweep is per record type, so a mucus record
    // is never a candidate for deletion while the flow records are being reconciled.
    removeStalePwnFitRecords(
        context,
        client,
        now,
        MenstruationFlowRecord::class,
        CYCLE_CLIENT_ID_PREFIX,
        flowRecords.mapNotNull { it.metadata.clientRecordId }.toSet(),
    )
    removeStalePwnFitRecords(
        context,
        client,
        now,
        IntermenstrualBleedingRecord::class,
        CYCLE_CLIENT_ID_PREFIX,
        spottingRecords.mapNotNull { it.metadata.clientRecordId }.toSet(),
    )
    removeStalePwnFitRecords(
        context,
        client,
        now,
        OvulationTestRecord::class,
        CYCLE_CLIENT_ID_PREFIX,
        ovulationRecords.mapNotNull { it.metadata.clientRecordId }.toSet(),
    )
    removeStalePwnFitRecords(
        context,
        client,
        now,
        CervicalMucusRecord::class,
        CYCLE_CLIENT_ID_PREFIX,
        mucusRecords.mapNotNull { it.metadata.clientRecordId }.toSet(),
    )
    removeStalePwnFitRecords(
        context,
        client,
        now,
        SexualActivityRecord::class,
        CYCLE_CLIENT_ID_PREFIX,
        sexRecords.mapNotNull { it.metadata.clientRecordId }.toSet(),
    )
}

/**
 * The days a published period stands for, read back from the interval it was published as.
 *
 * The end this file exports is exclusive — the first midnight after the period — so an end landing
 * exactly on a midnight belongs to the day before it. A foreign app that stamps the end inside the
 * last day needs no such correction. Null is an interval no period can be: backwards, or longer
 * than [MAX_PERIOD_LENGTH], the same cap the export applies.
 */
internal fun importedPeriodDays(
    startTime: Instant,
    endTime: Instant,
    startZone: ZoneId,
    endZone: ZoneId,
): Pair<LocalDate, LocalDate>? {
    if (endTime <= startTime || Duration.between(startTime, endTime) > MAX_PERIOD_LENGTH) return null
    val start = startTime.atZone(startZone).toLocalDate()
    var end = endTime.atZone(endZone).toLocalDate()
    if (endTime == end.atStartOfDay(endZone).toInstant()) end = end.minusDays(1)
    return (start to end).takeIf { end >= start }
}

/**
 * Whether a period read from Health Connect can be filed on [start] to [end] beside the cycle marks
 * the journal holds ([marks]) — whole, or not at all.
 *
 * The rule a period remembered afterwards is filed by ([pastPeriodFits]), for the same reason.
 * [cyclePeriods] gives each start the first end before the next start, so checking only the day
 * itself is not enough: another app's period a day off one on record — its start inside this one,
 * its end the day after — would take this one's end and leave its start running, and the guard
 * [StepsDb.logCycleStart] keeps on the screens' road does not stand on this one. The rows this same
 * record brought in before ([marker]) are not in its way, so a period its app has since moved is
 * followed rather than refused.
 */
internal fun importedPeriodFits(
    marks: List<JournalEntry>,
    start: LocalDate,
    end: LocalDate,
    marker: String,
    today: LocalDate,
): Boolean = pastPeriodFits(
    marks.filterNot { it.healthConnectRecordKey == marker || it.healthConnectRecordKey == periodEndMarker(marker) },
    start,
    end,
    today,
)

/** The marker of a period's end row: every row a record brings in carries a key of its own. */
private fun periodEndMarker(marker: String) = "$marker#end"

/**
 * The cycle back from Health Connect: the period as its start and end days, everything
 * instantaneous as the [CycleLog] row the cycle screen would have written itself. Another app's
 * records come in as imported rows; rows an earlier install of this app published are adopted back
 * by the same machinery as the main import. A period that would touch one already in the journal is
 * left out whole ([importedPeriodFits]): the record on file keeps its days.
 */
// One branch per Health Connect record type, like the export it mirrors.
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal suspend fun importCycleRecords(
    context: Context,
    db: StepsDb,
    imports: HealthImport,
    profile: UserProfile,
    now: Instant,
): Int {
    val resources = context.resources
    val zone = ZoneId.systemDefault()
    val today = now.atZone(zone).toLocalDate()
    // Read once and added to as the read goes, so two records of one page — two apps' copies of one
    // period — are held against each other as well as against the journal: a page is written only
    // once all of it has been mapped.
    val marks = db.dao().cycleMarksNow().toMutableList()
    var imported = 0
    imported += imports.readEach(MenstruationPeriodRecord::class) { record, marker ->
        val (start, end) = importedPeriodDays(
            record.startTime,
            record.endTime,
            record.startZoneOffset ?: zone,
            record.endZoneOffset ?: zone,
        ) ?: return@readEach emptyList()
        if (!importedPeriodFits(marks, start, end, marker, today)) return@readEach emptyList()
        listOf(
            cycleEntry(resources, start.toString()).copy(
                occurredAt = record.startTime.toEpochMilli(),
                measurementSource = "HEALTH_CONNECT",
                healthConnectRecordKey = marker,
                zoneOffsetSeconds = record.startZoneOffset?.totalSeconds,
            ),
            cycleEndEntry(resources, end.toString()).copy(
                occurredAt = end.atStartOfDay(record.endZoneOffset ?: zone).toInstant().toEpochMilli(),
                measurementSource = "HEALTH_CONNECT",
                healthConnectRecordKey = periodEndMarker(marker),
                zoneOffsetSeconds = record.endZoneOffset?.totalSeconds,
            ),
        ).also { marks += it }
    }

    fun logged(log: CycleLog, time: Instant, offset: ZoneOffset?, marker: String) =
        cycleLogEntry(resources, log, time.atZone(offset ?: zone).toLocalDate().toString()).copy(
            occurredAt = time.toEpochMilli(),
            measurementSource = "HEALTH_CONNECT",
            healthConnectRecordKey = marker,
            // The clock the record states, so the record goes back out with it once adopted ([offsetAt]).
            zoneOffsetSeconds = offset?.totalSeconds,
        )

    imported += imports.read(MenstruationFlowRecord::class) { record, marker ->
        HC_FLOW.logFor(record.flow)?.let { logged(it, record.time, record.zoneOffset, marker) }
    }
    imported += imports.read(IntermenstrualBleedingRecord::class) { record, marker ->
        logged(CycleLog.SPOTTING, record.time, record.zoneOffset, marker)
    }
    imported += imports.read(OvulationTestRecord::class) { record, marker ->
        HC_OVULATION_RESULT.logFor(record.result)?.let { logged(it, record.time, record.zoneOffset, marker) }
    }
    imported += imports.read(CervicalMucusRecord::class) { record, marker ->
        HC_MUCUS_APPEARANCE.logFor(record.appearance)?.let { logged(it, record.time, record.zoneOffset, marker) }
    }
    // Another app's records are taken on the same terms the screens offer the log: an underage or
    // unstated profile gets none. Rows this app published itself always come home regardless —
    // restoring someone's own diary is not the same act as offering to keep one, and skipping them
    // would leave the sweep reading "never restored" as "deleted" on a profile that merely has no
    // birth date yet.
    val sexOffered = sexLogOffered(profile.birthDate, now.atZone(zone).toLocalDate())
    imported += imports.read(SexualActivityRecord::class) { record, marker ->
        val foreign = record.metadata.dataOrigin.packageName != context.packageName
        if (foreign && !sexOffered) {
            null
        } else {
            // PROTECTION_USED_UNKNOWN reads as null: [CycleLog] has no "not stated" rung to file it
            // under, and neither answer it does have may be guessed for it. So the act is not kept,
            // and the doctor's sheet says that its count leaves such acts out rather than promising
            // a third state the journal cannot hold.
            HC_PROTECTION.logFor(record.protectionUsed)?.let { logged(it, record.time, record.zoneOffset, marker) }
        }
    }
    return imported
}

/**
 * The [CycleLog] answers each Health Connect record type speaks, by its code. One table serves the
 * export and the import both, so a code written out is read back as the answer it was written for;
 * with two hand-kept `when`s, one each way, a swapped pair would turn a positive test into a high
 * one on the way out or on the way home without a word.
 */
internal val HC_FLOW: Map<CycleLog, Int> = mapOf(
    CycleLog.FLOW_LIGHT to MenstruationFlowRecord.FLOW_LIGHT,
    CycleLog.FLOW_MEDIUM to MenstruationFlowRecord.FLOW_MEDIUM,
    CycleLog.FLOW_HEAVY to MenstruationFlowRecord.FLOW_HEAVY,
)

internal val HC_OVULATION_RESULT: Map<CycleLog, Int> = mapOf(
    CycleLog.OVULATION_POSITIVE to OvulationTestRecord.RESULT_POSITIVE,
    CycleLog.OVULATION_HIGH to OvulationTestRecord.RESULT_HIGH,
    CycleLog.OVULATION_NEGATIVE to OvulationTestRecord.RESULT_NEGATIVE,
)

internal val HC_MUCUS_APPEARANCE: Map<CycleLog, Int> = mapOf(
    CycleLog.MUCUS_DRY to CervicalMucusRecord.APPEARANCE_DRY,
    CycleLog.MUCUS_STICKY to CervicalMucusRecord.APPEARANCE_STICKY,
    CycleLog.MUCUS_CREAMY to CervicalMucusRecord.APPEARANCE_CREAMY,
    CycleLog.MUCUS_WATERY to CervicalMucusRecord.APPEARANCE_WATERY,
    CycleLog.MUCUS_EGG_WHITE to CervicalMucusRecord.APPEARANCE_EGG_WHITE,
)

internal val HC_PROTECTION: Map<CycleLog, Int> = mapOf(
    CycleLog.SEX_PROTECTED to SexualActivityRecord.PROTECTION_USED_PROTECTED,
    CycleLog.SEX_UNPROTECTED to SexualActivityRecord.PROTECTION_USED_UNPROTECTED,
)

/** The answer a record's [code] stands for in one of the tables above; null for a code no answer has. */
internal fun Map<CycleLog, Int>.logFor(code: Int): CycleLog? = entries.firstOrNull { it.value == code }?.key
