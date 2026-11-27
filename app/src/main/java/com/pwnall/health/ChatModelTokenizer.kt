package com.pwnall.health

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * The chat reader's tokenizer: SentencePiece Unigram over the pruned XLM-R vocabulary, ported line
 * for line from the Python tokenizer and normaliser the model was trained with. It
 * has to agree with them id for id and offset for offset — the model was trained on what they
 * produce — and [ChatModelTokenizerTest] holds it to every case of tokenizer/tokenizer-golden.json.
 *
 * The raw message goes in, never the chat's own `Message.of`: that normalisation cuts Hindi and
 * Arabic apart. Offsets come back in UTF-16 units of that raw text, the indices a Kotlin String
 * takes, so a span the model marks can be shown as the person's own words.
 *
 * Memory: the 70 925 pieces live in four flat arrays sorted by code point (about 2 MB) rather than
 * a map of strings and every prefix of them, which would cost five to ten times that. The file is
 * parsed once, when the reader loads, and the JSON tree goes with the parse.
 */
internal class ChatModelTokenizer private constructor(
    private val normalizer: ReaderNormalizer,
    private val index: PieceIndex,
    private val scores: FloatArray,
    private val unkScore: Double,
    val bos: Int,
    val eos: Int,
    val unk: Int,
    private val contextTokens: Map<String, Int>,
    val maxPositions: Int,
    val overlap: Int,
) {
    val vocabSize: Int get() = scores.size

    /** [text] as the model's pieces, with every piece's [start, end) in UTF-16 units of [text]. */
    fun encode(text: String): ChatEncoding {
        val raw = RawText.of(text)
        val norm = normalizer.normalize(raw.codePoints)
        val tokens = segment(norm.codePoints, norm.size)
        val count = tokens.count
        val ids = IntArray(count)
        val starts = IntArray(count)
        val ends = IntArray(count)
        val pieces = ArrayList<String>(count)
        for (t in 0 until count) {
            val s = tokens.starts[t]
            val e = tokens.ends[t]
            var start = Int.MAX_VALUE
            var end = -1
            for (k in s until e) {
                if (norm.codePoints[k] != SPACE_MARK) {
                    start = minOf(start, norm.starts[k])
                    end = maxOf(end, norm.ends[k])
                }
            }
            // A piece that is only ▁ stands for a space: the empty span at that space's end.
            if (end < 0) {
                start = norm.ends[e - 1]
                end = start
            }
            ids[t] = tokens.ids[t]
            starts[t] = raw.utf16[start]
            ends[t] = raw.utf16[end]
            pieces += String(norm.codePoints, s, e - s)
        }
        return ChatEncoding(ids, pieces, starts, ends)
    }

    /**
     * `[W=…] [S=…] [T=…]`: where the conversation is. A step the model was not taught by name is
     * `[S=OTHER]`, no step `[S=NONE]`, no thread topic `[T=NONE]` — exactly the context tokens the
     * model was trained with.
     */
    fun contextIds(context: ChatReaderContext): IntArray {
        val step = when (val name = context.step?.name) {
            null -> STEP_NONE
            in UNNAMED_STEPS -> STEP_OTHER
            else -> if ("[S=$name]" in contextTokens) name else STEP_OTHER
        }
        return intArrayOf(
            contextId("[W=${context.where.name}]"),
            contextId("[S=$step]"),
            contextId("[T=${context.topic?.name ?: TOPIC_NONE}]"),
        )
    }

    private fun contextId(token: String): Int =
        requireNotNull(contextTokens[token]) { "the reader's tokenizer has no context token $token" }

    /**
     * The windows [encoding] is read in: `[<s>] [W] [S] [T] pieces… [</s>]`, at most [maxPositions]
     * long. A longer message is read in windows that start every `cap − overlap` pieces until one
     * reaches its end; nothing is ever cut off (a crisis phrase at the end of 500 characters of
     * Chinese is the case this exists for).
     */
    fun windows(encoding: ChatEncoding, context: IntArray): List<ChatWindow> {
        val prefix = 1 + context.size
        val cap = maxPositions - prefix - 1
        val step = cap - overlap
        require(step > 0) { "an overlap of $overlap leaves no room in $maxPositions positions" }
        val out = ArrayList<ChatWindow>()
        var start = 0
        while (true) {
            val end = minOf(start + cap, encoding.size)
            val ids = LongArray(prefix + (end - start) + 1)
            ids[0] = bos.toLong()
            for (c in context.indices) ids[1 + c] = context[c].toLong()
            for (t in start until end) ids[prefix + t - start] = encoding.ids[t].toLong()
            ids[ids.size - 1] = eos.toLong()
            out += ChatWindow(ids, start, end, prefix)
            if (end >= encoding.size) return out
            start += step
        }
    }

    /**
     * Viterbi over the normalised code points, as SentencePiece and `tokenizers` do it: every start
     * in order, every piece starting there shortest first, a candidate taking an end only when its
     * score is strictly higher (the first found wins a tie). Scores are float32 widened to double and
     * summed in double. Where no one-character piece starts, a one-character `<unk>` is offered, and
     * consecutive unknowns fuse.
     */
    private fun segment(norm: IntArray, n: Int): Segments {
        val bestScore = DoubleArray(n + 1)
        val bestStart = IntArray(n + 1) { -1 }
        val bestId = IntArray(n + 1)
        for (i in 0 until n) {
            val base = bestScore[i]
            var single = false
            index.forEachPieceAt(norm, i, n) { length, id ->
                val candidate = base + scores[id].toDouble()
                val e = i + length
                if (bestStart[e] == -1 || candidate > bestScore[e]) {
                    bestScore[e] = candidate
                    bestStart[e] = i
                    bestId[e] = id
                }
                if (length == 1) single = true
            }
            if (!single) {
                val candidate = base + unkScore
                if (bestStart[i + 1] == -1 || candidate > bestScore[i + 1]) {
                    bestScore[i + 1] = candidate
                    bestStart[i + 1] = i
                    bestId[i + 1] = unk
                }
            }
        }
        // Back from the end; the tokens come out last first.
        val starts = IntArray(n)
        val ends = IntArray(n)
        val ids = IntArray(n)
        var count = 0
        var e = n
        while (e > 0) {
            val s = bestStart[e]
            if (bestId[e] == unk && count > 0 && ids[count - 1] == unk) {
                starts[count - 1] = s // the unknown before fuses with the unknown after it
            } else {
                starts[count] = s
                ends[count] = e
                ids[count] = bestId[e]
                count++
            }
            e = s
        }
        starts.reverse(0, count)
        ends.reverse(0, count)
        ids.reverse(0, count)
        return Segments(starts, ends, ids, count)
    }

    companion object {
        const val FORMAT = "chat-reader-tokenizer"
        const val FORMAT_VERSION = 1
        private const val STEP_OTHER = "OTHER"
        private const val STEP_NONE = "NONE"
        private const val TOPIC_NONE = "NONE"
        private val UNNAMED_STEPS = setOf(STEP_OTHER, STEP_NONE)

        /** reader-tokenizer.json, as the reader's tooling writes it. */
        fun parse(json: String): ChatModelTokenizer {
            val root = JSONObject(json)
            require(root.getString("format") == FORMAT && root.getInt("version") == FORMAT_VERSION) {
                "not a chat-reader tokenizer file"
            }
            val pieces = root.getJSONArray("pieces")
            val scoresJson = root.getJSONArray("scores")
            require(pieces.length() == scoresJson.length()) { "pieces and scores differ in length" }
            // The file's decimals are read as double and rounded to float32: what SentencePiece holds.
            val scores = FloatArray(scoresJson.length()) { scoresJson.getDouble(it).toFloat() }
            val specials = root.getJSONObject("specials")
            val context = root.getJSONObject("context_tokens")
            val firstContext = context.getInt("first_id")
            val contextNames = context.getJSONArray("tokens")
            val contextTokens = (0 until contextNames.length()).associate { i ->
                contextNames.getString(i) to firstContext + i
            }
            val windows = root.getJSONObject("windows")
            return ChatModelTokenizer(
                normalizer = ReaderNormalizer.parse(root.getJSONObject("normaliser")),
                index = PieceIndex.build(pieces, root.getInt("matchable_from")),
                scores = scores,
                unkScore = root.getDouble("unk_score"),
                // No <pad>: the phone reads one window at a time and pads nothing.
                bos = specials.getInt("bos"),
                eos = specials.getInt("eos"),
                unk = specials.getInt("unk"),
                contextTokens = contextTokens,
                maxPositions = windows.getInt("max_positions"),
                overlap = windows.getInt("overlap"),
            )
        }
    }
}

