package com.pwnall.health

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp

/**
 * Geometry and colors of the mood faces. Single mouth curves are subdivided into three
 * equivalent cubic curves so every expression has matching control points.
 *
 * [paths] run in one order for every face, which is what lets one morph into the next point by
 * point: the two brows, the two eyes, the mouth's line, the open mouth, then the four strokes beside
 * the head. [opacity] is read per part: brows, eyes, eye ovals, mouth line, open mouth, strokes.
 */
private class MoodFaceShape(
    val colors: List<Color>,
    val accent: Color,
    val opacity: FloatArray,
    val paths: List<FloatArray>,
)

/** The laughing mouth, the one closed and filled path; every face has it, shown or not. */
private val OPEN_MOUTH = floatArrayOf(
    101f, 151f,
    114f, 158f, 141f, 158f, 155f, 151f,
    153f, 171f, 143f, 183f, 128f, 184f,
    113f, 183f, 103f, 171f, 101f, 151f,
)

/** The eyes at rest — a shallow curve each — which three of the five faces share. */
private val RESTING_EYES = listOf(
    floatArrayOf(82f, 124f, 89f, 128f, 101f, 128f, 108f, 124f),
    floatArrayOf(148f, 124f, 155f, 128f, 167f, 128f, 174f, 124f),
)

/** Two short strokes either side of the head, in the face's accent; the same on every face. */
private val SIDE_STROKES = listOf(
    floatArrayOf(45f, 83f, 40f, 79f, 35f, 76f, 31f, 74f),
    floatArrayOf(57f, 64f, 53f, 58f, 49f, 53f, 45f, 49f),
    floatArrayOf(211f, 83f, 216f, 79f, 221f, 76f, 225f, 74f),
    floatArrayOf(199f, 64f, 203f, 58f, 207f, 53f, 211f, 49f),
)

private val moodFaces = listOf(
    MoodFaceShape(
        colors = listOf(Color(0xFFE3EDFB), Color(0xFFCDDCF1), Color(0xFFBAC9DF), Color(0xFFAEBED5)),
        accent = Color(0xFF90A8C7),
        opacity = floatArrayOf(0.72f, 0f, 1f, 1f, 0f, 0f),
        paths = listOf(
            floatArrayOf(82f, 99f, 89f, 103f, 98f, 103f, 106f, 97f),
            floatArrayOf(150f, 97f, 158f, 103f, 167f, 104f, 176f, 101f),
            floatArrayOf(81f, 122f, 87f, 138f, 102f, 140f, 109f, 123f),
            floatArrayOf(147f, 122f, 154f, 138f, 169f, 140f, 176f, 123f),
            floatArrayOf(
                102f, 169f,
                106.66667f, 164f, 112.44444f, 160.66667f, 118.59259f, 159f,
                124.74074f, 157.33333f, 131.25926f, 157.33333f, 137.40741f, 159f,
                143.55556f, 160.66667f, 149.33333f, 164f, 154f, 169f,
            ),
            OPEN_MOUTH,
        ) + SIDE_STROKES,
    ),
    MoodFaceShape(
        colors = listOf(Color(0xFFE3F2FF), Color(0xFFD0E7FA), Color(0xFFBDD7EF), Color(0xFFB4CDE6)),
        accent = Color(0xFF8FB4DA),
        opacity = floatArrayOf(0.82f, 1f, 0f, 1f, 0f, 0.95f),
        paths = listOf(
            floatArrayOf(82f, 103f, 90f, 103f, 99f, 99f, 105f, 91f),
            floatArrayOf(151f, 91f, 158f, 99f, 166f, 103f, 176f, 103f),
        ) + RESTING_EYES + listOf(
            floatArrayOf(
                99f, 160f,
                105f, 153f, 111f, 150f, 117f, 157f,
                122f, 164f, 127f, 164f, 133f, 157f,
                139f, 150f, 145f, 152f, 152f, 159f,
            ),
            OPEN_MOUTH,
        ) + SIDE_STROKES,
    ),
    MoodFaceShape(
        colors = listOf(Color(0xFFF0F1F2), Color(0xFFE7E8E9), Color(0xFFDCDDDF), Color(0xFFD5D7D9)),
        accent = Color(0xFFC8CDD1),
        opacity = floatArrayOf(0.8f, 1f, 0f, 1f, 0f, 0f),
        paths = listOf(
            floatArrayOf(83f, 100f, 90f, 95f, 99f, 94f, 106f, 96f),
            floatArrayOf(150f, 96f, 158f, 94f, 167f, 95f, 175f, 100f),
        ) + RESTING_EYES + listOf(
            floatArrayOf(
                107f, 158f,
                111f, 158f, 115.66667f, 158f, 120.55556f, 158f,
                125.44444f, 158f, 130.55556f, 158f, 135.44444f, 158f,
                140.33333f, 158f, 145f, 158f, 149f, 158f,
            ),
            OPEN_MOUTH,
        ) + SIDE_STROKES,
    ),
    MoodFaceShape(
        colors = listOf(Color(0xFFE5F5E7), Color(0xFFD7EEDB), Color(0xFFC5E4CC), Color(0xFFB9DABF)),
        accent = Color(0xFF8FC6A0),
        opacity = floatArrayOf(0.82f, 1f, 0f, 1f, 0f, 0f),
        paths = listOf(
            floatArrayOf(83f, 99f, 90f, 94f, 99f, 93f, 106f, 96f),
            floatArrayOf(150f, 96f, 158f, 93f, 167f, 94f, 175f, 99f),
        ) + RESTING_EYES + listOf(
            floatArrayOf(
                101f, 153f,
                105.66667f, 158f, 111.55556f, 161.44444f, 117.88889f, 163.22222f,
                124.22222f, 165f, 131f, 165.11111f, 137.44444f, 163.44444f,
                143.88889f, 161.77778f, 150f, 158.33333f, 155f, 153f,
            ),
            OPEN_MOUTH,
        ) + SIDE_STROKES,
    ),
    MoodFaceShape(
        colors = listOf(Color(0xFFFFF4BC), Color(0xFFFFE78F), Color(0xFFFFD86C), Color(0xFFF8C84E)),
        accent = Color(0xFFF6C94F),
        opacity = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f),
        paths = listOf(
            floatArrayOf(83f, 99f, 90f, 94f, 99f, 93f, 106f, 96f),
            floatArrayOf(150f, 96f, 158f, 93f, 167f, 94f, 175f, 99f),
            floatArrayOf(82f, 127f, 88f, 111f, 102f, 111f, 109f, 127f),
            floatArrayOf(147f, 127f, 154f, 111f, 168f, 111f, 175f, 127f),
            floatArrayOf(
                101f, 151f,
                105.33333f, 153.33333f, 111.22222f, 154.88889f, 117.66667f, 155.66667f,
                124.11111f, 156.44444f, 131.11111f, 156.44444f, 137.66667f, 155.66667f,
                144.22222f, 154.88889f, 150.33333f, 153.33333f, 155f, 151f,
            ),
            OPEN_MOUTH,
        ) + SIDE_STROKES,
    ),
)

