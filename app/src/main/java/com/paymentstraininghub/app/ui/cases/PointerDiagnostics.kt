// PointerDiagnostics.kt
// Gesture observation for the Payment Cases fast-scroll defect:
// records down/up coordinates, displacement, consumption, and
// drawer state for gestures on the Cases screen, without ever
// consuming a pointer event, so scrolling and taps are
// unaffected. Surfaced in Diagnostics. Coordinates are pixels
// in the Cases content area; no user content is recorded.

package com.paymentstraininghub.app.ui.cases

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PointerDiagnostics {

    /** One observed gesture on the Cases screen. */
    data class Gesture(
        val elapsedRealtimeMs: Long,
        val downX: Float,
        val downY: Float,
        val upX: Float?,
        val upY: Float?,
        val displacementPx: Float,
        val durationMs: Long,
        val cancelled: Boolean,
        val upConsumed: Boolean,
        val innerDrawerOpen: Boolean,
        val outerDrawerOpen: Boolean,
        val endedNearBottom: Boolean
    ) {
        /** One display line for Diagnostics. */
        fun displayLine(): String {
            val up = if (upX != null && upY != null) {
                "up=(${upX.toInt()},${upY.toInt()})"
            } else {
                "up=cancelled"
            }
            return "down=(${downX.toInt()},${downY.toInt()}) $up " +
                "move=${displacementPx.toInt()}px ${durationMs}ms " +
                "consumed=$upConsumed innerDrawer=$innerDrawerOpen " +
                "outerDrawer=$outerDrawerOpen nearBottom=$endedNearBottom"
        }
    }

    const val MAX_ENTRIES = 30

    private val entries: ArrayDeque<Gesture> = ArrayDeque()

    private val _entriesFlow = MutableStateFlow<List<Gesture>>(emptyList())
    val entriesFlow: StateFlow<List<Gesture>> = _entriesFlow.asStateFlow()

    /** Record one gesture. Old entries drop off past [MAX_ENTRIES]. */
    @Synchronized
    fun record(gesture: Gesture) {
        entries.addLast(gesture)
        while (entries.size > MAX_ENTRIES) {
            entries.removeFirst()
        }
        _entriesFlow.value = entries.toList()
    }

    /** A snapshot of the recorded gestures, oldest first. */
    @Synchronized
    fun snapshot(): List<Gesture> = entries.toList()

    /** Drop every recorded entry. Used by tests. */
    @Synchronized
    fun clear() {
        entries.clear()
        _entriesFlow.value = emptyList()
    }
}
