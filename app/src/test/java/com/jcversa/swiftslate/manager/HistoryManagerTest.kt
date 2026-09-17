package com.jcversa.swiftslate.manager

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryManagerTest {
    private lateinit var history: HistoryManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(HistoryManager.PREFS_NAME, 0).edit().clear().commit()
        history = HistoryManager(context)
    }

    @Test
    fun historyIsOptInAndDisabledRecordsNothing() {
        history.record("?fix", "secret input", "secret output", "gemini")

        assertTrue(history.getEntries().isEmpty())
    }

    @Test
    fun enabledHistoryCanBeRemovedAndDoesNotKeepDataAfterOptOut() {
        history.setEnabled(true)
        history.record("?fix", "input", "output", "groq")
        assertEquals(1, history.getEntries().size)

        history.setEnabled(false)

        assertTrue(history.getEntries().isEmpty())
    }
}
