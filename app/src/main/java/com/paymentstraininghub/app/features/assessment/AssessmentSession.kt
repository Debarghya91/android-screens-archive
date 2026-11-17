// AssessmentSession.kt
// Timed and randomised assessment sessions, ported from iOS
// AssessmentSession (Models/AssessmentSession.swift). A session
// runs over real lesson quiz questions only: questions are
// collected by AssessmentScreen from the lesson pages and handed
// to this session. This session never invents a question, an
// option or a correct answer. It adds two modes on top of that
// real question set:
// - Timed mode: 60 seconds per question, shown as a countdown,
//   with a total time limit for the whole session. When a
//   question's time expires the session auto-advances and the
//   question is marked unanswered, never guessed. When the total
//   time expires the session finishes.
// - Randomised mode: questions and options are shuffled with a
//   seed drawn per session, so a retake is different. The correct
//   answer is tracked through the shuffle permutation, never by
//   position.
// Remaining time is always computed from stored wall-clock end
// times, never by counting ticks, so backgrounding gains no extra
// time. The ticker is stopped when the session finishes and when
// stop() is called, so no timer can leak past the view.

package com.paymentstraininghub.app.features.assessment

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One assessment question, built from a real lesson quiz question. */
data class AssessmentQuestion(
    val id: String,
    val lessonId: String,
    val lessonTitle: String,
    val question: String,
    val options: List<String>,
    val correctOptionIndex: Int,
    val explanation: String
)

/** A small deterministic random source (xorshift64star) so one session's shuffle is reproducible from its seed. */
internal class SeededRandom(seed: Long) {
    private var state: Long = if (seed == 0L) -0x61C8864680B583EBL else seed

    fun nextLong(): Long {
        var x = state
        x = x xor (x ushr 12)
        x = x xor (x shl 25)
        x = x xor (x ushr 27)
        state = x
        return x * 0x2545F4914F6CDD1DL
    }

    /** Uniform int in [0, bound). */
    fun nextInt(bound: Int): Int {
        require(bound > 0)
        val value = nextLong() ushr 1
        return (value % bound).toInt()
    }
}

