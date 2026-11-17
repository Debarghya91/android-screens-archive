package com.paymentstraininghub.app.data

import com.paymentstraininghub.app.data.model.LessonBeat
import com.paymentstraininghub.app.data.model.LessonDetail
import com.paymentstraininghub.app.data.model.QuizQuestion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

// LessonDetailParser.kt
// Payments Training Hub Android - parses a lesson page into a LessonDetail.
// Ported from the iOS parseLessonHTML in ContentService.swift.
// Defensive throughout: missing sections yield empty lists/strings,
// never exceptions. parse() itself never throws.

object LessonDetailParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /// The lesson JSON blob embedded in each lesson page as
    /// window.__PA_STUDIO_QUEUE.push(["studio-<id>", {...}]).
    @Serializable
    internal data class StudioPayload(
        @SerialName("sample_id") val sampleId: String? = null,
        @SerialName("chapter_id") val chapterId: String? = null,
        val title: String? = null,
        @SerialName("chapter_title") val chapterTitle: String? = null,
        @SerialName("next_id") val nextId: String? = null,
        @SerialName("next_title") val nextTitle: String? = null,
        val scenes: List<StudioScene>? = null,
        // Quiz items are decoded as raw objects: the `answer` field
        // may be an option index (number) or option text (string).
        val quiz: List<JsonObject>? = null
    )

    @Serializable
    internal data class StudioScene(
        val id: String? = null,
        val title: String? = null,
        @SerialName("duration_sec") val durationSec: Double? = null,
        val transcript: String? = null,
        val paragraphs: List<String>? = null,
        val points: List<String>? = null,
        val beats: List<StudioBeat>? = null
    )

    @Serializable
    internal data class StudioBeat(
        val t0: Double? = null,
        val t1: Double? = null,
        val title: String? = null,
        val note: String? = null
    )

    /// Extract the lesson from its page. The page embeds the full lesson
    /// as a JSON blob (window.__PA_STUDIO_QUEUE), which is the primary
    /// source for transcript, takeaways, beats, quiz, next lesson,
    /// chapter title and duration. The diagram SVG comes from the
    /// studio-canvas div, and the quiz intro from the quiz score note.
    /// Every <svg> and <script> block is stripped before any HTML text
    /// extraction, so markup can never leak into lesson text. When the
    /// JSON blob is missing, the static HTML sections are used.
    fun parse(lessonId: String, html: String): LessonDetail {
        try {
            return parseInternal(lessonId, html)
        } catch (e: Exception) {
            return minimalDetail(lessonId)
        }
    }

    private fun parseInternal(lessonId: String, html: String): LessonDetail {
        val diagramSvg = extractDiagramSvg(html)
        val quizIntro = extractQuizIntro(html)
        val cleaned = stripSvgAndScriptBlocks(html)
        val audioFileName = "$lessonId.mp3"

        val studio = extractStudioJson(html)
        if (studio != null) {
            val sampleId = studio.sampleId ?: lessonId
            val transcripts = mutableListOf<String>()
            val takeaways = mutableListOf<String>()
            val beats = mutableListOf<LessonBeat>()
            var totalDuration = 0.0
            var sceneOffset = 0.0
            for ((sceneIndex, scene) in (studio.scenes ?: emptyList()).withIndex()) {
                var transcript = (scene.transcript ?: "").trim()
                if (transcript.isEmpty()) {
                    val paragraphs = (scene.paragraphs ?: emptyList())
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    transcript = paragraphs.joinToString("\n\n")
                }
                if (transcript.isNotEmpty()) transcripts.add(transcript)
                takeaways.addAll(scene.points ?: emptyList())
                for ((beatIndex, beat) in (scene.beats ?: emptyList()).withIndex()) {
                    val title = (beat.title ?: "").trim()
                    if (title.isEmpty()) continue
                    beats.add(
                        LessonBeat(
                            id = "$sampleId-beat-$sceneIndex-$beatIndex",
                            startTime = sceneOffset + (beat.t0 ?: 0.0),
                            endTime = sceneOffset + (beat.t1 ?: 0.0),
                            title = title,
                            note = (beat.note ?: "").trim()
                        )
                    )
                }
                val duration = scene.durationSec ?: 0.0
                totalDuration += duration
                sceneOffset += duration
            }
            val resolvedTakeaways = if (takeaways.isEmpty()) {
                extractListItems(cleaned, "Key takeaways")
            } else {
                takeaways
            }
            val title = studio.title ?: headingText(cleaned) ?: lessonId
            val transcript = transcripts.joinToString("\n\n")
            return LessonDetail(
                id = lessonId,
                title = title,
                chapterTitle = studio.chapterTitle ?: chapterTitleFallback(lessonId),
                audioFileName = audioFileName,
                transcript = transcript,
                takeaways = resolvedTakeaways.ifEmpty { defaultTakeaways(title) },
                doThisAtWork = extractListItems(cleaned, "Do this at work"),
                sources = extractListItems(cleaned, "Sources"),
                quiz = quizQuestions(studio.quiz, sampleId),
                nextLessonId = studio.nextId ?: extractNextLessonId(html, lessonId),
                beats = beats,
                diagramSvg = diagramSvg,
                durationSec = totalDuration,
                nextLessonTitle = studio.nextTitle,
                quizIntro = quizIntro
            )
        }

        // No studio JSON on the page: read the static HTML sections.
        // No-quiz/no-beats fallback, exactly as in iOS.
        val title = headingText(cleaned) ?: lessonId
        val transcript = extractTranscriptFromHtml(cleaned) ?: ""
        val takeaways = extractListItems(cleaned, "Key takeaways")
        return LessonDetail(
            id = lessonId,
            title = title,
            chapterTitle = chapterTitleFallback(lessonId),
            audioFileName = audioFileName,
            transcript = transcript,
            takeaways = takeaways.ifEmpty { defaultTakeaways(title) },
            doThisAtWork = extractListItems(cleaned, "Do this at work"),
            sources = extractListItems(cleaned, "Sources"),
            quiz = emptyList(),
            nextLessonId = extractNextLessonId(html, lessonId),
            beats = emptyList(),
            diagramSvg = diagramSvg,
            durationSec = 0.0,
            nextLessonTitle = null,
            quizIntro = quizIntro
        )
    }

    private fun minimalDetail(lessonId: String): LessonDetail {
        return LessonDetail(
            id = lessonId,
            title = lessonId,
            chapterTitle = chapterTitleFallback(lessonId),
            audioFileName = "$lessonId.mp3",
            transcript = "",
            takeaways = defaultTakeaways(lessonId),
            doThisAtWork = emptyList(),
            sources = emptyList(),
            quiz = emptyList(),
            nextLessonId = null,
            beats = emptyList(),
            diagramSvg = null,
            durationSec = 0.0,
            nextLessonTitle = null,
            quizIntro = ""
        )
    }

    private fun chapterTitleFallback(lessonId: String): String {
        if (lessonId.startsWith("ch")) {
            val digits = lessonId.drop(2).takeWhile { it.isDigit() }
            val number = digits.toIntOrNull()
            if (number != null) return ReadingOrder.chapterLabel("ch$number")
        }
        return "Chapter"
    }

    private fun defaultTakeaways(title: String): List<String> = listOf(
        title,
        "Listen to the full narration for the worked example.",
        "Try the quiz below to check your understanding."
    )

    /// The first <h1> heading text on the page, for pages without
    /// studio JSON. Null when the page has no heading.
    private fun headingText(html: String): String? {
        val h1Start = html.indexOf("<h1")
        if (h1Start < 0) return null
        val tagEnd = html.indexOf(">", h1Start)
        if (tagEnd < 0) return null
        val h1End = html.indexOf("</h1>", tagEnd)
        if (h1End < 0) return null
        val text = stripTags(html.substring(tagEnd + 1, h1End))
        return if (text.isEmpty()) null else text
    }

    /// Find the studio JSON object inside
    /// window.__PA_STUDIO_QUEUE.push([...]) and decode it. The scan
    /// respects JSON strings and escapes, so braces inside quoted text
    /// cannot end the object early.
    private fun extractStudioJson(html: String): StudioPayload? {
        val marker = "window.__PA_STUDIO_QUEUE.push([\"studio-"
        val markerIndex = html.indexOf(marker)
        if (markerIndex < 0) return null
        val braceIndex = html.indexOf("{", markerIndex + marker.length)
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
                if (depth == 0) {
                    val jsonText = html.substring(braceIndex, index + 1)
                    return try {
                        json.decodeFromString<StudioPayload>(jsonText)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            index += 1
        }
        return null
    }

    /// The lesson diagram SVG from the studio-canvas div. The narrow flow
    /// fits phone widths better, so it is preferred; the desktop flow is
    /// the fallback. Returns the whole <svg>...</svg> string.
    private fun extractDiagramSvg(html: String): String? {
        val canvasStart = html.indexOf("<div class=\"studio-canvas\"")
        if (canvasStart < 0) return null
        for (flowClass in listOf("flow-mobile", "flow-desktop")) {
            val marker = "<svg class=\"$flowClass\""
            val svgStart = html.indexOf(marker, canvasStart)
            if (svgStart < 0) continue
            val svgEnd = html.indexOf("</svg>", svgStart)
            if (svgEnd < 0) continue
            return html.substring(svgStart, svgEnd + "</svg>".length)
        }
        return null
    }

    /// The quiz intro line from the page, for example "Answer all 3
    /// questions to see your score. ...". Empty when the page has none.
    private fun extractQuizIntro(html: String): String {
        val pStart = html.indexOf("<p class=\"studio-note\" id=\"pf-quiz-score-")
        if (pStart < 0) return ""
        val tagEnd = html.indexOf(">", pStart)
        if (tagEnd < 0) return ""
        val pEnd = html.indexOf("</p>", tagEnd)
        if (pEnd < 0) return ""
        return stripTags(html.substring(tagEnd + 1, pEnd))
    }

    /// Build real quiz questions from the studio JSON quiz items. Items
    /// with no question, no options, or no resolvable correct option are
    /// skipped instead of inventing True/False options. The correct
    /// answer is an option index: answer_index wins, then answer when
    /// it is a number, then the option whose text matches answer or
    /// correct_answer.
    private fun quizQuestions(items: List<JsonObject>?, lessonId: String): List<QuizQuestion> {
        if (items == null) return emptyList()
        val questions = mutableListOf<QuizQuestion>()
        for ((index, item) in items.withIndex()) {
            val question = (item["question"] as? JsonPrimitive)?.contentOrNull?.trim()
            if (question.isNullOrEmpty()) continue
            val optionsElement = item["options"] as? JsonArray ?: continue
            val options = optionsElement.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (options.isEmpty()) continue
            var correct: Int? = (item["answer_index"] as? JsonPrimitive)
                ?.takeIf { !it.isString }?.intOrNull
            if (correct == null) {
                correct = (item["answer"] as? JsonPrimitive)
                    ?.takeIf { !it.isString }?.intOrNull
            }
            if (correct == null) {
                val answerText = (item["answer"] as? JsonPrimitive)?.contentOrNull
                    ?: (item["correct_answer"] as? JsonPrimitive)?.contentOrNull
                if (answerText != null) {
                    val found = options.indexOf(answerText)
                    if (found >= 0) correct = found
                }
            }
            val correctIndex = correct ?: continue
            if (correctIndex < 0 || correctIndex >= options.size) continue
            val explanation = (item["explanation"] as? JsonPrimitive)?.contentOrNull?.trim() ?: ""
            questions.add(
                QuizQuestion(
                    id = "$lessonId-q${index + 1}",
                    question = question,
                    options = options,
                    correctOptionIndex = correctIndex,
                    explanation = explanation.ifEmpty { "See the lesson transcript for the full explanation." }
                )
            )
        }
        return questions
    }

    // MARK: - HTML helpers

    /// Remove every <script>...</script> and <svg>...</svg> block. This
    /// runs BEFORE any HTML text extraction, so SVG attribute text and
    /// script source can never leak into lesson text, lists or the
    /// transcript.
    private fun stripSvgAndScriptBlocks(html: String): String {
        var result = html
        for (pattern in listOf("<script\\b[^>]*>.*?</script>", "<svg\\b[^>]*>.*?</svg>")) {
            val regex = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            result = regex.replace(result, "")
        }
        return result
    }

    private fun stripTags(html: String): String {
        val result = StringBuilder()
        var inTag = false
        for (ch in html) {
            if (ch == '<') {
                inTag = true
                continue
            }
            if (ch == '>') {
                inTag = false
                continue
            }
            if (!inTag) result.append(ch)
        }
        return result.toString()
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .trim()
    }

    /// The static "Full lesson text" transcript div, used only when the
    /// studio JSON blob is missing. Paragraphs are kept apart with blank
    /// lines so the text reads as paragraphs, not one run-on block.
    private fun extractTranscriptFromHtml(html: String): String? {
        val divStart = html.indexOf("<div id=\"studio-transcript-full-")
        if (divStart < 0) return null
        val tagEnd = html.indexOf(">", divStart)
        if (tagEnd < 0) return null
        val divEnd = html.indexOf("</div>", tagEnd)
        if (divEnd < 0) return null
        val inner = html.substring(tagEnd + 1, divEnd)
            .replace("</p>", "\n\n")
            .replace("</h4>", "\n\n")
        val cleaned = stripTags(inner)
        return if (cleaned.isEmpty()) null else cleaned
    }

    private fun extractListItems(html: String, sectionMarker: String): List<String> {
        // Anchor on the real <h2> heading. The page <style> block holds a
        // CSS comment that mentions "Do this at work", so bare text would
        // match the stylesheet instead of the section.
        val heading = "<h2>$sectionMarker</h2>"
        val headingIndex = html.indexOf(heading)
        if (headingIndex < 0) return emptyList()
        val afterHeading = headingIndex + heading.length
        var sectionEnd = html.length
        val sectionClose = html.indexOf("</section>", afterHeading)
        if (sectionClose >= 0) sectionEnd = sectionClose
        val divClose = html.indexOf("</div>", afterHeading)
        if (divClose >= 0 && divClose < sectionEnd) sectionEnd = divClose
        val section = html.substring(afterHeading, sectionEnd)
        // Match only real list items: "<li>" or "<li " with attributes.
        // Never "<line", "<link" or "<linearGradient".
        val liRegex = Regex("<li(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)
        val matches = liRegex.findAll(section).toList()
        val items = mutableListOf<String>()
        for (match in matches) {
            val itemStart = match.range.last + 1
            val closeIndex = section.indexOf("</li>", itemStart, ignoreCase = true)
            if (closeIndex < 0) continue
            val cleaned = stripTags(section.substring(itemStart, closeIndex))
            if (cleaned.isNotEmpty()) items.add(cleaned)
        }
        return items
    }

    private fun extractNextLessonId(html: String, currentId: String): String? {
        // Next lesson links use the lessons/<id> path.
        val regex = Regex("lessons/(ch\\d+s\\d+)")
        for (match in regex.findAll(html)) {
            val id = match.groupValues.getOrNull(1) ?: continue
            if (id != currentId) return id
        }
        return null
    }
}
