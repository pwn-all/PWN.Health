package com.pwnall.health

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class LevelBackupTest {
    @Test
    fun repeatedOpensAndRepeatedBackupImportsKeepOneRowPerDay() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val source = Room.inMemoryDatabaseBuilder(context, StepsDb::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, StepsDb::class.java).build()
        try {
            val day = AppOpenDay("2026-09-11")
            source.dao().recordAppOpen(day)
            source.dao().recordAppOpen(day)
            assertEquals(listOf(day), source.dao().allAppOpenDays())
            val bytes = ByteArrayOutputStream()
            LocalBackup.export(source, bytes, null, null, null, "level-test")
            target.dao().recordAppOpen(AppOpenDay("2026-09-10"))
            repeat(2) {
                LocalBackup.import(target, bytes.toByteArray().inputStream(), null, null, null, "level-test")
            }
            assertEquals(listOf(AppOpenDay("2026-09-10"), day), target.dao().allAppOpenDays())
        } finally {
            source.close()
            target.close()
        }
    }
}
