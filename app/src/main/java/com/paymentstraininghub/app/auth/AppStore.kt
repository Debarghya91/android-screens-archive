package com.paymentstraininghub.app.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Single DataStore file for the whole app. No other file may declare a
// DataStore with this name: every store goes through AppStore so keys
// stay account namespaced.
private val Context.pfDataStore: DataStore<Preferences> by preferencesDataStore(name = "pf_prefs")

private data class MigrationSource(val base: String, val value: Any, val legacy: Boolean)

/**
 * Account scoped key value storage, mirroring iOS AccountScopedStorage.
 *
 * Every key passed to get/put/remove is a BASE key. It is stored under
 * "account.<namespace>.<base>", where the namespace is the signed in
 * user's id, or "guest" when nobody is signed in. A different account
 * can never see the previous account's keys, because every read and
 * write resolves through the current namespace.
 *
 * AuthManager is the only writer of the current identity, through
 * [setCurrentUserId]. No other file may set it.
 *
 * Legacy adoption: a key written with no namespace (from a build
 * before namespacing) is treated as a guest key. On first read it is
 * copied into the guest namespace when the guest key has no value,
 * then the legacy key is removed, so it can never be adopted twice.
 *
 * Reads never throw for storage problems: they return null. Writes
 * propagate DataStore failures so callers that care can catch them.
 */
object AppStore {

    private const val GUEST_NAMESPACE = "guest"
    private const val CURRENT_USER_KEY_NAME = "account.current.v1"

    /**
     * Base keys that never migrate from the guest namespace to an
     * account. Notification preferences and notification schedule
     * state stay with the namespace that created them: signing in
     * to a new account starts with that account's own preferences
     * or the defaults, and guest preferences stay with the guest
     * namespace. Mirrors iOS, where pf.notifications.preferences.v1
     * is absent from AuthService.guestMigrationBaseKeys. Keep this
     * list in step with NotificationSchedulers.nonMigratingBaseKeys.
     * Habit reminder settings are deliberately NOT listed: iOS does
     * migrate them, so Android copies them too.
     */
    private val NON_MIGRATING_BASE_KEYS: Set<String> = setOf(
        "pf.notifications.preferences.v1",
        "pf.notifications.lastSeenContentMarker.v1",
        "pf.notifications.lastOfferScheduledAt.v1",
        "pf.notifications.lastOfferProductID.v1"
    )

    private val currentUserKey = stringPreferencesKey(CURRENT_USER_KEY_NAME)

    @Volatile
    private var currentUserIdValue: String? = null

    @Volatile
    private var userIdSetExplicitly = false

    private lateinit var appContext: Context
    private var loadJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initialised = false

    fun init(context: Context) {
        if (initialised) return
        initialised = true
        appContext = context.applicationContext
        loadJob = scope.launch {
            try {
                val stored = appContext.pfDataStore.data.first()[currentUserKey]
                // Only adopt the persisted identity when AuthManager
                // has not already set one explicitly (for example
                // from a restored session). The explicit value wins.
                if (!userIdSetExplicitly) {
                    currentUserIdValue = stored?.trim()?.takeIf { it.isNotEmpty() }
                }
            } catch (_: Exception) {
                if (!userIdSetExplicitly) {
                    currentUserIdValue = null
                }
            }
        }
    }

    private fun store(): DataStore<Preferences> {
        if (!initialised) {
            throw IllegalStateException("AppStore.init(context) must be called before use")
        }
        return appContext.pfDataStore
    }

    private suspend fun ensureLoaded() {
        if (!initialised) {
            throw IllegalStateException("AppStore.init(context) must be called before use")
        }
        loadJob?.join()
    }

    /** The full stored key for a base key under the current identity. */
    fun keyFor(base: String): String {
        return "account.${sanitisedNamespace(currentUserIdValue)}.$base"
    }

    /** The signed in user's id, or null for a guest. */
    fun currentUserId(): String? = currentUserIdValue

