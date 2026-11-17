// AssessmentSessionHolder.kt
// Retained assessment sessions: one session at a time, keyed by
// the assessment it belongs to, so a rotation or a navigation
// away and back keeps the questions, answers, index, and timers
// instead of wiping the attempt. The session's timers are
// wall-clock based, so retaining the object is enough; the
// ticker restarts through AssessmentSession.resumeTicker when
// the screen recomposes. The result-recorded flag is retained
// with the session: without it, a rotation on the results
// screen would record the same attempt twice (and consume a
// second Final Assessment attempt). A different key stops the
// held session and starts a fresh one. Process death still
// starts fresh, as with a ViewModel; the Final Assessment's
// stored end time still offers its resume path then.

package com.paymentstraininghub.app.features.assessment

object AssessmentSessionHolder {

    private var key: String? = null
    private var session: AssessmentSession? = null
    private var resultRecorded: Boolean = false

    /** The retained session for [key], creating it on first use. */
    @Synchronized
    fun sessionFor(key: String): AssessmentSession {
        if (this.key != key || session == null) {
            session?.stop()
            session = AssessmentSession()
            this.key = key
            resultRecorded = false
        }
        return session!!
    }

    /** Whether the held session for [key] already recorded its result. */
    @Synchronized
    fun isResultRecorded(key: String): Boolean = this.key == key && resultRecorded

    /** Mark the held session for [key] as having recorded its result. */
    @Synchronized
    fun markResultRecorded(key: String) {
        if (this.key == key) {
            resultRecorded = true
        }
    }

    /** Clear the recorded flag for [key], for a retake. */
    @Synchronized
    fun clearResultRecorded(key: String) {
        if (this.key == key) {
            resultRecorded = false
        }
    }
}
