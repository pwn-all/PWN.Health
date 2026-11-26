package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The statements the bathroom, the tap and the bedroom support — and the one rule that matters
 * more than any of them: none of this may reach a lock screen.
 */
class BodyAdviceTest {
    private val today = LocalDate.of(2026, 8, 9)
    private val profile = UserProfile(sex = "MALE", weightKg = 80.0, heightCm = 180, birthDate = "1990-06-15")

    private fun entry(kind: String, day: LocalDate, subtitle: String = "", serving: Double = 0.0) =
        JournalEntry(
            day = day.toString(),
            occurredAt = 0L,
            kind = kind,
            title = kind,
            subtitle = subtitle,
            calories = 0.0,
            servingG = serving,
        )

    private fun sexEntry(day: LocalDate, log: CycleLog, notes: List<SexNote> = emptyList()) = JournalEntry(
        day = day.toString(),
        occurredAt = 0L,
        kind = KIND_CYCLE_LOG,
        title = "sex",
        calories = 0.0,
        measurementPayloadJson = buildString {
            append("""{"$CYCLE_PAYLOAD_KEY":"${log.name}"""")
            if (notes.isNotEmpty()) {
                append(""","$CYCLE_NOTES_KEY":[""")
                append(notes.joinToString(",") { "\"${it.name}\"" })
                append("]")
            }
            append("}")
        },
    )

    // --- privacy ---------------------------------------------------------------------------------

    /**
     * The whole point of the feature: only steps may be announced. Anything else — a cycle, a
     * bowel habit, a blood result, a sexual symptom — waits until the app is opened.
     */
    @Test
    fun onlyActivityMayLeaveTheApp() {
        val public = AdviceTopic.entries.filterNot { it.inAppOnly }

        assertEquals(listOf(AdviceTopic.ACTIVITY), public)
    }

    /** A new topic that nobody thought about defaults to private rather than to the lock screen. */
    @Test
    fun everySensitiveTopicIsMarked() {
        val sensitive = listOf(
            AdviceTopic.CYCLE,
            AdviceTopic.SEXUAL,
            AdviceTopic.LABS,
            AdviceTopic.MOOD,
            AdviceTopic.BOWEL,
            AdviceTopic.HYDRATION,
            AdviceTopic.TOBACCO,
            AdviceTopic.SLEEP,
            AdviceTopic.ENERGY,
        )

        assertTrue(sensitive.all { it.inAppOnly })
        assertFalse(AdviceTopic.ACTIVITY.inAppOnly)
    }

    /**
     * The card draws the topic above the sentence, so every topic needs a name of its own — and two
     * topics sharing one would be a copy-paste, not a heading.
     */
    @Test
    fun everyTopicNamesItself() {
        assertTrue(AdviceTopic.entries.all { it.titleRes != 0 })

        assertEquals(AdviceTopic.entries.size, AdviceTopic.entries.map { it.titleRes }.toSet().size)
    }

    /** Switched off is switched off: the bowel card is gone from the list, not merely quieter. */
    @Test
    fun aTopicSwitchedOffStopsAppearing() {
        val journal = (0 until 3).map { entry(KIND_STOOL, today.minusDays(it.toLong()), StoolKind.LOOSE.name) }
        assertTrue(allAdvice(emptyList(), journal, profile, today, 2026).any { it.topic == AdviceTopic.BOWEL })

        val kept = allAdvice(emptyList(), journal, profile, today, 2026, setOf(AdviceTopic.BOWEL.name))

        assertTrue(kept.none { it.topic == AdviceTopic.BOWEL })
    }

    // --- water in, little out --------------------------------------------------------------------

    @Test
    fun threeLitresAndOneVisitIsWorthALine() {
        val journal = (0 until 3).flatMap { offset ->
            val day = today.minusDays(offset.toLong())
            listOf(
                entry(KIND_WATER, day, serving = 3_000.0),
                entry(KIND_URINATION, day),
            )
        }

        val advice = hydrationAdvice(journal, today, imperial = false)

        assertNotNull(advice)
        assertEquals(AdviceTopic.HYDRATION, advice?.topic)
        // The threshold travels as a volume rather than as a number in a millilitre template, so
        // the sentence can be read out in whichever unit the water cards beside it use.
        assertEquals(WaterVolume(2_500, imperial = false), advice?.args?.get(1))
    }

    /**
     * The same threshold on an imperial profile: still 2,500 ml compared, because that is what the
     * journal stores, and read out as the fl oz the person's own water card prints.
     */
    @Test
    fun theWaterThresholdIsReadOutOnTheProfilesOwnScale() {
        val journal = (0 until 3).flatMap { offset ->
            val day = today.minusDays(offset.toLong())
            listOf(entry(KIND_WATER, day, serving = 3_000.0), entry(KIND_URINATION, day))
        }

        val advice = hydrationAdvice(journal, today, imperial = true)

        assertEquals(WaterVolume(2_500, imperial = true), advice?.args?.get(1))
    }

    /** Water alone says nothing: a person who never logs a visit has not reported a symptom. */
    @Test
    fun waterWithoutAnyLoggedVisitStaysSilent() {
        val journal = (0 until 5).map { entry(KIND_WATER, today.minusDays(it.toLong()), serving = 3_000.0) }

        assertNull(hydrationAdvice(journal, today, imperial = false))
    }

    /** Ordinary drinking with ordinary visits is not a finding either. */
    @Test
    fun normalDaysStaySilent() {
        val journal = (0 until 7).flatMap { offset ->
            val day = today.minusDays(offset.toLong())
            listOf(entry(KIND_WATER, day, serving = 2_000.0)) + (1..6).map { entry(KIND_URINATION, day) }
        }

        assertNull(hydrationAdvice(journal, today, imperial = false))
    }

    // --- the bowel -------------------------------------------------------------------------------

    /**
     * Rome IV's own count: under three in a week, and it is said out loud — as attention. Two stools
     * in a fortnight is not the clinician line: two stools is also the whole record of somebody who
     * started logging on Tuesday.
     */
    @Test
    fun twoStoolsInAFortnightAreWorthAttentionNotAClinician() {
        val journal = listOf(
            entry(KIND_STOOL, today, StoolKind.NORMAL.name),
            entry(KIND_STOOL, today.minusDays(4), StoolKind.NORMAL.name),
        )

        val advice = bowelAdvice(journal, today)

        assertEquals(AdviceTopic.BOWEL, advice?.topic)
        assertEquals("bowel-rare-long", advice?.id)
        assertEquals(AdviceLevel.ATTENTION, advice?.level)
    }

    /**
     * Rome IV describes constipation over three months. A stool every five days for a hundred is
     * nineteen in the quarter against the thirty-nine that three a week comes to, over a record that
     * reaches back the whole quarter — and that is the clinician line.
     */
    @Test
    fun aQuarterUnderThreeAWeekIsTheClinicianLine() {
        val journal = (0 until 20).map { entry(KIND_STOOL, today.minusDays(it * 5L), StoolKind.NORMAL.name) }

        val advice = bowelAdvice(journal, today)

        assertEquals("bowel-rare-quarter", advice?.id)
        assertEquals(AdviceLevel.CLINICIAN, advice?.level)
        assertEquals(91, advice?.windowDays)
    }

    /** A dense record with one quiet fortnight at its end is a quiet fortnight, not a quarter. */
    @Test
    fun aQuietFortnightAfterADenseRecordStaysAtAttention() {
        val dense = (14 until 100).map { entry(KIND_STOOL, today.minusDays(it.toLong()), StoolKind.NORMAL.name) }
        val quiet = listOf(
            entry(KIND_STOOL, today, StoolKind.NORMAL.name),
            entry(KIND_STOOL, today.minusDays(8), StoolKind.NORMAL.name),
        )

        val advice = bowelAdvice(dense + quiet, today)

        assertEquals("bowel-rare-long", advice?.id)
        assertEquals(AdviceLevel.ATTENTION, advice?.level)
    }

    /**
     * A month of daily entries and then nothing is a log somebody put down. Its thirty rows are
     * under the thirty-nine a quarter at three a week comes to and the record reaches back the
     * whole quarter, so a clinician line drawn on the count alone would read two months of silence as
     * two months without.
     */
    @Test
    fun aLogThatWasPutDownIsNotAQuarterOfRareStools() {
        val month = (61..91).map { entry(KIND_STOOL, today.minusDays(it.toLong()), StoolKind.NORMAL.name) }

        assertNull(bowelAdvice(month, today))

        // Picked up again yesterday it is one stool in a fortnight — attention, as for anybody who
        // started logging this week — and still no quarter's verdict over the hole in the middle.
        val resumed = month + entry(KIND_STOOL, today.minusDays(1), StoolKind.NORMAL.name)
        assertEquals("bowel-rare-long", bowelAdvice(resumed, today)?.id)
    }

    /**
     * Loose stools in one week are worth fluids; four weeks in a row of them are worth a doctor.
     * Two bouts a month apart — six loose stools inside eight weeks with a month between the first
     * and the last — do not reach the clinician line: two upset stomachs are not chronic.
     */
    @Test
    fun looseStoolsEscalateOnlyOverConsecutiveWeeks() {
        val week = (0 until 3).map { entry(KIND_STOOL, today.minusDays(it.toLong()), StoolKind.LOOSE.name) }
        assertEquals(AdviceLevel.ATTENTION, bowelAdvice(week, today)?.level)

        val twoBouts = week + (0 until 4).map {
            entry(KIND_STOOL, today.minusDays(30L + it), StoolKind.LOOSE.name)
        }
        assertEquals("bowel-loose", bowelAdvice(twoBouts, today)?.id)
        assertEquals(AdviceLevel.ATTENTION, bowelAdvice(twoBouts, today)?.level)

        // Three loose stools in each of the last four weeks, without a clean week between.
        val persistent = (0 until 4).flatMap { weeksBack ->
            (0 until 3).map { entry(KIND_STOOL, today.minusDays(weeksBack * 7L + it), StoolKind.LOOSE.name) }
        }
        val long = bowelAdvice(persistent, today)
        assertEquals(AdviceLevel.CLINICIAN, long?.level)
        assertEquals("bowel-loose-long", long?.id)
        assertEquals(28, long?.windowDays)

        // The same twelve with the third week clean is two bouts again, not a run.
        val broken = persistent.filterNot { LocalDate.parse(it.day) in today.minusDays(20)..today.minusDays(14) } +
            (0 until 3).map { entry(KIND_STOOL, today.minusDays(28L + it), StoolKind.LOOSE.name) }
        assertEquals("bowel-loose", bowelAdvice(broken, today)?.id)
    }

    /** Straining in a quarter of them is the other half of Rome IV. */
    @Test
    fun strainingIsCountedAgainstTheWeeksTotal() {
        val journal = listOf(
            entry(KIND_STOOL, today, StoolKind.STRAINED.name),
            entry(KIND_STOOL, today.minusDays(1), StoolKind.STRAINED.name),
            entry(KIND_STOOL, today.minusDays(2), StoolKind.NORMAL.name),
            entry(KIND_STOOL, today.minusDays(3), StoolKind.NORMAL.name),
            entry(KIND_STOOL, today.minusDays(4), StoolKind.NORMAL.name),
        )

        assertEquals("bowel-straining", bowelAdvice(journal, today)?.id)
    }

    /** A subtitle this version has no name for is not evidence of straining. */
    @Test
    fun anUnrecognisedStoolSubtitleIsNotCountedAsStraining() {
        val journal = (0 until 4).map { entry(KIND_STOOL, today.minusDays(it.toLong()), "SOFT") }

        assertNull(bowelAdvice(journal, today))
    }

    /** Rome IV asks for more than a quarter, and one hard visit out of four is exactly a quarter. */
    @Test
    fun exactlyAQuarterIsNotMoreThanAQuarter() {
        val journal = listOf(
            entry(KIND_STOOL, today, StoolKind.STRAINED.name),
            entry(KIND_STOOL, today.minusDays(1), StoolKind.NORMAL.name),
            entry(KIND_STOOL, today.minusDays(2), StoolKind.NORMAL.name),
            entry(KIND_STOOL, today.minusDays(3), StoolKind.NORMAL.name),
        )

        assertNull(bowelAdvice(journal, today))
    }

    // --- tobacco ---------------------------------------------------------------------------------

    @Test
    fun smokingMostDaysIsNamedAsAConfounder() {
        val journal = (0 until 6).map { entry(KIND_SMOKING, today.minusDays(it.toLong()), serving = 0.8) }

        assertEquals(AdviceTopic.TOBACCO, tobaccoAdvice(journal, today)?.topic)
    }

    @Test
    fun anOccasionalCigaretteIsNotDailySmoking() {
        val journal = listOf(entry(KIND_SMOKING, today, serving = 0.8))

        assertNull(tobaccoAdvice(journal, today))
    }

    // --- sexual health ---------------------------------------------------------------------------

    /** A male profile with almost no ejaculations gets the association, stated as an association. */
    @Test
    fun theProstateAssociationNeedsAMaleProfileAndAQuietMonth() {
        val quiet = listOf(sexEntry(today, CycleLog.SEX_PROTECTED))

        val advice = sexualAdvice(quiet, profile, today)
        assertEquals("sexual-prostate", advice?.id)
        assertEquals(AdviceLevel.HABIT, advice?.level)

        // A female profile is not told about a prostate.
        assertNull(sexualAdvice(quiet, profile.copy(sex = "FEMALE"), today))

        // Nor is a man whose month was not quiet — masturbation counts towards the same figure.
        val busy = quiet + (0 until 12).map { entry(KIND_MASTURBATION, today.minusDays(it.toLong())) }
        assertNull(sexualAdvice(busy, profile, today))
    }

    /**
     * A month with nothing in it is not a quiet month. A profile that never opened the feature has
     * reported nothing to hold against the cohort, and the section is not offered below sixteen,
     * so neither is the association behind it.
     */
    @Test
    fun theProstateAssociationNeedsSomethingLoggedAndAnAgeToSayItTo() {
        assertNull(sexualAdvice(emptyList(), profile, today))

        val quiet = listOf(sexEntry(today, CycleLog.SEX_PROTECTED))
        assertNull(sexualAdvice(quiet, profile.copy(birthDate = today.minusYears(15).toString()), today))
    }

    /** A symptom outranks any frequency: pain twice is a clinician line, whatever else is true. */
    @Test
    fun repeatedPainOutranksFrequency() {
        val journal = listOf(
            sexEntry(today, CycleLog.SEX_UNPROTECTED, listOf(SexNote.PAIN)),
            sexEntry(today.minusDays(3), CycleLog.SEX_UNPROTECTED, listOf(SexNote.PAIN)),
        )

        val advice = sexualAdvice(journal, profile.copy(sex = "FEMALE"), today)

        assertEquals("sexual-pain", advice?.id)
        assertEquals(AdviceLevel.CLINICIAN, advice?.level)
    }

    /** Bleeding never waits for a second occurrence, on either body. */
    @Test
    fun bleedingIsRaisedTheFirstTime() {
        val female = listOf(sexEntry(today, CycleLog.SEX_PROTECTED, listOf(SexNote.BLEEDING)))
        assertEquals("sexual-symptom", sexualAdvice(female, profile.copy(sex = "FEMALE"), today)?.id)

        val male = listOf(sexEntry(today, CycleLog.SEX_PROTECTED, listOf(SexNote.BLOOD_IN_SEMEN)))
        assertEquals("sexual-symptom", sexualAdvice(male, profile, today)?.id)
    }

    /** The ticks a profile is offered follow the body, and the shared ones are shared. */
    @Test
    fun theTicksFollowTheProfile() {
        val male = sexNotesFor("MALE")
        val female = sexNotesFor("FEMALE")

        assertTrue(SexNote.PAIN in male && SexNote.PAIN in female)
        assertTrue(SexNote.ERECTION in male && SexNote.ERECTION !in female)
        assertTrue(SexNote.BLEEDING in female && SexNote.BLEEDING !in male)
        // An unstated sex is offered only what applies to anyone.
        assertEquals(listOf(SexNote.PAIN, SexNote.LOW_DESIRE, SexNote.DISCOMFORT), sexNotesFor(null))
    }

    /**
     * A very high count gets a sentence, and the sentence is not "cut down": ICD-11 puts the
     * disorder in loss of control and distress, never in a number, so the app says that instead.
     */
    @Test
    fun aHighSoloCountIsNamedWithoutBeingScolded() {
        val journal = (0 until 60).map { entry(KIND_MASTURBATION, today.minusDays((it % 30).toLong())) }

        val advice = sexualAdvice(journal, profile, today)

        assertEquals("sexual-solo-frequency", advice?.id)
        assertEquals(AdviceLevel.HABIT, advice?.level)
        // An ordinary month says nothing at all.
        val ordinary = (0 until 8).map { entry(KIND_MASTURBATION, today.minusDays(it.toLong())) }
        assertNull(sexualAdvice(ordinary, profile, today))
    }

    /** Masturbation counts towards the frequency and never leaves the phone. */
    @Test
    fun soloActsCountTowardsTheFigure() {
        val journal = listOf(
            sexEntry(today, CycleLog.SEX_PROTECTED),
            entry(KIND_MASTURBATION, today.minusDays(1)),
            entry(KIND_MASTURBATION, today.minusDays(2)),
        )

        val window = sexualWindow(journal, today, SEXUAL_WINDOW_DAYS)

        assertEquals(1, window.acts)
        assertEquals(2, window.soloActs)
        assertEquals(3, window.ejaculations)
    }
}
