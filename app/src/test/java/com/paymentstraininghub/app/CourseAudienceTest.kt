// CourseAudienceTest.kt
// Unit tests for the courses rework: every buyable course names
// its audience, in plain words, with no em dash.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.data.ProductCatalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseAudienceTest {

    @Test
    fun catalogueHasSixteenProducts() {
        assertEquals(16, ProductCatalogue.products.size)
    }

    @Test
    fun everyProductHasNameTaglineDescriptionAndAudience() {
        ProductCatalogue.products.forEach { product ->
            assertTrue(product.id, product.name.isNotBlank())
            assertTrue(product.id, product.tagline.isNotBlank())
            assertTrue(product.id, product.description.isNotBlank())
            assertTrue(product.id, product.audience.isNotBlank())
        }
    }

    @Test
    fun noEmDashInCourseCopy() {
        ProductCatalogue.products.forEach { product ->
            assertFalse(product.id, product.name.contains("—"))
            assertFalse(product.id, product.tagline.contains("—"))
            assertFalse(product.id, product.description.contains("—"))
            assertFalse(product.id, product.audience.contains("—"))
        }
    }

    @Test
    fun courseNamesAreProminentAndNumbered() {
        val parts = ProductCatalogue.products.filter { it.id.startsWith("part-") }
        assertEquals(10, parts.size)
        parts.forEachIndexed { index, product ->
            assertTrue(product.id, product.name.startsWith("Course ${index + 1}: "))
        }
    }
}
