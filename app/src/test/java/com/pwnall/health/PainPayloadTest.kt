package com.pwnall.health

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pain row's payload: what a restore, a backup merge and the day list all read back. The
 * string is part of the row's identity in the backup, so its exact shape is pinned as well.
 */
class PainPayloadTest {
    private val knee = RegionSide(PainRegion.KNEE, BodySide.LEFT)
    private val back = RegionSide(PainRegion.LOWER_BACK, BodySide.CENTRE)

    private val full = PainReport(
        regions = listOf(back, knee),
        types = listOf(PainType.ACHING, PainType.STIFF),
        nrs = 6,
        onset = PainOnset.YESTERDAY,
        onsetAt = 1_790_000_000_000,
        endedAt = 1_790_003_600_000,
        lastedMinutes = 60,
        noticed = PainNoticed.AFTER_WORKOUT,
        painkiller = true,
        strokes = listOf(PainStroke(PainView.FRONT, 0.035f, listOf(Offset(0.64f, 0.615f), Offset(0.7f, 0.62f)))),
    )

    @Test
    fun aReportComesBackAsItWent() {
        val decoded = decodePainPayload(encodePainPayload(full))
        assertEquals(full.copy(lastedMinutes = null, regions = full.regions.sortedWith(REGION_SIDE_ORDER)), decoded)
    }

    /**
     * Head to foot, then the optional keys in one fixed order, and only the ones that are set. The
     * schema says the map: 2 for a report painted on the second map, and 1 — the very string a
     * first-map entry is saved as — for one painted on the first.
     */
    @Test
    fun thePayloadIsWrittenInOneFixedOrder() {
        val rest = """"regions":["LOWER_BACK:C","KNEE:L"],"types":["ACHING","STIFF"],"nrs":6,""" +
            """"onset":"YESTERDAY","onsetAt":1790000000000,"endedAt":1790003600000,""" +
            """"noticed":"AFTER_WORKOUT","painkiller":true,"strokes":[{"v":"F","r":35,"p":[640,615,700,620]}]}"""
        assertEquals("""{"schema":2,""" + rest, encodePainPayload(full))
        assertEquals("""{"schema":1,""" + rest, encodePainPayload(full.copy(map = PainMapVersion.V1)))
        assertEquals("""{"schema":2,"regions":["KNEE:L"]}""", encodePainPayload(PainReport(regions = listOf(knee))))
        assertEquals(
            """{"schema":1,"regions":["KNEE:L"]}""",
            encodePainPayload(PainReport(regions = listOf(knee), map = PainMapVersion.V1)),
        )
    }

    /**
     * A payload of schema 1 reads back on the first map; saved again
     * without a change it is byte for byte the string it was, so a backup merge never takes it for a
     * new row. A payload with no schema at all is the first map's too.
     */
    @Test
    fun aFirstMapPayloadComesBackAsTheSameString() {
        val stored = listOf(
            """{"schema":1,"regions":["KNEE:L"]}""",
            """{"schema":1,"none":true}""",
            """{"schema":1,"regions":["NECK:C","CHEST:L","ABDOMEN:C","ANKLE_FOOT:R"],"types":["SHARP"],"nrs":8,""" +
                """"same":1789000000000,"noticed":"NO_CLEAR_MOMENT"}""",
            """{"schema":1,"regions":["LOWER_BACK:C","KNEE:L"],"types":["ACHING","STIFF"],"nrs":6,""" +
                """"onset":"YESTERDAY","onsetAt":1790000000000,"endedAt":1790003600000,"noticed":"AFTER_WORKOUT",""" +
                """"painkiller":true,"strokes":[{"v":"F","r":35,"p":[640,615,700,620]},""" +
                """{"v":"B","r":12,"p":[620,290]}]}""",
        )
        stored.forEach { payload ->
            val report = decodePainPayload(payload)
            assertEquals(payload, PainMapVersion.V1, report.map)
            assertEquals(payload, encodePainPayload(report))
        }
        assertEquals(PainMapVersion.V1, decodePainPayload("""{"regions":["KNEE:L"]}""").map)
    }

