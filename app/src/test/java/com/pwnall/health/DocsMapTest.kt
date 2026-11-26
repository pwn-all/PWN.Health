package com.pwnall.health

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * `docs/README.md` — the page that says which document is a rule held by tests, which describes the
 * app as of a date and which is a working note — held to the files it is about.
 *
 * Without the page nothing tells a living rule from a report three months old, and it answers that
 * only while it is complete, so a document added to the root or to `docs/` without a row fails here.
 * The second and third hold the one table in the documents that names the shipped models by their
 * bytes to the files that ship, so the record cannot describe a reader of another size than the one
 * in the APK. The next two hold the reader's notices to the same file, and the licence the README
 * names to the text in the root. The last three hold what the documents and the sources say of each
 * other: the source files a current document names, the documents a comment cites, and the design's
 * promise that the step logic takes nothing from Android.
 *
 * Read as files, from the module directory the unit tests run in, like the changelog test.
 */
class DocsMapTest {
    private val map = File("../docs/README.md").readText()

    @Test
    fun everyDocumentInTheRootAndInDocsHasARow() {
        val root = File("..").listFiles { file -> file.isFile && file.extension == "md" }.orEmpty().map { it.name }
        val docs = File("../docs")
            .listFiles()
            .orEmpty()
            .filterNot { it.name == "README.md" || it.name.startsWith(".") }
            .map { if (it.isDirectory) "${it.name}/" else it.name }

        assertTrue("the folders are read: $root $docs", "CHANGELOG.md" in root && "privacy-policy.html" in docs)
        (root + docs).forEach { name -> assertTrue("$name has no row in docs/README.md", "`$name" in map) }
    }

