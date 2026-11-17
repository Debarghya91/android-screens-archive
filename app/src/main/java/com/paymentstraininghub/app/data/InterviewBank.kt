// InterviewBank.kt
// The bundled interview question bank (interview-questions.json,
// the same file the iOS app bundles), preserving categories, as
// iOS InterviewQuestionBank does. HabitQuestionBank already loads
// the same file flat for the question of the day; this loader
// keeps the category structure the Interview screens need. The
// bank is bundled with the app, so a load failure is never a
// connection problem. No question or answer is ever invented.

package com.paymentstraininghub.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class InterviewQuestionItem(
    val id: String,
    val question: String,
    val answer: String
)

data class InterviewCategoryItem(
    val id: String,
    val title: String,
    val questions: List<InterviewQuestionItem>
)

data class InterviewBankData(
    val total: Int,
    val categories: List<InterviewCategoryItem>
) {
    val allQuestions: List<InterviewQuestionItem>
        get() = categories.flatMap { it.questions }

    /** Questions matching the query in question or answer text, with their category title. */
    fun search(query: String): List<Pair<InterviewCategoryItem, InterviewQuestionItem>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val result = mutableListOf<Pair<InterviewCategoryItem, InterviewQuestionItem>>()
        for (category in categories) {
            for (question in category.questions) {
                if (question.question.contains(trimmed, ignoreCase = true) ||
                    question.answer.contains(trimmed, ignoreCase = true)
                ) {
                    result.add(category to question)
                }
            }
        }
        return result
    }
}

object InterviewBank {
    /**
     * Loads the bundled bank. Returns null when the asset is
     * missing or unreadable; the caller shows the unavailable
     * state instead of inventing content.
     */
    suspend fun load(context: Context): InterviewBankData? = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.assets.open("interview-questions.json")
                .bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val categoriesJson = root.getJSONArray("categories")
            val categories = mutableListOf<InterviewCategoryItem>()
            for (i in 0 until categoriesJson.length()) {
                val category = categoriesJson.getJSONObject(i)
                val questionsJson = category.getJSONArray("questions")
                val questions = mutableListOf<InterviewQuestionItem>()
                for (j in 0 until questionsJson.length()) {
                    val item = questionsJson.getJSONObject(j)
                    questions.add(
                        InterviewQuestionItem(
                            id = item.getString("id"),
                            question = item.getString("question"),
                            answer = item.getString("answer")
                        )
                    )
                }
                categories.add(
                    InterviewCategoryItem(
                        id = category.getString("id"),
                        title = category.getString("title"),
                        questions = questions
                    )
                )
            }
            val total = root.optInt("total", 0).takeIf { it > 0 }
                ?: categories.sumOf { it.questions.size }
            InterviewBankData(total = total, categories = categories)
        }.getOrNull()
    }
}
