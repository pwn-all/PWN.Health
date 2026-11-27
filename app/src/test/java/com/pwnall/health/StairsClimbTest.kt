package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Two real stair climbs, recorded with the sensor trace (the raw recordings stay out of the
 * repository): every step event as the phone decided it — the hardware counter and the gate's
 * reading for its window — replayed through the accumulator from the state each recording began in.
 *
 * Both climbs begin with the phone in the hand and put it into a pocket on the way up. While it moved,
 * the gate read a walker's energy with no clean rhythm and refused 8 of 23 and 9 of 51 steps.
 */
class StairsClimbTest {

    private class Climb(val start: StepState, val events: String)

    /** Columns: event nanos, counter, motion, posture, std, cadence, periodicity, tilt, screen, credited. */
    private val firstClimb = Climb(
        StepState(
            bootId = 1,
            lastCumulative = 15_012,
            lastEventNanos = 205_133_054_331_831,
            boutStartedNanos = 205_040_059_382_649,
            boutLastNanos = 205_133_054_331_831,
            boutConfirmed = true,
            rateWindowStartedNanos = 205_131_370_889_444,
            rateWindowStartCounter = 15_009,
        ),
        """
        205134215982509,15014,WALKING,FLAT,1.9234,1.8563,0.7631,28.2932,true,2
        205134771483385,15015,WALKING,FLAT,1.9115,1.8563,0.6680,24.5985,true,1
        205135916242044,15016,WALKING,FLAT,1.8964,1.7471,0.6768,18.8084,true,1
        205136404470561,15017,WALKING,FLAT,2.5299,0.9000,0.4383,15.0233,false,1
        205136842134563,15018,VEHICLE,FLAT,2.8892,1.9800,0.3198,14.5442,false,0
        205137380861563,15019,VEHICLE,FLAT,3.1486,1.9800,0.2856,11.4232,false,0
        205137852240023,15020,VEHICLE,FLAT,3.4621,1.9800,0.2712,10.1298,false,0
        205138374106723,15021,VEHICLE,FLAT,3.4764,1.9800,0.3047,14.0607,false,0
        205138996990188,15022,VEHICLE,FLAT,3.7197,0.9900,0.3934,27.5428,false,0
        205139653545111,15023,NON_GAIT,UNKNOWN,3.8857,0.9900,0.3351,46.3885,false,0
        205140865642189,15025,NON_GAIT,POCKET,4.2979,1.9800,0.3421,78.2467,false,0
        205141690544830,15027,WALKING,POCKET,4.7049,0.9581,0.4533,90.6852,false,2
        205142296596294,15028,WALKING,POCKET,4.8696,0.9581,0.4971,96.5922,false,1
        205143037293672,15029,WALKING,POCKET,4.5551,0.9281,0.6029,95.0095,false,1
        205144133684111,15031,WALKING,POCKET,4.4895,0.9048,0.5553,95.5726,false,2
        205145326860571,15033,WALKING,POCKET,4.5567,0.8975,0.5226,95.9454,false,2
        205146505298450,15035,WALKING,POCKET,4.3944,0.8711,0.6144,95.5624,false,2
        """,
    )