    /**
     * Record the current identity. AuthManager only: call with the
     * signed in user's id on sign in and session restore, and with
     * null on sign out, deletion and failed refresh. The value is
     * held in memory at once and persisted under "account.current.v1",
     * which is deliberately NOT namespaced.
     */
    fun setCurrentUserId(id: String?) {
        val trimmed = id?.trim()?.takeIf { it.isNotEmpty() }
        userIdSetExplicitly = true
        currentUserIdValue = trimmed
        if (!initialised) return
        scope.launch {
            try {
                appContext.pfDataStore.edit { prefs ->
                    if (trimmed == null) {
                        prefs.remove(currentUserKey)
                    } else {
                        prefs[currentUserKey] = trimmed
                    }
                }
            } catch (_: Exception) {
                // Persistence of the identity marker is best effort.
                // The in memory value is authoritative for this run.
            }
        }
    }

    suspend fun getString(key: String): String? {
        ensureLoaded()
        return try {
            val prefs = store().data.first()
            readSafely { prefs[stringPreferencesKey(keyFor(key))] }?.let { return it }
            val legacyValue = readSafely { prefs[stringPreferencesKey(key)] } ?: return null
            adoptLegacyString(key, legacyValue)
            if (sanitisedNamespace(currentUserIdValue) == GUEST_NAMESPACE) legacyValue else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun putString(key: String, value: String) {
        ensureLoaded()
        store().edit { prefs ->
            prefs[stringPreferencesKey(keyFor(key))] = value
        }
    }

    suspend fun getLong(key: String): Long? {
        ensureLoaded()
        return try {
            val prefs = store().data.first()
            readSafely { prefs[longPreferencesKey(keyFor(key))] }?.let { return it }
            val legacyValue = readSafely { prefs[longPreferencesKey(key)] } ?: return null
            adoptLegacyLong(key, legacyValue)
            if (sanitisedNamespace(currentUserIdValue) == GUEST_NAMESPACE) legacyValue else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun putLong(key: String, value: Long) {
        ensureLoaded()
        store().edit { prefs ->
            prefs[longPreferencesKey(keyFor(key))] = value
        }
    }

    suspend fun getBoolean(key: String): Boolean? {
        ensureLoaded()
        return try {
            val prefs = store().data.first()
            readSafely { prefs[booleanPreferencesKey(keyFor(key))] }?.let { return it }
            val legacyValue = readSafely { prefs[booleanPreferencesKey(key)] } ?: return null
            adoptLegacyBoolean(key, legacyValue)
            if (sanitisedNamespace(currentUserIdValue) == GUEST_NAMESPACE) legacyValue else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun putBoolean(key: String, value: Boolean) {
        ensureLoaded()
        store().edit { prefs ->
            prefs[booleanPreferencesKey(keyFor(key))] = value
        }
    }

    /** Remove one base key from the current namespace. */
    suspend fun remove(key: String) {
        ensureLoaded()
        store().edit { prefs ->
            prefs.remove(stringPreferencesKey(keyFor(key)))
        }
    }

    /** Remove every key belonging to one namespace. Used by account deletion. */
    suspend fun clearNamespace(userId: String?) {
        ensureLoaded()
        val prefix = prefixFor(userId)
        val snapshot = store().data.first()
        val names = snapshot.asMap().keys.map { it.name }.filter { it.startsWith(prefix) }
        if (names.isEmpty()) return
        store().edit { prefs ->
            for (name in names) {
                // Key equality is by name, so this removes the entry
                // whatever type it was stored as.
                prefs.remove(stringPreferencesKey(name))
            }
        }
    }

    /** True when the guest namespace holds any migratable key, including legacy un-namespaced keys. Notification keys never count: they never migrate, so guest notification choices alone must not trigger the migration offer. */
    suspend fun guestHasData(): Boolean {
        ensureLoaded()
        return try {
            val snapshot = store().data.first()
            val guestPrefix = prefixFor(null)
            snapshot.asMap().keys.any { key ->
                val base = when {
                    key.name.startsWith(guestPrefix) -> key.name.removePrefix(guestPrefix)
                    !key.name.startsWith("account.") -> key.name
                    else -> return@any false
                }
                base !in NON_MIGRATING_BASE_KEYS
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Copy guest namespace values into an account namespace, only for
     * keys the account does not already have. Existing account values
     * are never overwritten. Migration copies, it does not move: the
     * guest copy is left in place. Never account to account: the
     * source is always the guest namespace (plus legacy keys, which
     * are guest keys). Keys in NON_MIGRATING_BASE_KEYS (notification
     * preferences and schedule state) are never copied: they stay
     * with the guest namespace. Returns true when anything was migrated.
     */
    suspend fun migrateGuestToAccount(accountId: String): Boolean {
        ensureLoaded()
        val target = sanitisedNamespace(accountId)
        if (accountId.trim().isEmpty() || target == GUEST_NAMESPACE) return false
        val guestPrefix = prefixFor(null)
        val accountPrefix = "account.$target."
        val snapshot = store().data.first()
        val sources = mutableListOf<MigrationSource>()
        for ((key, value) in snapshot.asMap()) {
            val name = key.name
            when {
                name.startsWith(guestPrefix) -> {
                    sources.add(MigrationSource(name.removePrefix(guestPrefix), value, false))
                }
                !name.startsWith("account.") -> {
                    sources.add(MigrationSource(name, value, true))
                }
            }
        }
        if (sources.isEmpty()) return false
        var migrated = false
        store().edit { prefs ->
            for (source in sources) {
                // Notification state never migrates: it stays with
                // the guest namespace, whatever account signs in.
                if (source.base in NON_MIGRATING_BASE_KEYS) continue
                val accountKeyName = accountPrefix + source.base
                if (!containsKey(prefs, accountKeyName)) {
                    if (setTyped(prefs, accountKeyName, source.value)) {
                        migrated = true
                    }
                }
                if (source.legacy) {
                    // Adopt the legacy key into the guest namespace
                    // too, then remove it so it is adopted once only.
                    val guestKeyName = guestPrefix + source.base
                    if (!containsKey(prefs, guestKeyName)) {
                        setTyped(prefs, guestKeyName, source.value)
                    }
                    prefs.remove(stringPreferencesKey(source.base))
                }
            }
        }
        return migrated
    }

    private fun prefixFor(userId: String?): String {
        return "account.${sanitisedNamespace(userId)}."
    }

    private fun sanitisedNamespace(userId: String?): String {
        val trimmed = userId?.trim().orEmpty()
        if (trimmed.isEmpty()) return GUEST_NAMESPACE
        val sanitised = trimmed.map { ch ->
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '-'
        }.joinToString("")
        return sanitised.ifEmpty { GUEST_NAMESPACE }
    }

    private inline fun <T> readSafely(block: () -> T?): T? {
        return try {
            block()
        } catch (_: ClassCastException) {
            // A value stored under this name with a different type.
            null
        }
    }

    private fun containsKey(prefs: Preferences, name: String): Boolean {
        return prefs.asMap().keys.any { it.name == name }
    }

    private fun setTyped(prefs: MutablePreferences, name: String, value: Any): Boolean {
        return try {
            when (value) {
                is String -> prefs[stringPreferencesKey(name)] = value
                is Long -> prefs[longPreferencesKey(name)] = value
                is Boolean -> prefs[booleanPreferencesKey(name)] = value
                is Int -> prefs[intPreferencesKey(name)] = value
                is Double -> prefs[doublePreferencesKey(name)] = value
                is Float -> prefs[floatPreferencesKey(name)] = value
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    prefs[stringSetPreferencesKey(name)] = value as Set<String>
                }
                else -> return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun adoptLegacyString(base: String, value: String) {
        try {
            store().edit { prefs ->
                val guestKey = stringPreferencesKey("account.$GUEST_NAMESPACE.$base")
                if (readSafely { prefs[guestKey] } == null) {
                    prefs[guestKey] = value
                }
                prefs.remove(stringPreferencesKey(base))
            }
        } catch (_: Exception) {
            // Adoption is retried on the next read.
        }
    }

    private suspend fun adoptLegacyLong(base: String, value: Long) {
        try {
            store().edit { prefs ->
                val guestKey = longPreferencesKey("account.$GUEST_NAMESPACE.$base")
                if (readSafely { prefs[guestKey] } == null) {
                    prefs[guestKey] = value
                }
                prefs.remove(longPreferencesKey(base))
            }
        } catch (_: Exception) {
            // Adoption is retried on the next read.
        }
    }

    private suspend fun adoptLegacyBoolean(base: String, value: Boolean) {
        try {
            store().edit { prefs ->
                val guestKey = booleanPreferencesKey("account.$GUEST_NAMESPACE.$base")
                if (readSafely { prefs[guestKey] } == null) {
                    prefs[guestKey] = value
                }
                prefs.remove(booleanPreferencesKey(base))
            }
        } catch (_: Exception) {
            // Adoption is retried on the next read.
        }
    }
}