    /**
     * A report painted on the second map, with the areas only it has, comes back as it went — the
     * map with it; a schema later than this build knows is read on the latest map it has.
     */
    @Test
    fun aSecondMapPayloadComesBackAsItWent() {
        val report = PainReport(
            regions = listOf(
                RegionSide(PainRegion.STERNUM, BodySide.CENTRE),
                RegionSide(PainRegion.LOWER_SIDE, BodySide.RIGHT),
                RegionSide(PainRegion.BACK_OF_KNEE, BodySide.LEFT),
                RegionSide(PainRegion.HEEL, BodySide.RIGHT),
            ),
            nrs = 5,
            strokes = listOf(PainStroke(PainView.BACK, 0.018f, listOf(Offset(0.3f, 0.62f), Offset(0.37f, 0.62f)))),
        )
        val payload = encodePainPayload(report)
        assertTrue(payload, payload.startsWith("""{"schema":2,"regions":["STERNUM:C","LOWER_SIDE:R","""))
        assertEquals(report, decodePainPayload(payload))
        assertEquals(PainMapVersion.V2, decodePainPayload(payload.replace("\"schema\":2", "\"schema\":3")).map)
        assertEquals(PainMapVersion.V1, PainMapVersion.ofSchema(0))
        assertEquals(PainMapVersion.V2, PainMapVersion.ofSchema(2))
    }

    /**
     * A painting past either cap, which no writer here makes and a restored or imported file can carry,
     * reads as none — every screen that reads the row would hit-test it — while its areas stay. One
     * right at the caps reads back whole, as it was written.
     */
    @Test
    fun aPaintingPastTheCapsReadsAsTheAreasAlone() {
        fun payload(strokes: Int, pointsEach: Int): String {
            val points = (0 until pointsEach).joinToString(",") { "${300 + it % 400},500" }
            val stroke = """{"v":"F","r":30,"p":[$points]}"""
            val all = List(strokes) { stroke }.joinToString(",")
            return """{"schema":2,"regions":["KNEE:L"],"strokes":[$all]}"""
        }
        assertEquals(PAIN_MAX_STROKES, decodePainPayload(payload(PAIN_MAX_STROKES, 1)).strokes.size)
        assertEquals(PAIN_MAX_POINTS, decodePainPayload(payload(1, PAIN_MAX_POINTS)).strokes.single().points.size)
        val tooMany = decodePainPayload(payload(PAIN_MAX_STROKES + 1, 1))
        assertEquals(emptyList<PainStroke>(), tooMany.strokes)
        assertEquals(listOf(knee), tooMany.regions)
        val tooLong = decodePainPayload(payload(1, PAIN_MAX_POINTS + 1))
        assertEquals(emptyList<PainStroke>(), tooLong.strokes)
        assertEquals(listOf(knee), tooLong.regions)
    }

    /**
     * The same pain as an earlier entry: `same` stands where the start would, and a start the report
     * still holds is never written beside it — the pain started with the first entry.
     */
    @Test
    fun theSamePainIsWrittenWhereTheStartWouldBe() {
        val followUp = full.copy(same = 1_789_000_000_000, endedAt = null, strokes = emptyList())
        assertEquals(
            """{"schema":2,"regions":["LOWER_BACK:C","KNEE:L"],"types":["ACHING","STIFF"],"nrs":6,""" +
                """"same":1789000000000,"noticed":"AFTER_WORKOUT","painkiller":true}""",
            encodePainPayload(followUp),
        )
        val decoded = decodePainPayload(encodePainPayload(followUp))
        assertEquals(1_789_000_000_000L, decoded.same)
        assertNull(decoded.onset)
        assertNull(decoded.onsetAt)
        assertNull(decodePainPayload(encodePainPayload(full)).same)
    }

