package com.pwnall.health

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.graphics.scale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * The classifiers, entirely on the device.
 *
 * One network ([PhotoModel], `photo_v3.onnx`) reads the photograph once for the two answers the sheet
 * shows side by side ([photoGuesses]): which of 2 023 dishes it is — Google's AIY food model's list
 * (Apache 2.0), including the ones a Russian kitchen actually produces, «борщ», «щи», «окрошка», «плов»,
 * «сырники» — and which plain foods are on it (an apple, an egg, a chicken breast, which no dish name covers).
 * A soup the network is less sure of than the tomato in it is still offered, in its own row. On 231
 * held-out photographs the sheet opens on the right answer 57 % of the time, and the right answer is
 * among its chips 76 % of the time. Slovak and Czech dishes are its weak side.
 *
 * It ships inside the APK. This app holds no INTERNET permission ([NoNetworkPermissionTest] enforces
 * it), so a model that downloads itself or checks a licence over the network cannot be used —
 * which also rules out ML Kit, whose image labeller arrives with Google's telemetry transport.
 *
 * The tensor's side is read from the model rather than written down here: a mismatch would be a
 * silent wrong answer. The size the photograph is decoded to is written down ([DECODE_SIDE]) and
 * has to stay at or above that side, or the model is shown a photograph enlarged from fewer pixels.
 */
internal object FoodRecognizer {
    /** The photo model over one photograph. Blocking and CPU-bound — call it off the main thread. */
    fun scores(context: Context, photo: Uri, onStep: (DishStep) -> Unit = {}): PhotoScores {
        onStep(DishStep.Decoding)
        val photograph = decode(context, photo)
        onStep(DishStep.Looking(dishes = true))
        val scores = try {
            photoModel.borrow({ PhotoModel(session(context, PhotoModel.ASSET)) }) { classify(it, photograph) }
        } finally {
            photograph.recycle()
        }
        check(scores.dishes.size == FOOD_LABELS.size) {
            "photo model: ${scores.dishes.size} dish outputs, ${FOOD_LABELS.size} labels"
        }
        check(scores.products.size == IMAGENET_LABELS.size) {
            "photo model: ${scores.products.size} product outputs, ${IMAGENET_LABELS.size} labels"
        }
        return scores
    }

    private fun classify(model: PhotoModel, photograph: Bitmap): PhotoScores {
        val scaled = photograph.scale(model.side, model.side)
        return try {
            model.scores(input(scaled, model.side))
        } finally {
            if (scaled !== photograph) scaled.recycle()
        }
    }

    /**
     * The ONNX session outlives a photograph by a minute, as the label reader's do: its weights are
     * mapped from the APK, the next dish reuses the session, and its native memory goes once the
     * photographing is over.
     */
    private val photoModel = LingeringModel<PhotoModel>()

    private fun session(context: Context, asset: String) =
        OrtSession.SessionOptions().use { options ->
            OrtEnvironment.getEnvironment().createSession(context.mapModelAsset(asset), options)
        }

    /**
     * [ImageDecoder] is the reason this is four lines: it applies the EXIF rotation a camera writes
     * (a sideways plate classifies as badly as a wrong one) and scales during decode, so a 12 MP
     * JPEG never becomes a 48 MB bitmap. The whole photograph is squashed to the model's square,
     * as it was trained; a centre crop loses the side of the plate that names the dish.
     */
    private fun decode(context: Context, photo: Uri): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, photo)) { decoder, _, _ ->
            decoder.setTargetSize(DECODE_SIDE, DECODE_SIDE)
            // A hardware bitmap has no pixels this process can read back.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    /** The model takes the raw RGB bytes; the graph's own normalisation does the rest. */
    private fun input(bitmap: Bitmap, side: Int): ByteBuffer {
        val pixels = IntArray(side * side)
        bitmap.getPixels(pixels, 0, side, 0, 0, side, side)
        val buffer = ByteBuffer.allocateDirect(side * side * CHANNELS)
        pixels.forEach { pixel ->
            buffer.put((pixel shr 16).toByte())
            buffer.put((pixel shr 8).toByte())
            buffer.put(pixel.toByte())
        }
        buffer.rewind()
        return buffer
    }

    /** What the photograph is decoded to; [classify] then scales it to the side the model names. */
    private const val DECODE_SIDE = 224
    private const val CHANNELS = 3
}

/** The build stores these assets uncompressed so their bytes need no copy into the Java heap. */
internal fun Context.mapModelAsset(asset: String): ByteBuffer =
    assets.openFd(asset).use { descriptor ->
        FileInputStream(descriptor.fileDescriptor).use { stream ->
            stream.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
        }
    }

/**
 * A model kept open [lingerMillis] after its last use rather than for the process, as the chat's
 * reader is ([ChatModelReaders]): a second scan within the minute reuses the sessions, and a phone
 * that scanned once gets their native memory back. A use in progress holds the model, so it is never
 * closed under a run.
 */
internal class LingeringModel<T : AutoCloseable>(private val lingerMillis: Long = LINGER_MILLIS) {
    private val timers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var model: T? = null
    private var users = 0
    private var closing: Job? = null

    /** [block] over the model, opened by [open] when none is held. */
    fun <R> borrow(open: () -> T, block: (T) -> R): R {
        val held = take(open)
        try {
            return block(held)
        } finally {
            give()
        }
    }

    @Synchronized
    private fun take(open: () -> T): T {
        closing?.cancel()
        closing = null
        return (model ?: open().also { model = it }).also { users++ }
    }

    @Synchronized
    private fun give() {
        users--
        if (users == 0) {
            closing = timers.launch {
                delay(lingerMillis)
                closeUnused()
            }
        }
    }

    @Synchronized
    private fun closeUnused() {
        if (users > 0) return
        // This runs on the timers' scope, where an exception has no handler and takes the process
        // down with it. A session that would not close is native memory lost, not a crash owed to
        // somebody who finished scanning a minute ago — and it is not handed to the next scan either.
        runCatching { model?.close() }
        model = null
    }

    companion object {
        /** Long enough for a retake or the next dish, short enough not to hold the models after the scanning. */
        const val LINGER_MILLIS = 60_000L
    }
}
