package com.paymentstraininghub.app.billing

/*
 * EntitlementStore: durable cache for purchase entitlements and the
 * durable queue of purchases still to record on the server. All
 * storage goes through auth.AppStore (DataStore), never through a
 * second store.
 *
 * This store is a CACHE, not an authority. In Release builds
 * BillingManager never reads unlockedProductIds to gate access:
 * entitlements come only from Google Play query results held in
 * memory. The cache exists so the account scoped purchase record
 * survives restarts for bookkeeping, and so clearAccount can drop
 * one account's record on sign-out or deletion without touching
 * another account's record or the Play entitlements (which belong
 * to the Play account, not the app account).
 *
 * Keys are account scoped twice over, deliberately: the base key
 * embeds the userId argument ("guest" for signed-out use), and
 * AppStore.keyFor applies its own account scoping. clearAccount
 * should be called while the account session is still current, so
 * both scopings resolve to the same slot; leftovers from a
 * differently ordered call are inert, because Release never reads
 * this cache to unlock anything.
 *
 * Debug keys: setDebugUnlocked and debugUnlockedProductIds are
 * DEBUG only and return nothing in Release. purgeDebugKeys clears
 * the debug slot in Release builds and is a no-op in DEBUG, so a
 * stray call can never wipe test unlocks during development.
 * BillingManager calls purgeDebugKeys on every Release startup.
 *
 * Pending server sync queue: purchases that could not be recorded
 * on the server are kept as one JSON object per line under a single
 * key, capped at 50 records (oldest dropped first). Malformed lines
 * are skipped on read, never crash the queue. ServerSync drains the
 * queue through retryPendingServerSync.
 *
 * Server-verified entitlement cache (H2, ported from iOS
 * StoreManager): one record per account holding the exact set of
 * product IDs a definitive server read returned, with the time the
 * server confirmed it. Written ONLY from decoded server rows, never
 * from any local purchase or debug state. An empty set is a real
 * record: it means the server definitively reported no active row,
 * and it overwrites any older record so a revoked entitlement
 * cannot be resurrected offline. Records are usable for 7 days
 * after a failed server read, only for the same account, never for
 * guests. The cache is the ONLY stored state BillingManager may
 * replay to unlock anything, and only after a server read was
 * attempted and failed. The in-record user ID is a second line of
 * defence behind the account-scoped key: a record for another
 * account is refused even if a key were ever misfiled.
 *
 * AppStore contract (confirmed against the landed Worker E file
 * by the coordinator): suspend fun getString(baseKey): String?
 * and putString(baseKey, value). The calls take BASE keys and the
 * account namespace is applied internally; never wrap a key in
 * keyFor() before passing it in, or the key is namespaced twice.
 * Only readRaw and writeRaw below touch AppStore.
 */

import com.paymentstraininghub.app.BuildConfig
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.data.ProductCatalogue
import org.json.JSONArray
import org.json.JSONObject

/**
 * One purchase awaiting its server record, or already recorded.
 * purchaseToken is the identity of the record: re-enqueueing the
 * same token replaces the older entry instead of duplicating it.
 */
data class PendingPurchaseRecord(
    val productId: String,
    val purchaseToken: String,
    val packageName: String,
    val orderId: String,
    val purchaseTime: Long,
    val userId: String?
)

/**
 * One server-verified entitlement cache record (H2): the exact set
 * of product IDs a definitive server read returned for one account,
 * with the time the server confirmed it, in epoch millis, and the
 * per-product expiry instants when the server supplied them (null
 * when a product's expiry is unknown: the store or server owns
 * the state). Expired products are dropped on replay.
 */
data class ServerEntitlementCacheRecord(
    val userId: String,
    val productIds: Set<String>,
    val verifiedAtMillis: Long,
    val expiresAtByProduct: Map<String, Long?> = emptyMap()
)

object EntitlementStore {

    private const val ENTITLEMENTS_KEY_NAME = "pf.entitlements"
    private const val DEBUG_KEY_NAME = "pf.debug.unlocked"
    private const val PENDING_QUEUE_KEY_NAME = "pf.serverSync.pending"
    private const val SERVER_ENTITLEMENT_CACHE_KEY_NAME = "iap.serverEntitlements.v1"
    private const val MAX_PENDING_RECORDS = 50

    /** How long a server-verified cache record stays usable after a failed server read: 7 days. */
    const val SERVER_ENTITLEMENT_CACHE_VALIDITY_MILLIS: Long = 7L * 24L * 60L * 60L * 1000L

