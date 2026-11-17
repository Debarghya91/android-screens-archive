package com.paymentstraininghub.app.media

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.model.LessonDetail
import com.paymentstraininghub.app.security.CrashBreadcrumbs
import com.paymentstraininghub.app.security.CrashCapture
import com.paymentstraininghub.app.security.EventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lesson narration playback, backed by one shared ExoPlayer.
 *
 * Behaviour mirrors the iOS AudioPlayerService: streams from the primary
 * audio host, and if the primary fails, switches to the backup audio host
 * once, resuming from the same position. A downloaded copy on this device
 * is preferred over streaming. The saved position for a lesson is restored
 * when playback starts, and saved while playing and on pause or stop.
 * Narration never auto-plays: playback starts only from an explicit
 * playLesson call, which the lesson view makes from the Play tap.
 *
 * Audio focus and becoming-noisy handling use the Media3 defaults
 * (audio attributes are set with handleAudioFocus, and becoming-noisy
 * pauses playback). No wake lock is held here: Media3 handles it.
 */
object AudioPlayer {

    private const val TAG = "AudioPlayer"

    /** Playback speeds, matching iOS: 0.75, 1.0, 1.25, 1.5, 2.0. */
    val playbackSpeeds: List<Float> = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    private const val POSITION_SAVE_INTERVAL_MS = 5_000L
    private const val POSITION_POLL_MS = 250L
    private const val END_CLEAR_MARGIN_MS = 5_000L

    /** AppStore key for the auto-play toggle. Same key as iOS, account-namespaced here. */
    private const val AUTO_ADVANCE_KEY = "pf.audio.autoAdvance.v1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _currentLessonId = MutableStateFlow<String?>(null)
    val currentLessonId: StateFlow<String?> = _currentLessonId.asStateFlow()

    /**
     * True while a playLesson start is in flight: the lesson id is
     * set but the player has not started playing yet (Tester 2
     * P3). The play button shows a spinner and ignores taps while
     * this is true, so a second rapid tap cannot toggle the wrong
     * lesson. Cleared when playback starts, when the start
     * aborts, on error with no fallback, and on stop; every clear
     * is generation-guarded so a newer start is never cleared by
     * an older one's completion.
     */
    private val _playStarting = MutableStateFlow(false)
    val isPlayStarting: StateFlow<Boolean> = _playStarting.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _sleepTimerRemainingMin = MutableStateFlow<Int?>(null)
    val sleepTimerRemainingMin: StateFlow<Int?> = _sleepTimerRemainingMin.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** True when playback fell back to the backup audio host. */
    private val _isUsingFallback = MutableStateFlow(false)
    val isUsingFallback: StateFlow<Boolean> = _isUsingFallback.asStateFlow()

    /** True when the current lesson is playing from its downloaded copy. */
    private val _isPlayingDownloadedCopy = MutableStateFlow(false)
    val isPlayingDownloadedCopy: StateFlow<Boolean> = _isPlayingDownloadedCopy.asStateFlow()

    /** The position playback resumed from on the current play, if it resumed mid-lesson. */
    private val _resumedFromMs = MutableStateFlow<Long?>(null)
    val resumedFromMs: StateFlow<Long?> = _resumedFromMs.asStateFlow()

    /** Mute state, matching iOS AudioPlayerService.isMuted. Session-only: not persisted. */
    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    /** Auto-play next lesson, matching iOS. Persisted under the iOS key; off by default. */
    private val _autoAdvanceEnabled = MutableStateFlow(false)
    val autoAdvanceEnabled: StateFlow<Boolean> = _autoAdvanceEnabled.asStateFlow()

    /**
     * Set by the lesson screen before navigating to the next lesson
     * from auto-advance or the finished prompt: the next lesson's
     * screen starts its narration on load, then clears this. Null
     * in every other navigation, so narration never auto-plays
     * except through the iOS auto-play path.
     */
    var pendingAutoPlayLessonId: String? = null

    /**
     * Fired once when the current lesson's narration plays to the
     * end. Registered with an owner token (the lesson id) so a
     * leaving screen can only clear its own callback: during
     * navigation the old screen's dispose must never null the new
     * screen's callback (audit fix 8). The listener invokes the
     * registered callback only.
     */
    private var playbackEndedCallback: (() -> Unit)? = null
    private var playbackEndedOwner: Any? = null

    fun setOnPlaybackEnded(owner: Any, callback: (() -> Unit)?) {
        playbackEndedOwner = owner
        playbackEndedCallback = callback
    }

    fun clearOnPlaybackEnded(owner: Any) {
        if (playbackEndedOwner == owner) {
            playbackEndedOwner = null
            playbackEndedCallback = null
        }
    }

