package com.autovoice.app

import com.autovoice.voicecore.AudioReply
import org.junit.Assert.*
import org.junit.Test

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
}
