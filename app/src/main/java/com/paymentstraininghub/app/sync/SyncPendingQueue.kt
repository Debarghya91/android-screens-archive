package com.paymentstraininghub.app.sync

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * One durable pending sync operation. The payload is the exact JSON
 * body to replay against the named table (a row object or an array
 * of row objects, already encoded by the caller). The queue never
 * interprets the payload: it only stores it, hands it back, and
 * counts retries.
 *
 * Divergence from iOS, per the coordinator brief: the iOS
 * SyncPendingQueue records only THAT a sync is owed (a change kind
 * plus identifiers) and every sync re-reads the local stores, which
 * hold the data. This Android queue carries replayable payload ops
 * instead, persisted as JSON in internal storage. The local stores
 * remain the durable record of progress itself, and SyncService
 * still re-reads them on every run, so dropping a queued op never
 * loses progress data: the next run re-derives the same rows.
 */
@Serializable
data class SyncPendingOp(
    /** Stable id (UUID string) used by remove() and retry counting. */
    val id: String = UUID.randomUUID().toString(),
    /** Supabase table name, one of SyncTables. Never invented. */
    val table: String,
    /** "upsert" or "delete". */
    val action: String,
    /** JSON body to replay for this op. */
    val payloadJson: String,
    /** Epoch millis when the op was first enqueued. */
    val createdAt: Long = System.currentTimeMillis(),
    /** Failed replay attempts so far. Capped by SyncService. */
    val retryCount: Int = 0
) {
    companion object {
        const val ACTION_UPSERT = "upsert"
        const val ACTION_DELETE = "delete"
    }
}

/**
 * Durable, thread-safe queue of pending sync operations, persisted
 * as one JSON array at <filesDir>/sync-pending/queue.json (internal
 * storage, private to the app). The file is loaded lazily on first
 * use and written through on every mutation, so pending work
 * survives process death.
 *
 * Corrupt file behaviour: a stored payload that cannot be decoded
 * degrades to an empty queue and is logged. Nothing is lost: the
 * local stores hold the progress data and the next sync re-reads
 * them in full, which matches the iOS degradation rule (a corrupt
 * queue forces a full re-read; the stores hold the data). This is
 * the queue's own bookkeeping, not server data: the rule that a
 * SERVER decode failure must fail the sync still applies in
 * SyncService and is unaffected.
 *
 * All operations are suspend functions guarded by one Mutex, so the
 * queue is safe to use from any coroutine and any thread.
 */
class SyncPendingQueue(context: Context) {

    private val appContext: Context = context.applicationContext
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private var loaded: Boolean = false
    private var ops: MutableList<SyncPendingOp> = mutableListOf()

    private val queueFile: File
        get() = File(File(appContext.filesDir, QUEUE_DIR), QUEUE_FILE)

    /** Append one op and persist. Returns the stored op. */
    suspend fun enqueue(op: SyncPendingOp): SyncPendingOp = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoadedLocked()
            ops.add(op)
            saveLocked()
            op
        }
    }

    /** Convenience enqueue that builds the op with a fresh UUID. */
    suspend fun enqueue(table: String, action: String, payloadJson: String): SyncPendingOp =
        enqueue(SyncPendingOp(table = table, action = action, payloadJson = payloadJson))

    /** Snapshot of every queued op, oldest first. */
    suspend fun peekAll(): List<SyncPendingOp> = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoadedLocked()
            ops.toList()
        }
    }

    /** True when at least one op is queued. */
    suspend fun hasPending(): Boolean = peekAll().isNotEmpty()

    /** Remove the ops with the given ids and persist. */
    suspend fun remove(ids: Collection<String>): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoadedLocked()
            if (ops.removeAll { it.id in ids }) {
                saveLocked()
            }
        }
    }

    /** Remove one op by id and persist. */
    suspend fun remove(id: String): Unit = remove(listOf(id))

    /** Drop the whole queue and persist the empty state. */
    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoadedLocked()
            if (ops.isNotEmpty()) {
                ops.clear()
                saveLocked()
            }
        }
    }

    /**
     * Increment one op's retry count and persist. Used by
     * SyncService when a replay fails, so bounded retries (max 3)
     * survive process death. The op keeps its id and position.
     */
    suspend fun incrementRetryCount(id: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoadedLocked()
            val index = ops.indexOfFirst { it.id == id }
            if (index >= 0) {
                ops[index] = ops[index].copy(retryCount = ops[index].retryCount + 1)
                saveLocked()
            }
        }
    }

    // MARK: - Storage (call with the mutex held, on Dispatchers.IO)

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val file = queueFile
        if (!file.exists()) {
            ops = mutableListOf()
            return
        }
        try {
            val text = file.readText()
            if (text.isBlank()) {
                ops = mutableListOf()
            } else {
                ops = json.decodeFromString<List<SyncPendingOp>>(text).toMutableList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stored pending queue could not be decoded; starting empty. The local stores hold the data, so the next sync re-reads them in full.", e)
            ops = mutableListOf()
        }
    }

    private fun saveLocked() {
        try {
            val file = queueFile
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(ops))
        } catch (e: Exception) {
            Log.e(TAG, "Could not persist the pending sync queue.", e)
        }
    }

    companion object {
        private const val TAG = "SyncPendingQueue"
        private const val QUEUE_DIR = "sync-pending"
        private const val QUEUE_FILE = "queue.json"
    }
}
