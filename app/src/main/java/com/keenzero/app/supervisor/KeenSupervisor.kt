package com.keenzero.app.supervisor

import android.content.Context
import android.os.SystemClock
import com.keenzero.app.AppUiState
import com.keenzero.app.diagnostics.NavigationEvent
import com.keenzero.app.perf.PerformanceGovernor
import com.keenzero.app.perf.PerformancePolicy
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * Sole journey/state authority. [com.keenzero.app.KeenActivity] remains a thin
 * Android host that forwards lifecycle/input and renders [uiState].
 *
 * Full behavioural migration is incremental: generation tracking, stale rejection,
 * crash-loop protection, and performance policy live here first.
 */
class KeenSupervisor(
    context: Context,
) {
    val generation = EventGeneration()
    val policy: PerformancePolicy = PerformanceGovernor.evaluate(context)

    @Volatile
    var uiState: AppUiState = AppUiState.HOME
        private set

    private val events = ArrayDeque<NavigationEvent>(MAX_EVENTS)
    private val rendererDeathTimestamps = ArrayDeque<Long>()
    private var crashLoopTripped = false
    private var lastAcceptedSequence = 0L

    fun setUiState(state: AppUiState) {
        uiState = state
    }

    fun record(event: NavigationEvent) {
        synchronized(events) {
            if (events.size >= MAX_EVENTS) events.removeFirst()
            events.addLast(event)
        }
    }

    fun eventSnapshot(): List<NavigationEvent> = synchronized(events) { events.toList() }

    fun eventsTailJson(n: Int): org.json.JSONArray {
        val snap = eventSnapshot().takeLast(n)
        return org.json.JSONArray().also { arr ->
            snap.forEach { e ->
                arr.put(
                    JSONObject()
                        .put("t", e.t)
                        .put("type", e.type)
                        .put("url", e.url)
                        .put("detail", e.detail),
                )
            }
        }
    }

    /**
     * Accept or reject an async product mutation. Stale envelopes are logged and dropped.
     */
    fun accept(event: EventEnvelope): Boolean {
        if (!generation.accepts(event)) {
            record(
                NavigationEvent(
                    System.currentTimeMillis(),
                    "STALE_EVENT_DROPPED",
                    detail = "type=${event.type} nav=${event.navigationId}/${generation.navigationId} " +
                        "play=${event.playIntentId}/${generation.playIntentId} " +
                        "sess=${event.playbackSessionId}/${generation.playbackSessionId} seq=${event.sequenceNumber}",
                ),
            )
            return false
        }
        if (event.sequenceNumber <= lastAcceptedSequence && event.sequenceNumber > 0) {
            // Allow equal only for first; strictly increasing preferred.
            if (lastAcceptedSequence > 0 && event.sequenceNumber < lastAcceptedSequence) {
                record(
                    NavigationEvent(
                        System.currentTimeMillis(),
                        "STALE_EVENT_DROPPED",
                        detail = "type=${event.type} reason=sequence seq=${event.sequenceNumber} last=$lastAcceptedSequence",
                    ),
                )
                return false
            }
        }
        lastAcceptedSequence = maxOf(lastAcceptedSequence, event.sequenceNumber)
        return true
    }

    fun onTopLevelNavigationCommitted() {
        generation.bumpNavigation()
    }

    fun onPlayIntentCreated(id: String, playbackSessionId: String) {
        generation.setPlayIntent(id)
        generation.setPlaybackSession(playbackSessionId)
    }

    fun onPlaybackCleared() {
        generation.clearPlayback()
    }

    /**
     * @return true if recovery should auto-restore; false if crash-loop protection engaged.
     */
    fun onRendererDeath(): Boolean {
        val now = SystemClock.elapsedRealtime()
        rendererDeathTimestamps.addLast(now)
        while (rendererDeathTimestamps.isNotEmpty() &&
            now - rendererDeathTimestamps.first() > policy.crashLoopWindowMs
        ) {
            rendererDeathTimestamps.removeFirst()
        }
        if (rendererDeathTimestamps.size >= policy.crashLoopMaxDeaths) {
            crashLoopTripped = true
            record(
                NavigationEvent(
                    System.currentTimeMillis(),
                    "CRASH_LOOP_PROTECTION",
                    detail = "deaths=${rendererDeathTimestamps.size} windowMs=${policy.crashLoopWindowMs}",
                ),
            )
            return false
        }
        return true
    }

    fun isCrashLoopTripped(): Boolean = crashLoopTripped

    fun resetCrashLoopForUserAction() {
        crashLoopTripped = false
        rendererDeathTimestamps.clear()
    }

    fun diagnosticsExtras(): JSONObject = JSONObject()
        .put("performance", policy.toJson())
        .put(
            "supervisor",
            JSONObject()
                .put("sessionId", generation.sessionId)
                .put("navigationId", generation.navigationId)
                .put("playIntentId", generation.playIntentId)
                .put("playbackSessionId", generation.playbackSessionId)
                .put("sequenceNumber", generation.sequenceNumber)
                .put("crashLoopTripped", crashLoopTripped)
                .put("uiState", uiState.name),
        )

    companion object {
        private const val MAX_EVENTS = 400
    }
}
