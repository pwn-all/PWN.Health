package com.pwnall.health

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Html
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a document bundled with the app without a browser, WebView, network, or automatic link
 * handling: the policy, or — asked for with [EXTRA_LICENCES] — the licence texts of what the build is
 * made of. One window for both, because both are the same thing to the person reading: words that
 * ship inside the app and are read on a page of their own. The activity is not exported, so the
 * extra is the app's own word.
 */
class PrivacyPolicyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val licences = intent.getBooleanExtra(EXTRA_LICENCES, false)
        setContent {
            PwnFitTheme(darkTheme = isSystemInDarkTheme()) {
                if (licences) LicenceTextsPage(onClose = ::finish) else PrivacyPolicyPage(onClose = ::finish)
            }
        }
    }
}

private const val EXTRA_LICENCES = "licences"

internal fun Context.showPrivacyPolicy() {
    startActivity(Intent(this, PrivacyPolicyActivity::class.java))
}

internal fun Context.showLicenceTexts() {
    startActivity(Intent(this, PrivacyPolicyActivity::class.java).putExtra(EXTRA_LICENCES, true))
}

/** How many lines of a licence text one row of the list lays out. */
private const val LICENCE_RUN_LINES = 12

/**
 * A licence text cut into runs of lines, which set one under another with nothing between them are
 * the text again, line for line. The longest of these files is six thousand lines, and a single
 * Text that long is measured whole before its first line is drawn; a list measures only the runs
 * on screen. The lines are kept as the file breaks them: these are other people's words, to be
 * reproduced and not reflowed.
 */
internal fun licenceTextRuns(text: String): List<String> =
    text.trimEnd().lines().chunked(LICENCE_RUN_LINES) { it.joinToString("\n") }

/** What one stretch of the policy is, which decides how it is set and whether it is a heading. */
internal enum class PolicyBlockKind { TITLE, SECTION, SUBSECTION, PARAGRAPH, ITEM, TABLE_HEAD, TABLE_ROW }

/** One stretch of the policy: [cells] holds its HTML, one entry per column for a table row. */
internal data class PolicyBlock(val kind: PolicyBlockKind, val cells: List<String>)

private val POLICY_BLOCK = Regex("""<(h[1-3]|p|li|tr|div)\b[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
private val POLICY_CELL = Regex("""<(t[hd])\b[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)

/**
 * The policy's body cut into the blocks it is written in — the title, the numbered sections and
 * their subsections, paragraphs, list items and the rows of the permissions table — in reading
 * order. Each keeps its own inner HTML for [Html.fromHtml] to turn into words. A block inside
 * another, a paragraph in a list item, stays with the outer one.
 */
internal fun policyBlocks(html: String): List<PolicyBlock> {
    val body = html.substringAfter("<body>").substringBefore("</body>")
    return POLICY_BLOCK.findAll(body).map { match ->
        val inner = match.groupValues[2]
        when (match.groupValues[1]) {
            "h1" -> PolicyBlock(PolicyBlockKind.TITLE, listOf(inner))

            "h2" -> PolicyBlock(PolicyBlockKind.SECTION, listOf(inner))

            "h3" -> PolicyBlock(PolicyBlockKind.SUBSECTION, listOf(inner))

            "li" -> PolicyBlock(PolicyBlockKind.ITEM, listOf(inner))

            "tr" -> {
                val cells = POLICY_CELL.findAll(inner).toList()
                val head = cells.isNotEmpty() && cells.all { it.groupValues[1] == "th" }
                PolicyBlock(
                    if (head) PolicyBlockKind.TABLE_HEAD else PolicyBlockKind.TABLE_ROW,
                    cells.map { it.groupValues[2] },
                )
            }

            else -> PolicyBlock(PolicyBlockKind.PARAGRAPH, listOf(inner))
        }
    }.toList()
}

/** A block with its HTML already turned into words, ready to be set. */
private data class PolicyText(val kind: PolicyBlockKind, val cells: List<String>)

@Composable
private fun PrivacyPolicyPage(onClose: () -> Unit) {
    val context = LocalContext.current
    val policy by produceState(emptyList<PolicyText>()) {
        value = withContext(Dispatchers.IO) {
            val html = context.assets.open("privacy-policy.html").bufferedReader().use { it.readText() }
            // Keep the published words, their headings and their paragraph boundaries; discard
            // website navigation, styles and clickable URL spans. The whole document remains
            // selectable for copying.
            policyBlocks(html).map { block ->
                PolicyText(
                    block.kind,
                    block.cells.map { Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT).toString().trim() },
                )
            }
        }
    }
    Surface(Modifier.fillMaxSize()) {
        // The system bars and the display cutout together, the set Scaffold gives the app's own
        // pages: in landscape a camera cutout on the side edge is not part of the system bars.
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
            PwnTopBar(stringResource(R.string.about_privacy_policy), onClose)
            // The scroll spans the whole width, so a wide window scrolls from anywhere on it; the
            // text inside is held to a readable measure and centred.
            SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Column(
                    Modifier.fillMaxWidth()
                        .wrapContentWidth(Alignment.CenterHorizontally)
                        .widthIn(max = PwnDimensions.maxPaneWidth)
                        .fillMaxWidth()
                        .padding(PwnDimensions.cardPadding),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    policy.forEach { PolicyBlockText(it) }
                }
            }
        }
    }
}

