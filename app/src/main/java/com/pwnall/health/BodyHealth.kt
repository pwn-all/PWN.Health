package com.pwnall.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Power
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

internal const val BMR_CLIENT_ID_PREFIX = "pwnfit-journal-bmr:"
internal const val BODY_WATER_CLIENT_ID_PREFIX = "pwnfit-journal-bodywater:"

/** Body Composition Service reports basal metabolism in kilojoules per day. */
private const val KJ_PER_KCAL = 4.184

/**
 * The body values a scale measures but the journal has no column for, plus the resting rate the app
 * already computes for its own energy budget.
 *
 * Bone mass is missing on purpose: no profile the app speaks reports it, and Health Connect would
 * rather have nothing than an estimate dressed up as a measurement. Lean mass is missing on purpose
 * too, though a scale reports it and the reading keeps it: no screen here shows it, and Play's review
 * refuses a Health Connect type it cannot find on one.
 */
// One block per Health Connect record type, like the main export.
@Suppress("LongMethod")
internal suspend fun exportBodyRecords(
    context: Context,
    client: HealthConnectClient,
    now: Instant,
    localEvents: List<JournalEntry>,
    profile: UserProfile?,
    zone: ZoneId,
    version: Long,
) {
    // A scale that computed its own figure wins; otherwise Mifflin–St Jeor on the weight that was
    // actually on the scale that morning, which is the same equation the day's budget uses.
    val bmrRecords = localEvents.healthRecords { entry ->
        val time = Instant.ofEpochMilli(entry.occurredAt)
        val measured = entry.payloadValue("basalMetabolismKj")?.takeIf { it > 0.0 }?.div(KJ_PER_KCAL)
        val kcalPerDay = measured ?: run {
            val weight = entry.measurementWeightKg?.takeIf { it in PLAUSIBLE_KG } ?: return@healthRecords null
            val owner = profile ?: return@healthRecords null
            restingCaloriesPerDay(
                owner.copy(weightKg = weight),
                time.atZone(zone).year,
            )
        }
        if (kcalPerDay <= 0.0) return@healthRecords null
        BasalMetabolicRateRecord(
            time = time,
            zoneOffset = entry.offsetAt(time, zone),
            basalMetabolicRate = Power.kilocaloriesPerDay(kcalPerDay),
            // The scale's own figure goes as the scale took it ([publishedMetadata]); one worked out
            // here from the weight was taken by nobody, and goes as typed in.
            metadata = publishedClientId(context, BMR_CLIENT_ID_PREFIX, entry.id).let { id ->
                if (measured != null) publishedMetadata(entry, id, version) else Metadata.manualEntry(id, version)
            },
        )
    }
    val bodyWaterRecords = localEvents.healthRecords { entry ->
        val kilograms = entry.payloadValue("bodyWaterMassKg")?.takeIf { it in 1.0..200.0 }
            ?: return@healthRecords null
        val time = Instant.ofEpochMilli(entry.occurredAt)
        BodyWaterMassRecord(
            time = time,
            zoneOffset = entry.offsetAt(time, zone),
            mass = Mass.kilograms(kilograms),
            metadata = publishedMetadata(
                entry,
                publishedClientId(context, BODY_WATER_CLIENT_ID_PREFIX, entry.id),
                version,
            ),
        )
    }

    client.insertChanged(bmrRecords)
    client.insertChanged(bodyWaterRecords)

    removeStalePwnFitRecords(
        context,
        client,
        now,
        BasalMetabolicRateRecord::class,
        BMR_CLIENT_ID_PREFIX,
        bmrRecords.mapNotNull { it.metadata.clientRecordId }.toSet(),
    )
    removeStalePwnFitRecords(
        context,
        client,
        now,
        BodyWaterMassRecord::class,
        BODY_WATER_CLIENT_ID_PREFIX,
        bodyWaterRecords.mapNotNull { it.metadata.clientRecordId }.toSet(),
    )
}

/**
 * The same two values back from Health Connect, into the payload keys the export reads. Another
 * scale app's figures come in as measurement rows; our own records left from an earlier install
 * adopt back through the standard machinery, and one that lands on the instant its scale row
 * already occupies is dropped there instead of doubling it.
 */
internal suspend fun importBodyRecords(context: Context, imports: HealthImport): Int {
    var imported = 0
    imported += imports.read(BasalMetabolicRateRecord::class) { record, marker ->
        record.basalMetabolicRate.inKilocaloriesPerDay.takeIf { it > 0.0 }?.let { kcal ->
            measurementEntry(
                context,
                record.time,
                R.string.direct_bluetooth_basal_metabolism,
                marker,
                record.zoneOffset,
            )
                .copy(
                    title = context.getString(R.string.direct_bluetooth_basal_metabolism) +
                        " " + context.getString(R.string.entry_energy, kcal),
                    measurementPayloadJson = JSONObject()
                        .put("schema", 1).put("basalMetabolismKj", kcal * KJ_PER_KCAL).toString(),
                )
        }
    }
    imported += imports.read(BodyWaterMassRecord::class) { record, marker ->
        record.mass.inKilograms.takeIf { it in 1.0..200.0 }?.let { kilograms ->
            measurementEntry(context, record.time, R.string.direct_bluetooth_body_water, marker, record.zoneOffset)
                .copy(
                    title = context.getString(R.string.direct_bluetooth_body_water) +
                        " ${kilograms.pretty()} ${context.getString(R.string.unit_kg)}",
                    measurementPayloadJson = JSONObject()
                        .put("schema", 1).put("bodyWaterMassKg", kilograms).toString(),
                )
        }
    }
    return imported
}
