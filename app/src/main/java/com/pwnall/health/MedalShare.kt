package com.pwnall.health

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.Locale

/** The card is a 4:5 portrait: the shape Telegram, Signal and Instagram all show whole. */
private const val CARD_WIDTH = 1080
private const val CARD_HEIGHT = 1350

/** The widest a line may run before it is shrunk, leaving the frame a clear margin either side. */
private const val TEXT_WIDTH = 860f

// The app's light palette by value. None of this runs inside a composition, so there is no theme to
// ask; the numbers are PwnCanvas, PwnBorder, PwnInk and PwnMuted as PwnDesign defines them, and the
// wash behind the disc is the same indigo the rest of the app accents with, fading to nothing.
//
// Copying a palette by value means copying it again when it moves. CARD_MUTED is PwnMutedRaw's
// #5F6A7D, which measures 5.23:1 on this card's #F9FAFC ground; the two grey lines need 4.5:1,
// since 52px and 38px on a 1080px-wide card are 17.3dp and 12.7dp at phone width and so claim no
// large-text exemption. A paler grey such as #69758A measures 4.46:1 there and falls under it.
private val CARD_GROUND = 0xFFF9FAFC.toInt()
private val CARD_FRAME = 0xFFE7EBF0.toInt()
private val CARD_INK = 0xFF0B1B2E.toInt()
private val CARD_MUTED = 0xFF5F6A7D.toInt()
private const val HALO_CORE = 0x223E6FE0
private const val HALO_EDGE = 0x003E6FE0

/**
 * A won medal, as something that can be posted: the card is drawn, written to the cache, and offered
 * to whatever the person picks out of the chooser.
 *
 * Handed a sentence about a step count, a chat app shows a sentence about a step count. The disc is
 * the part worth sending, so what leaves here is a picture — the same art the profile draws, large,
 * over the rung it stands for and the day it fell. It travels as a `content://` URI through the
 * FileProvider the camera's shots already go to the camera app by, out of the cache's shared folder
 * ([sharedCacheDir]) the card is written into; the provider is not exported, and what the chooser is
 * granted is this one URI and nothing beside it. Nothing is uploaded and nothing is fetched: the app
 * holds no INTERNET permission at all, and where the picture goes next is between the person and the
 * app they chose.
 *
 * Only a dated medal gets this far. [earnedOn] is the day the running total crossed [threshold], and
 * a caller holding no such day does not offer the button. Drawing a megapixel and deflating it is a
 * tenth of a second that has no business on the frame clock, so it happens off the main thread and
 * only the chooser is started back on it; a card that cannot be written is logged and dropped,
 * because failing to share a picture is not worth taking the screen down for.
 */
internal suspend fun shareMedal(context: Context, threshold: Long, art: Int, earnedOn: String) {
    val card = withContext(Dispatchers.IO) {
        runCatching { writeMedalCard(context, threshold, art, earnedOn) }
            .onFailure { Log.w("MedalShare", "the medal card could not be written", it) }
            .getOrNull()
    } ?: return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", card)
    val title = context.getString(R.string.achievement_share_title)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        // The clip carries the same URI a second time: it is what the chooser reads to draw the
        // thumbnail over the list of apps, and a card offered without one is offered blind.
        clipData = ClipData.newUri(context.contentResolver, title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, title))
}

/**
 * The card under the medal's own name, so a chat that shows a file name shows `medal-1M`.
 *
 * One file per rung, overwritten every time that rung is sent: the cache is exactly where a picture
 * that has already left belongs, and the whole ladder is five of them.
 */
private fun writeMedalCard(context: Context, threshold: Long, art: Int, earnedOn: String): File {
    val card = medalCard(context, threshold, art, earnedOn)
    val file = File(sharedCacheDir(context), "medal-${badgeLabel(threshold)}.png")
    // PNG ignores the quality figure; flat colour and one drawing compress to well under a megabyte.
    file.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
    card.recycle()
    return file
}

