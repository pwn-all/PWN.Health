package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * Android keeps one notification per id, so two features under one number replace each other in the
 * shade and nothing says so. Every number comes from one registry: literals spread over files, with
 * comments listing "the others" as the only check, would let a new one such as the planned workout's
 * 302 collide unseen.
 */
class NotificationIdsTest {
    /** Every constant of the registry, read from the class: one added later is checked without being listed here. */
    private val named: Map<String, Int> = NotificationIds::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
        .associate { it.name to it.getInt(null) }

    @Test
    fun noTwoNotificationsShareAnId() {
        // The hourly worker's nudges take a run of ids, one per kind, from the base.
        val reminders = ReminderKind.entries.associate { "reminder ${it.name}" to NotificationIds.reminder(it) }
        val all = named - "REMINDER_BASE" + reminders

        assertTrue("the registry is read: $named", named.size >= 13)
        assertEquals(NotificationIds.REMINDER_BASE, NotificationIds.reminder(ReminderKind.entries.first()))
        val shared = all.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
        assertEquals(emptyMap<Int, List<String>>(), shared)
    }

    /** The registry is only a registry while nothing posts under a number of its own. */
    @Test
    fun nothingPostsUnderANumberWrittenInPlace() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.size > 50)
        val inPlace = Regex(
            """\b(?:notify|cancel|startForeground|startForegroundDegrading)\(\s*(?:this,\s*)?\d+\s*[,)]""" +
                """|\bid = \d{3}\b|NOTI[A-Z_]*_ID\s*=\s*\d|\bconst val ID = \d""",
        )
        val found = sources.filter { it.name != "NotificationIds.kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                "${file.name}:${index + 1}".takeIf { inPlace.containsMatchIn(line) }
            }
        }
        assertEquals(emptyList<String>(), found)
    }
}
