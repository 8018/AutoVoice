package com.autovoice.app.navigation

import com.autovoice.app.navigation.NavigationOperation.*
import com.autovoice.app.navigation.NavigationOutcome.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NavigationOperationTrackerTest {
    private var now = 100L
    private val samples = mutableListOf<NavigationOperationSample>()
    private val tracker = NavigationOperationTracker({ now }, { samples.add(it) })

    @Test fun `plan stays pending until its real failure arrives`() {
        val id = tracker.begin(PLAN)
        assertTrue(samples.isEmpty())

        now += 275
        tracker.finish(id, FAILED)

        assertEquals(listOf(NavigationOperationSample(id, PLAN, FAILED, 275)), samples)
    }

    @Test fun `timeout and late callbacks cannot finalize a retry`() {
        val original = tracker.begin(START)
        now += 12_000
        tracker.finish(original, TIMEOUT)

        val retry = tracker.begin(START)
        tracker.finish(original, SUCCEEDED)
        now += 42
        tracker.finish(retry, SUCCEEDED)
        tracker.finish(retry, FAILED)

        assertEquals(listOf(
            NavigationOperationSample(original, START, TIMEOUT, 12_000),
            NavigationOperationSample(retry, START, SUCCEEDED, 42),
        ), samples)
    }

    @Test fun `closing records a pending search as unknown once`() {
        val search = tracker.begin(SEARCH)
        val mute = tracker.begin(AUDIO_MUTE)
        tracker.finish(mute, UNSUPPORTED)
        now += 300
        tracker.finishPending(UNKNOWN)
        tracker.finish(search, SUCCEEDED)

        assertEquals(listOf(
            NavigationOperationSample(mute, AUDIO_MUTE, UNSUPPORTED, 0),
            NavigationOperationSample(search, SEARCH, UNKNOWN, 300),
        ), samples)
    }
}
