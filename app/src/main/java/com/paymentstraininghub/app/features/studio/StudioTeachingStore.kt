// StudioTeachingStore.kt
// Loads the bundled Studio teaching content (156 lessons, 5
// career guides), converted from the iOS StudioExercises.swift
// data. Bundled, so the Studio works offline, and the app never
// fails to load without it: a missing or undecodable file reads
// as empty. Parsing runs off the main thread. Lesson order
// matches iOS StudioLesson.all exactly: Level 1, then Level 2,
// then Level 3; within a level, key concepts, tasks, techniques,
// competency groups followed by their individual competencies,
// perspectives, then orientations, each in file order.

package com.paymentstraininghub.app.features.studio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class StudioLessonsJson(
    val keyConcepts: List<StudioLesson> = emptyList(),
    val tasks: List<StudioLesson> = emptyList(),
    val techniques: List<StudioLesson> = emptyList(),
    val competencies: List<StudioLesson> = emptyList(),
    val individualCompetencies: List<StudioLesson> = emptyList(),
    val perspectives: List<StudioLesson> = emptyList(),
    val orientations: List<StudioLesson> = emptyList()
)

object StudioTeachingStore {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var appContext: Context? = null
    private val mutex = Mutex()
    private var cachedLessons: List<StudioLesson>? = null
    private var cachedGuides: List<StudioCareerGuide>? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** All lessons, in iOS learning order. Empty when the asset is missing or undecodable. */
    suspend fun lessons(): List<StudioLesson> {
        cachedLessons?.let { return it }
        return mutex.withLock {
            cachedLessons?.let { return@withLock it }
            val loaded = loadLessons()
            cachedLessons = loaded
            loaded
        }
    }

    /** All career guides, in file order. Empty when the asset is missing or undecodable. */
    suspend fun guides(): List<StudioCareerGuide> {
        cachedGuides?.let { return it }
        return mutex.withLock {
            cachedGuides?.let { return@withLock it }
            val loaded = loadGuides()
            cachedGuides = loaded
            loaded
        }
    }

    /** The lessons for one level, in display order. */
    suspend fun lessonsFor(level: StudioLevel): List<StudioLesson> {
        return lessons().filter { it.levelEnum == level }
    }

    /** The task lessons for one knowledge area, in order. */
    suspend fun tasksFor(area: StudioKnowledgeArea): List<StudioLesson> {
        return lessons().filter {
            it.kindEnum == StudioLessonKind.TASK && it.knowledgeAreaEnum == area
        }
    }

    private suspend fun loadLessons(): List<StudioLesson> {
        val context = appContext ?: return emptyList()
        return withContext(Dispatchers.Default) {
            runCatching {
                val raw = context.assets.open("studio-lessons.json")
                    .bufferedReader().use { it.readText() }
                val grouped = json.decodeFromString<StudioLessonsJson>(raw)
                val inFileOrder = grouped.keyConcepts + grouped.tasks +
                    grouped.techniques + grouped.competencies +
                    grouped.individualCompetencies + grouped.perspectives +
                    grouped.orientations
                StudioLevel.entries.flatMap { level ->
                    inFileOrder.filter { it.levelEnum == level }
                }
            }.getOrElse { emptyList() }
        }
    }

    private suspend fun loadGuides(): List<StudioCareerGuide> {
        val context = appContext ?: return emptyList()
        return withContext(Dispatchers.Default) {
            runCatching {
                val raw = context.assets.open("studio-career-guides.json")
                    .bufferedReader().use { it.readText() }
                json.decodeFromString<List<StudioCareerGuide>>(raw)
            }.getOrElse { emptyList() }
        }
    }
}
