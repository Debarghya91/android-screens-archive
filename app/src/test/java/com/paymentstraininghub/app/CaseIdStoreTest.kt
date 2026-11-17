package com.paymentstraininghub.app

import com.paymentstraininghub.app.auth.CaseIdStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Case ID scheme tests: the canonical "PF-" plus 6 characters
 * from the shared alphabet (no 0, O, 1, I or L), reconciled with
 * the website and iOS. Storage-backed behaviour (loadCaseId,
 * ensureCaseId) needs AppStore and Android, so it is not covered
 * here.
 */
class CaseIdStoreTest {

    @Test
    fun generate_producesValidCanonicalIds() {
        repeat(200) {
            val id = CaseIdStore.generate()
            assertTrue("generated id must be valid: $id", CaseIdStore.isValid(id))
            assertTrue(id.startsWith("PF-"))
            assertEquals(9, id.length)
            val suffix = id.removePrefix("PF-")
            assertTrue(
                "suffix must use only the canonical alphabet: $suffix",
                suffix.all { it in CaseIdStore.CASE_ID_ALPHABET }
            )
            assertFalse("suffix must never contain 0: $suffix", suffix.contains('0'))
            assertFalse("suffix must never contain 1: $suffix", suffix.contains('1'))
        }
    }

    @Test
    fun generate_producesDistinctIds() {
        val ids = (1..200).map { CaseIdStore.generate() }.toSet()
        // 200 draws from 32^6 possibilities must not collide in
        // practice; a collision would mean the generator is broken.
        assertEquals(200, ids.size)
    }

    @Test
    fun isValid_acceptsCanonicalIds() {
        assertTrue(CaseIdStore.isValid("PF-ABCDEF"))
        assertTrue(CaseIdStore.isValid("PF-234567"))
        assertTrue(CaseIdStore.isValid("PF-89HJKM"))
    }

    @Test
    fun isValid_rejectsAmbiguousCharacters() {
        // 0, 1, I, L and O are outside the shared alphabet.
        assertFalse(CaseIdStore.isValid("PF-ABCDE0"))
        assertFalse(CaseIdStore.isValid("PF-ABCDE1"))
        assertFalse(CaseIdStore.isValid("PF-ABCDEI"))
        assertFalse(CaseIdStore.isValid("PF-ABCDEL"))
        assertFalse(CaseIdStore.isValid("PF-ABCDEO"))
    }

    @Test
    fun isValid_rejectsWrongShape() {
        assertFalse(CaseIdStore.isValid(null))
        assertFalse(CaseIdStore.isValid(""))
        assertFalse(CaseIdStore.isValid("PF-ABCDE"))
        assertFalse(CaseIdStore.isValid("PF-ABCDEFGH"))
        assertFalse(CaseIdStore.isValid("XX-ABCDEFG"))
        assertFalse(CaseIdStore.isValid("PFABCDEF"))
    }

    @Test
    fun isValid_acceptsLowercaseAfterNormalisation() {
        assertTrue(CaseIdStore.isValid("pf-abcdef"))
        assertTrue(CaseIdStore.isValid("  PF-ABCDEF  "))
    }

    @Test
    fun normalized_returnsUppercaseCanonicalForm() {
        assertEquals("PF-ABCDEF", CaseIdStore.normalized("pf-abcdef"))
        assertEquals("PF-ABCDEF", CaseIdStore.normalized("  PF-ABCDEF  "))
        assertNull(CaseIdStore.normalized("PF-ABCDE0"))
        assertNull(CaseIdStore.normalized(null))
        assertNull(CaseIdStore.normalized(""))
    }

    @Test
    fun alphabet_has31CharactersWithoutAmbiguousOnes() {
        val alphabet = CaseIdStore.CASE_ID_ALPHABET
        assertEquals(31, alphabet.length)
        assertEquals(31, alphabet.toSet().size)
        for (excluded in "01ILO") {
            assertFalse("alphabet must not contain $excluded", alphabet.contains(excluded))
        }
    }
}