    /**
     * Only a real moment makes an entry a follow-up: a null, a string, a nought or a negative number
     * would make it the same pain as one told in 1970, which it would then be judged by and saved as.
     */
    @Test
    fun aSameThatIsNotAMomentIsSkipped() {
        fun same(value: String) = decodePainPayload("""{"schema":1,"regions":["KNEE:L"],"same":$value}""").same
        assertNull(same("null"))
        assertNull(same("\"1789000000000\""))
        assertNull(same("0"))
        assertNull(same("-5"))
        assertNull(same("true"))
        assertEquals(1_789_000_000_000L, same("1789000000000"))
        // An entry that never was a follow-up keeps its own start when it is saved again.
        val kept = decodePainPayload("""{"schema":1,"regions":["KNEE:L"],"same":null,"onset":"YESTERDAY"}""")
        assertEquals(PainOnset.YESTERDAY, kept.onset)
        assertTrue(encodePainPayload(kept).contains("\"onset\":\"YESTERDAY\""))
    }

    /** One to ten: a stored number off the scale — a 0 among them — reads as unrated. */
    @Test
    fun theScaleRunsFromOneToTen() {
        fun rated(nrs: Int) = decodePainPayload("""{"schema":1,"regions":["KNEE:L"],"nrs":$nrs}""").nrs
        assertEquals(1..10, PAIN_NRS_RANGE)
        assertNull(rated(0))
        assertEquals(1, rated(1))
        assertEquals(10, rated(10))
        assertNull(rated(11))
    }

    @Test
    fun theSameAnswersAlwaysMakeTheSameString() {
        val shuffled = full.copy(regions = listOf(knee, back, knee))
        assertEquals(encodePainPayload(full), encodePainPayload(shuffled))
    }

    @Test
    fun noPainTodayIsItsOwnShortPayload() {
        assertEquals("""{"schema":2,"none":true}""", encodePainPayload(PainReport(none = true, regions = listOf(knee))))
        assertTrue(decodePainPayload("""{"schema":2,"none":true}""").none)
        val old = decodePainPayload("""{"schema":1,"none":true}""")
        assertTrue(old.none)
        assertEquals("""{"schema":1,"none":true}""", encodePainPayload(old))
    }

    /**
     * A later version's names are skipped, never guessed at, and never take the rest down with them
     * — as this build's own second-map names are by a build that knows only the first map, which never
     * reads the schema.
     */
    @Test
    fun namesThisBuildDoesNotKnowAreSkipped() {
        val report = decodePainPayload(
            """{"schema":3,"regions":["KNEE:L","ELBOW_PIT:L","KNEE:C",7],"types":["ACHING","ELECTRIC"],""" +
                """"nrs":14,"onset":"LAST_YEAR","noticed":"IN_THE_BATH","future":{"a":1},""" +
                """"strokes":[{"v":"S","r":10,"p":[1,2]},{"v":"B","r":20,"p":[1,2,3]},""" +
                """{"v":"B","r":20,"p":[100,-50]}]}""",
        )
        assertEquals(listOf(knee), report.regions)
        assertEquals(listOf(PainType.ACHING), report.types)
        assertEquals(null, report.nrs)
        assertEquals(null, report.onset)
        assertEquals(null, report.noticed)
        assertEquals(listOf(PainStroke(PainView.BACK, 0.02f, listOf(Offset(0.1f, -0.05f)))), report.strokes)
        assertEquals(PainMapVersion.CURRENT, report.map)
    }

    @Test
    fun aPayloadThatIsNotJsonReadsAsAnEmptyReport() {
        assertEquals(PainReport(), decodePainPayload("not json"))
        assertEquals(PainReport(), decodePainPayload(null))
    }

    @Test
    fun pointsAreStoredInThousandthsWithTheRepeatsDropped() {
        val stroke = PainStroke(
            PainView.BACK,
            0.01234f,
            listOf(Offset(0.12345f, 0.5f), Offset(0.1231f, 0.5002f), Offset(0.2f, -0.0456f)),
        )
        val decoded = decodePainPayload(encodePainPayload(PainReport(regions = listOf(knee), strokes = listOf(stroke))))
        assertEquals(
            listOf(PainStroke(PainView.BACK, 0.012f, listOf(Offset(0.123f, 0.5f), Offset(0.2f, -0.046f)))),
            decoded.strokes,
        )
    }

