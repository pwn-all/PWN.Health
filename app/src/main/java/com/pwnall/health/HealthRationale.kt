package com.pwnall.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.CervicalMucusRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.IntermenstrualBleedingRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OvulationTestRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PlannedExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SexualActivityRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import kotlin.reflect.KClass

/**
 * Publication identity of the bundled policy. The app reads the local copy; PrivacyPolicyTest
 * holds its words and canonical address to the document published from this repository.
 */
internal const val PRIVACY_POLICY_URL = "https://pwn-all.com/app/health/privacy"

/**
 * One row of the rationale table: the record types it covers and the one sentence saying what the
 * app does with them.
 *
 * Types sharing a sentence share a row, but every type is still named in it — the row's heading is
 * built from [HEALTH_RECORD_TYPE_NAMES], so a type with no name fails the test rather than going
 * unmentioned. The history permission is a row of its own with no types and a [title] instead: it
 * is not a record type, but Play reads it as one more grant that needs a reason.
 */
internal data class HealthRationaleRow(
    val types: List<KClass<out Record>>,
    @StringRes val purpose: Int,
    @StringRes val title: Int? = null,
)

/**
 * The words for each record type. Reused from the screens that already show the thing where a name
 * fits, so the rationale calls a reading what the app calls it.
 */
@OptIn(ExperimentalMindfulnessSessionApi::class)
internal val HEALTH_RECORD_TYPE_NAMES: Map<KClass<out Record>, Int> = mapOf(
    WeightRecord::class to R.string.weight,
    HeightRecord::class to R.string.height,
    HeartRateRecord::class to R.string.heart_rate,
    RestingHeartRateRecord::class to R.string.vital_resting_heart_rate_title,
    HeartRateVariabilityRmssdRecord::class to R.string.measurement_hrv_title,
    SleepSessionRecord::class to R.string.sleep,
    ExerciseSessionRecord::class to R.string.health_rationale_type_workouts,
    StepsRecord::class to R.string.steps,
    HydrationRecord::class to R.string.water_title,
    NutritionRecord::class to R.string.health_rationale_type_nutrition,
    BloodPressureRecord::class to R.string.direct_bluetooth_blood_pressure_title,
    BloodGlucoseRecord::class to R.string.direct_bluetooth_glucose_title,
    OxygenSaturationRecord::class to R.string.health_rationale_type_oxygen,
    BodyTemperatureRecord::class to R.string.direct_bluetooth_temperature_title,
    BodyFatRecord::class to R.string.body_fat_title,
    MenstruationPeriodRecord::class to R.string.health_rationale_type_period,
    MenstruationFlowRecord::class to R.string.health_rationale_type_flow,
    IntermenstrualBleedingRecord::class to R.string.cycle_spotting,
    OvulationTestRecord::class to R.string.cycle_section_ovulation,
    CervicalMucusRecord::class to R.string.cycle_section_mucus,
    SexualActivityRecord::class to R.string.health_rationale_type_sexual_activity,
    BasalMetabolicRateRecord::class to R.string.direct_bluetooth_basal_metabolism,
    BodyWaterMassRecord::class to R.string.direct_bluetooth_body_water,
    ActiveCaloriesBurnedRecord::class to R.string.health_rationale_type_active_calories,
    DistanceRecord::class to R.string.direct_bluetooth_distance,
    ElevationGainedRecord::class to R.string.health_rationale_type_elevation,
    PowerRecord::class to R.string.direct_bluetooth_power,
    StepsCadenceRecord::class to R.string.health_rationale_type_step_cadence,
    CyclingPedalingCadenceRecord::class to R.string.health_rationale_type_cycling_cadence,
    MindfulnessSessionRecord::class to R.string.health_rationale_type_mindfulness,
    PlannedExerciseSessionRecord::class to R.string.health_rationale_type_planned,
    TotalCaloriesBurnedRecord::class to R.string.health_rationale_type_total_calories,
    SpeedRecord::class to R.string.direct_bluetooth_speed,
)

/**
 * What the rationale screen tabulates, in the order a person meets the things in the app.
 *
 * Every sentence names a screen or a card where the reading is visible: Play turns down a wording
 * that covers a category ("body measurements") without saying what the app shows for each grant.
 * [HealthRationaleTest] holds this list to the request in [HEALTH_RECORD_TYPES],
 * [HEALTH_WRITE_ONLY_TYPES] and [HEALTH_READ_ONLY_TYPES], so a type added to the manifest without a
 * reason here fails the build rather than the review.
 */
