// LearnStores.kt
// Path bookmarks and the active learning path, ported from iOS
// PathBookmarkStore (LearningPath.swift, key pf.pathBookmarks.v1)
// and ActivePathStore (RolePathway.swift, key pf.activePath.v1).
// Both persist through AppStore, which namespaces keys per
// account exactly as iOS AccountScopedStorage does. A bookmark
// only marks a path; it never gates or reorders anything.

package com.paymentstraininghub.app.data

import com.paymentstraininghub.app.auth.AppStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

object PathBookmarkStore {
    private const val KEY = "pf.pathBookmarks.v1"

    private val _bookmarkedIds = MutableStateFlow<Set<String>>(emptySet())
    val bookmarkedIds: StateFlow<Set<String>> = _bookmarkedIds.asStateFlow()

    suspend fun refresh() {
        val raw = runCatching { AppStore.getString(KEY) }.getOrNull() ?: return
        val ids = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getString(it) }.toSet()
        }.getOrDefault(emptySet())
        _bookmarkedIds.value = ids
    }

    fun isBookmarked(pathId: String): Boolean = pathId in _bookmarkedIds.value

    suspend fun toggle(pathId: String) {
        val next = _bookmarkedIds.value.toMutableSet()
        if (!next.add(pathId)) next.remove(pathId)
        _bookmarkedIds.value = next
        runCatching {
            val array = JSONArray()
            next.sorted().forEach { array.put(it) }
            AppStore.putString(KEY, array.toString())
        }
    }
}

object ActivePathStore {
    private const val KEY = "pf.activePath.v1"

    private val _activePathId = MutableStateFlow<String?>(null)
    val activePathId: StateFlow<String?> = _activePathId.asStateFlow()

    suspend fun refresh() {
        val raw = runCatching { AppStore.getString(KEY) }.getOrNull()
        _activePathId.value = raw?.trim()?.takeIf { it.isNotEmpty() }
    }

    suspend fun setActive(pathId: String?) {
        _activePathId.value = pathId
        runCatching { AppStore.putString(KEY, pathId ?: "") }
    }
}