    init {
        // Crash reports carry the audio state, read from StateFlow
        // values only: the ExoPlayer instance is never touched
        // from the crashing thread.
        CrashCapture.audioStateProvider = {
            "lesson=${_currentLessonId.value} playing=${_isPlaying.value} " +
                "positionMs=${_positionMs.value} durationMs=${_durationMs.value} " +
                "fallback=${_isUsingFallback.value} downloaded=${_isPlayingDownloadedCopy.value} " +
                "playerInstance=$playerInstanceId released=$playerReleased " +
                "generation=$playGeneration serviceAlive=$serviceAlive"
        }
        scope.launch {
            _autoAdvanceEnabled.value =
                runCatching { AppStore.getBoolean(AUTO_ADVANCE_KEY) }.getOrNull() == true
        }
    }

    private var player: ExoPlayer? = null

    /**
     * Player instance identity (solved-APK defect 1): incremented
     * on every construction, so a coroutine holding a stale
     * reference can prove the player it captured is still the
     * live one. [playerReleased] is set only by the serialised
     * release path and cleared only when a new player is built.
     * Both are volatile: the crash-report provider reads them
     * from the crashing thread.
     */
    @Volatile
    private var playerInstanceId = 0

    @Volatile
    private var playerReleased = true

    /**
     * True when [stop] detached the shared listener from the
     * live player. The next [getPlayer] call re-attaches it
     * before returning the player, so no playback ever runs
     * without the listener: detaching on stop keeps a queued
     * state callback from a stopped player from mutating state
     * after the stop, and re-attaching on build or reuse keeps
     * the state flows live. Only ever touched on the main
     * thread, like the player itself.
     */
    private var listenerDetached = false

    /** The live player, or null when no player exists or the player was released. */
    private fun livePlayer(): ExoPlayer? = if (playerReleased) null else player

    /** The current playback generation, for delayed ops outside this object (auto-advance). */
    val playbackGeneration: Int get() = playGeneration

    /**
     * One structured audio lifecycle line for the Diagnostics
     * event log: player instance, generation, thread, playback
     * state, and service ownership, so a crash seen on a device
     * can be traced to the exact lifecycle sequence.
     */
    private fun logAudio(event: String, detail: String = "") {
        val state = runCatching { player?.playbackState }.getOrNull()
        val stateName = when (state) {
            Player.STATE_IDLE -> "idle"
            Player.STATE_BUFFERING -> "buffering"
            Player.STATE_READY -> "ready"
            Player.STATE_ENDED -> "ended"
            else -> "none"
        }
        EventLog.record(
            "AUDIO",
            "$event player#$playerInstanceId gen=$playGeneration " +
                "thread=${Thread.currentThread().name} state=$stateName " +
                "serviceAlive=$serviceAlive released=$playerReleased" +
                if (detail.isNotEmpty()) " $detail" else ""
        )
    }

    private var currentDetail: LessonDetail? = null
    private var fallbackAttempted = false
    private var positionPollJob: Job? = null
    private var sleepJob: Job? = null
    private var lastPositionSavedAtMs = 0L

    /**
     * Serialises playLesson and the error fallback (audit fix 4):
     * two rapid Play taps must never interleave setMediaItem,
     * seekTo, prepare and play on the same player.
     */
    private val playMutex = Mutex()

    /**
     * Generation counter (audit fix 5): incremented on every
     * playLesson, stop, and pause. A fallback coroutine captured
     * an older generation aborts instead of resurrecting playback
     * the user stopped.
     */
    private var playGeneration = 0

    /** The generation of the in-flight playLesson start, or -1 when none is starting. */
    private var startingGeneration = -1

    /** Clear the starting flag only when [generation] is still the in-flight start. */
    private fun clearPlayStarting(generation: Int) {
        if (generation == startingGeneration) {
            _playStarting.value = false
            startingGeneration = -1
        }
    }

    /** True only while playback is user-intended (playing, not user-paused). */
    private var userIntendedPlaying = false

    /** Application context, kept from the first getPlayer call, so the play button can rebuild playback after an idle release. */
    private var appContextRef: Context? = null

    /** True while the playback service (and its MediaSession) is alive. The player is released while idle only when no session holds it. */
    @Volatile
    private var serviceAlive = false

    /**
     * Called by AudioPlaybackService from onCreate/onDestroy. The
     * service NEVER releases the player: it only reports its own
     * lifetime here, and AudioPlayer alone decides about release
     * (releaseIfIdle). Service teardown flips this flag, which
     * makes the next idle release possible; it never releases
     * anything itself.
     */
    fun notePlaybackServiceAlive(alive: Boolean) {
        serviceAlive = alive
        AudioTrace.log(
            if (alive) "SERVICE_ALIVE" else "SERVICE_DESTROYED",
            generation = playGeneration,
            playerInstanceId = playerInstanceId,
            detail = "serviceAlive=$alive"
        )
        if (!alive) {
            // Service teardown invalidates the generation: an
            // in-flight play start or error fallback captured an
            // older generation aborts instead of touching a
            // player whose session is gone. The starting flag is
            // cleared with it, so a start aborted this way can
            // never wedge the play button on "Starting...".
            playGeneration++
            startingGeneration = -1
            _playStarting.value = false
            AudioTrace.log(
                "GENERATION_INVALIDATED",
                generation = playGeneration,
                detail = "reason=service-teardown"
            )
        }
        logAudio(if (alive) "SERVICE_ALIVE" else "SERVICE_DESTROYED")
    }