/** Elapsed time as "M:SS", matching AssessmentSession.format(seconds:) on iOS. */
fun formatElapsed(seconds: Int): String {
    val total = seconds.coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

class AssessmentSession {
    companion object {
        /** Seconds allowed per question in timed mode. */
        const val SECONDS_PER_QUESTION = 60

        /** The Final Assessment total time limit: 30 minutes for all 20 questions. Always on there. */
        const val FINAL_ASSESSMENT_TOTAL_SECONDS = 30 * 60
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickerJob: Job? = null

    private val _questions = MutableStateFlow<List<AssessmentQuestion>>(emptyList())
    val questions: StateFlow<List<AssessmentQuestion>> = _questions.asStateFlow()

    private val _isStarted = MutableStateFlow(false)
    val isStarted: StateFlow<Boolean> = _isStarted.asStateFlow()

    private val _isFinished = MutableStateFlow(false)
    val isFinished: StateFlow<Boolean> = _isFinished.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    /** Chosen option index per question id. Absent means unanswered. */
    private val _answers = MutableStateFlow<Map<String, Int>>(emptyMap())
    val answers: StateFlow<Map<String, Int>> = _answers.asStateFlow()

    /** Seconds spent on each question id. */
    private val _timeOnQuestion = MutableStateFlow<Map<String, Int>>(emptyMap())
    val timeOnQuestion: StateFlow<Map<String, Int>> = _timeOnQuestion.asStateFlow()

    private val _secondsLeftForQuestion = MutableStateFlow(0)
    val secondsLeftForQuestion: StateFlow<Int> = _secondsLeftForQuestion.asStateFlow()

    private val _totalSecondsLeft = MutableStateFlow(0)
    val totalSecondsLeft: StateFlow<Int> = _totalSecondsLeft.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()

    var isTimed: Boolean = false
        private set

    private var questionEndTimeMs: Long = 0L
    private var sessionEndTimeMs: Long = 0L
    private var sessionStartTimeMs: Long = 0L
    private var questionStartTimeMs: Long = 0L
    private var totalSecondsLimit: Int = 0
    private var perQuestionTimeoutEnabled: Boolean = true

    /** The total time limit for this session, for the timer bar progress. Zero before start. */
    val totalLimitSeconds: Int
        get() = totalSecondsLimit

    val currentQuestion: AssessmentQuestion?
        get() = _questions.value.getOrNull(_currentIndex.value)

    val correctCount: Int
        get() = _questions.value.count { _answers.value[it.id] == it.correctOptionIndex }

    val scorePercent: Double
        get() {
            val total = _questions.value.size
            if (total == 0) return 0.0
            return 100.0 * correctCount.toDouble() / total.toDouble()
        }

    val unansweredCount: Int
        get() = _questions.value.count { it.id !in _answers.value }

    /**
     * Starts a session over the given questions. When randomised,
     * questions and options are shuffled with a fresh seed; the
     * correct answer follows its option through the permutation.
     * [totalSecondsOverride] sets a fixed total limit (the Final
     * Assessment's 30 minutes) instead of 60 seconds per question.
     * [perQuestionTimeout] false disables the per-question
     * auto-advance: the Final Assessment has only the total clock.
     */
    fun start(
        sourceQuestions: List<AssessmentQuestion>,
        timed: Boolean,
        randomised: Boolean,
        totalSecondsOverride: Int? = null,
        perQuestionTimeout: Boolean = true
    ) {
        stop()
        val prepared = if (randomised) shuffleQuestions(sourceQuestions) else sourceQuestions
        _questions.value = prepared
        _answers.value = emptyMap()
        _timeOnQuestion.value = emptyMap()
        _currentIndex.value = 0
        _isFinished.value = false
        isTimed = timed
        perQuestionTimeoutEnabled = perQuestionTimeout
        val now = System.currentTimeMillis()
        sessionStartTimeMs = now
        questionStartTimeMs = now
        totalSecondsLimit = totalSecondsOverride ?: (SECONDS_PER_QUESTION * maxOf(prepared.size, 1))
        questionEndTimeMs = now + SECONDS_PER_QUESTION * 1000L
        sessionEndTimeMs = now + totalSecondsLimit * 1000L
        _secondsLeftForQuestion.value = SECONDS_PER_QUESTION
        _totalSecondsLeft.value = totalSecondsLimit
        _elapsedSeconds.value = 0
        _isStarted.value = true
        if (timed) startTicker()
    }

    /** Records the chosen option for the current question and advances, or finishes on the last. */
    fun answerAndAdvance(selectedOption: Int) {
        val question = currentQuestion ?: return
        recordTimeOnCurrentQuestion()
        _answers.value = _answers.value + (question.id to selectedOption)
        advance()
    }

    /** Moves to the next question without recording an answer (timed expiry). Never guesses. */
    private fun expireCurrentQuestion() {
        recordTimeOnCurrentQuestion()
        advance()
    }

    private fun advance() {
        val now = System.currentTimeMillis()
        if (_currentIndex.value >= _questions.value.size - 1) {
            finish()
        } else {
            _currentIndex.value = _currentIndex.value + 1
            questionStartTimeMs = now
            questionEndTimeMs = now + SECONDS_PER_QUESTION * 1000L
            _secondsLeftForQuestion.value = SECONDS_PER_QUESTION
        }
    }

    fun finish() {
        recordTimeOnCurrentQuestion()
        _elapsedSeconds.value =
            ((System.currentTimeMillis() - sessionStartTimeMs) / 1000L).toInt().coerceAtLeast(0)
        _isFinished.value = true
        stopTicker()
    }

    /** Returns the session to its pre-start state, for "Change options". Clears answers and timers. */
    fun resetToSetup() {
        stopTicker()
        _answers.value = emptyMap()
        _timeOnQuestion.value = emptyMap()
        _currentIndex.value = 0
        _isFinished.value = false
        _isStarted.value = false
        _secondsLeftForQuestion.value = 0
        _totalSecondsLeft.value = 0
        _elapsedSeconds.value = 0
    }

    /** Stops the ticker. The view calls this when it disappears, so no timer can leak. */
    fun stop() {
        stopTicker()
    }

    /**
     * Restarts the ticker after a rotation or a return to the
     * screen, when the session is running and timed. The
     * wall-clock end times carry the truth, so restarting is
     * always safe and never grants extra time. No-op when the
     * session never started, is untimed, already finished, or
     * the ticker is already running.
     */
    fun resumeTicker() {
        if (!_isStarted.value || !isTimed || _isFinished.value) return
        if (tickerJob?.isActive == true) return
        startTicker()
    }

    private fun recordTimeOnCurrentQuestion() {
        val question = currentQuestion ?: return
        val spent = ((System.currentTimeMillis() - questionStartTimeMs) / 1000L).toInt().coerceAtLeast(0)
        _timeOnQuestion.value = _timeOnQuestion.value + (question.id to spent)
    }

    private fun startTicker() {
        tickerJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val questionLeft = ((questionEndTimeMs - now) / 1000L).toInt().coerceAtLeast(0)
                val totalLeft = ((sessionEndTimeMs - now) / 1000L).toInt().coerceAtLeast(0)
                _secondsLeftForQuestion.value = questionLeft
                _totalSecondsLeft.value = totalLeft
                _elapsedSeconds.value =
                    ((now - sessionStartTimeMs) / 1000L).toInt().coerceAtLeast(0)
                if (totalLeft <= 0) {
                    finish()
                    return@launch
                }
                if (perQuestionTimeoutEnabled && questionLeft <= 0 && !_isFinished.value) {
                    expireCurrentQuestion()
                    if (_isFinished.value) return@launch
                }
                delay(250L)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun shuffleQuestions(source: List<AssessmentQuestion>): List<AssessmentQuestion> {
        val random = SeededRandom(System.nanoTime())
        val shuffledQuestions = source.toMutableList()
        for (i in shuffledQuestions.size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = shuffledQuestions[i]
            shuffledQuestions[i] = shuffledQuestions[j]
            shuffledQuestions[j] = tmp
        }
        return shuffledQuestions.map { question ->
            val order = question.options.indices.toMutableList()
            for (i in order.size - 1 downTo 1) {
                val j = random.nextInt(i + 1)
                val tmp = order[i]
                order[i] = order[j]
                order[j] = tmp
            }
            val shuffledOptions = order.map { question.options[it] }
            val newCorrect = order.indexOf(question.correctOptionIndex)
            question.copy(options = shuffledOptions, correctOptionIndex = newCorrect)
        }
    }
}