@OptIn(ExperimentalMindfulnessSessionApi::class)
internal val HEALTH_RATIONALE_ROWS: List<HealthRationaleRow> = listOf(
    HealthRationaleRow(
        listOf(WeightRecord::class, HeightRecord::class),
        R.string.health_rationale_purpose_body,
    ),
    HealthRationaleRow(
        listOf(
            BodyFatRecord::class,
            BodyWaterMassRecord::class,
            BasalMetabolicRateRecord::class,
        ),
        R.string.health_rationale_purpose_composition,
    ),
    HealthRationaleRow(listOf(HeartRateRecord::class), R.string.health_rationale_purpose_heart_rate),
    HealthRationaleRow(
        listOf(RestingHeartRateRecord::class, HeartRateVariabilityRmssdRecord::class),
        R.string.health_rationale_purpose_recovery,
    ),
    HealthRationaleRow(listOf(SleepSessionRecord::class), R.string.health_rationale_purpose_sleep),
    HealthRationaleRow(listOf(StepsRecord::class), R.string.health_rationale_purpose_steps),
    HealthRationaleRow(listOf(ExerciseSessionRecord::class), R.string.health_rationale_purpose_workouts),
    HealthRationaleRow(
        listOf(
            ActiveCaloriesBurnedRecord::class,
            StepsCadenceRecord::class,
            CyclingPedalingCadenceRecord::class,
        ),
        R.string.health_rationale_purpose_workout_details,
    ),
    HealthRationaleRow(listOf(HydrationRecord::class), R.string.health_rationale_purpose_water),
    HealthRationaleRow(listOf(NutritionRecord::class), R.string.health_rationale_purpose_nutrition),
    HealthRationaleRow(
        listOf(
            BloodPressureRecord::class,
            BloodGlucoseRecord::class,
            OxygenSaturationRecord::class,
            BodyTemperatureRecord::class,
        ),
        R.string.health_rationale_purpose_vitals,
    ),
    HealthRationaleRow(
        listOf(
            MenstruationPeriodRecord::class,
            MenstruationFlowRecord::class,
            IntermenstrualBleedingRecord::class,
            OvulationTestRecord::class,
            CervicalMucusRecord::class,
        ),
        R.string.health_rationale_purpose_cycle,
    ),
    HealthRationaleRow(listOf(SexualActivityRecord::class), R.string.health_rationale_purpose_sexual),
    HealthRationaleRow(listOf(MindfulnessSessionRecord::class), R.string.health_rationale_purpose_mindfulness),
    HealthRationaleRow(listOf(PlannedExerciseSessionRecord::class), R.string.health_rationale_purpose_plans),
    HealthRationaleRow(
        listOf(
            TotalCaloriesBurnedRecord::class,
            SpeedRecord::class,
            DistanceRecord::class,
            ElevationGainedRecord::class,
            PowerRecord::class,
        ),
        R.string.health_rationale_purpose_derived,
    ),
    HealthRationaleRow(
        emptyList(),
        R.string.health_rationale_purpose_history,
        title = R.string.health_rationale_history,
    ),
)

/**
 * The screen Health Connect opens for "why does this app want access", from its permission dialog
 * and from the app's entry in its settings — the manifest names it under both actions.
 *
 * It is the app's own colours and not the default purple because it is the app, seen from inside a
 * system dialog; and it scrolls, because thirty grants explained one by one do not fit a phone.
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PwnFitTheme(darkTheme = isSystemInDarkTheme()) {
                Surface(Modifier.fillMaxSize()) {
                    HealthRationalePage(onPolicy = { showPrivacyPolicy() }, onClose = ::finish)
                }
            }
        }
    }
}

@Composable
private fun HealthRationalePage(onPolicy: () -> Unit, onClose: () -> Unit) {
    // The bars and a display cutout both, since the activity draws edge to edge and a landscape
    // window reaches under a side cutout. The scroll keeps the whole window; the column in it is
    // centred at the width a panel stands at, so a tablet does not stretch the table across it.
    Column(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .panelVerticalScroll(rememberScrollState())
            .padding(20.dp)
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = PwnDimensions.maxPaneWidth),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(R.string.health_rationale_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(stringResource(R.string.health_rationale_body), color = PwnMuted)
        PwnSectionTitle(stringResource(R.string.health_rationale_types_title))
        PwnCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(PwnDimensions.cardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HEALTH_RATIONALE_ROWS.forEachIndexed { index, row ->
                    HealthRationaleRowView(row)
                    if (index != HEALTH_RATIONALE_ROWS.lastIndex) HorizontalDivider()
                }
            }
        }
        // Each button is as wide as its own label and drops onto a second line when the pair no
        // longer fits, rather than taking half the row each. Split 50/50 a 360dp phone leaves 107dp
        // of text width per button, and "Datenschutzerklärung" is a single unbreakable word about
        // 141dp wide at labelLarge, so German would break mid-word with no hyphen and Russian
        // would run to three lines. At their own widths German needs 189 + 10 + 111 = 310dp of the 320dp
        // between the page's 20dp margins, and the longer Russian label simply sends Close to the
        // next line whole. A weight would not help: a weighted child is capped at its share of the
        // row, which is the squeeze itself.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onPolicy) {
                Text(stringResource(R.string.health_rationale_privacy_policy))
            }
            Button(onClick = onClose) {
                Text(stringResource(R.string.common_close))
            }
        }
    }
}

@Composable
private fun HealthRationaleRowView(row: HealthRationaleRow) {
    val resources = LocalResources.current
    val heading = row.title?.let { resources.getString(it) }
        ?: row.types.joinToString(" · ") { resources.getString(HEALTH_RECORD_TYPE_NAMES.getValue(it)) }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(heading, style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(row.purpose),
            color = PwnMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
