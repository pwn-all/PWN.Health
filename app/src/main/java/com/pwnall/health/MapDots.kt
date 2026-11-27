package com.pwnall.health

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * The map as the app's own film draws it: the ground a lattice of dots, each coloured by what lies
 * under its middle — the land tinted by its height and the relief's light, water, forest, a town, ice,
 * a salt flat — sized by how far the slope it stands on is turned from the light, so mountains come
 * out of the dots as a halftone does, and the whole of it seen tilted, from above and behind, with the
 * ground standing at its height.
 *
 * All of it is one shader on the GPU, a pixel at a time: the ray through the pixel meets the ground —
 * the first place it sinks below it, found in a few steps along the stretch where it passes between
 * the highest ground there is and the lowest, so a ridge hides the valley behind it — and the dot
 * of the lattice nearest that point is drawn there, about the same size on screen wherever it stands.
 * The lattice is the ground's, so a pan moves the dots with the land under them; it coarsens with
 * distance as the dots would crowd, continuously: every coarser lattice holds every dot of the finer
 * one, and the dots it does not hold fade out as the distance grows, so there are no rings where the
 * spacing jumps and no fat dots on the horizon. A pan, a pinch or a turn of the phone moves a few
 * numbers; the CPU draws nothing but the route over it.
 */
internal class GroundShading private constructor(private val shader: RuntimeShader) {
    private val brush = ShaderBrush(shader)
    private var boundGround: Bitmap? = null
    private var boundTints: Bitmap? = null
    private var boundHeights: Bitmap? = null

    /** Paints the tilted ground over the whole canvas, as [camera] sees it. */
    fun DrawScope.drawGround(ground: GroundImage, relief: ReliefImage?, camera: TiltCamera, style: MapDots) {
        if (camera.zoom <= 0.0) return
        bind(ground, relief)
        val spacingPx = DOT_SPACING.toPx()
        val level = dotLevel(camera.zoom, spacingPx)
        val unit = 2.0.pow(-level)
        val base = (unit * camera.zoom).toFloat()
        shader.setFloatUniform("view", camera.middleX, camera.middleY, camera.distance, 0f)
        shader.setFloatUniform("look", camera.sinHeading, camera.cosHeading, camera.sinTilt, camera.cosTilt)
        shader.setFloatUniform("spacing", base)
        shader.setFloatUniform("levelShift", log2(base / spacingPx))
        val anchor = latticeAnchor(camera.centerX, camera.centerY, unit * 2.0.pow(COARSEST_LEVEL + 1))
        shader.setFloatUniform(
            "anchor",
            ((anchor.x - camera.centerX) * camera.zoom).toFloat(),
            (
                (
                    anchor.y -
                        camera.centerY
                    ) *
                    camera.zoom
                ).toFloat(),
        )
        val scale = 1.0 / (camera.zoom * ground.texel)
        shader.setFloatUniform(
            "groundMap",
            scale.toFloat(),
            ((camera.centerX - ground.left) / ground.texel).toFloat(),
            ((camera.centerY - ground.top) / ground.texel).toFloat(),
            0f,
        )
        val reach = minOf(ground.covers.width, ground.covers.height) / 2 * camera.zoom.toFloat()
        shader.setFloatUniform("fog", reach * FOG_FROM, reach)
        if (relief != null) {
            shader.setFloatUniform(
                "reliefMap",
                (1.0 / (camera.zoom * relief.texelX)).toFloat(),
                (1.0 / (camera.zoom * relief.texelY)).toFloat(),
                ((camera.centerX - relief.left) / relief.texelX).toFloat(),
                ((camera.centerY - relief.top) / relief.texelY).toFloat(),
            )
            shader.setFloatUniform("heights", relief.heightLow, relief.heightSpan, camera.baseM, camera.metrePx)
        } else {
            shader.setFloatUniform("heights", 0f, 1f, 0f, 0f)
        }
        shader.setFloatUniform("hasRelief", if (relief != null) 1f else 0f)
        style.bindTo(shader)
        drawRect(brush, Offset.Zero, size)
    }