    /** Past the cap only the areas are kept: they are the record, the strokes only redraw it. */
    @Test
    fun tooMuchPaintingKeepsTheAreasAndDropsTheStrokes() {
        val dab = PainStroke(PainView.FRONT, 0.03f, listOf(Offset(0.5f, 0.3f)))
        val many = PainReport(regions = listOf(knee), strokes = List(PAIN_MAX_STROKES + 1) { dab })
        assertFalse(encodePainPayload(many).contains("strokes"))
        assertEquals(listOf(knee), decodePainPayload(encodePainPayload(many)).regions)
        val zigzag = List(PAIN_MAX_POINTS + 1) { Offset(if (it % 2 == 0) 0.30f else 0.31f, 0.3f) }
        val long = PainStroke(PainView.FRONT, 0.03f, zigzag)
        assertFalse(encodePainPayload(PainReport(regions = listOf(knee), strokes = listOf(long))).contains("strokes"))
        val atTheCap = PainReport(regions = listOf(knee), strokes = List(PAIN_MAX_STROKES) { dab })
        assertEquals(PAIN_MAX_STROKES, decodePainPayload(encodePainPayload(atTheCap)).strokes.size)
    }

    /**
     * The page and the chat say so the moment the painting is past what a row keeps, while an Undo can
     * still mend it — and say it exactly when the writer would drop the strokes, never sooner.
     */
    @Test
    fun tooMuchPaintingIsKnownBeforeTheSave() {
        val dab = PainStroke(PainView.FRONT, 0.03f, listOf(Offset(0.5f, 0.3f)))
        val zigzag = List(PAIN_MAX_POINTS + 1) { Offset(if (it % 2 == 0) 0.30f else 0.31f, 0.3f) }
        val paintings: List<List<PainStroke>> = listOf(
            emptyList(),
            listOf(dab),
            List(PAIN_MAX_STROKES) { dab },
            List(PAIN_MAX_STROKES + 1) { dab },
            listOf(PainStroke(PainView.FRONT, 0.03f, zigzag.dropLast(1))),
            listOf(PainStroke(PainView.FRONT, 0.03f, zigzag)),
        )
        assertEquals(listOf(false, false, false, true, false, true), paintings.map(::painPaintingTooMuch))
        paintings.filter { it.isNotEmpty() }.forEach { strokes ->
            val payload = encodePainPayload(PainReport(regions = listOf(knee), strokes = strokes))
            assertEquals(!payload.contains("strokes"), painPaintingTooMuch(strokes))
        }
    }

    /**
     * The canvas keeps a finished touch as the payload stores it, so the stroke the page read its areas
     * off is the very stroke every later reader gets: kept as painted, a dab on the edge of an area
     * could mark it on the page and miss it once its points had been rounded to thousandths.
     */
    @Test
    fun aStrokeKeptByTheCanvasReadsBackExactlyAsItWasKept() {
        val painted = listOf(
            // The whole-figure brush on the edge of the chest, and the same touch zoomed in.
            PainStroke(PainView.FRONT, 0.0318f, listOf(Offset(0.6714f, 0.0834f))),
            PainStroke(PainView.FRONT, 0.0064f, listOf(Offset(0.6714f, 0.0834f))),
            // A drag whose neighbours round onto each other, and a point above the plate.
            PainStroke(
                PainView.BACK,
                0.01234f,
                listOf(Offset(0.12345f, 0.5f), Offset(0.1231f, 0.5002f), Offset(0.2f, -0.0456f)),
            ),
            // A brush finer than a thousandth is stored as one.
            PainStroke(PainView.BACK, 0.0002f, listOf(Offset(0.4f, 0.62f), Offset(0.4449f, 0.6551f))),
        )
        val kept = painted.map { quantised(it) }
        assertEquals(
            PainStroke(PainView.BACK, 0.012f, listOf(Offset(0.123f, 0.5f), Offset(0.2f, -0.046f))),
            kept[2],
        )
        assertEquals(0.001f, kept[3].radius, 0f)
        // The same strokes back, so whatever hit test reads them later reads what the page read.
        val saved = encodePainPayload(PainReport(regions = listOf(knee), strokes = kept))
        assertEquals(kept, decodePainPayload(saved).strokes)
        assertEquals(kept, kept.map { quantised(it) })
    }

