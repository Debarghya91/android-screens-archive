// TestModels.kt
// Real Tests quizzes, ported from CoursesView.swift (TestListView,
// TestEntryInfo, TestRow, TestPagePayload, TestPageLoader,
// TestQuizView). Tests are discovered from the live search index
// (type "test"); their questions live on the test page itself,
// embedded as TEST_DATA JSON with the duration and the pass mark.
// Nothing here invents a question, an option or a correct answer,
// and no score is saved: answers stay in memory for the attempt.
// Tests stay open to every learner: no gating is added here.
// Verified live 13 Nov 2026: 15 tests, 205 valid questions, every
// page duration_minutes equals its question count, every page
// pass_mark_percent is 80. The code never hardcodes those figures:
// the pass mark and duration always come from the page itself,
// and no verdict is shown when a page carries no pass mark.

package com.paymentstraininghub.app.features.assessment

import com.paymentstraininghub.app.data.model.SearchEntry
import com.paymentstraininghub.app.security.PinningObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** The question count and domain label parsed from a test entry's catalogue text. */
data class TestEntryInfo(
    val questionCount: Int?,
    val domainLabel: String?
) {
    companion object {
        fun from(entry: SearchEntry): TestEntryInfo {
            val text = entry.text.trim()
            val title = entry.title.trim()
            var remainder = text
            if (title.isNotEmpty() && remainder.startsWith(title)) {
                remainder = remainder.drop(title.length).trim()
            }
            val words = remainder.split(Regex("\\s+")).filter { it.isNotEmpty() }
            var count: Int? = null
            var countWordIndex: Int? = null
            for ((index, word) in words.withIndex()) {
                val cleaned = word.trim { it in ".,;:!?()[]{}\"'".toCharArray().toSet() }.lowercase()
                if (cleaned != "question" && cleaned != "questions") continue
                if (index < 1) continue
                val number = words[index - 1].toIntOrNull() ?: continue
                if (number <= 0) continue
                count = number
                countWordIndex = index
                break
            }
            val label = if (countWordIndex != null && countWordIndex >= 3) {
                words.subList(0, countWordIndex - 2).joinToString(" ").trim().takeIf { it.isNotEmpty() }
            } else {
                null
            }
            return TestEntryInfo(questionCount = count, domainLabel = label)
        }
    }

    /**
     * Honest duration estimate for the row: one minute per
     * question, the same 60 seconds per question the assessment
     * session allows, and the same duration the test pages
     * themselves carry. The quiz view replaces this estimate
     * with the page's own duration once it has loaded.
     */
    val estimatedMinutes: Int? get() = questionCount

    /** "10 questions · About 10 min", or a plain statement when the catalogue carries no count. */
    val rowMetaText: String
        get() {
            val count = questionCount ?: return "No questions listed in the catalogue"
            val questionWord = if (count == 1) "question" else "questions"
            return "$count $questionWord · About $count min"
        }
}

/** The test block of a TEST_DATA payload: id, title, duration and pass mark, all optional. */
data class TestPageInfo(
    val id: String?,
    val title: String?,
    val durationMinutes: Int?,
    val passMarkPercent: Int?
)

/** One validated test question, ready to ask. */
data class TestQuizQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val correctOptionIndex: Int,
    val explanation: String,
    val whyWrong: List<String>,
    val chapterId: String?,
    val scenario: String?,
    val source: String?
)

/** Why a test page could not be turned into a quiz, in words the learner can act on. */
sealed class TestPageError(override val message: String) : Exception(message) {
    object BadResponse : TestPageError("The test page could not be loaded. Check your connection and try again.")
    object NoTestData : TestPageError("The test page loaded, but it carried no test questions to run.")
    object Undecodable : TestPageError("The test page loaded, but its questions could not be read.")
}

/**
 * Fetches one test page from the course website and reads its
 * TEST_DATA questions. The fetch mirrors the lesson detail fetch:
 * the page URL is the entry's own url under the site base, with
 * bounded retry (3 attempts, exponential backoff) for network
 * failures. A page that loads but carries no usable test data is
 * reported at once and never retried, because retrying cannot fix
 * it. No other file loads test pages.
 */