    private fun bind(ground: GroundImage, relief: ReliefImage?) {
        if (boundGround !== ground.bitmap) {
            shader.setInputShader("classes", texture(ground.bitmap, BitmapShader.FILTER_MODE_NEAREST))
            boundGround = ground.bitmap
        }
        val tints = relief?.bitmap ?: PLAIN
        if (boundTints !== tints) {
            shader.setInputShader("tints", texture(tints, BitmapShader.FILTER_MODE_LINEAR))
            boundTints = tints
        }
        val heights = relief?.heights ?: PLAIN
        if (boundHeights !== heights) {
            shader.setInputShader("relief", texture(heights, BitmapShader.FILTER_MODE_LINEAR))
            boundHeights = heights
        }
    }

    private fun texture(bitmap: Bitmap, filter: Int): BitmapShader =
        BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { it.filterMode = filter }

    companion object {
        /** Null where the renderer refuses to build the shader; the map is then drawn as lines and fills. */
        fun createOrNull(): GroundShading? = try {
            GroundShading(RuntimeShader(GROUND_SHADER))
        } catch (_: IllegalArgumentException) {
            null
        }

        /** What an input reads while there is no relief: never sampled, but every input must be bound. */
        private val PLAIN: Bitmap by lazy { createBitmap(1, 1) }
    }
}

/**
 * The dots by day or by night: [paper] shows between them; [land] says how a land dot takes its tint
 * and size from the relief; water, forest, a town, ice and a salt flat each have a colour of their
 * own; [pens] draw the rivers and streams across the dots, as rows of dots.
 */
internal class MapDots(
    val paper: Color,
    val land: DotLand,
    val water: Color,
    val forest: Color,
    val town: Color,
    val ice: Color,
    val salt: Color,
    val pens: List<MapPen>,
) {
    fun bindTo(shader: RuntimeShader) {
        shader.setColorUniform("paper", paper.toArgb())
        shader.setColorUniform("landFlat", land.flat.toArgb())
        shader.setColorUniform("landInk", land.ink.toArgb())
        shader.setFloatUniform("landInkShare", land.inkShare)
        shader.setColorUniform("waterDot", water.toArgb())
        shader.setColorUniform("forestDot", forest.toArgb())
        shader.setColorUniform("townDot", town.toArgb())
        shader.setColorUniform("iceDot", ice.toArgb())
        shader.setColorUniform("saltDot", salt.toArgb())
        shader.setFloatUniform("sizeAt", land.sizes.at)
        shader.setFloatUniform("lumAt", land.sizes.lumAt)
        shader.setFloatUniform("sizeGain", land.sizes.gain)
    }
}

/**
 * A land dot: the relief's tint at its middle, drawn [inkShare] of the way toward [ink] so a pale tint
 * still stands off the paper — [flat] where there is no relief — at the size [sizes] gives it.
 */
internal class DotLand(val flat: Color, val ink: Color, val inkShare: Float, val sizes: DotSizes)

/**
 * A land dot's radius, as a share of the spacing: [at] where the tint's luminance is [lumAt], growing
 * by [gain] for each unit the luminance falls below it. By day a slope in shadow is a bigger, darker
 * dot; by night [gain] is negative and the lit slope is the bigger, brighter one.
 */
internal class DotSizes(val at: Float, val lumAt: Float, val gain: Float)

/**
 * What lies under each dot of a part of the world, a texel per [texel] map units in both directions —
 * the map's own Mercator, so the land's outlines are drawn into it as they are — with its corner at
 * [left], [top]. Each texel's [Ground] is coded in its red channel for the GPU, and kept in [codes]
 * for the CPU, row by row.
 */
internal class GroundImage(
    val bitmap: Bitmap,
    val left: Double,
    val top: Double,
    val texel: Double,
    val codes: IntArray,
    /** The lattice level it was classified for: see [DotLattice.level]. */
    val level: Int,
) {
    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height

    /** The part of the world it covers, in map units. */
    val covers: Rect
        get() = Rect(left.toFloat(), top.toFloat(), (left + width * texel).toFloat(), (top + height * texel).toFloat())
}