/**
 * The licence texts of [LICENCE_TEXTS], one after another, each under the name it stands for. A lazy
 * list rather than the policy's scrolling column — see [licenceTextRuns] — and not selectable: a
 * selection over a list only reaches the rows on screen, and nobody copies a licence out of an app.
 */
@Composable
private fun LicenceTextsPage(onClose: () -> Unit) {
    val context = LocalContext.current
    val texts by produceState(emptyList<Pair<String, List<String>>>()) {
        value = withContext(Dispatchers.IO) {
            LICENCE_TEXTS.map { text ->
                text.title to licenceTextRuns(context.assets.open(text.asset).bufferedReader().use { it.readText() })
            }
        }
    }
    val typography = MaterialTheme.typography
    // The policy's measure, held row by row: a lazy list has no one column to hold and centre.
    val measure = Modifier.widthIn(max = PwnDimensions.maxPaneWidth).fillMaxWidth()
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
            PwnTopBar(stringResource(R.string.about_licence_texts), onClose)
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(PwnDimensions.cardPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                texts.forEach { (title, runs) ->
                    item {
                        Text(
                            title,
                            measure.padding(top = 12.dp, bottom = 8.dp).semantics { heading() },
                            style = typography.titleMedium,
                        )
                    }
                    items(runs) { Text(it, measure, style = typography.bodySmall) }
                }
            }
        }
    }
}

/**
 * One block, set in the type role its kind calls for. The title, the sections and the subsections
 * are headings to a screen reader as well as to the eye, so a person can jump from one to the next
 * rather than hear the whole document as a single paragraph.
 */
@Composable
private fun PolicyBlockText(block: PolicyText) {
    val typography = MaterialTheme.typography
    val heading = Modifier.semantics { heading() }
    val first = block.cells.firstOrNull().orEmpty()
    when (block.kind) {
        PolicyBlockKind.TITLE -> Text(first, heading, style = typography.headlineSmall)

        PolicyBlockKind.SECTION -> Text(first, heading.padding(top = 12.dp), style = typography.titleLarge)

        PolicyBlockKind.SUBSECTION -> Text(first, heading.padding(top = 4.dp), style = typography.titleMedium)

        PolicyBlockKind.PARAGRAPH -> Text(first, style = typography.bodyLarge)

        PolicyBlockKind.ITEM -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The dot is for the eye. A screen reader would stop on it before every item and say
            // "bullet"; the item's own words are all it needs.
            Text("•", Modifier.clearAndSetSemantics { }, style = typography.bodyLarge)
            Text(first, Modifier.weight(1f), style = typography.bodyLarge)
        }

        // The table's columns are read down rather than across: a phone has no room for two
        // columns of prose side by side, so each row is its name over what it is used for.
        PolicyBlockKind.TABLE_HEAD -> Text(
            block.cells.joinToString(" · "),
            style = typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PolicyBlockKind.TABLE_ROW -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(first, style = typography.titleSmall)
            block.cells.drop(1).forEach { Text(it, style = typography.bodyLarge) }
        }
    }
}