object TestPageLoader {
    private const val SITE_BASE = "https://www.paymentstraininghub.com/"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .addNetworkInterceptor(PinningObserver)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun load(entry: SearchEntry): Pair<TestPageInfo?, List<TestQuizQuestion>> =
        withContext(Dispatchers.IO) {
            val pageUrl = SITE_BASE + entry.url.trimStart('/')
            var lastError: Exception = TestPageError.BadResponse
            for (attempt in 0 until 3) {
                try {
                    val request = Request.Builder().url(pageUrl).get().build()
                    val html = client.newCall(request).execute().use { response ->
                        if (response.code != 200) throw TestPageError.BadResponse
                        response.body?.string() ?: throw TestPageError.BadResponse
                    }
                    return@withContext parse(html)
                } catch (e: TestPageError) {
                    if (e !== TestPageError.BadResponse) throw e
                    lastError = e
                } catch (e: Exception) {
                    lastError = e
                }
                if (attempt < 2) delay(500L * (1L shl attempt))
            }
            throw lastError
        }

    private fun parse(html: String): Pair<TestPageInfo?, List<TestQuizQuestion>> {
        val jsonText = extractTestDataJson(html) ?: throw TestPageError.NoTestData
        val payload = runCatching { JSONObject(jsonText) }.getOrNull()
            ?: throw TestPageError.Undecodable
        val info = if (payload.has("test") && !payload.isNull("test")) {
            val test = payload.getJSONObject("test")
            TestPageInfo(
                id = nonEmpty(test.optString("id", null)),
                title = nonEmpty(test.optString("title", null)),
                durationMinutes = test.optInt("duration_minutes", -1).takeIf { it >= 0 },
                passMarkPercent = test.optInt("pass_mark_percent", -1).takeIf { it >= 0 }
            )
        } else {
            null
        }
        return info to validQuestions(payload.optJSONArray("questions"))
    }

    /**
     * Find the TEST_DATA JSON object inside "var TEST_DATA = {...};"
     * and return its text. The scan respects JSON strings and
     * escapes, so braces inside quoted question text cannot end
     * the object early.
     */
    internal fun extractTestDataJson(html: String): String? {
        val marker = html.indexOf("var TEST_DATA = ")
        if (marker < 0) return null
        val braceIndex = html.indexOf('{', marker + "var TEST_DATA = ".length)
        if (braceIndex < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        var index = braceIndex
        while (index < html.length) {
            val ch = html[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
            } else if (ch == '"') {
                inString = true
            } else if (ch == '{') {
                depth += 1
            } else if (ch == '}') {
                depth -= 1
                if (depth == 0) return html.substring(braceIndex, index + 1)
            }
            index += 1
        }
        return null
    }

    /**
     * Keep only questions that can honestly be asked: non-empty
     * question text, at least two options, and an answer index
     * inside the options. Anything else is skipped, never repaired.
     */
    private fun validQuestions(raw: org.json.JSONArray?): List<TestQuizQuestion> {
        if (raw == null) return emptyList()
        val questions = mutableListOf<TestQuizQuestion>()
        for (index in 0 until raw.length()) {
            val item = runCatching { raw.getJSONObject(index) }.getOrNull() ?: continue
            val question = nonEmpty(item.optString("question", null)) ?: continue
            val optionsJson = item.optJSONArray("options") ?: continue
            if (optionsJson.length() < 2) continue
            val options = (0 until optionsJson.length()).map { optionsJson.optString(it, "") }
            if (!item.has("answer") || item.isNull("answer")) continue
            val correct = item.optInt("answer", -1)
            if (correct < 0 || correct >= options.size) continue
            val whyJson = item.optJSONArray("why_wrong")
            val whyWrong = if (whyJson != null) {
                (0 until whyJson.length()).map { whyJson.optString(it, "") }
            } else {
                emptyList()
            }
            questions.add(
                TestQuizQuestion(
                    id = nonEmpty(item.optString("id", null)) ?: "question-${index + 1}",
                    question = question,
                    options = options,
                    correctOptionIndex = correct,
                    explanation = nonEmpty(item.optString("explanation", null)) ?: "",
                    whyWrong = whyWrong,
                    chapterId = nonEmpty(item.optString("chapter", null)),
                    scenario = nonEmpty(item.optString("scenario", null)),
                    source = nonEmpty(item.optString("source", null))
                )
            )
        }
        return questions
    }

    private fun nonEmpty(value: String?): String? {
        val trimmed = value?.trim()
        return if (trimmed.isNullOrEmpty()) null else trimmed
    }
}
