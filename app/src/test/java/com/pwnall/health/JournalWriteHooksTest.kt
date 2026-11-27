package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What a write is followed by, once it has committed: the widgets that show the row are redrawn and,
 * where the row moves them, the medicine alarms worked out again. Both are tied to the row written
 * and not to the function that wrote it, so a row that comes by another door is not left unseen
 * until the next write or the half-hourly redraw.
 */
class JournalWriteHooksTest {
    private val day = "2026-11-18"
    private val at = 1_795_000_000_000L

    /**
     * A cup with milk from the caffeine dialog is a food row carrying caffeine. It moves the day's
     * energy and the year's XP as any meal does, and it ends "before food" for a dose — so it is not
     * redrawn and followed up as a cup of black coffee, which does neither.
     */
    @Test
    fun aCupWithMilkIsFollowedUpAsTheFoodRowItIs() {
        val black = cupEntry("Coffee", CaffeineDrink.COFFEE, STANDARD_CUP_ML, day, at)
        val withMilk = cupEntry("Cappuccino", CaffeineDrink.CAPPUCCINO, STANDARD_CUP_ML, day, at)

        assertEquals(setOf(KIND_CAFFEINE), cupRedrawKinds(black))
        assertEquals(setOf(KIND_CAFFEINE, "EAT"), cupRedrawKinds(withMilk))
        assertFalse(black.movesMedicineAlarms())
        assertTrue(withMilk.movesMedicineAlarms())
    }

    /** A meal, a dose and a night's sleep move the alarms; a glass of water does not. */
    @Test
    fun theRowsTheMedicineAlarmsAreWorkedOutAgainFor() {
        fun row(kind: String, sleepEndAt: Long? = null) =
            JournalEntry(day = day, occurredAt = at, kind = kind, title = "", calories = 0.0, sleepEndAt = sleepEndAt)

        assertTrue(row("EAT").movesMedicineAlarms())
        assertTrue(row(KIND_MED).movesMedicineAlarms())
        assertTrue(row("MEASUREMENT", sleepEndAt = at).movesMedicineAlarms())
        assertFalse(row("MEASUREMENT").movesMedicineAlarms())
        assertFalse(row(KIND_WATER).movesMedicineAlarms())
    }

    /**
     * A backup merged, a file imported and an exchange with Health Connect write through the DAO,
     * past the single-row save and what follows it, and each ends with the recalculation. So the
     * recalculation is where both hooks run for them — or a night the watch brought would leave the
     * sleep widget and the dose taken on waking where they were.
     */
    @Test
    fun aWriteOfManyRowsEndsWithBothHooks() {
        val data = File("src/main/java/com/pwnall/health/Data.kt").readText()
        val recalculation = data.substringAfter("suspend fun StepsDb.recalculateJournal() {").substringBefore("\n}\n")

        assertTrue(recalculation.contains("refreshMedicineAfterWrite()"))
        assertTrue(recalculation.contains("refreshWidgetsAfterWrite()"))
        assertTrue(recalculation.indexOf("withTransaction") < recalculation.indexOf("refreshWidgetsAfterWrite()"))
    }
}