/** One continuously changing face, including at the boundaries between mood scores. */
@Composable
internal fun MorphingMoodFace(score: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val position = (score - 1f).coerceIn(0f, 4f)
        val index = position.toInt().coerceAtMost(3)
        val from = moodFaces[index]
        val to = moodFaces[index + 1]
        val fraction = position - index
        fun mix(a: Float, b: Float) = a + (b - a) * fraction
        fun opacity(index: Int) = mix(from.opacity[index], to.opacity[index])
        val unit = size.minDimension / 256f
        translate((size.width - 256f * unit) / 2f, (size.height - 256f * unit) / 2f) {
            scale(unit, unit, pivot = Offset.Zero) {
                val colors = from.colors.zip(to.colors) { a, b -> lerp(a, b, fraction) }
                drawCircle(
                    Brush.radialGradient(
                        listOf(colors.last().copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(128f, 143f),
                        radius = 112f,
                    ),
                    radius = 112f,
                    center = Offset(128f, 143f),
                )
                drawCircle(
                    Brush.radialGradient(
                        0f to colors[0],
                        0.43f to colors[1],
                        0.74f to colors[2],
                        1f to colors[3],
                        center = Offset(106.16f, 87.96f),
                        radius = 141.96f,
                    ),
                    radius = 91f,
                    center = Offset(128f, 128f),
                )
                val ink = Color(0xFF122238)
                val stroke = Stroke(7f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                from.paths.forEachIndexed { i, coordinates ->
                    val next = to.paths[i]
                    fun value(j: Int) = mix(coordinates[j], next[j])
                    val path = Path().apply {
                        moveTo(value(0), value(1))
                        var j = 2
                        while (j + 5 < coordinates.size) {
                            cubicTo(value(j), value(j + 1), value(j + 2), value(j + 3), value(j + 4), value(j + 5))
                            j += 6
                        }
                        if (i == 5) close()
                    }
                    val alpha = opacity(
                        when (i) {
                            0, 1 -> 0
                            2, 3 -> 2
                            4 -> 3
                            5 -> 4
                            else -> 5
                        },
                    )
                    val color = when {
                        i >= 6 -> lerp(from.accent, to.accent, fraction)
                        i == 5 -> Color(0xFF34404B)
                        else -> ink
                    }.copy(alpha = alpha)
                    if (i == 5) drawPath(path, color) else drawPath(path, color, style = stroke)
                }
                listOf(94f, 162f).forEach { x ->
                    drawOval(ink.copy(alpha = opacity(1)), Offset(x - 7.5f, 114.5f), Size(15f, 21f))
                }
            }
        }
    }
}
