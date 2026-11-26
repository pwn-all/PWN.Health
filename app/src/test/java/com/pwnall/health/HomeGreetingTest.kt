package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeGreetingTest {
    @Test
    fun theGreetingTurnsOverAtFiveNoonSixAndEleven() {
        assertEquals(R.string.home_good_night, greetingForHour(0))
        assertEquals(R.string.home_good_night, greetingForHour(4))
        assertEquals(R.string.home_good_morning, greetingForHour(5))
        assertEquals(R.string.home_good_morning, greetingForHour(11))
        assertEquals(R.string.home_good_afternoon, greetingForHour(12))
        assertEquals(R.string.home_good_afternoon, greetingForHour(17))
        assertEquals(R.string.home_good_evening, greetingForHour(18))
        assertEquals(R.string.home_good_evening, greetingForHour(22))
        assertEquals(R.string.home_good_night, greetingForHour(23))
    }
}
