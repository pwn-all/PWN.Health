package com.pwnall.health

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.ByteBuffer

/**
 * The photo model: one network, three answers about one photograph, from one pass.
 *
 * The photo model's one network, a PE-Core-S16 with three heads:
 * - `dish_probs`: one probability per name in [FOOD_LABELS], index 0 the background, summing to one. These are
 *   the 2 023 dishes of Google's AIY food model.
 * - `product_probs`: one independent probability per food in [IMAGENET_LABELS]. A plate can hold rice *and*
 *   chicken.
 * - `dish_gate`: how likely the photograph is a dish rather than plain foods. The sheet does not read it: it shows
 *   the dish and the product answers side by side ([photoGuesses]).
 *
 * It runs through ONNX Runtime on the bytes FoodRecognizer builds — raw RGB, NHWC, uint8 — with the
 * normalisation inside the graph.
 */
internal class PhotoModel(private val session: OrtSession) : AutoCloseable {
    /**
     * The square side the graph was exported at, read from the model rather than written down here.
     * Read on first use rather than in the constructor: a constructor that threw would leave the
     * session it was handed open, with no model built to close it.
     */
    val side: Int by lazy { (session.inputInfo.values.first().info as TensorInfo).shape[1].toInt() }

    /** [rgb]: `side × side × 3` bytes, R, G, B per pixel, rows top to bottom (FoodRecognizer's input). */
    fun scores(rgb: ByteBuffer): PhotoScores {
        val shape = longArrayOf(1, side.toLong(), side.toLong(), CHANNELS.toLong())
        OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), rgb, shape, OnnxJavaType.UINT8).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                fun output(name: String): FloatArray {
                    val buffer = (result.get(name).get() as OnnxTensor).floatBuffer
                    return FloatArray(buffer.remaining()).also(buffer::get)
                }
                return PhotoScores(dishes = output(DISHES), products = output(PRODUCTS))
            }
        }
    }

    override fun close() = session.close()

    internal companion object {
        const val ASSET = "photo_v3.onnx"
        const val DISHES = "dish_probs"
        const val PRODUCTS = "product_probs"
        private const val CHANNELS = 3
    }
}
