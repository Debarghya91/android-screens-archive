// SupplementalLessons.kt
// The 72 supplemental lessons bundled with the app, ported from iOS
// ContentService (v34 entries, v82 flow descriptors). The JSON is
// the same file the iOS app bundles (supplemental-lessons.json):
// CIPSP and Payment Guru syllabus topics the website catalogue
// does not teach in depth. Bundled, so they work offline too, and
// the app never fails to load without them: a missing or
// undecodable file reads as empty.
//
// Each entry carries a flow descriptor: kind "flow" for lessons
// whose text describes an ordered sequence (shown with arrows)
// and "diagram" for lessons structured as parts, aspects, or
// comparisons (shown with a plain rail). Any other kind behaves
// like "diagram". Flow steps become untimed beats: start and end
// times are all zero, no timings are invented, and the lesson
// view never highlights or seeks from them.

package com.paymentstraininghub.app.data

import android.content.Context
import com.paymentstraininghub.app.data.model.LessonBeat
import com.paymentstraininghub.app.data.model.LessonDetail
import com.paymentstraininghub.app.data.model.SearchEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SupplementalLessonJson(
    val id: String,
    val title: String,
    val url: String = "",
    val type: String = "lesson",
    val text: String = "",
    val flow: SupplementalFlowJson? = null
)

@Serializable
data class SupplementalFlowJson(
    val kind: String? = null,
    val steps: List<SupplementalFlowStepJson>? = null
)

@Serializable
data class SupplementalFlowStepJson(
    val title: String? = null,
    val note: String? = null
)

object SupplementalLessons {

    /// The diagram signal for a supplemental flow lesson. Android
    /// never renders diagram SVG (see LessonDetail.diagramSvg): the
    /// field is only the flow-lesson signal, and a non-blank value
    /// is what makes the lesson view draw arrows between flow
    /// points. This marker is never shown to the learner.
    const val FLOW_DIAGRAM_MARKER = "supplemental-flow"

    private val json = Json { ignoreUnknownKeys = true }

    private var appContext: Context? = null
    private val loadMutex = Mutex()

    @Volatile
    private var cached: SupplementalData? = null

    data class SupplementalData(
        val entries: List<SearchEntry>,
        val flowsById: Map<String, SupplementalFlowJson>
    )

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    /// The bundled supplemental entries and their flow
    /// descriptors, read once and cached. Empty when the file is
    /// missing, undecodable, or init was never called; the app
    /// never fails to load without them. The first load reads and
    /// decodes off the caller thread.
    suspend fun load(): SupplementalData {
        cached?.let { return it }
        return loadMutex.withLock {
            cached?.let { return@withLock it }
            val loaded = runCatching { readBundled() }
                .getOrDefault(SupplementalData(emptyList(), emptyMap()))
            cached = loaded
            loaded
        }
    }

    private suspend fun readBundled(): SupplementalData =
        withContext(Dispatchers.IO) {
            val context = appContext
                ?: return@withContext SupplementalData(emptyList(), emptyMap())
            val text = context.assets.open("supplemental-lessons.json")
                .bufferedReader().use { it.readText() }
            val documents = json.decodeFromString<List<SupplementalLessonJson>>(text)
            val entries = documents.map {
                SearchEntry(
                    id = it.id,
                    title = it.title,
                    url = it.url,
                    type = it.type,
                    text = it.text
                )
            }
            val flows = documents.mapNotNull { document ->
                document.flow?.let { document.id to it }
            }.toMap()
            SupplementalData(entries, flows)
        }

    /// The untimed beats for a supplemental lesson, from its flow
    /// descriptor steps, in order. Beat times are all zero: no
    /// per-beat timings exist for supplemental lessons, and none
    /// are invented. Steps with an empty title are skipped; a
    /// missing descriptor yields no beats.
    fun beatsFor(entryId: String, flowsById: Map<String, SupplementalFlowJson>): List<LessonBeat> {
        val steps = flowsById[entryId]?.steps ?: return emptyList()
        val beats = mutableListOf<LessonBeat>()
        var index = 0
        for (step in steps) {
            val title = step.title?.trim().orEmpty()
            if (title.isEmpty()) continue
            beats.add(
                LessonBeat(
                    id = "$entryId-beat-$index",
                    startTime = 0.0,
                    endTime = 0.0,
                    title = title,
                    note = step.note?.trim().orEmpty()
                )
            )
            index++
        }
        return beats
    }

    /// True when the lesson's flow descriptor is kind "flow": the
    /// flow renders with arrows. Every other kind, and a missing
    /// descriptor, renders with a plain rail.
    fun showsFlowArrows(entryId: String, flowsById: Map<String, SupplementalFlowJson>): Boolean {
        return flowsById[entryId]?.kind == "flow"
    }

    /// The locally built detail for one supplemental lesson: no
    /// network fetch, no cache write. Narration is recorded for
    /// all 72 (audio file id + ".mp3" on the audio hosts); there
    /// is no quiz. Beats come from the bundled flow descriptor
    /// and are untimed. Duration is zero: no duration is invented.
    /// Takeaways match iOS defaultTakeaways verbatim.
    suspend fun detailFor(id: String, chapterTitle: String): LessonDetail? {
        val data = load()
        val entry = data.entries.firstOrNull { it.id == id } ?: return null
        return LessonDetail(
            id = entry.id,
            title = entry.title,
            chapterTitle = chapterTitle,
            audioFileName = entry.id + ".mp3",
            transcript = entry.text,
            takeaways = listOf(
                entry.title,
                "Listen to the full narration for the worked example.",
                "Try the quiz below to check your understanding."
            ),
            doThisAtWork = emptyList(),
            sources = emptyList(),
            quiz = emptyList(),
            nextLessonId = null,
            beats = beatsFor(entry.id, data.flowsById),
            diagramSvg = if (showsFlowArrows(entry.id, data.flowsById)) {
                FLOW_DIAGRAM_MARKER
            } else {
                null
            },
            durationSec = 0.0,
            nextLessonTitle = null,
            quizIntro = ""
        )
    }
}
