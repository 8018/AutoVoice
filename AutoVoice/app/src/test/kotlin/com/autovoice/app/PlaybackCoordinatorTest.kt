package com.autovoice.app

import com.autovoice.voicecore.AudioReply
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlaybackCoordinatorTest {
    @Test fun `late same turn playback and duplicate terminal events are ignored`() {
        val events = mutableListOf<Pair<PlaybackIdentity, PlaybackStage>>()
        val coordinator = PlaybackCoordinator(AudioPlayer {}) { id, stage, _, _ -> events += id to stage }
        val old = coordinator.prepare("turn")
        coordinator.accept("start", "info", old.payload())
        val next = coordinator.prepare("turn")
        coordinator.accept("completed", "info", old.payload())
        coordinator.accept("failed", "error", old.payload())
        coordinator.accept("start", "info", next.payload())
        coordinator.accept("start", "info", next.payload())
        coordinator.accept("completed", "info", next.payload())
        coordinator.accept("completed", "info", next.payload())
        assertEquals(listOf(old to PlaybackStage.STARTED, old to PlaybackStage.INTERRUPTED,
            next to PlaybackStage.STARTED, next to PlaybackStage.COMPLETED), events)
    }

    @Test fun `superseded synthesis cannot start playback or report failure`() {
        var played = 0
        val stages = mutableListOf<PlaybackStage>()
        val coordinator = PlaybackCoordinator(AudioPlayer { played++ }) { _, stage, _, _ -> stages += stage }
        val old = coordinator.prepare("old")
        val next = coordinator.prepare("new")
        coordinator.play(old, AudioReply("audio/wav", byteArrayOf(1)))
        coordinator.failed(old, IllegalStateException("late"))
        assertEquals(0, played)
        assertTrue(coordinator.isActive(next))
        assertTrue(stages.isEmpty())
    }

    @Test fun `stop invalidates identity before synchronous driver completion`() {
        lateinit var coordinator: PlaybackCoordinator
        lateinit var id: PlaybackIdentity
        val events = mutableListOf<PlaybackStage>()
        val driver = object : AudioPlayer {
            override fun play(reply: AudioReply) = Unit
            override fun stop() { coordinator.accept("completed", "info", id.payload()) }
        }
        coordinator = PlaybackCoordinator(driver) { _, stage, _, _ -> events += stage }
        id = coordinator.prepare("turn")
        coordinator.accept("start", "info", id.payload())
        coordinator.stop()
        coordinator.stop()
        assertEquals(listOf(PlaybackStage.STARTED, PlaybackStage.INTERRUPTED), events)
    }

    @Test fun `unidentified events cannot be assigned to newest playback`() {
        val events = mutableListOf<PlaybackStage>()
        val coordinator = PlaybackCoordinator(AudioPlayer {}) { _, stage, _, _ -> events += stage }
        val id = coordinator.prepare("turn")
        coordinator.accept("completed", "info", emptyMap())
        assertTrue(events.isEmpty())
        assertTrue(coordinator.isActive(id))
    }

    @Test fun `blocked event consumer does not hold playback state lock`() {
        val eventEntered = CountDownLatch(1)
        val releaseEvent = CountDownLatch(1)
        val coordinator = PlaybackCoordinator(AudioPlayer {}) { _, stage, _, _ ->
            if (stage == PlaybackStage.STARTED) {
                eventEntered.countDown()
                assertTrue(releaseEvent.await(5, TimeUnit.SECONDS))
            }
        }
        val current = coordinator.prepare("old")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val start = executor.submit { coordinator.accept("start", "info", current.payload()) }
            assertTrue(eventEntered.await(5, TimeUnit.SECONDS))

            val next = executor.submit<PlaybackIdentity> { coordinator.prepare("new") }
                .get(5, TimeUnit.SECONDS)
            assertTrue(coordinator.isActive(next))

            releaseEvent.countDown()
            start.get(5, TimeUnit.SECONDS)
        } finally {
            releaseEvent.countDown()
            executor.shutdownNow()
        }
    }
}