/** What a dot can stand for, as [GroundImage] codes it. The numbers are the shader's too. */
internal enum class Ground(val code: Int) {
    LAND(0),
    WATER(1),
    FOREST(2),
    TOWN(3),
    ICE(4),
    SALT(5),
    ;

    /** The texel colour that carries [code]: [GROUND_CODE_STEP] of red a step, read back by the shader. */
    val argb: Int get() = android.graphics.Color.rgb(code * GROUND_CODE_STEP, 0, 0)
}

private const val GROUND_CODE_STEP = 40

/**
 * Classifies [region] for the dots of lattice [level]: the land, lakes, ice and salt flats of [map]
 * drawn into a picture [texel] map units a texel, then the forests and the towns laid on what is land.
 * Null when the region is empty. Blocking — the forests are read from the asset.
 */
internal fun classifyGround(
    map: MapLevel,
    towns: WorldTowns?,
    forest: WorldForest?,
    region: Rect,
    texel: Double,
    level: Int,
): GroundImage? {
    if (region.isEmpty) return null
    val step = maxOf(texel, region.width / MAX_GROUND_TEXELS, region.height / MAX_GROUND_TEXELS)
    val width = ceil(region.width / step).toInt().coerceAtLeast(1)
    val height = ceil(region.height / step).toInt().coerceAtLeast(1)
    val left = region.left.toDouble()
    val top = region.top.toDouble()
    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Ground.WATER.argb)
    val painter = OutlinePainter(canvas, left, top, step)
    painter.fill(map.layers.getOrNull(LAND_LAYER), Ground.LAND)
    painter.fill(map.layers.getOrNull(LAKE_LAYER), Ground.WATER)
    painter.fill(map.layers.getOrNull(GLACIER_LAYER), Ground.ICE)
    painter.fill(map.layers.getOrNull(SALT_LAYER), Ground.SALT)
    val codes = IntArray(width * height)
    bitmap.getPixels(codes, 0, width, 0, 0, width, height)
    for (index in codes.indices) {
        codes[index] =
            (android.graphics.Color.red(codes[index]) + GROUND_CODE_STEP / 2) / GROUND_CODE_STEP
    }
    val image = GroundImage(bitmap, left, top, step, codes, level)
    forest?.let { markForest(image, it) }
    towns?.let { markTowns(image, it) }
    val colours = IntArray(codes.size) { Ground.entries[codes[it]].argb }
    bitmap.setPixels(colours, 0, width, 0, 0, width, height)
    return image
}

/** Fills a layer's closed rings into a ground picture, even-odd, as [drawWorld] fills them on screen. */
private class OutlinePainter(
    private val canvas: Canvas,
    private val left: Double,
    private val top: Double,
    private val texel: Double,
) {
    private val paint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.FILL
    }
    private val path = android.graphics.Path().apply { fillType = android.graphics.Path.FillType.EVEN_ODD }
    private val right = left + canvas.width * texel
    private val bottom = top + canvas.height * texel

    fun fill(lines: List<MapLine>?, ground: Ground) {
        if (lines.isNullOrEmpty()) return
        path.rewind()
        lines.forEach { line ->
            val insideX = line.maxX >= left && line.minX <= right
            if (!insideX || line.maxY < top || line.minY > bottom) return@forEach
            val points = line.points
            path.moveTo(((points[0] - left) / texel).toFloat(), ((points[1] - top) / texel).toFloat())
            for (index in 2 until points.size step 2) {
                path.lineTo(((points[index] - left) / texel).toFloat(), ((points[index + 1] - top) / texel).toFloat())
            }
            path.close()
        }
        paint.color = ground.argb
        canvas.drawPath(path, paint)
    }
}

