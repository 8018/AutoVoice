package com.autovoice.app

/** Client-side admission for an accepted travel task's Markdown frames. */
internal class TravelDocumentInterceptor(private val maxChars: Int = 24_000) {
    private var turnId: String? = null
    private var segmentId: String? = null
    private var started = false
    private var length = 0

    @Synchronized fun admit(turn: String, segment: String) {
        require(turn.isNotBlank() && segment.isNotBlank())
        turnId = turn
        segmentId = segment
        started = false
        length = 0
    }

    /** Returns the retired task identity, if a different recognized turn takes over. */
    @Synchronized fun retireIfDifferent(newTurn: String): Pair<String, String>? {
        val oldTurn = turnId ?: return null
        val oldSegment = segmentId ?: return null
        if (oldTurn == newTurn) return null
        clear()
        return oldTurn to oldSegment
    }

    @Synchronized fun accept(turn: String, segment: String, operation: String, text: String): Boolean {
        if (turn != turnId || segment != segmentId) return false
        when (operation) {
            "start" -> {
                if (started) return false
                started = true
            }
            "delta" -> {
                if (!started || text.isEmpty() || length + text.length > maxChars) return false
                length += text.length
            }
            "complete" -> {
                if (!started) return false
                clear()
            }
            "error" -> clear()
            else -> return false
        }
        return true
    }

    @Synchronized fun clear() {
        turnId = null
        segmentId = null
        started = false
        length = 0
    }
}
