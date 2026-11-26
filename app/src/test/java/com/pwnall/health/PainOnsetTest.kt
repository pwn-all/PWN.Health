package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * "When did it start?" as the page asks it — now, today, yesterday, 2–3 days, about a week, about a
 * month, more than a month — and the two answers it does not offer, still read for the entries that
 * hold them.
 * Each answer has to mean what it says to every reader of it: the soreness rule's window, the pain
 * starts the cards and the doctor's sheet count, and the list the page offers.
 */
class PainOnsetTest {
    private val zone = ZoneOffset.UTC
    private val reportDay = LocalDate.of(2026, 9, 21)
    private val thigh = RegionSide(PainRegion.THIGH_FRONT, BodySide.LEFT)

    private fun at(date: LocalDate, hours: Int) = date.atTime(hours, 0).toInstant(zone).toEpochMilli()

    private fun dayStart(daysBack: Long) = reportDay.minusDays(daysBack).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun pain(onset: PainOnset?, day: LocalDate = reportDay, region: RegionSide = thigh) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, 19),
        kind = KIND_PAIN,
        title = "Pain",
        calories = 0.0,
        measurementPayloadJson = encodePainPayload(
            PainReport(regions = listOf(region), types = listOf(PainType.ACHING), nrs = 5, onset = onset),
        ),
    )

    private fun span(onset: PainOnset?): LongRange? {
        val row = pain(onset)
        return painOnsetSpan(decodePainPayload(row.measurementPayloadJson), row, zone)
    }

    private fun kept(day: LocalDate) = JournalEntry(
        day = day.toString(),
        occurredAt = at(day, 12),
        kind = "WATER",
        title = "Water",
        calories = 0.0,
    )

    @Test
    fun theListOffersTheSevenAnswersInOrderAndNeverTheOldOnes() {
        assertEquals(
            listOf(
                PainOnset.JUST_NOW,
                PainOnset.EARLIER_TODAY,
                PainOnset.YESTERDAY,
                PainOnset.DAYS_2_3,
                PainOnset.ABOUT_A_WEEK,
                PainOnset.ABOUT_A_MONTH,
                PainOnset.MORE_THAN_A_MONTH,
            ),
            painOnsetChoices(null),
        )
        assertEquals(painOnsetChoices(null), painOnsetChoices(PainOnset.YESTERDAY))
        // An older entry opened again shows its own answer, at the end of the list.
        assertEquals(painOnsetChoices(null) + PainOnset.WEEK, painOnsetChoices(PainOnset.WEEK))
        assertEquals(PainOnset.MONTHS_3_PLUS, painOnsetChoices(PainOnset.MONTHS_3_PLUS).last())
    }

    /** The payload stores the names: every answer reads back as itself, the two not offered included. */
    @Test
    fun everyAnswerOldAndNewSurvivesThePayload() {
        PainOnset.entries.forEach { onset ->
            val report = PainReport(regions = listOf(thigh), onset = onset)
            assertEquals(onset, decodePainPayload(encodePainPayload(report)).onset)
        }
        assertEquals(
            PainOnset.WEEK,
            decodePainPayload("""{"schema":1,"regions":["THIGH_FRONT:L"],"onset":"WEEK"}""").onset,
        )
        assertEquals(
            PainOnset.EARLIER_TODAY,
            decodePainPayload("""{"schema":1,"regions":["THIGH_FRONT:L"],"onset":"EARLIER_TODAY"}""").onset,
        )
    }

    /**
     * Each answer covers its own stretch, and the stretches meet: "2–3 days ago" ends where
     * "yesterday" begins, and "about a week ago" — the fourth to the tenth day back — ends where
     * "2–3 days ago" begins.
     */
    @Test
    fun eachAnswerCoversItsOwnDaysWithNoGapBetweenThem() {
        assertEquals(dayStart(0)..at(reportDay, 19), span(PainOnset.EARLIER_TODAY))
        assertEquals(dayStart(1) until dayStart(0), span(PainOnset.YESTERDAY))
        assertEquals(dayStart(3) until dayStart(1), span(PainOnset.DAYS_2_3))
        val week = span(PainOnset.ABOUT_A_WEEK)!!
        assertEquals(dayStart(PAIN_ONSET_REACH_DAYS), week.first)
        assertEquals(dayStart(PAIN_ABOUT_A_WEEK_NEAREST - 1) - 1, week.last)
        assertEquals(span(PainOnset.DAYS_2_3)!!.first, week.last + 1)
    }

    @Test
    fun aMonthAndLongerAreTooFarBackToPlace() {
        listOf(PainOnset.ABOUT_A_MONTH, PainOnset.MORE_THAN_A_MONTH, PainOnset.WEEK, PainOnset.MONTHS_3_PLUS)
            .forEach { onset ->
                assertTrue(onset.longStanding)
                assertNull(onset.name, span(onset))
            }
        PainOnset.entries.filter { it.offered }.take(5).forEach { assertFalse(it.longStanding) }
    }

    /**
     * A pain that began about a week ago is never matched to a workout: its stretch is seven days
     * long, and no session is six to seventy-two hours before every moment of it.
     */
    @Test
    fun aboutAWeekAgoIsNeverSorenessFromOneSession() {
        val rows = (2L..14L).map { back ->
            JournalEntry(
                day = reportDay.minusDays(back).toString(),
                occurredAt = at(reportDay.minusDays(back), 18),
                kind = "ACTIVITY",
                title = "Squats",
                calories = 200.0,
                activityCode = "SQUATS",
                durationMinutes = 45.0,
            )
        }
        listOf(PainOnset.ABOUT_A_WEEK, PainOnset.ABOUT_A_MONTH, PainOnset.MORE_THAN_A_MONTH).forEach { onset ->
            assertEquals(onset.name, SorenessFit.NO_SESSION, trainingSoreness(pain(onset), rows, zone).fit)
        }
    }

    /**
     * A pain first told as about a week old or more did not start the day it was told. A week's
     * seven days are wider than the three-day stretches a start is set against, and a start placed
     * there would only ever read as one outside them. One told as 2–3 days old did start, on one of
     * those two days.
     */
    @Test
    fun aWeekOldPainIsNoStartAndOneDaysOldIs() {
        val legs = PainGroup.HIPS_LEGS.areas
        val window = reportDay.minusDays(30)..reportDay
        val days = (0L..30L).map { reportDay.minusDays(it) }
        listOf(PainOnset.ABOUT_A_WEEK, PainOnset.ABOUT_A_MONTH, PainOnset.MORE_THAN_A_MONTH).forEach { onset ->
            val diary = PainDiary(days.drop(1).map(::kept) + pain(onset), zone)
            assertTrue(onset.name, diary.starts(legs, window).isEmpty())
        }
        val diary = PainDiary(days.drop(1).map(::kept) + pain(PainOnset.DAYS_2_3), zone)
        assertEquals(
            mapOf(reportDay to reportDay.minusDays(3)..reportDay.minusDays(2)),
            diary.startsFrom(legs, window),
        )
    }

    /** Typed in either language, any case, accents or none, part of a word or all of it. */
    @Test
    fun theAreaSearchFindsEveryWordInEitherLanguage() {
        val leftThigh = RegionSide(PainRegion.THIGH_FRONT, BodySide.LEFT)
        val rightThigh = RegionSide(PainRegion.THIGH_FRONT, BodySide.RIGHT)
        val leftKnee = RegionSide(PainRegion.KNEE, BodySide.LEFT)
        val names = mapOf(
            leftThigh to listOf("Front of the thigh · left", "Бедро спереди · слева"),
            rightThigh to listOf("Front of the thigh · right", "Бедро спереди · справа"),
            leftKnee to listOf("Knee · left", "Колено · слева"),
        )
        assertEquals(names.keys.toList(), painAreasMatching(names, ""))
        assertEquals(names.keys.toList(), painAreasMatching(names, "  · "))
        assertEquals(listOf(leftThigh, rightThigh), painAreasMatching(names, "THIGH"))
        assertEquals(listOf(leftThigh), painAreasMatching(names, "thigh left"))
        assertEquals(listOf(leftThigh, rightThigh), painAreasMatching(names, "бедр"))
        assertEquals(listOf(leftThigh, leftKnee), painAreasMatching(names, "СЛЕВА"))
        assertEquals(listOf(leftKnee), painAreasMatching(names, "kneé"))
        assertTrue(painAreasMatching(names, "elbow").isEmpty())
    }

    /**
     * On a page in Russian the area is still found by its English words, side and all — and an area
     * the second map carved out of another by its family's too: "knee left" is the knee and the back
     * of it, "lower back" the strip over the spine and the loins either side, "abdomen" the six parts
     * of the belly the second map offers in its place.
     */
    @Test
    fun theAreaSearchKnowsEveryAreasEnglishWords() {
        val russian = RegionSide.ALL.associateWith { listOf("Область", painAreaSearchName(it)) }
        assertEquals(
            listOf(RegionSide(PainRegion.KNEE, BodySide.LEFT), RegionSide(PainRegion.BACK_OF_KNEE, BodySide.LEFT)),
            painAreasMatching(russian, "knee left"),
        )
        assertEquals(
            listOf(
                RegionSide(PainRegion.LOWER_BACK, BodySide.CENTRE),
                RegionSide(PainRegion.FLANK, BodySide.LEFT),
                RegionSide(PainRegion.FLANK, BodySide.RIGHT),
            ),
            painAreasMatching(russian, "Lower back"),
        )
        val belly = listOf(
            PainRegion.ABDOMEN,
            PainRegion.UPPER_BELLY,
            PainRegion.UNDER_RIBS,
            PainRegion.LOWER_BELLY,
            PainRegion.LOWER_SIDE,
        )
        assertEquals(belly, painAreasMatching(russian, "abdomen").map { it.region }.distinct())
        assertEquals(listOf(RegionSide(PainRegion.TAILBONE, BodySide.CENTRE)), painAreasMatching(russian, "tailbone"))
        assertEquals(
            listOf(RegionSide(PainRegion.STERNUM, BodySide.CENTRE)),
            painAreasMatching(russian, "sternum"),
        )
        assertEquals(RegionSide.ALL, painAreasMatching(russian, "облас"))
    }

    /** The areas' labels as the resources of [folder] give them, read as text: the words the page shows. */
    private fun labels(folder: String): (PainRegion) -> String {
        val text = File("src/main/res/$folder/ui.xml").readText()
        return { region ->
            text.substringAfter("<string name=\"pain_region_${region.name.lowercase()}\">", "")
                .substringBefore("</string>")
        }
    }

    /**
     * The breastbone is found by the word its own label gives it, on a page in English and on one in
     * Russian — the list's names read from the resources, as the page searches them — and by the chest
     * it was carved from, in either language.
     */
    @Test
    fun theBreastboneIsFoundByTheWordPeopleUse() {
        val sternum = RegionSide(PainRegion.STERNUM, BodySide.CENTRE)
        val chest = listOf(RegionSide(PainRegion.CHEST, BodySide.LEFT), RegionSide(PainRegion.CHEST, BodySide.RIGHT))
        val words = mapOf("values" to ("breastbone" to "chest"), "values-ru" to ("грудина" to "груд"))
        words.forEach { (folder, word) ->
            val label = labels(folder)
            val names = RegionSide.ALL.associateWith { area -> painAreaSearchNames(area) { label(it.region) } }
            names.forEach { (area, of) -> assertTrue("$folder ${area.code}", of.first().isNotEmpty()) }
            assertEquals(folder, listOf(sternum), painAreasMatching(names, word.first))
            assertEquals(folder, chest + sternum, painAreasMatching(names, word.second))
        }
        // Under the ribs, whose own name has no belly in it, is found by the belly it was carved from.
        val russianLabel = labels("values-ru")
        val russian = RegionSide.ALL.associateWith { area -> painAreaSearchNames(area) { russianLabel(it.region) } }
        val underRibs = RegionSide.ALL.filter { it.region == PainRegion.UNDER_RIBS }
        assertTrue(painAreasMatching(russian, "живот").containsAll(underRibs))
    }
}