/** The forests, read at the level a texel or two across, laid on the texels that are land. */
private fun markForest(image: GroundImage, forest: WorldForest) {
    val texelDegrees = image.texel * DEGREES_PER_TURN
    val window = ForestWindow.of(image, forest, texelDegrees)
    val cells = forest.cells(window.index, window.firstRow, window.rows, window.firstColumn, window.columns)
    val cell = forest.levels[window.index].cellDegrees
    val west = image.left * DEGREES_PER_TURN - HALF_TURN
    // Where each texel lies in cells, counted from the middle of the window's first: a texel is
    // forest where the four cells round it, weighed by how near each is, are at least half forest.
    // Taken from the nearest cell alone, a wood would be a block of half-kilometre steps across the view.
    val columnAt = DoubleArray(image.width) { tx ->
        (west + (tx + 0.5) * texelDegrees + HALF_TURN) / cell - window.firstColumn - 0.5
    }
    for (ty in 0 until image.height) {
        val rowAt = (NORTH_POLE - latitudeAt(image.top + (ty + 0.5) * image.texel)) / cell - window.firstRow - 0.5
        val rowStart = ty * image.width
        for (tx in 0 until image.width) {
            val index = rowStart + tx
            val onLand = image.codes[index] == Ground.LAND.code
            if (onLand && forestShare(cells, window.rows, window.columns, rowAt, columnAt[tx]) >= 0.5) {
                image.codes[index] = Ground.FOREST.code
            }
        }
    }
}

/**
 * How much forest lies at [row], [column] of a [rows] × [columns] grid of [cells], counted in cells from
 * the middle of the first: the four cells round the point, each weighed by how near it is.
 */
internal fun forestShare(cells: BooleanArray, rows: Int, columns: Int, row: Double, column: Double): Double {
    val r = floor(row).toInt()
    val c = floor(column).toInt()
    fun at(y: Int, x: Int) = if (cells[y.coerceIn(0, rows - 1) * columns + x.coerceIn(0, columns - 1)]) 1.0 else 0.0
    val upper = at(r, c) + (at(r, c + 1) - at(r, c)) * (column - c)
    val lower = at(r + 1, c) + (at(r + 1, c + 1) - at(r + 1, c)) * (column - c)
    return upper + (lower - upper) * (row - r)
}

/** The cells of one forest level a ground picture needs: the finest whose window is not too large. */
private class ForestWindow(val index: Int, val firstRow: Int, val rows: Int, val firstColumn: Int, val columns: Int) {
    companion object {
        fun of(image: GroundImage, forest: WorldForest, texelDegrees: Double): ForestWindow {
            val north = latitudeAt(image.top)
            val south = latitudeAt(image.top + image.height * image.texel)
            val west = image.left * DEGREES_PER_TURN - HALF_TURN
            val east = west + image.width * texelDegrees
            var index = forest.levelFor(texelDegrees * FOREST_TEXELS_PER_CELL)
            var window = windowAt(forest.levels[index], index, north, south, west, east)
            while (window.rows.toLong() * window.columns > MAX_FOREST_CELLS && index > 0) {
                index--
                window = windowAt(forest.levels[index], index, north, south, west, east)
            }
            return window
        }

        private fun windowAt(
            level: ForestLevel,
            index: Int,
            north: Double,
            south: Double,
            west: Double,
            east: Double,
        ): ForestWindow {
            val cell = level.cellDegrees
            val firstRow = floor((NORTH_POLE - north) / cell).toInt().coerceIn(0, level.rows - 1)
            val lastRow = floor((NORTH_POLE - south) / cell).toInt().coerceIn(0, level.rows - 1)
            val firstColumn = floor((west + HALF_TURN) / cell).toInt()
            val lastColumn = floor((east + HALF_TURN) / cell).toInt()
            return ForestWindow(index, firstRow, lastRow - firstRow + 1, firstColumn, lastColumn - firstColumn + 1)
        }
    }
}

/** Each town's ground, laid on the texels inside it that are land or forest: never on water. */
private fun markTowns(image: GroundImage, towns: WorldTowns) {
    // Past the antimeridian, as the land is: see [turnsInto].
    val turns = turnsInto(image.covers)
    for (town in 0 until towns.size) {
        for (turn in turns) markTown(image, towns, town, turn)
    }
}

