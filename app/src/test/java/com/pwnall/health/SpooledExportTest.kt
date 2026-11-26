package com.pwnall.health

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/**
 * Every export's way out of the app, on a document kept in memory: the backup is rendered whole into
 * the cache before the document is touched, lies there under no name, and a failure takes away only
 * the document this export spoiled — never yesterday's copy a provider refused to open.
 */
class SpooledExportTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val document = ByteArrayOutputStream()
    private var discarded = false
    private var emptyDocument = true

    private fun export(
        render: suspend (OutputStream) -> String = {
            it.write(BACKUP)
            "summary"
        },
        open: () -> OutputStream = { document },
    ): Result<String> = runCatching {
        runBlocking {
            spooledExport(
                folder.root,
                render,
                open,
                documentIsEmpty = { emptyDocument },
                discard = { discarded = true },
            )
        }
    }

    /** The document is opened only once the whole backup is rendered, and the cache keeps no name of it. */
    @Test
    fun theBackupIsRenderedWholeUnderNoNameBeforeTheDocumentIsOpened() {
        val namedWhileRendering = mutableListOf<String>()
        var rendered = false

        val result = export(
            render = {
                it.write(BACKUP)
                namedWhileRendering += folder.root.list().orEmpty()
                rendered = true
                "summary"
            },
            open = {
                assertTrue("opened before the rendering ended", rendered)
                document
            },
        )

        assertEquals("summary", result.getOrThrow())
        assertEquals(String(BACKUP), document.toString())
        assertEquals(emptyList<String>(), namedWhileRendering)
        assertEquals(emptyList<String>(), folder.root.list().orEmpty().toList())
        assertFalse(discarded)
    }

    /** A provider that will not open the document has left yesterday's copy in it, and that copy stays. */
    @Test
    fun aDocumentTheProviderWouldNotOpenKeepsYesterdaysCopy() {
        emptyDocument = false

        val result = export(open = { throw FileNotFoundException("refused") })

        assertEquals("refused", result.exceptionOrNull()?.message)
        assertFalse(discarded)
        assertEquals(emptyList<String>(), folder.root.list().orEmpty().toList())
    }

    /** A render that fails takes away the empty document the picker just made, and nothing that holds a copy. */
    @Test
    fun aFailedRenderRemovesOnlyAnEmptyDocument() {
        val failing: suspend (OutputStream) -> String = { throw IOException("no room") }

        assertTrue(export(render = failing).isFailure)
        assertTrue("the picker's empty document goes", discarded)

        discarded = false
        emptyDocument = false
        assertTrue(export(render = failing).isFailure)
        assertFalse("a document that holds a copy stays", discarded)
        assertFalse("and the document was never written", document.size() > 0)
    }

    /** Half a backup is no backup: once the document was opened, a copy cut off removes it. */
    @Test
    fun aCopyCutOffHalfWayRemovesTheHalfThatGotAcross() {
        emptyDocument = false
        val breaking = object : OutputStream() {
            override fun write(b: Int): Unit = throw IOException("connection lost")

            override fun write(b: ByteArray, off: Int, len: Int): Unit = throw IOException("connection lost")
        }

        val result = export(open = { breaking })

        assertEquals("connection lost", result.exceptionOrNull()?.message)
        assertTrue(discarded)
    }

    /** A provider that refuses the removal too does not hide why the export failed. */
    @Test
    fun aRefusedRemovalDoesNotHideTheFailure() {
        val result = runCatching {
            runBlocking {
                spooledExport<String>(
                    folder.root,
                    render = { throw IOException("no room") },
                    open = { document },
                    documentIsEmpty = { true },
                    discard = { throw SecurityException("read-only") },
                )
            }
        }

        assertEquals("no room", result.exceptionOrNull()?.message)
        assertEquals(emptyList<String>(), folder.root.list().orEmpty().toList())
    }

    private companion object {
        val BACKUP = """{"schema":11}""".toByteArray()
    }
}