/**
 * One message in the model's pieces. [starts] and [ends] are UTF-16 offsets into the raw message:
 * a piece's leading ▁ is not part of its span, and a piece that is only ▁ has the empty span at the
 * end of the space it stands for.
 */
internal class ChatEncoding(
    val ids: IntArray,
    val pieces: List<String>,
    val starts: IntArray,
    val ends: IntArray,
) {
    val size: Int get() = ids.size
}

/** One pass of the encoder: its input ids, and which of the message's pieces it holds ([start], [end]). */
internal class ChatWindow(val inputIds: LongArray, val start: Int, val end: Int, val prefix: Int) {
    /** Where the message's piece [token] sits in this window. */
    fun positionOf(token: Int): Int = prefix + token - start
}

/**
 * The window whose outputs a piece takes: the one where it sits farthest from an edge, the earlier
 * window on a tie.
 */
internal fun ownerWindow(windows: List<ChatWindow>, token: Int): Int {
    var best = -1
    var bestMargin = -1
    for ((w, window) in windows.withIndex()) {
        if (token >= window.start && token < window.end) {
            val margin = minOf(token - window.start, window.end - 1 - token)
            if (margin > bestMargin) {
                best = w
                bestMargin = margin
            }
        }
    }
    if (best < 0) throw IndexOutOfBoundsException("piece $token is in no window")
    return best
}