/**
 * The picture itself: a wash, a frame, the disc, and the lines that say what it is.
 *
 * Plain [android.graphics] rather than Compose, on purpose. A shared card has no screen to adapt
 * to — it is one fixed size in one fixed light — so the medal posted from a phone in dark theme is
 * the same medal posted from a phone in daylight.
 */
private fun medalCard(context: Context, threshold: Long, art: Int, earnedOn: String): Bitmap {
    val card = createBitmap(CARD_WIDTH, CARD_HEIGHT)
    val canvas = Canvas(card)
    canvas.drawColor(CARD_GROUND)
    val centre = CARD_WIDTH / 2f
    val discCentre = 545f
    val discSide = 620f

    // The ground is very nearly white, and a medal dropped straight onto it floats. A wash of the
    // app's indigo sits it down, and a hairline frame gives the picture an edge of its own for the
    // chat bubble — which is white too — to end against.
    val brush = Paint(Paint.ANTI_ALIAS_FLAG)
    brush.shader = RadialGradient(centre, discCentre, 430f, HALO_CORE, HALO_EDGE, Shader.TileMode.CLAMP)
    canvas.drawCircle(centre, discCentre, 430f, brush)
    brush.shader = null
    brush.style = Paint.Style.STROKE
    brush.strokeWidth = 4f
    brush.color = CARD_FRAME
    canvas.drawRoundRect(RectF(40f, 40f, CARD_WIDTH - 40f, CARD_HEIGHT - 40f), 64f, 64f, brush)

    // The art is a 264 px disc and this card wants it two and a half times that; a bitmap drawable
    // is drawn filtered, so soft is what upscaling costs rather than the staircase on every edge.
    // Taken as a drawable, not decoded: the cup a rung without a disc of its own falls back to is a
    // vector, which the bitmap decoder answers with null — and the card would then be dropped in silence.
    // Resolved in the day theme, the only light this card is ever drawn in.
    val daylight = Configuration(context.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
    }
    val disc = checkNotNull(context.createConfigurationContext(daylight).getDrawable(art)) { "no drawable $art" }
    disc.setBounds(
        (centre - discSide / 2f).toInt(),
        (discCentre - discSide / 2f).toInt(),
        (centre + discSide / 2f).toInt(),
        (discCentre + discSide / 2f).toInt(),
    )
    disc.draw(canvas)

    val headline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CARD_INK
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    val note = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CARD_MUTED
        textAlign = Paint.Align.CENTER
        typeface = Typeface.SANS_SERIF
    }
    canvas.centred(context.getString(R.string.achievement_year_value, threshold.formatted()), headline, 1030f, 96f)
    canvas.centred(context.getString(R.string.achievement_earned_value, spokenDay(earnedOn)), note, 1132f, 52f)
    // The app signs the card quietly at the foot of it: whoever it reaches has no other way of
    // knowing which app drew the thing they are looking at.
    note.letterSpacing = 0.18f
    canvas.centred(context.getString(R.string.app_name), note, 1252f, 38f)
    return card
}

/**
 * One line centred on the card, shrunk if the language it is written in runs longer than the English
 * it was laid out in. A glyph's width scales with the point size, so the ratio of the two widths is
 * the answer outright and there is nothing to iterate.
 */
private fun Canvas.centred(text: String, paint: Paint, baseline: Float, size: Float) {
    paint.textSize = size
    val measured = paint.measureText(text)
    if (measured > TEXT_WIDTH) paint.textSize = size * TEXT_WIDTH / measured
    drawText(text, CARD_WIDTH / 2f, baseline, paint)
}

/**
 * The stored day as somebody would say it out loud. The dialog behind this card shows the ISO date
 * because dates in a list have to sort; a picture has nothing to sort against, so it gets the name
 * of the month. A day the parser will not take is printed exactly as it was stored rather than
 * guessed at.
 */
private fun spokenDay(day: String): String = runCatching {
    LocalDate.parse(day).format(localizedDatePattern(Locale.getDefault(), "yMMMMd", "d MMMM yyyy"))
}.getOrDefault(day)