/** The land and forest texels on [town]'s ground made town, if any of it falls on [image] [turn] whole turns east. */
private fun markTown(image: GroundImage, towns: WorldTowns, town: Int, turn: Int) {
    val reach = towns.reach[town] / image.texel
    val cx = (towns.x[town] + turn - image.left) / image.texel
    val cy = (towns.y[town] - image.top) / image.texel
    val insideX = cx + reach >= 0 && cx - reach <= image.width
    if (!insideX || cy + reach < 0 || cy - reach > image.height) return
    val fromX = floor(cx - reach).toInt().coerceAtLeast(0)
    val toX = ceil(cx + reach).toInt().coerceAtMost(image.width - 1)
    val fromY = floor(cy - reach).toInt().coerceAtLeast(0)
    val toY = ceil(cy + reach).toInt().coerceAtMost(image.height - 1)
    for (ty in fromY..toY) {
        val dy = (ty + 0.5 - cy) * image.texel
        for (tx in fromX..toX) {
            val dx = (tx + 0.5 - cx) * image.texel
            val index = ty * image.width + tx
            val code = image.codes[index]
            val onLand = code == Ground.LAND.code || code == Ground.FOREST.code
            if (onLand && towns.covers(town, dx, dy)) image.codes[index] = Ground.TOWN.code
        }
    }
}

/**
 * The level of the dots' lattice at a zoom of [scale] pixels a map unit: the power of two whose
 * spacing, 2⁻ˡᵉᵛᵉˡ of the world's width, is between [spacingPx] and twice it on screen at the view's
 * middle. A ground picture classified for one level serves it and the level below: see [servesLattice].
 */
internal fun dotLevel(scale: Double, spacingPx: Float): Int = floor(ln(scale / spacingPx) / ln(2.0)).toInt()

/**
 * Whether a ground picture classified for lattice level [classified] serves the lattice level [wanted]:
 * its own, or the next coarser, which it classifies finer than it needs. Every two-finger twist pinches
 * a little, and on the walk page's closest zoom the next level down starts 3.7% away at 420 dpi: with
 * the picture classified again at each crossing, a twist there would cost 25 MB and an 8.5 MB texture
 * each way, in the middle of the turn. A finer lattice is classified again, and so is a view
 * that has grown past the picture.
 */
internal fun servesLattice(classified: Int, wanted: Int): Boolean = classified == wanted || classified == wanted + 1

/** The texel a ground picture is classified at for lattice [level]: a quarter of its spacing. */
internal fun groundTexel(level: Int): Double = 2.0.pow(-level) / GROUND_TEXELS_PER_SPACING

/**
 * A point of the lattice of [spacing] map units on a row it counts as even, near [x], [y]: the
 * shader works relative to it, and every finer lattice holds it on an even row too, so all of them
 * shift the same rows the ground's own lattices do.
 */
internal fun latticeAnchor(x: Double, y: Double, spacing: Double): Offset2 {
    val rowHeight = spacing * ROW_SHARE
    return Offset2(round(x / spacing) * spacing, 2.0 * round(y / rowHeight / 2.0) * rowHeight)
}

/** A point in map units, in doubles: a float cannot hold a street's position on the world's map. */
internal data class Offset2(val x: Double, val y: Double)

/** How far apart the dots are at least, on any screen, at the view's middle; at most twice that. */
internal val DOT_SPACING = 7.dp

/** A hexagonal lattice's rows stand this share of its spacing apart. */
private val ROW_SHARE = sqrt(3.0) / 2

/** A ground picture has this many texels to the base lattice's spacing: two to each dot of the finer one. */
private const val GROUND_TEXELS_PER_SPACING = 4.0

/** No ground picture past this on a side. */
private const val MAX_GROUND_TEXELS = 1_536.0

/** The forests are read at the level whose cells are no wider than this many texels. */
private const val FOREST_TEXELS_PER_CELL = 2.0

/** No forest window past this many cells: past it, a coarser level is read instead. */
private const val MAX_FOREST_CELLS = 4_000_000L

/** The coarsest lattice the shader climbs to, counted from the base lattice. */
private const val COARSEST_LEVEL = 4

/** The dots fade into the paper from this share of the classified ground's half-width out to its edge. */
private const val FOG_FROM = 0.82f

