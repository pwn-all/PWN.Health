package com.pwnall.health

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The privacy policy the app opens, held against the one the repository publishes.
 *
 * The address the app hands a person is a constant; the address the policy claims for itself is
 * the `canonical` link at the top of the document. The two must name one address, since Health
 * Connect's review follows exactly that link. The tracked document is `docs/privacy-policy.html`.
 * The website serves its own copy, which is not in this repository, so nothing here can hold the
 * two together.
 *
 * Both are read as files, from the module directory the unit tests run in, like the changelog test.
 */
class PrivacyPolicyTest {
    private val published = File("../docs/privacy-policy.html")

    @Test
    fun `offline policy contains exactly the published document`() {
        assertArrayEquals(published.readBytes(), File("src/main/assets/privacy-policy.html").readBytes())
    }

    /**
     * The heading says which release the policy describes, and has to name the build's. A release
     * raises `versionName`, and the policy is read against the code before it goes.
     */
    @Test
    fun `the policy names the version the build makes`() {
        val versionName = Regex("""versionName = "([^"]+)"""").find(File("build.gradle.kts").readText())
            ?.groupValues?.get(1)
        val stated = Regex("""App version ([\d.]+)</p>""").find(published.readText())?.groupValues?.get(1)

        assertTrue("no versionName in build.gradle.kts", versionName != null)
        assertEquals(versionName, stated)
    }

    @Test
    fun `the bundled policy identifies the published canonical address`() {
        val canonical = Regex("""<link rel="canonical" href="([^"]+)">""")
            .find(published.readText())
            ?.groupValues
            ?.get(1)
        assertEquals(PRIVACY_POLICY_URL, canonical)
    }

    /**
     * The chat as it is built, in one text for its symptoms, mood and medicines: chats rather
     * than "symptom chats", what the chat reads in the words — medicines' names among them — what it
     * keeps of the entries it made, that a chat is on no report for a doctor, and a chat's own entries
     * first among the five things a deletion inside the app does not reach.
     */
    @Test
    fun `the policy describes the chat as it is built`() {
        val text = published.readText().replace(Regex("""\s+"""), " ")
        assertFalse("symptom chat" in text.lowercase())
        assertTrue("<strong>Chats</strong>: everything you write in a chat, word for word" in text)
        // How long the earlier state Undo puts back is kept is said as the chat keeps it: until the chat
        // is over and left, or opened to read, rather than "while a chat is open".
        assertTrue(
            "so Undo can put it back — kept until you leave the chat once it is over, or else until you next open it " +
                "to read." in text,
        )
        assertTrue("a short list of common painkillers and antidepressants built into the app" in text)
        // The words make no entry, but the topics they named are kept in the hidden categories, which a
        // symptom entry carries too, so "Nothing is recorded" would be false.
        assertTrue("No entry is made from those words alone; the topics they named are kept only among" in text)
        assertTrue("for a symptom, in its entry too, and so they travel wherever the chat and that entry do." in text)
        assertFalse("Nothing is recorded from those words alone." in text)
        assertTrue("chats and symptom entries are not on the report." in text)
        assertTrue("when you note it in a chat, also what you picked it was about" in text)
        val deletion = text.substringAfter("Five things a deletion inside the app does not reach:")
            .substringBefore("</ul>")
        assertEquals(5, Regex("<li>").findAll(deletion).count())
        assertTrue(
            deletion.substringAfter("<ul>").trimStart().startsWith(
                "<li><strong>Deleting a chat removes its words and answers, not the entries it made.</strong>",
            ),
        )
    }

    /**
     * The chat's model as it is built: what it reads — the message and where the
     * chat stands, nothing else — what it does with it, that it can be wrong and only ever adds a question
     * about safety, what is kept with the message and so goes wherever the chat goes, never to Health
     * Connect, that it sends nothing and learns nothing from what is written, that what it reads is no
     * decision about the person and that the runtime under it is built without Microsoft's telemetry
     * client. All of it is said of a version that carries one, which the line under the chat's title
     * tells apart, so nothing is claimed of a build without a model, and nothing that a backup or a move
     * to a new phone would make untrue.
     */
    @Test
    fun `the policy describes the chat's model as it is built`() {
        val text = published.readText().replace(Regex("""\s+"""), " ")
        val model = text.substringAfter("<h3>2.2 The chat's model</h3>", "").substringBefore("<h2>")
        assertTrue(model.isNotEmpty())
        assertTrue("Not every version of the app carries one: while one reads your messages, a line under" in model)
        assertTrue("It does not read your journal, your medicines, your profile or anything else" in model)
        assertTrue("never remove, reword or reorder one" in model)
        assertTrue("Nothing it reads answers a question or makes an entry: only your tap does." in model)
        assertTrue("<strong>It can be wrong</strong>" in model)
        assertTrue("into the automatic daily copy if you switch it on, and to a new phone in a move (§8)" in model)
        assertTrue("never written to Health Connect" in model)
        assertTrue("a backup written before that still holds them" in model)
        assertTrue("it sends nothing anywhere" in model)
        assertTrue("never on anything written in the app, and it learns nothing from what you write" in model)
        // A reading travels with the chat, so nothing here says it stays on the phone.
        listOf("never leaves", "stays on the phone", "nothing leaves").forEach { assertFalse(it, it in model) }
        // The chat's own items say where the model's part of them is, and the third-party code runs it.
        assertTrue("where the chat's model read a message, what it made of it (§2.2)" in text)
        assertTrue("and what the model made of a message only with the chat." in text)
        assertTrue("in a version that carries one, the chat's (§2.2)" in text)
        assertTrue("ONNX Runtime, built for this app without Microsoft's telemetry client" in text)
        // No automated decision about the person: the model only chooses, and only a tap answers.
        val decision = "Nor is what the chat's model reads in a message, in a version that carries one, a decision " +
            "about you: it only chooses among the chat's fixed questions and buttons, and only your taps answer " +
            "them (§2.2)."
        assertTrue(decision in text)
    }

    /**
     * The clinics a visit's Place page suggests: its "?" says which places the list holds and credits the
     * open data it names, which leave out the Dubai and Abu Dhabi health authorities, so the policy claims
     * no register is named there.
     */
    @Test
    fun `the policy names no source of the clinics the page does not`() {
        val text = published.readText().replace(Regex("""\s+"""), " ")
        assertFalse("which the page names behind its" in text)
        assertTrue(
            "drawn from official registers and open data on places of care in Czechia, Ukraine and the United " +
                "Arab Emirates (the page's &ldquo;?&rdquo; says which places it holds), and searching it" in text,
        )
    }

    /**
     * §10 tells a person which channel to mute for what, so every channel the code creates is named there
     * by the name Android Settings shows.
     */
    @Test
    fun `the policy names every notification channel the app creates`() {
        val strings = File("src/main/res/values").listFiles()!!.flatMap { file ->
            Regex("""<string name="(\w+)"[^>]*>([^<]+)</string>""").findAll(file.readText()).map {
                it.groupValues[1] to it.groupValues[2]
            }.toList()
        }.toMap()
        val names = File("src/main/java").walk().filter { it.extension == "kt" }.flatMap { file ->
            val source = file.readText()
            Regex("""(?<![A-Za-z])NotificationChannel\(""").findAll(source).flatMap { call ->
                Regex("""R\.string\.(\w+)""").findAll(source.substring(call.range.first, call.range.first + 300))
                    .map { it.groupValues[1] }
            }
        }.toSet()
        val text = published.readText().replace(Regex("""\s+"""), " ").replace('-', ' ').lowercase()

        assertTrue("found only $names", names.size >= 7)
        names.forEach { name ->
            val shown = strings.getValue(name)
            assertTrue("channel $name, \"$shown\", is not in the policy", shown.lowercase() in text)
        }
    }
}
