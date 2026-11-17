package com.paymentstraininghub.app.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The signed-in learner's profile picture URL, resolved once per
 * account from the profiles row. The contract mirrors iOS:
 * the profiles row stores avatar_url, which is either a direct
 * https URL (used as-is) or a path in the private avatars
 * storage bucket, resolved through a fresh signed URL. A
 * missing picture, a failed fetch, or a guest session all
 * leave the value null, and the greeting shows the default
 * person icon. This store never throws to its callers.
 */
object ProfilePictureStore {

    private val _avatarUrl = MutableStateFlow<String?>(null)
    val avatarUrl: StateFlow<String?> = _avatarUrl.asStateFlow()

    private val mutex = Mutex()
    private var loadedForUserId: String? = null

    /**
     * Resolves the picture for [userId], or clears it for a
     * guest (null). Repeat calls for the same account are a
     * no-op: the signed URL stays valid for an hour and the
     * picture does not change mid-session.
     */
    suspend fun refreshFor(userId: String?) {
        if (userId == null) {
            mutex.withLock {
                loadedForUserId = null
                _avatarUrl.value = null
            }
            return
        }
        mutex.withLock {
            if (loadedForUserId == userId) return
            _avatarUrl.value = runCatching { resolveAvatarUrl(userId) }.getOrNull()
            loadedForUserId = userId
        }
    }

    /**
     * Reads the profiles row for [userId] and resolves the
     * stored avatar value to a fetchable URL. Returns null
     * when the row, the field, the token, or the signed URL
     * is missing. Throws on network failure; [refreshFor]
     * catches it.
     */
    internal suspend fun resolveAvatarUrl(userId: String): String? {
        val token = AuthManager.validAccessToken() ?: return null
        val body = SupabaseClient.restGet(
            "profiles",
            token,
            "select=avatar_url&id=eq.$userId"
        )
        val stored = parseAvatarUrl(body) ?: return null
        if (isDirectUrl(stored)) return stored
        val path = storagePathFromStored(stored) ?: return null
        return SupabaseClient.storageSignedUrl("avatars", path, token)
    }

    /** A direct URL is used as-is; anything else is a storage path. */
    internal fun isDirectUrl(stored: String): Boolean {
        return stored.startsWith("http://") || stored.startsWith("https://")
    }

    /**
     * Normalises a stored avatar value to a storage path:
     * an optional leading avatars/ bucket prefix and stray
     * slashes are stripped. Returns null for an empty path.
     */
    internal fun storagePathFromStored(stored: String): String? {
        val trimmed = stored.trim().removePrefix("avatars/").trimStart('/')
        return trimmed.takeIf { it.isNotBlank() }
    }

    /**
     * Parses a PostgREST reply of the shape
     * [{"avatar_url": "..."}] and returns the first row's
     * avatar_url, or null when the reply has no row, no
     * field, or a blank value. Uses kotlinx-serialization:
     * org.json throws "not mocked" in JVM unit tests.
     */
    internal fun parseAvatarUrl(restBody: String): String? {
        return try {
            val array = Json.parseToJsonElement(restBody) as? JsonArray ?: return null
            val first = array.firstOrNull() as? JsonObject ?: return null
            (first["avatar_url"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /** Test seam: forgets the cached account and picture. */
    internal fun clearForTest() {
        loadedForUserId = null
        _avatarUrl.value = null
    }
}