/** ▁, which stands for a space in the pieces and in the normalised text. */
private const val SPACE_MARK = 0x2581
private const val REPLACEMENT = 0xFFFD
private const val ASCII_END = 0x80

/** A raw message as code points (a lone surrogate is U+FFFD), with the UTF-16 offset of every boundary. */
private class RawText(val codePoints: IntArray, val utf16: IntArray) {
    companion object {
        fun of(text: String): RawText {
            val codePoints = IntArray(text.length)
            val utf16 = IntArray(text.length + 1)
            var n = 0
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                utf16[n] = i
                if (ch.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                    codePoints[n++] = Character.toCodePoint(ch, text[i + 1])
                    i += 2
                } else {
                    codePoints[n++] = if (ch.isSurrogate()) REPLACEMENT else ch.code
                    i += 1
                }
            }
            utf16[n] = text.length
            return RawText(codePoints.copyOf(n), utf16.copyOf(n + 1))
        }
    }
}

/** The normalised text: code points with ▁ for spaces, each carrying its raw code-point span. */
private class NormalizedText(val codePoints: IntArray, val starts: IntArray, val ends: IntArray, val size: Int)

/**
 * XLM-R's normaliser, explicit (id `xlmr-nmt-nfkc-units-v2`).
 *
 * SentencePiece's "nmt_nfkc" character map is NFKC everywhere except `overrides` (controls removed;
 * tab, newlines, the zero-width space and joiners, the BOM, U+FFFD, ▁… made a space) and `keep`
 * (U+FF5E and characters newer than the map, left alone); it composes a letter with its marks but
 * never reorders marks typed out of canonical order. Bare NFKC is not the same: it keeps U+200D,
 * which inside an emoji the map turns into a space.
 *
 * Units, left to right: an override or kept code point is a unit of its own; any other code point
 * joins the open unit when it composes with it without moving — NFKD(u + c) = NFKD(u) + NFKD(c) and
 * NFKC(u + c) ≠ NFKC(u) + NFKC(c) — and the unit is under `maxUnit` code points. A unit's text is
 * NFKC of it. Then spaces: dropped at the start, after a space and at the end, the rest written as
 * ▁, and one ▁ in front of anything left. No lower-casing: XLM-R is cased.
 *
 * The phone's ICU may be a Unicode version or two behind the tables the tokenizer was built on: NFKC of
 * an assigned character never changes, so only a composite newer than the phone could differ, and
 * none is in the scripts of the app's sixteen languages.
 */
