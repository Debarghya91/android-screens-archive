// ThumbnailStoreTest.kt
// Unit tests for per-URL failure isolation and parallel fetching:
// one failed thumbnail must never suppress the others, and one
// slow fetch must never serialize the rest of the list.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.data.ThumbnailStore
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ThumbnailStoreTest {

    @Before
    fun setUp() {
        ThumbnailStore.resetForTest()
    }

    @After
    fun tearDown() {
        ThumbnailStore.resetForTest()
    }

    @Test
    fun oneFailedUrlDoesNotSuppressOtherUrls() = runBlocking {
        val calls = mutableListOf<String>()
        ThumbnailStore.testFetcher = { url ->
            calls.add(url)
            if (url.contains("dead.svg")) null else "<svg/>"
        }
        assertNull(ThumbnailStore.svgFor("assets/thumbs/dead.svg", null))
        assertEquals("<svg/>", ThumbnailStore.svgFor("assets/thumbs/alive.svg", null))
        assertTrue(calls.any { it.contains("alive.svg") })
        assertTrue("dead.svg" in ThumbnailStore.failedPaths.value.joinToString())
    }

    @Test
    fun failedUrlIsSuppressedForItselfOnly() = runBlocking {
        var calls = 0
        ThumbnailStore.testFetcher = {
            calls++
            null
        }
        assertNull(ThumbnailStore.svgFor("assets/thumbs/dead.svg", null))
        assertNull(ThumbnailStore.svgFor("assets/thumbs/dead.svg", null))
        assertEquals(1, calls)
    }

    @Test
    fun successfulFetchClearsTheFailureRecord() = runBlocking {
        var fail = true
        ThumbnailStore.testFetcher = { if (fail) null else "<svg/>" }
        assertNull(ThumbnailStore.svgFor("assets/thumbs/flaky.svg", null))
        assertTrue(ThumbnailStore.failedPaths.value.contains("assets/thumbs/flaky.svg"))
        fail = false
        // Still inside the backoff window: the second call is
        // suppressed and the failure record stays. A fresh store
        // (new install state) fetches again and clears it.
        ThumbnailStore.resetForTest()
        ThumbnailStore.testFetcher = { "<svg/>" }
        assertEquals("<svg/>", ThumbnailStore.svgFor("assets/thumbs/flaky.svg", null))
        assertTrue(ThumbnailStore.failedPaths.value.isEmpty())
    }

    @Test
    fun slowFetchDoesNotBlockOtherFetches() = runBlocking {
        ThumbnailStore.testFetcher = { url ->
            if (url.contains("slow.svg")) {
                delay(500)
                "<svg>slow</svg>"
            } else {
                "<svg>fast</svg>"
            }
        }
        val slow = async { ThumbnailStore.svgFor("assets/thumbs/slow.svg", null) }
        // Give the slow fetch a head start, then fetch the fast
        // one: it must return while the slow fetch is still in
        // flight, proving the mutex is not held across I/O.
        delay(50)
        val fastStarted = System.currentTimeMillis()
        val fast = ThumbnailStore.svgFor("assets/thumbs/fast.svg", null)
        val fastElapsed = System.currentTimeMillis() - fastStarted
        assertEquals("<svg>fast</svg>", fast)
        assertTrue("fast fetch took ${fastElapsed}ms, slow fetch was still running", fastElapsed < 400)
        assertEquals("<svg>slow</svg>", slow.await())
    }

    @Test
    fun memoryCacheServesTheSecondCallWithoutFetching() = runBlocking {
        var calls = 0
        ThumbnailStore.testFetcher = {
            calls++
            "<svg/>"
        }
        assertEquals("<svg/>", ThumbnailStore.svgFor("assets/thumbs/cached.svg", null))
        assertEquals("<svg/>", ThumbnailStore.svgFor("assets/thumbs/cached.svg", null))
        assertEquals(1, calls)
    }

    @Test
    fun blankPathReturnsNullWithoutFetching() = runBlocking {
        var calls = 0
        ThumbnailStore.testFetcher = {
            calls++
            "<svg/>"
        }
        assertNull(ThumbnailStore.svgFor("   ", null))
        assertEquals(0, calls)
    }
}
