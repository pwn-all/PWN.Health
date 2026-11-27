package com.pwnall.health

import android.content.Context
import android.content.res.Configuration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * A whole sheet put into words with the app's own translations, in every language the app ships.
 *
 * The layout tests read a sheet whose lines are resource names, so a translation whose arguments do
 * not fit its English — a %1$d where the sheet hands a word — passes all of them and throws only
 * here, the way it would throw on the phone: in the middle of an export, taking the whole document
 * with it. The drawing onto a PdfDocument is native and is left to a device; this is everything
 * before it, resolved through [sheetResources] as the renderer resolves it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class DoctorReportEveryLanguageTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 5)
    private val from: LocalDate = today.minusMonths(3)
    private val zone: ZoneId = ZoneOffset.UTC

    private fun at(day: LocalDate): Long = day.atStartOfDay(zone).plusHours(9).toInstant().toEpochMilli()

    private fun entry(day: LocalDate, kind: String, payload: String? = null) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day),
        kind = kind,
        title = kind,
        calories = 0.0,
        measurementPayloadJson = payload,
    )

    /** Something for most sections a woman is offered, every one of them reached for its words. */
    private fun journal(): List<JournalEntry> = (0L until 10L).flatMap { back ->
        val day = today.minusDays(back)
        listOf(
            entry(day, "MEASUREMENT").copy(measurementWeightKg = 68.0 + back / 10.0, measurementHeartRateBpm = 64),
            entry(day, "MEASUREMENT", """{"schema":1,"systolicMmhg":118,"diastolicMmhg":76}"""),
            entry(day, "MEASUREMENT", """{"schema":1,"spo2Percent":97}"""),
            entry(day, "MEASUREMENT", """{"schema":1,"temperatureCelsius":36.6}"""),
            entry(day, "MEASUREMENT", """{"schema":1,"concentrationMmolL":5.1,"fasting":true}"""),
            entry(day, KIND_EAT).copy(calories = 700.0, proteinG = 30.0),
            entry(day, KIND_MOOD).copy(moodScore = 4),
            entry(day, "MEASUREMENT").copy(sleepStartAt = at(day) - 480 * 60_000L, sleepEndAt = at(day)),
            entry(day, KIND_ALCOHOL).copy(servingG = 16.0),
            entry(day, KIND_STOOL).copy(subtitle = StoolKind.NORMAL.name),
        )
    } + listOf(
        entry(today.minusMonths(8), KIND_LABS, labsPayload(mapOf(LabMarker.LDL to 3.4), fasting = true)),
        entry(today.minusDays(2), KIND_LABS, labsPayload(mapOf(LabMarker.LDL to 3.0), fasting = true)),
        entry(today.minusDays(28), KIND_CYCLE),
        entry(today, KIND_CYCLE),
        entry(today, KIND_CYCLE_LOG, """{"$CYCLE_PAYLOAD_KEY":"${CycleLog.FLOW_MEDIUM.name}"}"""),
        entry(today.minusDays(3), KIND_CYCLE_LOG, """{"$CYCLE_PAYLOAD_KEY":"${CycleLog.SEX_PROTECTED.name}"}"""),
        entry(
            today,
            KIND_PAIN,
            encodePainPayload(PainReport(regions = listOf(RegionSide(PainRegion.KNEE, BodySide.LEFT)), nrs = 4)),
        ),
    )

    private fun sheet(country: CountryProfile): DoctorReport = doctorReport(
        sources = ReportSources(
            profile = UserProfile(
                sex = "FEMALE",
                weightKg = 68.0,
                heightCm = 168,
                birthDate = "1990-04-10",
                allergies = AllergyAnswer.Known(setOf(Allergen.PEANUTS)).stored(),
            ),
            entries = journal(),
            days = (0L until 10L).map { DaySteps(today.minusDays(it).toString(), 9_000) },
            medications = listOf(
                Medication(
                    id = 1,
                    name = "Metformin",
                    dose = "1 tablet",
                    times = "480",
                    startDay = from.toString(),
                    createdAt = 0,
                ),
            ),
            visits = listOf(
                Appointment(
                    id = 1,
                    specialty = "GP",
                    doctor = "Dr Ivanova",
                    atMs = at(today.minusDays(5)),
                    doneAt = at(today.minusDays(5)),
                ),
            ),
        ),
        request = ReportRequest(
            from = from,
            to = today,
            country = country,
            parts = ReportPart.entries.toSet(),
            sexualDetail = SexualDetail.DETAILED,
        ),
        today = today,
        zone = zone,
    )

    private fun inLanguage(tag: String): Context {
        val app = RuntimeEnvironment.getApplication()
        val configuration = Configuration(app.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(tag))
        return app.createConfigurationContext(configuration)
    }

    /** The languages the app ships, read off its resource folders so a new one is tested the day it lands. */
    private val languages: List<String> = listOf("en") +
        File("src/main/res").listFiles().orEmpty().map { it.name }
            .filter { it.matches(Regex("values-[a-z]{2}")) }
            .map { it.removePrefix("values-") }

    private val generated =
        ReportText.Res(R.string.report_generated, listOf("PWN.Health", "1.0", ReportText.Day(today)))

    private val nativeDigits = ('٠'..'٩') + ('۰'..'۹') + ('०'..'९')

    @Test
    fun aFullSheetIsPutIntoWordsInEveryLanguageAndInLatinDigits() {
        assertTrue("only $languages", languages.size >= 16)
        languages.forEach { tag ->
            val resources = sheetResources(inLanguage(tag))
            val locale = resources.configuration.locales[0]
            listOf(InternationalProfile, UnitedStatesProfile).forEach { country ->
                val datePattern = country.datePatternIn(locale)
                val layout = SheetLayout(
                    text = { it.resolve(resources, datePattern) },
                    measure = { line, _ -> line.length * 5f },
                    locale = locale,
                    rtl = tag == "ar",
                    datePattern = datePattern,
                ).apply { compose(sheet(country), generated) }
                val lines = layout.pages.flatten().filterIsInstance<TextOp>().map(TextOp::text)

                assertTrue("$tag ${country.id}: nothing printed", lines.size > 50)
                val foreign = lines.filter { line -> line.any { it in nativeDigits } }
                assertTrue("$tag ${country.id}: $foreign", foreign.isEmpty())
            }
        }
    }
}
