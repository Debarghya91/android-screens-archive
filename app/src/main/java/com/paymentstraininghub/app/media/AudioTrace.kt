package com.paymentstraininghub.app.media

import android.os.SystemClock
import android.util.Log
import com.paymentstraininghub.app.security.EventLog
import java.util.concurrent.atomic.AtomicInteger

/**
 * One shared structured logger for lesson audio, used by
 * [AudioPlayer], [AudioPlaybackService], and the lesson screen
 * gate-flip effect. Every line carries the event name, the
 * operation id, the playback generation, the player instance
 * identity, the thread, and the elapsed realtime, so a crash
 * seen on a device can be traced to the exact lifecycle
 * sequence. Lines go to the Diagnostics event log (bounded,
 * shown in Diagnostics and appended to crash reports) and to
 * logcat under the same tag, so an emulator reproduction can
 * capture them with `adb logcat -s AudioTrace`.
 *
 * This player is ExoPlayer (Media3), not MediaPlayer: there is
 * no onPrepared/onCompletion/onError/onSeekComplete surface.
 * The listener events traced here are the Media3 equivalents:
 * onPlaybackStateChanged (including the prepared READY state
 * and the ENDED state), onIsPlayingChanged, and onPlayerError.
 * Failures log the full throwable to logcat; nothing is
 * swallowed silently.
 */
object AudioTrace {

    const val TAG = "AudioTrace"

    /** No operation id: the event belongs to no STOP/PLAY request. */
    const val NO_OP = 0

    /** Unknown generation or instance: the caller has none to report. */
    const val UNKNOWN = -1

    private val opCounter = AtomicInteger(0)

    /**
     * A fresh operation id for one STOP or PLAY request. The id
     * links the request, mutex, player, and exit lines of that
     * one operation across threads.
     */
    fun nextOpId(): Int = opCounter.incrementAndGet()

    /**
     * Log one audio event. [player] is the player instance the
     * event concerns, or null when there is none: only its
     * identity hash is recorded, never the instance itself.
     * [throwable] is logged in full to logcat; the Diagnostics
     * line carries its class and message.
     */
    fun log(
        event: String,
        opId: Int = NO_OP,
        generation: Int = UNKNOWN,
        playerInstanceId: Int = UNKNOWN,
        player: Any? = null,
        detail: String = "",
        throwable: Throwable? = null
    ) {
        val hash = player?.let { Integer.toHexString(System.identityHashCode(it)) } ?: "-"
        val line = "$event op=$opId gen=$generation player#=$playerInstanceId hash=$hash " +
            "thread=${Thread.currentThread().name} elapsedMs=${SystemClock.elapsedRealtime()}" +
            if (detail.isNotEmpty()) " $detail" else ""
        val full = if (throwable != null) {
            "$line err=${throwable.javaClass.simpleName}: ${throwable.message}"
        } else {
            line
        }
        EventLog.record(TAG, full)
        if (throwable != null) {
            Log.d(TAG, full, throwable)
        } else {
            Log.d(TAG, full)
        }
    }
}