    private fun scopedKeyName(userId: String?): String {
        val scope = userId?.takeIf { it.isNotBlank() } ?: "guest"
        return "$ENTITLEMENTS_KEY_NAME.$scope"
    }

    private suspend fun readRaw(keyName: String): String {
        return AppStore.getString(keyName) ?: ""
    }

    private suspend fun writeRaw(keyName: String, value: String) {
        AppStore.putString(keyName, value)
    }

    private fun decodeIdSet(raw: String): Set<String> {
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /** Record or clear one cached entitlement for one account. Cache only, never an unlock. */
    suspend fun setUnlocked(userId: String?, productId: String, unlocked: Boolean) {
        val current = unlockedProductIds(userId).toMutableSet()
        if (unlocked) {
            current.add(productId)
        } else {
            current.remove(productId)
        }
        writeRaw(scopedKeyName(userId), current.sorted().joinToString(","))
    }

    /** Cached product IDs for one account. Never consulted to gate access in Release. */
    suspend fun unlockedProductIds(userId: String?): Set<String> {
        return decodeIdSet(readRaw(scopedKeyName(userId)))
    }

    /**
     * Drop one account's cached entitlements and its queued server
     * sync records, so the next account never inherits them. Play
     * entitlements are untouched: they belong to the Play account.
     */
    suspend fun clearAccount(userId: String?) {
        writeRaw(scopedKeyName(userId), "")
        val remaining = pendingServerSyncRecords().filter { it.userId != userId }
        writePendingQueue(remaining)
    }

    /**
     * Remove any debug unlock keys. Only acts in Release builds;
     * no-op in DEBUG and qa test builds. Called on every Release startup so debug
     * state left by an earlier build can never unlock anything.
     */
    suspend fun purgeDebugKeys() {
        if (BuildConfig.DEBUG || BuildConfig.ENABLE_TEST_UNLOCK) return
        writeRaw(DEBUG_KEY_NAME, "")
    }

    /** DEBUG only: record or clear one debug test unlock. No-op in Release. */
    suspend fun setDebugUnlocked(productId: String, unlocked: Boolean) {
        if (!BuildConfig.DEBUG && !BuildConfig.ENABLE_TEST_UNLOCK) return
        val current = debugUnlockedProductIds().toMutableSet()
        if (unlocked) {
            current.add(productId)
        } else {
            current.remove(productId)
        }
        writeRaw(DEBUG_KEY_NAME, current.sorted().joinToString(","))
    }

    /** DEBUG only: product IDs unlocked for testing. Always empty in Release. */
    suspend fun debugUnlockedProductIds(): Set<String> {
        if (!BuildConfig.DEBUG && !BuildConfig.ENABLE_TEST_UNLOCK) return emptySet()
        return decodeIdSet(readRaw(DEBUG_KEY_NAME))
    }

    /** Queue one purchase for a later server record attempt. Replaces any same-token entry. */
    suspend fun enqueuePendingServerSync(record: PendingPurchaseRecord) {
        val queue = pendingServerSyncRecords().toMutableList()
        queue.removeAll { it.purchaseToken == record.purchaseToken }
        queue.add(record)
        while (queue.size > MAX_PENDING_RECORDS) {
            queue.removeAt(0)
        }
        writePendingQueue(queue)
    }

    /** All purchases still awaiting a server record, oldest first. */
    suspend fun pendingServerSyncRecords(): List<PendingPurchaseRecord> {
        val raw = readRaw(PENDING_QUEUE_KEY_NAME)
        if (raw.isBlank()) return emptyList()
        return raw.lines().mapNotNull { line -> decodeRecord(line) }
    }

    /** Remove one purchase from the pending queue, matched by purchase token. */
    suspend fun removePendingServerSync(record: PendingPurchaseRecord) {
        val remaining = pendingServerSyncRecords()
            .filter { it.purchaseToken != record.purchaseToken }
        writePendingQueue(remaining)
    }

    // MARK: - Server-verified entitlement cache (H2)

    private fun serverCacheKeyName(userId: String): String {
        return "$SERVER_ENTITLEMENT_CACHE_KEY_NAME.$userId"
    }

    /**
     * Write one cache record from a definitive server answer. The
     * ONLY writer is BillingManager.applyDefinitiveServerEntitlements,
     * fed only by its server read. Never call this from any
     * purchase, debug, or local-cache path. An empty map is written
     * as a real record: it overwrites any older record. Expiry
     * instants are stored per product; a null expiry means the
     * server supplied none and the store or server owns the state.
     */
    suspend fun saveServerEntitlementCache(userId: String, entitlements: Map<String, Long?>) {
        val json = JSONObject()
        json.put("user_id", userId)
        val array = JSONArray()
        entitlements.keys.sorted().forEach { array.put(it) }
        json.put("product_ids", array)
        val expiries = JSONObject()
        entitlements.entries.sortedBy { it.key }.forEach { (productId, expiresAt) ->
            if (expiresAt != null) expiries.put(productId, expiresAt)
        }
        json.put("expires_at", expiries)
        json.put("verified_at", System.currentTimeMillis())
        writeRaw(serverCacheKeyName(userId), json.toString())
    }

    /**
     * The fresh server-verified cache for one account, as a map of
     * base product id to expiry instant (null when unknown), or
     * null when there is no usable record: missing, corrupt, filed
     * for a different account, or older than the validity window.
     * A record dated in the future is also unusable (fail closed
     * on clock or storage surprises). Products whose stored expiry
     * has passed are dropped. The returned map holds only known
     * catalogue product IDs. Records written before expiry was
     * stored carry no expires_at object and replay with null
     * expiries, as before.
     */
    suspend fun cachedServerEntitlements(userId: String): Map<String, Long?>? {
        val raw = readRaw(serverCacheKeyName(userId))
        if (raw.isBlank()) return null
        val record = try {
            val json = JSONObject(raw)
            val recordUserId = json.optString("user_id", "")
            if (recordUserId != userId) return null
            val verifiedAt = json.optLong("verified_at", 0L)
            val ids = mutableSetOf<String>()
            val array = json.optJSONArray("product_ids")
            if (array != null) {
                for (i in 0 until array.length()) {
                    ids.add(array.optString(i, ""))
                }
            }
            val expiries = mutableMapOf<String, Long?>()
            val expiriesJson = json.optJSONObject("expires_at")
            if (expiriesJson != null) {
                for (key in expiriesJson.keys()) {
                    expiries[key] = expiriesJson.optLong(key, 0L).takeIf { it > 0L }
                }
            }
            ServerEntitlementCacheRecord(recordUserId, ids, verifiedAt, expiries)
        } catch (e: Exception) {
            return null
        }
        val now = System.currentTimeMillis()
        val age = now - record.verifiedAtMillis
        if (age < 0 || age >= SERVER_ENTITLEMENT_CACHE_VALIDITY_MILLIS) return null
        val known = ProductCatalogue.allProductIds.toSet()
        val result = mutableMapOf<String, Long?>()
        for (productId in record.productIds) {
            val base = EntitlementRules.baseProductIdFor(productId)
            if (base !in known) continue
            val expiresAt = record.expiresAtByProduct[productId]
                ?: record.expiresAtByProduct[base]
            if (expiresAt != null && expiresAt <= now) continue
            if (!result.containsKey(base)) {
                result[base] = expiresAt
            } else {
                // Union semantics: active when ANY record is
                // active, so an unknown expiry (store-owned) wins
                // over a finite one, and of two finite expiries
                // the later one wins.
                val existing = result[base]
                if (expiresAt == null || (existing != null && expiresAt > existing)) {
                    result[base] = expiresAt
                }
            }
        }
        return result
    }

    private suspend fun writePendingQueue(records: List<PendingPurchaseRecord>) {
        writeRaw(PENDING_QUEUE_KEY_NAME, records.joinToString("\n") { encodeRecord(it) })
    }

    private fun encodeRecord(record: PendingPurchaseRecord): String {
        val json = JSONObject()
        json.put("product_id", record.productId)
        json.put("purchase_token", record.purchaseToken)
        json.put("package_name", record.packageName)
        json.put("order_id", record.orderId)
        json.put("purchase_time", record.purchaseTime)
        if (record.userId != null) {
            json.put("user_id", record.userId)
        } else {
            json.put("user_id", JSONObject.NULL)
        }
        return json.toString()
    }

    private fun decodeRecord(line: String): PendingPurchaseRecord? {
        if (line.isBlank()) return null
        return try {
            val json = JSONObject(line)
            val userId = json.optString("user_id", "").takeIf { it.isNotEmpty() }
            PendingPurchaseRecord(
                productId = json.optString("product_id", ""),
                purchaseToken = json.optString("purchase_token", ""),
                packageName = json.optString("package_name", ""),
                orderId = json.optString("order_id", ""),
                purchaseTime = json.optLong("purchase_time", 0L),
                userId = userId
            ).takeIf { it.productId.isNotEmpty() && it.purchaseToken.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
}
