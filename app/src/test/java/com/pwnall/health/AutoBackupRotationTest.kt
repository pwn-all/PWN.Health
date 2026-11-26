package com.pwnall.health

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.time.LocalDate
import java.util.UUID

/**
 * The nightly copy's steps in their order, on a folder kept in a map: which document is written, and
 * what is pruned after it. The provider calls themselves need a phone; what is decided between them
 * does not.
 */
class AutoBackupRotationTest {
    private val today = LocalDate.of(2026, 9, 10)
    private val generation = "00000000-0000-0000-0000-000000000001"
    private val nonce = UUID(0, 0)
    private val next = autoBackupName(today, generation, 20L, nonce)

    /** A folder of the nine days before today, each copy's id its name with an `id:` before it. */
    private val nineDays = (1..9).associate { day ->
        "pwn-health-auto-2026-09-%02d.json".format(day).let { it to "id:$it" }
    }

    /** What the rotation did to the folder, in the order it did it. */
    private class Folder(val children: Map<String, String>) {
        val calls = mutableListOf<String>()

        fun create(name: String): String = "new:$name".also { calls += "create $it" }

        fun delete(id: String) {
            calls += "delete $id"
        }
    }

    private fun Folder.rotate(startedOver: Set<String> = emptySet(), export: (String) -> Unit = {}) = runBlocking {
        rotateAutomaticCopy(
            children,
            next,
            startedOver,
            create = { create(next) },
            export = { document ->
                calls += "export $document"
                export(document)
            },
            delete = this@rotate::delete,
        )
    }

    @Test
    fun aSecondRunInOneDayReplacesTheOldCopyOnlyAfterSuccess() {
        val old = autoBackupName(today, generation, 10L, nonce)
        val folder = Folder(mapOf(old to "id:today"))
        val failure = runCatching { folder.rotate { throw IOException("disk full") } }.exceptionOrNull()
        assertEquals("disk full", failure?.message)
        assertEquals(listOf("create new:$next", "export new:$next"), folder.calls)

        folder.calls.clear()
        folder.rotate()
        assertEquals(listOf("create new:$next", "export new:$next", "delete id:today"), folder.calls)
    }

    @Test
    fun aDatabaseResetKeepsTheSameDaysCopyOfTheLostRecord() {
        val old = autoBackupName(today, "00000000-0000-0000-0000-000000000002", 10L, nonce)
        val legacy = "pwn-health-auto-$today.json"
        val folder = Folder(mapOf(old to "id:old", legacy to "id:legacy"))
        folder.rotate(startedOver = setOf(today.toString()))
        assertEquals(listOf("create new:$next", "export new:$next"), folder.calls)
    }

    /** The copy first, then the pruning: today's is new, and the oldest three of ten go after it. */
    @Test
    fun theOldestCopiesArePrunedOnlyAfterTodaysIsWritten() {
        val folder = Folder(nineDays)

        folder.rotate()

        val made = "new:$next"
        assertEquals(listOf("create $made", "export $made"), folder.calls.take(2))
        assertEquals(
            (1..3).map { "delete id:pwn-health-auto-2026-09-%02d.json".format(it) },
            folder.calls.drop(2).sorted(),
        )
    }

    /** A copy that failed prunes nothing: the old copies are then all there is. */
    @Test
    fun aCopyThatFailedLeavesEveryOldCopyInTheFolder() {
        val folder = Folder(nineDays)

        val failure = runCatching { folder.rotate { throw IOException("disk full") } }.exceptionOrNull()

        assertEquals("disk full", failure?.message)
        assertTrue(folder.calls.none { it.startsWith("delete") })
    }

    /** A folder that will not take a new document is a failure, and nothing in it is touched. */
    @Test
    fun aFolderThatWillNotMakeTheDocumentFailsBeforeAnythingIsWritten() {
        val folder = Folder(nineDays)
        val gone = FileNotFoundException("gone")

        val failure = runCatching {
            runBlocking {
                rotateAutomaticCopy<String>(
                    folder.children,
                    next,
                    emptySet(),
                    create = { throw gone },
                    export = { folder.calls += "export $it" },
                    delete = folder::delete,
                )
            }
        }.exceptionOrNull()

        assertEquals(gone, failure)
        assertEquals(emptyList<String>(), folder.calls)
    }

    /** A stale copy the provider will not delete is left for tomorrow, and the copy still succeeded. */
    @Test
    fun aDeleteTheProviderRefusesDoesNotFailTheCopy() {
        val folder = Folder(nineDays)

        runBlocking {
            rotateAutomaticCopy(
                folder.children,
                next,
                emptySet(),
                create = { folder.create(next) },
                export = { folder.calls += "export $it" },
                delete = { id ->
                    folder.calls += "refused $id"
                    throw SecurityException("read-only")
                },
            )
        }

        assertEquals(3, folder.calls.count { it.startsWith("refused") })
    }

    /** The days the record began again reach the pruning: the lost record's last copies stay. */
    @Test
    fun theCopiesOfARecordThatWasLostStayThroughTheRotation() {
        val folder = Folder(nineDays)

        // The record began again on the 8th, after that day's copy: the 1st to the 8th are the lost
        // record's, and only the oldest of those eight goes. Unmarked, the three oldest of ten would go.
        folder.rotate(startedOver = setOf("2026-09-08"))

        assertEquals(listOf("delete id:pwn-health-auto-2026-09-01.json"), folder.calls.drop(2))
    }

    /** A revoked grant is said in the app's words; anything else as it came, and never as nothing. */
    @Test
    fun aRevokedGrantIsSaidAsTheFolderGoneAndAnythingElseAsItCame() {
        assertEquals("folder gone", autoBackupFailureText(SecurityException("Permission Denial"), "folder gone"))
        assertEquals("disk full", autoBackupFailureText(IOException("disk full"), "folder gone"))
        assertEquals("IOException", autoBackupFailureText(IOException(), "folder gone"))
    }
}
