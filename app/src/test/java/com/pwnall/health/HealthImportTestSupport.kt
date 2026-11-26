package com.pwnall.health

/**
 * The words an imported row wears, in the tests.
 *
 * Deliberately not the real strings: the readers take them as plain text precisely so that they can
 * be exercised on a bare JVM with no resources, and a title asserted on in a test should be one that
 * cannot silently change when somebody rewords a string resource.
 */
internal fun testTitles() = HealthImportTitles(
    weight = "Body measurement",
    sleep = "Sleep",
    periodStart = "Period",
    periodEnd = "Period finished",
    water = "Water",
    caffeine = "Caffeine",
    vital = { it.name },
    meal = { "Meal ${it.name}" },
)