private const val DEGREES_PER_TURN = 360.0
private const val HALF_TURN = 180.0
private const val NORTH_POLE = 90.0

/**
 * The tilted ground, a pixel at a time. Coordinates are pixels at the view's middle — a map unit is
 * the zoom's worth of them — east and south of it; heights are pixels up.
 *
 * The camera stands view.z pixels from the middle along its line of sight, which looks toward the
 * heading (look.xy, as sine and cosine) and down from the horizontal by the tilt's complement
 * (look.zw, the tilt's sine and cosine from straight down). A pixel's ray is walked in eight steps
 * across the heights the ground can have, and the first step below the ground is narrowed to where
 * it crossed by the two heights either side; that point's depth along the line of sight picks
 * the lattice's level, nudged up where the ground is seen at a grazing angle and the rows crowd.
 * Near the end of each level the finer lattice's own dots grow out of nothing between the coarser's,
 * so no second, paler lattice ever shows over the first. A dot is a disc lying on the ground — round
 * from above, an ellipse as the ground tips away — so it never reaches past its own cell of the
 * lattice, coloured by the ground's class and the relief's tint at its middle, its edge softened over
 * about a pixel of the screen.
 */
private const val GROUND_SHADER = """
uniform shader classes;
uniform shader tints;
uniform shader relief;
uniform float4 view;
uniform float4 look;
uniform float spacing;
uniform float levelShift;
uniform float2 anchor;
uniform float4 groundMap;
uniform float4 reliefMap;
uniform float4 heights;
uniform float2 fog;
uniform float hasRelief;
layout(color) uniform half4 paper;
layout(color) uniform half4 landFlat;
layout(color) uniform half4 landInk;
uniform float landInkShare;
layout(color) uniform half4 waterDot;
layout(color) uniform half4 forestDot;
layout(color) uniform half4 townDot;
layout(color) uniform half4 iceDot;
layout(color) uniform half4 saltDot;
uniform float sizeAt;
uniform float lumAt;
uniform float sizeGain;

float2 ahead() { return float2(look.x, -look.y); }
float2 rightward() { return float2(look.y, look.x); }

float heightAt(float2 g) {
    if (hasRelief < 0.5) {
        return 0.0;
    }
    float2 t = float2(g.x * reliefMap.x + reliefMap.z, g.y * reliefMap.y + reliefMap.w);
    float metres = heights.x + float(relief.eval(t).r) * heights.y;
    return (metres - heights.z) * heights.w;
}

float4 nearestPoint(float2 q, float s) {
    float h = s * 0.8660254;
    float r0 = floor(q.y / h);
    float r1 = r0 + 1.0;
    float shift0 = mod(r0, 2.0) * 0.5 * s;
    float shift1 = mod(r1, 2.0) * 0.5 * s;
    float c0 = floor((q.x - shift0) / s + 0.5);
    float c1 = floor((q.x - shift1) / s + 0.5);
    float2 p0 = float2(c0 * s + shift0, r0 * h);
    float2 p1 = float2(c1 * s + shift1, r1 * h);
    float2 d0 = q - p0;
    float2 d1 = q - p1;
    if (dot(d1, d1) < dot(d0, d0)) {
        return float4(p1, r1, c1);
    }
    return float4(p0, r0, c0);
}

half4 dotAt(float2 g) {
    float2 t = g * groundMap.x + groundMap.yz;
    float code = floor(float(classes.eval(t).r) * 255.0 / 40.0 + 0.5);
    half4 tint = landFlat;
    if (hasRelief > 0.5) {
        tint = tints.eval(float2(g.x * reliefMap.x + reliefMap.z, g.y * reliefMap.y + reliefMap.w));
    }
    float lum = dot(float3(tint.rgb), float3(0.299, 0.587, 0.114));
    float size = clamp(sizeAt + sizeGain * (lumAt - lum), 0.16, 0.44);
    // A wood or a glacier takes the relief's light and shade as the land does, against the plain's
    // own tint: in one colour, a forested mountain read as flat ground.
    float plain = dot(float3(landFlat.rgb), float3(0.299, 0.587, 0.114));
    half shade = half(clamp(lum / max(plain, 0.01), 0.6, 1.15));
    if (code > 0.5 && code < 1.5) {
        return half4(waterDot.rgb, 0.40);
    }
    if (code > 1.5 && code < 2.5) {
        return half4(forestDot.rgb * shade, max(size, 0.34));
    }
    if (code > 2.5 && code < 3.5) {
        return half4(townDot.rgb, 0.38);
    }
    if (code > 3.5 && code < 4.5) {
        return half4(iceDot.rgb * shade, max(size, 0.32));
    }
    if (code > 4.5) {
        return half4(saltDot.rgb, 0.34);
    }
    return half4(mix(tint.rgb, landInk.rgb, half(landInkShare)), size);
}

half4 main(float2 p) {
    float u = (p.x - view.x) / view.z;
    float v = (p.y - view.y) / view.z;
    float wz = -look.w - v * look.z;
    if (wz > -0.01) {
        return paper;
    }
    float2 a = ahead();
    float2 wxy = (look.z - v * look.w) * a + u * rightward();
    float2 eye = -view.z * look.z * a;
    float eyeZ = view.z * look.w;
    float t = -eyeZ / wz;
    if (hasRelief > 0.5 && heights.w > 0.0) {
        // The ray meets the ground somewhere between where it sinks below the highest ground there
        // is and where it sinks below the lowest: walk that stretch and take the first crossing.
        float zHigh = (heights.x + heights.y - heights.z) * heights.w;
        float zLow = (heights.x - heights.z) * heights.w;
        float from = max((zHigh - eyeZ) / wz, 0.0);
        float stride = ((zLow - eyeZ) / wz - from) / 8.0;
        float before = from;
        float gapBefore = max(eyeZ + from * wz - heightAt(eye + from * wxy), 0.001);
        t = from + stride * 8.0;
        for (int i = 1; i <= 8; i++) {
            float at = from + stride * float(i);
            float gap = eyeZ + at * wz - heightAt(eye + at * wxy);
            if (gap <= 0.0) {
                t = mix(before, at, gapBefore / (gapBefore - gap));
                break;
            }
            before = at;
            gapBefore = gap;
        }
    }
    float2 g = eye + t * wxy;
    float crowd = clamp(-wz / length(float3(wxy, wz)) / look.w, 0.2, 1.0);
    float level = clamp(log2(max(t / view.z, 0.05)) - levelShift - 0.75 * log2(crowd), -1.0, 4.0);
    float lower = floor(level);
    float fine = 1.0 - smoothstep(0.0, 0.3, level - lower);
    float coarse = spacing * exp2(lower + 1.0);
    float sized = mix(coarse, coarse * 0.5, fine);
    float pixel = t / view.z / sqrt(crowd);
    float haze = 1.0 - smoothstep(fog.x, fog.y, length(g));
    float2 q = g - anchor;
    half3 colour = paper.rgb;
    if (fine > 0.001) {
        float4 fineNearest = nearestPoint(q, coarse * 0.5);
        bool alsoCoarse = mod(fineNearest.z, 2.0) < 0.5
            && abs(mod(fineNearest.w, 2.0) - mod(fineNearest.z * 0.5, 2.0)) < 0.5;
        if (!alsoCoarse) {
            half4 fineDot = dotAt(anchor + fineNearest.xy);
            float fineCover = clamp((float(fineDot.a) * sized * fine - distance(q, fineNearest.xy)) / pixel + 0.5, 0.0, 1.0);
            colour = mix(colour, fineDot.rgb, half(fineCover * haze));
        }
    }
    float4 coarseNearest = nearestPoint(q, coarse);
    half4 coarseDot = dotAt(anchor + coarseNearest.xy);
    float coarseCover = clamp((float(coarseDot.a) * sized - distance(q, coarseNearest.xy)) / pixel + 0.5, 0.0, 1.0);
    return half4(mix(colour, coarseDot.rgb, half(coarseCover * haze)), 1.0);
}
"""
