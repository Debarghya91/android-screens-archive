package com.paymentstraininghub.app.ui.home

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.awaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import com.paymentstraininghub.app.security.EventLog

/**
 * Log-only gesture observer for the Home scrolling list. It
 * records press, release, throttled move, and scroll-state
 * events with coordinates and the active destination, so a
 * fast scroll that lands somewhere unexpected can be told
 * apart from a tap: a tap is a short press and release with
 * almost no displacement, a fling is a long press with large
 * displacement and a scroll run after release.
 *
 * The observer never consumes a pointer event, never handles
 * a gesture, and never changes layout: it only watches. Lines
 * go to the Diagnostics event log (bounded, shown in
 * Diagnostics and appended to crash reports) and to logcat
 * under the same tag, so an emulator reproduction can capture
 * them with `adb logcat -s ScrollTrace`.
 */
object ScrollTrace {

    const val TAG = "ScrollTrace"

    fun record(message: String) {
        val line = "elapsedMs=${SystemClock.elapsedRealtime()} $message"
        EventLog.record(TAG, line)
        Log.d(TAG, line)
    }
}

/**
 * Attach the [ScrollTrace] observer to a scrolling list.
 * [destination] names the active destination (the Home route
 * plus the selected tab). [listState] is the list's scroll
 * state, watched for scroll start and end. Move events are
 * throttled to one line per 200ms so a fling cannot flood the
 * bounded event log; press, release, and scroll transitions
 * are always recorded.
 */
fun Modifier.scrollTrace(destination: String, listState: LazyListState): Modifier = composed {
    LaunchedEffect(listState) {
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling != wasScrolling) {
                wasScrolling = scrolling
                val label = if (scrolling) "SCROLL_START" else "SCROLL_END"
                ScrollTrace.record(
                    "$label dest=$destination firstItem=${listState.firstVisibleItemIndex} " +
                        "offset=${listState.firstVisibleItemScrollOffset}"
                )
            }
        }
    }
    pointerInput(destination) {
        awaitPointerEventScope {
            var downAtMs = 0L
            var downX = 0f
            var downY = 0f
            var lastMoveLogMs = 0L
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                val nowMs = SystemClock.elapsedRealtime()
                when (event.type) {
                    PointerEventType.Press -> {
                        downAtMs = nowMs
                        downX = change.position.x
                        downY = change.position.y
                        ScrollTrace.record(
                            "DOWN dest=$destination x=${downX.toInt()} y=${downY.toInt()} " +
                                "pointers=${event.changes.size}"
                        )
                    }
                    PointerEventType.Release -> {
                        val dx = change.position.x - downX
                        val dy = change.position.y - downY
                        ScrollTrace.record(
                            "UP dest=$destination x=${change.position.x.toInt()} " +
                                "y=${change.position.y.toInt()} durationMs=${nowMs - downAtMs} " +
                                "dx=${dx.toInt()} dy=${dy.toInt()}"
                        )
                    }
                    PointerEventType.Move -> {
                        if (nowMs - lastMoveLogMs >= 200L) {
                            lastMoveLogMs = nowMs
                            ScrollTrace.record(
                                "MOVE dest=$destination x=${change.position.x.toInt()} " +
                                    "y=${change.position.y.toInt()}"
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}