private class ReaderNormalizer(
    private val overrides: Map<Int, String>,
    private val keep: IntArray,
    private val maxUnit: Int,
) {
    fun normalize(raw: IntArray): NormalizedText {
        val out = UnitWriter(raw.size)
        val unit = OpenUnit(maxUnit)
        for (i in raw.indices) {
            val cp = raw[i]
            val replacement = overrides[cp]
            val c = replacement ?: String(Character.toChars(cp))
            if (replacement != null || keep.binarySearch(cp) >= 0) {
                unit.close(out)
                out.unit(i, i + 1, c)
            } else {
                // ASCII is its own NFKC and NFKD; the normaliser is not asked about it at all.
                val ascii = cp < ASCII_END
                val cNorm = if (ascii) c else Normalizer.normalize(c, Normalizer.Form.NFKC)
                val cDec = if (ascii) c else Normalizer.normalize(c, Normalizer.Form.NFKD)
                if (ascii || !unit.join(c, cNorm, cDec)) {
                    unit.close(out)
                    unit.open(i, c, cNorm, cDec)
                }
            }
        }
        unit.close(out)
        return out.finish()
    }

    companion object {
        const val ID = "xlmr-nmt-nfkc-units-v2"

        fun parse(spec: JSONObject): ReaderNormalizer {
            require(spec.getString("id") == ID) { "unknown normaliser ${spec.getString("id")}" }
            val overridesJson = spec.getJSONObject("overrides")
            val overrides = overridesJson.keys().asSequence().associate { it.toInt(HEX) to overridesJson.getString(it) }
            val keepJson: JSONArray = spec.getJSONArray("keep")
            val keep = IntArray(keepJson.length()) { keepJson.getString(it).toInt(HEX) }.apply { sort() }
            return ReaderNormalizer(overrides, keep, spec.getInt("max_unit"))
        }

        private const val HEX = 16
    }
}

/** The ordinary unit being built: its raw code points, their NFKC (the unit's text) and NFKD. */
private class OpenUnit(private val maxUnit: Int) {
    private var start = -1
    private val raw = StringBuilder()
    private var length = 0
    private var norm = ""
    private var dec = ""

    /**
     * Whether the code point [c] (NFKC [cNorm], NFKD [cDec]) composes onto the open unit without
     * moving, taking it if so. Never called for ASCII: no canonical composite ends in an ASCII
     * character (ChatModelTokenizerTest proves it on the tables of the JVM it runs on), so ASCII
     * never joins.
     */
    fun join(c: String, cNorm: String, cDec: String): Boolean {
        if (start < 0 || length >= maxUnit) return false
        val joinedRaw = raw.toString() + c
        val joinedDec = Normalizer.normalize(joinedRaw, Normalizer.Form.NFKD)
        val joined = if (joinedDec == dec + cDec) Normalizer.normalize(joinedRaw, Normalizer.Form.NFKC) else null
        if (joined != null && joined != norm + cNorm) {
            raw.append(c)
            length++
            norm = joined
            dec = joinedDec
            return true
        }
        return false
    }

    fun open(at: Int, c: String, cNorm: String, cDec: String) {
        start = at
        raw.setLength(0)
        raw.append(c)
        length = 1
        norm = cNorm
        dec = cDec
    }

    fun close(out: UnitWriter) {
        if (start >= 0) out.unit(start, start + length, norm)
        start = -1
    }
}

/** The Viterbi path: every token's [start, end) in normalised code points and its id. */
private class Segments(val starts: IntArray, val ends: IntArray, val ids: IntArray, val count: Int)

/** Writes units' texts out as normalised code points, applying the space rules as they arrive. */
private class UnitWriter(capacity: Int) {
    private var codePoints = IntArray(maxOf(capacity * 2, 8))
    private var starts = IntArray(codePoints.size)
    private var ends = IntArray(codePoints.size)
    private var size = 0
    private var afterSpace = true // drops spaces at the start