    private val secondClimb = Climb(
        StepState(
            bootId = 1,
            lastCumulative = 2_120,
            lastEventNanos = 14_197_846_514_751,
            boutStartedNanos = 13_722_987_398_569,
            boutLastNanos = 14_197_846_514_751,
            boutConfirmed = true,
            rateWindowStartedNanos = 14_195_506_974_114,
            rateWindowStartCounter = 2_115,
        ),
        """
        14199462297789,2122,WALKING,IN_HAND,1.1480,1.0610,0.5323,31.9876,true,2
        14199883130388,2123,WALKING,IN_HAND,1.1798,1.0244,0.5236,32.6570,true,1
        14205555375311,2124,VEHICLE,FLAT,3.2583,1.5635,0.2619,24.4779,false,0
        14207120717720,2126,VEHICLE,FLAT,3.7475,1.4853,0.2592,17.2029,false,0
        14208383093423,2128,NON_GAIT,POCKET,4.0486,1.4853,0.2598,62.7824,false,0
        14209679131601,2130,NON_GAIT,POCKET,4.2407,0.7817,0.3518,90.1234,false,0
        14210992008077,2132,WALKING,POCKET,3.6162,0.7617,0.5548,94.4465,false,2
        14212422701692,2134,WALKING,POCKET,3.1753,0.7817,0.7763,95.0423,false,2
        14213887060742,2136,WALKING,POCKET,3.0876,0.7617,0.6491,94.4240,false,2
        14215452410455,2138,WALKING,POCKET,3.1507,0.7245,0.5816,93.9641,false,2
        14216832613062,2140,WALKING,POCKET,3.2151,0.7073,0.5702,95.4439,false,2
        14217977176020,2142,WALKING,POCKET,3.2199,0.7245,0.4925,94.7780,false,2
        14219189061795,2144,NON_GAIT,POCKET,3.2236,0.7073,0.3860,95.3208,false,0
        14220619763415,2146,WALKING,POCKET,3.2359,0.8029,0.5342,96.4890,false,2
        14221949479572,2148,WALKING,POCKET,3.2295,0.8029,0.6031,96.9708,false,2
        14223397005768,2150,WALKING,POCKET,3.3438,0.7426,0.7836,96.7681,false,2
        14224911834891,2152,WALKING,POCKET,3.3034,0.7426,0.6313,96.0368,false,2
        14226292070539,2154,WALKING,POCKET,3.2385,0.7426,0.5953,94.5974,false,2
        14227571258467,2156,WALKING,POCKET,3.2892,0.7426,0.5689,92.5247,false,2
        14228900998876,2158,WALKING,POCKET,2.9609,0.7426,0.6746,92.1017,false,2
        14230500018529,2160,WALKING,POCKET,2.7845,0.7426,0.5990,95.0293,false,2
        14231930722520,2162,WALKING,POCKET,2.8044,0.6908,0.5467,97.3185,false,2
        14233277265836,2164,WALKING,POCKET,2.9051,0.7073,0.5637,98.7062,false,2
        14234640640725,2166,WALKING,POCKET,3.1470,0.7426,0.5931,99.3379,false,2
        14236054512421,2168,WALKING,POCKET,3.4150,0.7426,0.7944,98.4114,false,2
        14236559467824,2169,WALKING,POCKET,3.4230,0.7426,0.6794,98.4656,false,1
        14237569376862,2171,WALKING,POCKET,3.3128,0.7426,0.6064,97.0910,false,2
        """,
    )

    private class Event(val nanos: Long, val counter: Long, val reading: MotionReading, val credited: Long)

    private fun Climb.events(): List<Event> = events.trim().lines().map { line ->
        val cell = line.trim().split(',')
        Event(
            nanos = cell[0].toLong(),
            counter = cell[1].toLong(),
            reading = MotionReading(
                motion = Motion.valueOf(cell[2]),
                posture = Posture.valueOf(cell[3]),
                std = cell[4].toFloat(),
                cadenceHz = cell[5].toFloat(),
                periodicity = cell[6].toFloat(),
                tiltDeg = cell[7].toFloat(),
                screenOn = cell[8].toBoolean(),
            ),
            credited = cell[9].toLong(),
        )
    }

    /** What each event credits, from the recording's own start, with or without the doubt heard. */
    private fun Climb.replay(doubts: Boolean): List<Long> {
        var state = start
        return events().map { event ->
            val update = StepAccumulator.accumulate(
                state,
                rawCounter = event.counter,
                bootId = state.bootId,
                eventTimestampNanos = event.nanos,
                gaitEvidence = event.reading.stepsAllowed,
                doubtful = doubts && event.reading.stepsDoubtful,
            )
            state = update.state
            update.creditedDelta
        }
    }

    /** The replay is the phone: without the doubt, it credits each event exactly as the phone did. */
    @Test
    fun `the replay credits what the phone credited`() {
        listOf(firstClimb, secondClimb).forEach { climb ->
            assertEquals(climb.events().map { it.credited }, climb.replay(doubts = false))
        }
        assertEquals(15L, firstClimb.replay(doubts = false).sum())
        assertEquals(42L, secondClimb.replay(doubts = false).sum())
    }

    /** With the doubt heard inside the walk, every stair the pedometer counted is a step. */
    @Test
    fun `every stair the pedometer counted is credited`() {
        listOf(firstClimb, secondClimb).forEach { climb ->
            val counted = climb.events().last().counter - climb.start.lastCumulative
            assertEquals(counted, climb.replay(doubts = true).sum())
        }
    }
}