    /**
     * The shared player listener. Media3 delivers these
     * callbacks synchronously on the main looper, so a callback
     * cannot go stale halfway through handling on this thread;
     * the guards that matter are the released-flag check at the
     * entry of every callback (a callback that arrives after
     * the player was released must touch nothing) and the
     * instance and generation captured here and threaded into
     * the asynchronous error fallback, which re-proves them
     * before touching the player.
     */
    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val instanceAtCallback = playerInstanceId
            val generationAtCallback = playGeneration
            AudioTrace.log(
                "LISTENER_STATE",
                generation = generationAtCallback,
                playerInstanceId = instanceAtCallback,
                player = player,
                detail = "state=$playbackState lesson=${_currentLessonId.value ?: "?"}"
            )
            if (playerReleased) {
                AudioTrace.log(
                    "LISTENER_STALE",
                    generation = generationAtCallback,
                    playerInstanceId = instanceAtCallback,
                    detail = "state=$playbackState released"
                )
                return
            }
            logAudio("PLAYBACK_STATE_CHANGED", "state=$playbackState lesson=${_currentLessonId.value ?: "?"}")
            when (playbackState) {
                Player.STATE_BUFFERING -> _isBuffering.value = true
                Player.STATE_READY -> {
                    _isBuffering.value = false
                    _errorMessage.value = null
                    AudioTrace.log(
                        "PLAYER_PREPARED",
                        generation = generationAtCallback,
                        playerInstanceId = instanceAtCallback,
                        player = player,
                        detail = "lesson=${_currentLessonId.value ?: "?"}"
                    )
                    refreshDuration()
                }
                Player.STATE_ENDED -> {
                    _isPlaying.value = false
                    _isBuffering.value = false
                    stopPositionPolling()
                    clearSavedPosition()
                    playbackEndedCallback?.invoke()
                }
                Player.STATE_IDLE -> {
                    _isBuffering.value = false
                    // External stop (audit fix 3): the notification
                    // or lock-screen stop action calls Player.stop()
                    // directly, which clears the playlist while this
                    // object still reports a loaded lesson. The play
                    // button would then no-op on an empty player.
                    // Reset the state here (the position was already
                    // saved by onIsPlayingChanged) so the play button
                    // re-runs playLesson. An error also lands in
                    // STATE_IDLE, but the media item stays set then,
                    // so the empty-playlist check keeps the two apart.
                    if (_currentLessonId.value != null && livePlayer()?.mediaItemCount == 0) {
                        CrashBreadcrumbs.record("external stop ${_currentLessonId.value}")
                        _currentLessonId.value = null
                        _resumedFromMs.value = null
                        _positionMs.value = 0L
                        _isPlaying.value = false
                        clearPlayStarting(playGeneration)
                        stopPositionPolling()
                    }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val instanceAtCallback = playerInstanceId
            val generationAtCallback = playGeneration
            AudioTrace.log(
                "LISTENER_IS_PLAYING",
                generation = generationAtCallback,
                playerInstanceId = instanceAtCallback,
                player = player,
                detail = "isPlaying=$isPlaying lesson=${_currentLessonId.value ?: "?"}"
            )
            if (playerReleased) {
                AudioTrace.log(
                    "LISTENER_STALE",
                    generation = generationAtCallback,
                    playerInstanceId = instanceAtCallback,
                    detail = "isPlaying=$isPlaying released"
                )
                return
            }
            _isPlaying.value = isPlaying
            logAudio("IS_PLAYING_CHANGED", "isPlaying=$isPlaying lesson=${_currentLessonId.value ?: "?"}")
            if (isPlaying) {
                clearPlayStarting(playGeneration)
                startPositionPolling()
            } else {
                stopPositionPolling()
                persistPosition()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val instanceAtCallback = playerInstanceId
            val generationAtCallback = playGeneration
            AudioTrace.log(
                "LISTENER_ERROR",
                generation = generationAtCallback,
                playerInstanceId = instanceAtCallback,
                player = player,
                detail = "code=${error.errorCodeName} lesson=${currentDetail?.id ?: "?"}",
                throwable = error
            )
            CrashBreadcrumbs.record("error ${currentDetail?.id ?: "?"}: ${error.errorCodeName}")
            logAudio("PLAYER_ERROR", "code=${error.errorCodeName} lesson=${currentDetail?.id ?: "?"}")
            handlePlayerError(error, instanceAtCallback, generationAtCallback)
        }
    }

    /**
     * The shared player instance, built on first use with the application
     * context. The playback service uses this same instance for its
     * MediaSession, so notification controls drive the same playback.
     * The decoder-fallback factory is a Media3 unstable API, used
     * deliberately (audit fix 8): if the device's MP3 decoder fails
     * to initialise, Media3 falls back to another decoder instead
     * of erroring. The lint suppression is scoped to this function
     * only, and covers only that factory usage.
     */
    @SuppressLint("UnsafeOptInUsageError")
    fun getPlayer(context: Context): ExoPlayer {
        // A released player is never reused: the reference is
        // dropped here so the build path below always makes a
        // fresh instance. The serialised release path already
        // nulls the reference, so this is the proof, not the
        // mechanism.
        if (playerReleased) {
            AudioTrace.log(
                "PLAYER_RELEASED_REUSE_BLOCKED",
                generation = playGeneration,
                playerInstanceId = playerInstanceId
            )
            player = null
        }
        player?.let { existing ->
            if (listenerDetached) {
                existing.addListener(listener)
                listenerDetached = false
                AudioTrace.log(
                    "LISTENER_REATTACHED",
                    generation = playGeneration,
                    playerInstanceId = playerInstanceId,
                    player = existing
                )
            }
            AudioTrace.log(
                "PLAYER_REUSED",
                generation = playGeneration,
                playerInstanceId = playerInstanceId,
                player = existing
            )
            return existing
        }
        val appContext = context.applicationContext
        appContextRef = appContext
        AudioTrace.log(
            "PLAYER_CREATE",
            generation = playGeneration,
            detail = "lesson=${currentDetail?.id ?: "?"}"
        )
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()
        // Decoder fallback (audit fix 8): if the device's MP3
        // decoder fails to initialise, Media3 falls back to
        // another decoder instead of erroring. No behaviour
        // change on healthy devices.
        val renderersFactory = DefaultRenderersFactory(appContext)
            .setEnableDecoderFallback(true)
        val built = ExoPlayer.Builder(appContext)
            .setRenderersFactory(renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        built.addListener(listener)
        built.setPlaybackSpeed(_playbackSpeed.value)
        built.volume = if (_isMuted.value) 0f else 1f
        player = built
        playerReleased = false
        listenerDetached = false
        playerInstanceId++
        AudioTrace.log(
            "PLAYER_CREATED",
            generation = playGeneration,
            playerInstanceId = playerInstanceId,
            player = built,
            detail = "lesson=${currentDetail?.id ?: "?"}"
        )
        logAudio("PLAYER_CREATED", "lesson=${currentDetail?.id ?: "?"}")
        return built
    }

    /**
     * Release the shared player while idle (audit fix 2), freeing
     * ExoPlayer's buffers under memory pressure. Only when nothing
     * is playing AND no MediaSession holds the player AND no play
     * start is in flight: releasing under a live session would
     * strand it, and releasing during a start would let the
     * suspended start coroutine touch a released player. The
     * release runs serialised through [playMutex] on the main
     * thread, so it can never interleave with a play start or a
     * fallback retry. The next playLesson or play tap rebuilds
     * the player and resumes from the saved position. Never
     * releases while playing. This is the ONLY release path: the
     * playback service never releases the player.
     */
    fun releaseIfIdle() {
        scope.launch {
            val opId = AudioTrace.nextOpId()
            AudioTrace.log("MUTEX_WAIT", opId = opId, generation = playGeneration, detail = "releaseIfIdle")
            playMutex.withLock {
                try {
                    AudioTrace.log("MUTEX_ACQUIRED", opId = opId, generation = playGeneration, detail = "releaseIfIdle")
                    val current = player ?: return@withLock
                    if (playerReleased) return@withLock
                    if (current.isPlaying || _isPlaying.value) return@withLock
                    if (serviceAlive) return@withLock
                    if (startingGeneration != -1) return@withLock
                    stopPositionPolling()
                    AudioTrace.log(
                        "PLAYER_RELEASE",
                        opId = opId,
                        generation = playGeneration,
                        playerInstanceId = playerInstanceId,
                        player = current
                    )
                    runCatching { current.removeListener(listener) }
                    runCatching { current.release() }
                    player = null
                    playerReleased = true
                    listenerDetached = false
                    AudioTrace.log(
                        "PLAYER_RELEASED",
                        opId = opId,
                        generation = playGeneration,
                        playerInstanceId = playerInstanceId
                    )
                    logAudio("PLAYER_RELEASED")
                    Log.i(TAG, "Player released while idle")
                } finally {
                    AudioTrace.log("MUTEX_RELEASED", opId = opId, generation = playGeneration, detail = "releaseIfIdle")
                }
            }
        }
    }

    /** "1.0x" style label for one playback speed. */
    fun speedLabel(speed: Float): String {
        return when (speed) {
            0.75f -> "0.75x"
            1.25f -> "1.25x"
            1.5f -> "1.5x"
            2.0f -> "2.0x"
            else -> "1.0x"
        }
    }

    /**
     * Start a lesson's narration. Prefers the downloaded file when one is
     * on this device, else streams from the primary audio host. When
     * [startMs] is 0, the saved position for the lesson is restored.
     * When [startMs] is greater than 0, playback starts there instead.
     */
    fun playLesson(context: Context, detail: LessonDetail, startMs: Long = 0L) {
        // Blank file guard (audit fix 8): all current callers
        // check, but the function is public and the guard is free.
        if (detail.audioFileName.isBlank()) {
            _errorMessage.value = "The audio could not be played. Check your connection and tap Retry audio."
            return
        }
        val appContext = context.applicationContext
        appContextRef = appContext
        // Set the lesson id and detail synchronously, before the
        // first suspension (audit fix 4, extended for Tester 2
        // P3): a second rapid tap then takes the togglePlayPause
        // branch for THIS lesson instead of launching a second
        // play coroutine or toggling the previous lesson, whose
        // detail used to linger here until the coroutine ran.
        _currentLessonId.value = detail.id
        currentDetail = detail
        playGeneration++
        startingGeneration = playGeneration
        _playStarting.value = true
        userIntendedPlaying = true
        val opId = AudioTrace.nextOpId()
        CrashBreadcrumbs.record("play ${detail.id} @${startMs}ms")
        logAudio("PLAY_REQUESTED", "lesson=${detail.id} startMs=$startMs")
        AudioTrace.log(
            "PLAY_REQUEST",
            opId = opId,
            generation = playGeneration,
            playerInstanceId = playerInstanceId,
            detail = "lesson=${detail.id} startMs=$startMs"
        )
        // Start the playback service so the MediaSession exists: without
        // it there are no notification/lock-screen controls and no
        // background playback. The service promotes itself to the
        // foreground synchronously in onCreate/onStartCommand,
        // before any player work (wave 3 proven-crash fix); Media3
        // manages the ongoing playback notification afterwards.
        // This call is only ever made while the app is foregrounded: from a UI tap,
        // or from the auto-play handoff on the lesson screen. The
        // service is addressed by class name, not a class literal:
        // the service class carries the Media3 @UnstableApi marker,
        // and a class literal here would force the opt-in onto
        // every caller of playLesson. The name matches the manifest
        // declaration (.media.AudioPlaybackService).
        ContextCompat.startForegroundService(
            appContext,
            Intent().setClassName(
                appContext,
                "com.paymentstraininghub.app.media.AudioPlaybackService"
            )
        )
        val generationAtStart = playGeneration
        scope.launch {
            // Serialised (audit fix 4): a newer playLesson, stop or
            // pause increments the generation, and the older
            // coroutine aborts instead of interleaving player calls.
            AudioTrace.log("MUTEX_WAIT", opId = opId, generation = generationAtStart, detail = "play lesson=${detail.id}")
            playMutex.withLock {
                try {
                    AudioTrace.log("MUTEX_ACQUIRED", opId = opId, generation = generationAtStart, detail = "play lesson=${detail.id}")
                    if (generationAtStart != playGeneration) {
                        AudioTrace.log(
                            "PLAY_ABORTED",
                            opId = opId,
                            generation = playGeneration,
                            detail = "lesson=${detail.id} reason=generation"
                        )
                        clearPlayStarting(generationAtStart)
                        return@withLock
                    }
                    _errorMessage.value = null
                    val player = getPlayer(appContext)
                    val instanceAtStart = playerInstanceId
                    currentDetail = detail
                    fallbackAttempted = false
                    _isUsingFallback.value = false

                    val savedRate = AppStore.getString("audio.rate")
                        ?.toFloatOrNull()
                        ?.takeIf { playbackSpeeds.contains(it) }
                    if (savedRate != null) {
                        _playbackSpeed.value = savedRate
                        player.setPlaybackSpeed(savedRate)
                    }

                    val startPosition: Long
                    if (startMs > 0) {
                        startPosition = startMs
                        _resumedFromMs.value = null
                    } else {
                        val saved = AppStore.getLong("audio.position.${detail.id}") ?: 0L
                        startPosition = saved
                        _resumedFromMs.value = if (saved > 0) saved else null
                    }

                    val downloaded = LessonDownloadStore.downloadedFile(appContext, detail.id)
                    _isPlayingDownloadedCopy.value = downloaded != null
                    val mediaItem = if (downloaded != null) {
                        MediaItem.fromUri(Uri.fromFile(downloaded))
                    } else {
                        MediaItem.fromUri(ContentRepository.audioUrl(detail.audioFileName, true))
                    }
                    _currentLessonId.value = detail.id
                    _positionMs.value = startPosition
                    refreshDuration()
                    // Use-after-release guard (solved-APK defect 1):
                    // the DataStore reads above suspend, so re-prove
                    // the captured player is still the live one and
                    // this start is still current before touching it.
                    // The serialised release path should already make
                    // this impossible; the check is the proof. The
                    // reference check proves the captured player is
                    // the same object the singleton still holds, not
                    // merely an instance with a matching id.
                    if (generationAtStart != playGeneration ||
                        playerInstanceId != instanceAtStart || playerReleased ||
                        this@AudioPlayer.player !== player
                    ) {
                        AudioTrace.log(
                            "PLAY_ABORTED",
                            opId = opId,
                            generation = playGeneration,
                            playerInstanceId = playerInstanceId,
                            player = player,
                            detail = "lesson=${detail.id} reason=stale-player"
                        )
                        logAudio("PLAY_START_ABORTED", "lesson=${detail.id} gen=$generationAtStart")
                        clearPlayStarting(generationAtStart)
                        return@withLock
                    }
                    player.setMediaItem(mediaItem)
                    if (startPosition > 0) player.seekTo(startPosition)
                    AudioTrace.log(
                        "PLAYER_PREPARE",
                        opId = opId,
                        generation = playGeneration,
                        playerInstanceId = playerInstanceId,
                        player = player,
                        detail = "lesson=${detail.id}"
                    )
                    player.prepare()
                    player.play()
                    AudioTrace.log(
                        "PLAYER_START",
                        opId = opId,
                        generation = playGeneration,
                        playerInstanceId = playerInstanceId,
                        player = player,
                        detail = "lesson=${detail.id} startMs=$startPosition"
                    )
                    lastPositionSavedAtMs = 0L
                } finally {
                    AudioTrace.log("MUTEX_RELEASED", opId = opId, generation = playGeneration, detail = "play lesson=${detail.id}")
                }
            }
        }
    }

    /** Pause when playing, resume when paused. Does nothing when no lesson is loaded. */
    fun togglePlayPause() {
        // A start is in flight (Tester 2 P3): the player is not
        // ready yet, so a toggle now would pause the wrong lesson
        // or no-op. The play button shows "Starting..." until
        // playback begins.
        AudioTrace.log(
            "TOGGLE_REQUEST",
            generation = playGeneration,
            playerInstanceId = playerInstanceId,
            detail = "lesson=${_currentLessonId.value ?: "?"} starting=${_playStarting.value}"
        )
        if (_playStarting.value) {
            AudioTrace.log("TOGGLE_IGNORED", generation = playGeneration, detail = "reason=start-in-flight")
            return
        }
        val detail = currentDetail
        val player = livePlayer()
        if (player == null) {
            // The player was released while idle (audit fix 2):
            // rebuild playback for the loaded lesson from the
            // current position instead of doing nothing.
            if (detail != null) {
                AudioTrace.log("TOGGLE_REBUILD", generation = playGeneration, detail = "lesson=${detail.id}")
                appContextRef?.let { playLesson(it, detail, _positionMs.value) }
            }
            return
        }
        if (player.isPlaying) {
            CrashBreadcrumbs.record("pause ${detail?.id ?: "?"}")
            userIntendedPlaying = false
            playGeneration++
            logAudio("PAUSE", "lesson=${detail?.id ?: "?"}")
            AudioTrace.log(
                "TOGGLE_PAUSE",
                generation = playGeneration,
                playerInstanceId = playerInstanceId,
                player = player,
                detail = "lesson=${detail?.id ?: "?"}"
            )
            player.pause()
            return
        }
        // Recovery (audit fix 3): a player left empty by an
        // external stop reports STATE_IDLE. Re-run playLesson for
        // the loaded lesson instead of no-op play().
        if (player.playbackState == Player.STATE_IDLE && detail != null) {
            AudioTrace.log("TOGGLE_REBUILD", generation = playGeneration, detail = "lesson=${detail.id} reason=idle-empty")
            appContextRef?.let { playLesson(it, detail, _positionMs.value) }
            return
        }
        if (_currentLessonId.value == null) return
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        _errorMessage.value = null
        userIntendedPlaying = true
        CrashBreadcrumbs.record("resume ${detail?.id ?: "?"}")
        logAudio("RESUME", "lesson=${detail?.id ?: "?"}")
        AudioTrace.log(
            "TOGGLE_RESUME",
            generation = playGeneration,
            playerInstanceId = playerInstanceId,
            player = player,
            detail = "lesson=${detail?.id ?: "?"}"
        )
        player.play()
    }

    /** Seek to an absolute position in milliseconds, clamped to the lesson length. */
    fun seekTo(ms: Long) {
        val player = livePlayer() ?: return
        val duration = player.duration.takeIf { it > 0 } ?: _durationMs.value
        val clamped = ms.coerceIn(0L, if (duration > 0) duration else ms.coerceAtLeast(0L))
        CrashBreadcrumbs.record("seek ${currentDetail?.id ?: "?"} @${clamped}ms")
        player.seekTo(clamped)
        _positionMs.value = clamped
        persistPosition()
    }

    /** Seek relative to the current position, for the 15 second skip buttons. */
    fun seekBy(deltaMs: Long) {
        val player = livePlayer() ?: return
        seekTo(player.currentPosition + deltaMs)
    }

    /** Toggle mute, matching iOS toggleMute. Applies at once and to the next player built. */
    fun toggleMute() {
        _isMuted.value = !_isMuted.value
        livePlayer()?.volume = if (_isMuted.value) 0f else 1f
    }

    /** Set the auto-play-next-lesson toggle and persist it. */
    fun setAutoAdvance(enabled: Boolean) {
        _autoAdvanceEnabled.value = enabled
        scope.launch {
            persistSafely { AppStore.putBoolean(AUTO_ADVANCE_KEY, enabled) }
        }
    }

    /** Set the playback speed and persist it for the next lesson. */
    fun setSpeed(speed: Float) {
        val clamped = speed.takeIf { playbackSpeeds.contains(it) } ?: 1.0f
        _playbackSpeed.value = clamped
        livePlayer()?.setPlaybackSpeed(clamped)
        scope.launch {
            persistSafely { AppStore.putString("audio.rate", clamped.toString()) }
        }
    }

    /**
     * Set the sleep timer. [minutes] null turns it off. When the timer
     * fires, playback pauses. The remaining minutes count down in
     * [sleepTimerRemainingMin] while the timer runs.
     */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        sleepJob = null
        if (minutes == null || minutes <= 0) {
            _sleepTimerRemainingMin.value = null
            return
        }
        _sleepTimerRemainingMin.value = minutes
        val deadlineMs = System.currentTimeMillis() + minutes * 60_000L
        val generationAtSet = playGeneration
        val instanceAtSet = playerInstanceId
        sleepJob = scope.launch {
            while (true) {
                val remainingMs = deadlineMs - System.currentTimeMillis()
                if (remainingMs <= 0) break
                val remainingMin = ((remainingMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
                _sleepTimerRemainingMin.value = remainingMin
                delay(5_000L)
            }
            _sleepTimerRemainingMin.value = null
            // Generation-guarded (solved-APK defect 1): only pause
            // when this timer is still current, playback is still
            // user-intended, and the player is still the live one.
            // A newer play, a manual pause, or a release-and-rebuild
            // since the timer was set aborts the fire.
            if (generationAtSet != playGeneration || !userIntendedPlaying ||
                instanceAtSet != playerInstanceId || playerReleased
            ) {
                logAudio("SLEEP_TIMER_ABORTED", "gen=$generationAtSet")
                return@launch
            }
            logAudio("SLEEP_TIMER_FIRED", "minutes=$minutes")
            livePlayer()?.pause()
        }
    }

    /**
     * Stop playback. The saved position is kept, so playing again
     * resumes from it. Idempotent: a second stop finds no loaded
     * lesson and an idle or absent player, skips the player
     * call, and repeats only the already-idle state reset. The
     * player call is guarded to legal states only: it runs only
     * when a live player exists and holds media or is out of
     * the idle state, so stop on an empty idle player is a
     * no-op instead of a state churn. The shared listener is
     * detached from the stopped player, so a queued state
     * callback from it can never mutate state after the stop;
     * the next [getPlayer] call re-attaches it before the
     * player is used again. The player instance itself is
     * reused after a stop: ExoPlayer documents stop to idle
     * followed by setMediaItem and prepare as the reuse path,
     * and the playback service's MediaSession holds this same
     * instance, so replacing it on every stop would strand the
     * session and the notification controls. A released player
     * is never reused: release nulls the reference, and
     * [getPlayer] rebuilds a fresh instance.
     */
    fun stop() {
        val opId = AudioTrace.nextOpId()
        AudioTrace.log(
            "STOP_REQUEST",
            opId = opId,
            generation = playGeneration,
            playerInstanceId = playerInstanceId,
            detail = "lesson=${_currentLessonId.value ?: "?"}"
        )
        CrashBreadcrumbs.record("stop ${_currentLessonId.value ?: "?"}")
        playGeneration++
        startingGeneration = -1
        _playStarting.value = false
        userIntendedPlaying = false
        persistPosition()
        setSleepTimer(null)
        logAudio("STOPPED", "lesson=${_currentLessonId.value ?: "?"}")
        val current = livePlayer()
        if (current != null &&
            (current.playbackState != Player.STATE_IDLE || current.mediaItemCount > 0)
        ) {
            AudioTrace.log(
                "STOP_PLAYER_CALL",
                opId = opId,
                generation = playGeneration,
                playerInstanceId = playerInstanceId,
                player = current,
                detail = "state=${current.playbackState} items=${current.mediaItemCount}"
            )
            runCatching { current.stop() }
                .onFailure {
                    AudioTrace.log(
                        "STOP_PLAYER_FAILED",
                        opId = opId,
                        generation = playGeneration,
                        playerInstanceId = playerInstanceId,
                        player = current,
                        throwable = it
                    )
                }
            runCatching { current.removeListener(listener) }
            listenerDetached = true
        } else {
            AudioTrace.log(
                "STOP_PLAYER_SKIPPED",
                opId = opId,
                generation = playGeneration,
                playerInstanceId = playerInstanceId,
                detail = "no-live-player-or-idle-empty"
            )
        }
        _isPlaying.value = false
        _isBuffering.value = false
        stopPositionPolling()
        _currentLessonId.value = null
        _resumedFromMs.value = null
        _positionMs.value = 0L
        AudioTrace.log("STOP_EXIT", opId = opId, generation = playGeneration)
    }

    /**
     * The error fallback. [instanceAtError] and
     * [generationAtError] are captured by the listener callback
     * that observed the error, so the fallback coroutine
     * re-proves the exact player and generation that failed,
     * rather than whatever is live when it runs.
     */
    private fun handlePlayerError(
        error: PlaybackException,
        instanceAtError: Int,
        generationAtError: Int
    ) {
        val player = livePlayer()
        if (player == null) {
            AudioTrace.log(
                "FALLBACK_SKIPPED",
                generation = generationAtError,
                playerInstanceId = instanceAtError,
                detail = "no-live-player code=${error.errorCodeName}"
            )
            return
        }
        val detail = currentDetail
        if (!fallbackAttempted && detail != null) {
            fallbackAttempted = true
            val resumeAt = player.currentPosition.coerceAtLeast(0L)
            val opId = AudioTrace.nextOpId()
            scope.launch {
                // Serialised with playLesson (audit fix 4), and
                // guarded (audit fix 5): abort when the user
                // paused, stopped, or moved to another lesson
                // between the error and this relaunch, or when
                // the player was released and rebuilt meanwhile.
                AudioTrace.log("MUTEX_WAIT", opId = opId, generation = generationAtError, detail = "fallback lesson=${detail.id}")
                playMutex.withLock {
                    try {
                        AudioTrace.log("MUTEX_ACQUIRED", opId = opId, generation = generationAtError, detail = "fallback lesson=${detail.id}")
                        val aborted = generationAtError != playGeneration ||
                            !userIntendedPlaying ||
                            _currentLessonId.value != detail.id ||
                            this@AudioPlayer.player !== player ||
                            playerInstanceId != instanceAtError || playerReleased
                        if (aborted) {
                            AudioTrace.log(
                                "FALLBACK_ABORTED",
                                opId = opId,
                                generation = playGeneration,
                                playerInstanceId = playerInstanceId,
                                detail = "lesson=${detail.id}"
                            )
                            clearPlayStarting(generationAtError)
                            return@withLock
                        }
                        _isUsingFallback.value = true
                        _isPlayingDownloadedCopy.value = false
                        CrashBreadcrumbs.record("fallback ${detail.id} @${resumeAt}ms")
                        AudioTrace.log(
                            "FALLBACK_START",
                            opId = opId,
                            generation = playGeneration,
                            playerInstanceId = playerInstanceId,
                            player = player,
                            detail = "lesson=${detail.id} resumeAt=$resumeAt"
                        )
                        val fallbackUrl = ContentRepository.audioUrl(detail.audioFileName, false)
                        player.setMediaItem(MediaItem.fromUri(fallbackUrl))
                        if (resumeAt > 0) player.seekTo(resumeAt)
                        player.prepare()
                        player.play()
                    } finally {
                        AudioTrace.log("MUTEX_RELEASED", opId = opId, generation = playGeneration, detail = "fallback lesson=${detail.id}")
                    }
                }
            }
        } else {
            _errorMessage.value = "The audio could not be played. Check your connection and tap Retry audio."
            _isBuffering.value = false
            clearPlayStarting(playGeneration)
        }
    }

    private fun refreshDuration() {
        val playerDuration = runCatching { livePlayer()?.duration }.getOrNull()?.takeIf { it > 0 }
        val fallback = currentDetail?.durationSec
            ?.takeIf { it > 0 }
            ?.let { (it * 1000).toLong() }
            ?: 0L
        _durationMs.value = playerDuration ?: fallback
    }

    private fun startPositionPolling() {
        if (positionPollJob?.isActive == true) return
        positionPollJob = scope.launch {
            while (true) {
                val player = livePlayer() ?: break
                val instanceAtPoll = playerInstanceId
                if (!player.isPlaying) break
                _positionMs.value = player.currentPosition.coerceAtLeast(0L)
                refreshDuration()
                val now = System.currentTimeMillis()
                if (now - lastPositionSavedAtMs >= POSITION_SAVE_INTERVAL_MS) {
                    lastPositionSavedAtMs = now
                    persistPosition()
                }
                delay(POSITION_POLL_MS)
                if (playerReleased || playerInstanceId != instanceAtPoll) break
            }
        }
    }

    private fun stopPositionPolling() {
        positionPollJob?.cancel()
        positionPollJob = null
    }

    private fun persistPosition() {
        val lessonId = _currentLessonId.value ?: return
        // The position is read on the calling (main) thread,
        // before switching to IO: the player is never touched
        // from the IO thread, and a released player yields the
        // last known position instead of throwing.
        val position = runCatching { livePlayer()?.currentPosition }.getOrNull()
            ?: _positionMs.value
        val duration = _durationMs.value
        scope.launch(Dispatchers.IO) {
            val key = "audio.position.$lessonId"
            persistSafely {
                if (duration > 0 && position >= duration - END_CLEAR_MARGIN_MS) {
                    AppStore.putLong(key, 0L)
                } else if (position > 0) {
                    AppStore.putLong(key, position)
                }
            }
        }
    }

    private fun clearSavedPosition() {
        val lessonId = _currentLessonId.value ?: return
        scope.launch(Dispatchers.IO) {
            persistSafely { AppStore.putLong("audio.position.$lessonId", 0L) }
        }
    }

    /**
     * Guarded DataStore write (audit fix 1): AppStore propagates
     * DataStore failures, and this object's scope is a
     * SupervisorJob, so an unguarded throw from a launched
     * coroutine kills the process. A failed position save must
     * never crash playback: log it and carry on.
     */
    private suspend fun persistSafely(block: suspend () -> Unit) {
        runCatching { block() }
            .onFailure { Log.w(TAG, "Audio setting save failed: ${it.message}") }
    }
}