    @Test
    fun aStraightStrokeIsSimplifiedToItsEnds() {
        val line = List(50) { Offset(0.2f + it * 0.004f, 0.5f) }
        assertEquals(listOf(line.first(), line.last()), simplifyStroke(line))
        val corner = listOf(Offset(0.2f, 0.5f), Offset(0.3f, 0.5f), Offset(0.3f, 0.6f))
        assertEquals(corner, simplifyStroke(corner))
    }

    @Test
    fun theDayListLineNamesAreasScoreAndWords() {
        val line = painSummaryLine(
            PainReport(regions = listOf(knee, back), nrs = 6, types = listOf(PainType.ACHING)),
            area = { if (it.region == PainRegion.KNEE) "Knee (L)" else "Lower back" },
            score = { "$it/10" },
            word = { it.name.lowercase() },
            none = "No pain",
            comma = ", ",
        )
        assertEquals("Knee (L), Lower back · 6/10 · aching", line)
        assertEquals("No pain", painSummaryLine(PainReport(none = true), { "" }, { "" }, { "" }, "No pain", ", "))
    }

    /**
     * The chest — the whole of it, the centre the second map carved from its two sides as well — and a
     * severe pain in the head. Not the upper belly beside it, nor the base of the neck.
     */
    @Test
    fun theSafetyLineIsForTheChestAndForASevereHeadPain() {
        val chest = RegionSide(PainRegion.CHEST, BodySide.LEFT)
        val temple = RegionSide(PainRegion.TEMPLE, BodySide.RIGHT)
        val forehead = RegionSide(PainRegion.HEAD_FRONT, BodySide.CENTRE)
        assertTrue(painNeedsSafetyLine(listOf(chest), null))
        assertTrue(painNeedsSafetyLine(listOf(RegionSide(PainRegion.STERNUM, BodySide.CENTRE)), null))
        assertTrue(painNeedsSafetyLine(listOf(knee, RegionSide(PainRegion.STERNUM, BodySide.CENTRE)), 2))
        assertFalse(painNeedsSafetyLine(listOf(RegionSide(PainRegion.UPPER_BELLY, BodySide.CENTRE)), 9))
        assertFalse(painNeedsSafetyLine(listOf(RegionSide(PainRegion.TRAPEZIUS, BodySide.RIGHT)), 9))
        assertFalse(painNeedsSafetyLine(listOf(RegionSide(PainRegion.NECK, BodySide.CENTRE)), 9))
        assertTrue(painNeedsSafetyLine(listOf(forehead), 8))
        assertTrue(painNeedsSafetyLine(listOf(temple), 9))
        assertFalse(painNeedsSafetyLine(listOf(forehead), 7))
        assertFalse(painNeedsSafetyLine(listOf(knee), 10))
    }

    @Test
    fun anAreaCountsOnceADay() {
        fun row(day: String, vararg areas: RegionSide) = JournalEntry(
            day = day,
            occurredAt = 0,
            kind = KIND_PAIN,
            title = "Pain",
            calories = 0.0,
            measurementPayloadJson = encodePainPayload(PainReport(regions = areas.toList())),
        )
        val none = row("2026-09-03").copy(measurementPayloadJson = encodePainPayload(PainReport(none = true)))
        val days = painDaysByArea(
            listOf(row("2026-09-01", knee, back), row("2026-09-01", knee), row("2026-09-02", knee), none),
        )
        assertEquals(mapOf(knee to 2, back to 1), days)
    }
}
