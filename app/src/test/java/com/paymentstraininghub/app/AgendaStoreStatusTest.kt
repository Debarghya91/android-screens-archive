// AgendaStoreStatusTest.kt
// Unit tests for the agendas load status Diagnostics reads: a
// failed fetch records its cause, a good fetch clears it, and the
// loaded agendas stay in place across a failure.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.data.AgendaStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgendaStoreStatusTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sampleJson = """
        {
          "lessons": {
            "ch1s1": {"agenda": "Why take this lesson.", "thumbnail": "assets/thumbs/ch1s1.svg"}
          },
          "chapters": {},
          "courses": {},
          "cases": {}
        }
    """.trimIndent()

    @Before
    fun setUp() {
        AgendaStore.resetForTest()
    }

    @After
    fun tearDown() {
        AgendaStore.resetForTest()
    }

    @Test
    fun failedFetchRecordsItsCause() = runBlocking {
        AgendaStore.testFetcher = { throw java.io.IOException("No route to host") }
        AgendaStore.refreshIfNeeded(tempFolder.root)
        val status = AgendaStore.loadStatus.value
        assertNotNull(status.lastError)
        assertTrue(status.lastError!!.contains("No route to host"))
        assertEquals(0L, status.lastSuccessMs)
        assertTrue(AgendaStore.agendas.value.isEmpty)
    }

    @Test
    fun successfulFetchClearsTheErrorAndLoadsAgendas() = runBlocking {
        AgendaStore.testFetcher = { sampleJson }
        AgendaStore.refreshIfNeeded(tempFolder.root)
        val status = AgendaStore.loadStatus.value
        assertNull(status.lastError)
        assertTrue(status.lastSuccessMs > 0L)
        assertEquals(
            "Why take this lesson.",
            AgendaStore.agendas.value.lesson("ch1s1")?.agenda
        )
    }

    @Test
    fun emptyDocumentRecordsAReadableCause() = runBlocking {
        AgendaStore.testFetcher = { "{}" }
        AgendaStore.refreshIfNeeded(tempFolder.root)
        val status = AgendaStore.loadStatus.value
        assertNotNull(status.lastError)
        assertTrue(AgendaStore.agendas.value.isEmpty)
    }

    @Test
    fun failureAfterSuccessKeepsTheLoadedAgendas() = runBlocking {
        AgendaStore.testFetcher = { sampleJson }
        AgendaStore.refreshIfNeeded(tempFolder.root)
        val loaded = AgendaStore.agendas.value
        assertEquals(
            "Why take this lesson.",
            loaded.lesson("ch1s1")?.agenda
        )
        // A fresh attempt that fails must not wipe the agendas:
        // reset only the throttle, reinstall the loaded agendas,
        // then fail the network fetch.
        AgendaStore.resetForTest()
        AgendaStore.setTestAgendas(loaded)
        AgendaStore.testFetcher = { throw java.io.IOException("Offline") }
        AgendaStore.refreshIfNeeded(tempFolder.root)
        assertNotNull(AgendaStore.loadStatus.value.lastError)
        assertEquals(
            "Why take this lesson.",
            AgendaStore.agendas.value.lesson("ch1s1")?.agenda
        )
    }
}
