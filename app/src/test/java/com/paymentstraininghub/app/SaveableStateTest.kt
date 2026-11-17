package com.paymentstraininghub.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import com.paymentstraininghub.app.ui.enumSaver
import com.paymentstraininghub.app.ui.nullableEnumSaver
import com.paymentstraininghub.app.ui.setSaver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM round-trip tests for the rememberSaveable helpers.
 * Each saver covers the MutableState object itself, as
 * rememberSaveable requires for a custom saver, so the tests
 * save and restore state objects and assert on their values.
 */
class SaveableStateTest {

    private enum class Flavour { SWEET, SOUR }

    // Saver.save is a member extension on SaverScope, so tests
    // call it through a scope that accepts every value.
    private val saverScope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    private fun <O, S : Any> Saver<O, S>.saveValue(value: O): S? =
        with(saverScope) { save(value) }

    @Test
    fun enumSaver_roundTripsByName() {
        val saver = enumSaver(Flavour.entries.toTypedArray())
        assertEquals("SOUR", saver.saveValue(mutableStateOf(Flavour.SOUR)))
        assertEquals(Flavour.SOUR, saver.restore("SOUR")?.value)
    }

    @Test
    fun enumSaver_unknownNameRestoresFallback() {
        val saver = enumSaver(Flavour.entries.toTypedArray(), Flavour.SWEET)
        assertEquals(Flavour.SWEET, saver.restore("NOPE")?.value)
    }

    @Test
    fun nullableEnumSaver_roundTripsNullAndValue() {
        val saver = nullableEnumSaver(Flavour.entries.toTypedArray())
        assertNull(saver.saveValue(mutableStateOf<Flavour?>(null)))
        assertEquals("SWEET", saver.saveValue(mutableStateOf<Flavour?>(Flavour.SWEET)))
        assertEquals(Flavour.SWEET, saver.restore("SWEET")?.value)
        assertNull(saver.restore("NOPE")?.value)
    }

    @Test
    fun setSaver_roundTripsStringsAndInts() {
        val stringSaver = setSaver<String>()
        assertEquals(
            listOf("a", "b"),
            stringSaver.saveValue(mutableStateOf(setOf("a", "b")))?.sorted()
        )
        assertEquals(setOf("a", "b"), stringSaver.restore(listOf("a", "b"))?.value)
        val intSaver = setSaver<Int>()
        assertEquals(setOf(1, 2), intSaver.restore(listOf(1, 2))?.value)
        assertEquals(emptySet<Int>(), intSaver.restore(emptyList())?.value)
    }
}