    fun unit(start: Int, end: Int, text: String) {
        var j = 0
        while (j < text.length) {
            val cp = text.codePointAt(j)
            j += Character.charCount(cp)
            if (cp == ' '.code) {
                if (!afterSpace) add(SPACE_MARK, start, end)
                afterSpace = true
            } else {
                add(cp, start, end)
                afterSpace = false
            }
        }
    }

    private fun add(cp: Int, start: Int, end: Int) {
        if (size == codePoints.size) {
            codePoints = codePoints.copyOf(size * 2)
            starts = starts.copyOf(size * 2)
            ends = ends.copyOf(size * 2)
        }
        codePoints[size] = cp
        starts[size] = start
        ends[size] = end
        size++
    }

    fun finish(): NormalizedText {
        while (size > 0 && codePoints[size - 1] == SPACE_MARK) size--
        if (size == 0) return NormalizedText(IntArray(0), IntArray(0), IntArray(0), 0)
        // SentencePiece's dummy prefix: one ▁ in front, the empty span where the first unit kept starts.
        val first = starts[0]
        return NormalizedText(
            codePoints = intArrayOf(SPACE_MARK) + codePoints.copyOf(size),
            starts = intArrayOf(first) + starts.copyOf(size),
            ends = intArrayOf(first) + ends.copyOf(size),
            size = size + 1,
        )
    }
}

/**
 * The matchable pieces (special and context tokens never are: "<s>" typed in a message is three
 * characters), sorted by code point and concatenated, so a prefix walk is a narrowing range found by
 * binary search — a trie without the trie's per-node objects.
 */
private class PieceIndex(
    private val codePoints: IntArray,
    private val offsets: IntArray,
    private val ids: IntArray,
) {
    private val maxLength: Int = (0 until ids.size).maxOfOrNull(::lengthOf) ?: 0

    private fun lengthOf(k: Int): Int = offsets[k + 1] - offsets[k]

    private fun at(k: Int, j: Int): Int = codePoints[offsets[k] + j]

    /** Every piece that [text] holds at [from], shortest first: (length, id). */
    inline fun forEachPieceAt(text: IntArray, from: Int, end: Int, visit: (Int, Int) -> Unit) {
        var lo = 0
        var hi = ids.size
        var length = 0
        val longest = minOf(maxLength, end - from)
        while (length < longest) {
            // Every piece in [lo, hi) starts with text[from, from + length); the one that is exactly
            // that prefix, if any, sorts first and is no longer a candidate.
            if (lo < hi && lengthOf(lo) == length) lo++
            val c = text[from + length]
            var a = lo
            var b = hi
            while (a < b) {
                val m = (a + b) ushr 1
                if (at(m, length) < c) a = m + 1 else b = m
            }
            val first = a
            b = hi
            while (a < b) {
                val m = (a + b) ushr 1
                if (at(m, length) <= c) a = m + 1 else b = m
            }
            if (first == a) return
            lo = first
            hi = a
            length++
            if (lengthOf(lo) == length) visit(length, ids[lo])
        }
    }

    companion object {
        fun build(pieces: JSONArray, matchableFrom: Int): PieceIndex {
            val texts = Array(pieces.length() - matchableFrom) { k ->
                pieces.getString(matchableFrom + k).codePoints().toArray()
            }
            val order = texts.indices.sortedWith { x, y -> compareCodePoints(texts[x], texts[y]) }
            for (k in 1 until order.size) {
                require(compareCodePoints(texts[order[k - 1]], texts[order[k]]) != 0) {
                    "duplicate piece ${String(texts[order[k]], 0, texts[order[k]].size)}"
                }
            }
            val offsets = IntArray(order.size + 1)
            for ((k, piece) in order.withIndex()) offsets[k + 1] = offsets[k] + texts[piece].size
            val codePoints = IntArray(offsets[order.size])
            for ((k, piece) in order.withIndex()) texts[piece].copyInto(codePoints, offsets[k])
            return PieceIndex(codePoints, offsets, IntArray(order.size) { matchableFrom + order[it] })
        }

        private fun compareCodePoints(x: IntArray, y: IntArray): Int {
            for (j in 0 until minOf(x.size, y.size)) {
                if (x[j] != y[j]) return x[j].compareTo(y[j])
            }
            return x.size.compareTo(y.size)
        }
    }
}