    @Test
    fun theIntegrationRecordNamesTheChatReaderThatShips() {
        val manifest = File("src/main/assets/chat/reader-manifest.json")
        assumeTrue("no chat model in this tree: nothing to hold the record to", manifest.isFile)
        val onnx = manifest.readText().substringAfter("\"onnx\"")
        val sha = checkNotNull(Regex(""""sha256":\s*"([0-9a-f]{64})"""").find(onnx)).groupValues[1]
        val bytes = checkNotNull(Regex(""""bytes":\s*(\d+)""").find(onnx)).groupValues[1].toLong()
        val record = File("../docs/MODELS.md").readText()

        assertTrue("docs/MODELS.md does not name the reader.onnx that ships: $sha", sha in record)
        assertTrue("the record does not give its size: $bytes", "%,d".format(Locale.ROOT, bytes) in record)
    }

    /**
     * Every model in the assets, and the dictionaries the OCR models read their letters from, by its
     * bytes and SHA-256 in the record's table of what ships. Gradle's checksums cover what the build
     * downloads and not what is committed: without this, a photo or OCR network swapped in the assets
     * would be another network with nothing to say so.
     */
    @Test
    fun theIntegrationRecordNamesEveryModelThatShips() {
        val assets = File("src/main/assets")
        val models = assets.walk().filter { it.isFile }
            .filter { it.extension == "onnx" || it.parentFile?.name == "ocr" }.toList()
        val shipped = File("../docs/MODELS.md").readText()
            .substringAfter("\n## Shipped in ").substringBefore("\n## ")

        assertTrue("the assets are read: $models", models.count { it.parentFile?.name == "ocr" } >= 4)
        models.forEach { file ->
            val name = file.relativeTo(assets).path
            assertTrue("$name is not in the table of what ships", "`$name`" in shipped)
            assertTrue("the record does not name $name by its hash", sha256(file.readBytes()) in shipped)
            assertTrue("the record does not give $name's size", "%,d".format(Locale.ROOT, file.length()) in shipped)
        }
    }

    @Test
    fun theNoticesNameTheChatReaderThatShips() {
        val manifest = File("src/main/assets/chat/reader-manifest.json")
        assumeTrue("no chat model in this tree: nothing to hold the notices to", manifest.isFile)
        val model = checkNotNull(Regex(""""model":\s*"chat-reader \S+ (\S+) """).find(manifest.readText()))
        val shipped = model.groupValues[1]

        val notices = File("src/main/assets/chat/NOTICES.md").readText()
        assertTrue("chat/NOTICES.md says nothing of the package that ships: $shipped", shipped in notices)
    }

    @Test
    fun theLicenceTheReadmeNamesIsTheTextInTheRoot() {
        val licence = File("../LICENSE").readText()

        assertTrue("LICENSE is not the GPL's text", "GNU GENERAL PUBLIC LICENSE" in licence && "Version 3" in licence)
        assertTrue("README.md does not name the licence", "GPL-3.0-or-later" in File("../README.md").readText())
    }

    /**
     * A document the map calls current, or held by tests, names source files as if they were there.
     * A file renamed or removed would leave the document leading nowhere without a word. A dated
     * record is left alone: what it names was there on its date.
     */
    @Test
    fun everySourceFileACurrentDocumentNamesExists() {
        val sources = File("src").walkTopDown().filter { it.extension == "kt" }.map { it.name }.toSet()
        val folders = listOf("../docs", "../docs/medical-export").flatMap { markdownIn(it) }
        val tools = File("../tools").listFiles().orEmpty().map { File(it, "README.md") }.filter { it.isFile }
        val current = (folders + tools + File("../README.md")).filter { CURRENT.containsMatchIn(statusOf(it)) }

        assertTrue("the sources and the documents are read: $current", "Data.kt" in sources && current.size > 10)
        current.forEach { document ->
            val named = SOURCE_NAME.findAll(document.readText()).map { it.value }.toSet()
            val gone = named - sources
            assertTrue("${document.path} names source files that are not there: $gone", gone.isEmpty())
        }
    }

    /**
     * The status table in docs/DESIGN.md says what holds each feature by naming its test class, which
     * the check above does not see without a `.kt`, so a class it names that is gone fails here. The
     * README is held the same way. Code blocks are left out; a class is named in
     * backticks, alone or before one of its tests.
     */
    @Test
    fun everyTestClassTheReadmeAndTheDesignNameExists() {
        val sources = File("src").walkTopDown().filter { it.extension == "kt" }.map { it.nameWithoutExtension }.toSet()
        val prose = listOf("../README.md", "../docs/DESIGN.md").joinToString("\n") { File(it).readText() }
            .replace(Regex("(?m)^```[\\s\\S]*?^```"), "")
        val named = Regex("""`([A-Z]\w*Test)[`.]""").findAll(prose).map { it.groupValues[1] }.toSet()

        assertTrue("both are read: $named", "NoNetworkPermissionTest" in named && "StepAccumulatorTest" in named)
        assertTrue("the documents name test classes that are gone: ${named - sources}", (named - sources).isEmpty())
    }

    /**
     * A comment that cites a document is where its reader goes for the reasons, so the document has
     * to be there. Looked for from the root, from `docs/`, from this module
     * and from its `src/main` and the assets there, which are the ways the sources name one.
     */
    @Test
    fun everyDocumentASourceCommentCitesExists() {
        val cited = File("src").walkTopDown().filter { it.extension == "kt" }.flatMap { source ->
            val comments = COMMENT.findAll(source.readText())
            comments.flatMap { DOCUMENT_NAME.findAll(it.value) }.map { it.value to source.name }
        }.toSet()

        assertTrue("the comments are read", cited.any { it.first == "docs/REPORT_COMPARISONS.md" })
        cited.forEach { (document, source) ->
            val there = DOCUMENT_ROOTS.any { File(it, document).isFile }
            assertTrue("$source cites $document, which is not there", there)
        }
    }

    /**
     * docs/DESIGN.md §1.1 promises that the step logic and the motion gate import nothing from Android, so
     * that both are tested on the JVM as they are. An import is the half a compiler shows. The other
     * half is a helper taken from a file that has such imports, which one package lets through
     * without a line at the top. A name the file declares itself is its own, whatever another file
     * calls by the same word.
     */
    @Test
    fun theStepLogicTakesNothingFromAFileThatImportsAndroid() {
        val folder = File("src/main/java/com/pwnall/health")
        val theirs = folder.listFiles { file -> file.extension == "kt" }.orEmpty()
            .map { it.name to it.readText() }
            .filter { (_, text) -> ANDROID_IMPORT.containsMatchIn(text) }
            .flatMap { (name, text) -> TOP_LEVEL.findAll(text).map { it.groupValues[1] to name } }
            .toMap()

        assertTrue("the declarations are read: ${theirs.size}", theirs["StepsDb"] == "Data.kt")
        listOf("StepAccumulator.kt", "MotionGate.kt").forEach { name ->
            val code = File(folder, name).readText().replace(COMMENT_OR_STRING, " ")
            val own = DECLARED.findAll(code).map { it.groupValues[1] + it.groupValues[2] }.toSet()
            val taken = WORD.findAll(code).map { it.value }.filter { it in theirs && it !in own }.toSet()

            assertTrue("$name imports Android", !ANDROID_IMPORT.containsMatchIn(code))
            assertTrue("$name uses ${taken.map { "$it of ${theirs[it]}" }}", taken.isEmpty())
        }
    }

    private fun markdownIn(folder: String): List<File> =
        File(folder).listFiles { file -> file.extension == "md" && file.name != "README.md" }.orEmpty().toList()

    /** What the map's row says [document] is. A row names it from the root, from `docs/`, or bare. */
    private fun statusOf(document: File): String {
        val path = document.invariantSeparatorsPath.removePrefix("../")
        val row = listOf(path, path.removePrefix("docs/"), document.name).firstNotNullOfOrNull { name ->
            map.lineSequence().firstOrNull { "`$name`" in it }
        }
        return checkNotNull(row) { "$path has no row in docs/README.md" }.split("|")[2]
    }

    private companion object {
        val CURRENT = Regex("Current|Held by tests")
        val SOURCE_NAME = Regex("""\b[A-Z]\w*\.kt\b""")

        val COMMENT = Regex("""/\*[\s\S]*?\*/|//.*""")

        /** A path that ends in `.md`, and not the tail of a web address. */
        val DOCUMENT_NAME = Regex("""(?<![\w:./-])[\w./-]*\w\.md\b""")
        val DOCUMENT_ROOTS = listOf(
            "..",
            "../docs",
            "../docs/medical-export",
            ".",
            "src/main",
            "src/main/assets",
        )
        val ANDROID_IMPORT = Regex("""^import androidx?\.""", RegexOption.MULTILINE)

        /** A declaration at the left margin that is not private: its name, after the receiver if it has one. */
        val TOP_LEVEL = Regex(
            """^(?:@[\w.]+(?:\([^)\n]*\))? )*(?!private )(?:[a-z]+ )*""" +
                """(?:fun|val|var|class|object|interface|typealias) (?:<[^>]+> )?(?:[\w.<>?, ]+\.)?(\w+)""",
            RegexOption.MULTILINE,
        )
        val COMMENT_OR_STRING = Regex("""/\*[\s\S]*?\*/|//.*|"(?:\\.|[^"\\\n])*"""")

        /** What a file declares itself, anywhere in it: by a keyword, or as a parameter before its colon. */
        val DECLARED = Regex("""\b(?:fun|val|var|class|object|interface)\s+(?:[\w.<>?, ]+\.)?(\w+)|(\w+)\s*:(?!:)""")
        val WORD = Regex("""\b[A-Za-z_]\w*""")
    }
}
