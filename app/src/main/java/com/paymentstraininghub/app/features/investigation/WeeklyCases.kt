// WeeklyCases.kt
// The dedicated weekly-case list (52 items in week order) and the
// deterministic weekly pick. Ports iOS ContentService.loadWeeklyCases
// and ReferralInvite.WeeklyFreeCaseSelector (v76).
//
// The weekly asset is free public content, no purchase and no
// sign-in. On failure the caller keeps the catalogue pick, so the
// weekly case never goes empty. The 52 weekly items plus the 564
// situations are the 616 items the investigation engine covers.
//
// Week math reuses CommunityChallenge (the v1 ISO week
// implementation: UTC calendar, Monday first, 4 minimal days),
// which implements the same rule as the iOS selector: the week
// ordinal (ISO year times 53 plus ISO week) modulo the list
// length. No second calendar implementation is added.
// No em dashes in any user-facing text.

package com.paymentstraininghub.app.features.investigation

import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.FullSituationItem
import com.paymentstraininghub.app.data.model.Situation
import com.paymentstraininghub.app.features.growth.CommunityChallenge
import com.paymentstraininghub.app.security.PinningObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Loads the dedicated weekly-case list from the live website host,
 * exactly as iOS loads it. No disk cache: the v1 ContentRepository
 * keeps no disk cache for the situations file either, so this
 * loader follows the v1 pattern (in-memory cache, bounded retry)
 * rather than the iOS file cache. See INVESTIGATION-NOTES.md. */
object WeeklyCaseLoader {

    /** The weekly-cases data URL, the same live source as iOS
     * (ContentService.weeklyCasesDataURL). */
    const val WEEKLY_CASES_URL = "https://www.paymentstraininghub.com/assets/weekly-cases-data.js"

    private const val WEEKLY_PREFIX = "window.PA_WEEKLY_CASES = "

    private val client: OkHttpClient = OkHttpClient.Builder()
        .addNetworkInterceptor(PinningObserver)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mutex = Mutex()

    @Volatile
    private var cachedWeeklyItems: List<FullSituationItem>? = null

    /** Load the weekly-case list. The result is cached in memory
     * and the load is thread-safe: concurrent callers share one
     * fetch. Bounded retry (3 attempts, exponential backoff).
     * Throws IOException when every attempt fails. */
    suspend fun weeklyItems(): List<FullSituationItem> {
        cachedWeeklyItems?.let { return it }
        return mutex.withLock {
            cachedWeeklyItems?.let { return@withLock it }
            val loaded = fetchWeeklyItemsWithRetry()
            cachedWeeklyItems = loaded
            loaded
        }
    }

    /** The weekly cases as Situations, converted exactly like
     * catalogue items from the same data-file shape. */
    suspend fun weeklySituations(): List<Situation> =
        weeklyItems().map { ContentRepository.situationFromFull(it) }

    private suspend fun fetchWeeklyItemsWithRetry(): List<FullSituationItem> {
        var lastError: IOException = IOException("The website did not respond.")
        for (attempt in 0 until 3) {
            try {
                val text = fetchText(WEEKLY_CASES_URL)
                return parseWeeklyCasesData(text)
            } catch (e: IOException) {
                lastError = e
            } catch (e: Exception) {
                lastError = IOException("The website did not respond.", e)
            }
            if (attempt < 2) delay(500L * (1L shl attempt))
        }
        throw lastError
    }

    /** Strip the JS assignment wrapper (window.PA_WEEKLY_CASES =
     * [...];), then decode through the shared situations parser,
     * which skips malformed items individually so one bad item
     * cannot empty the whole list. The extra `week` field on
     * weekly items is ignored by the decoder. */
    internal fun parseWeeklyCasesData(text: String): List<FullSituationItem> {
        var cleaned = text.trim()
        if (cleaned.startsWith(WEEKLY_PREFIX)) {
            cleaned = cleaned.substring(WEEKLY_PREFIX.length).trim()
        }
        if (cleaned.endsWith(";")) {
            cleaned = cleaned.dropLast(1).trim()
        }
        return ContentRepository.parseSituationsData(cleaned)
    }

    private suspend fun fetchText(url: String): String {
        val request = Request.Builder().url(url).get().build()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("The website did not respond.")
                }
                response.body?.string() ?: throw IOException("The website did not respond.")
            }
        }
    }
}

/** The deterministic weekly pick, mirroring iOS
 * WeeklyFreeCaseSelector. The same week and the same list always
 * pick the same case, on every device, with no server. */
object WeeklyCaseSelector {

    /** The week key for a moment, for example "2026-W46": the ISO
     * year for the week plus the ISO week number. Used as the
     * storage key for the opened marker, so one marker per week. */
    fun weekKey(timeMillis: Long = System.currentTimeMillis()): String =
        CommunityChallenge.weekKey(timeMillis)

    /** The label for the week containing a moment, for example
     * "Week of 9 Nov 2026". Built from the week start only. */
    fun weekLabel(timeMillis: Long = System.currentTimeMillis()): String =
        CommunityChallenge.weekLabel(timeMillis)

    /** The index picked for a week, or null when the list is empty.
     * The week ordinal (ISO year times 53 plus ISO week) modulo
     * the list length. Over the 52-case weekly list this is one
     * case per week in continuous sequence across year
     * boundaries, wrapping after 52 weeks. */
    fun index(count: Int, timeMillis: Long = System.currentTimeMillis()): Int? {
        if (count <= 0) return null
        val ordinal = CommunityChallenge.weekOrdinal(timeMillis)
        return ((ordinal % count) + count) % count
    }

    /** The situation id picked for a week, from ids in list order.
     * Null when the list is empty. */
    fun situationId(ids: List<String>, timeMillis: Long = System.currentTimeMillis()): String? {
        val picked = index(ids.size, timeMillis) ?: return null
        return ids[picked]
    }

    /** This week's item from the weekly list, in week order.
     * Null when the list is empty. */
    fun pickThisWeek(items: List<FullSituationItem>, timeMillis: Long = System.currentTimeMillis()): FullSituationItem? {
        val picked = index(items.size, timeMillis) ?: return null
        return items[picked]
    }
}
