// PagingTest.kt
// Unit tests for the shared 20-per-page paging helpers.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.data.PAGE_SIZE
import com.paymentstraininghub.app.data.PageWindowItem
import com.paymentstraininghub.app.data.pageItems
import com.paymentstraininghub.app.data.pageRange
import com.paymentstraininghub.app.data.pageWindow
import com.paymentstraininghub.app.data.remainingCount
import com.paymentstraininghub.app.data.showingRangeText
import com.paymentstraininghub.app.data.showingText
import com.paymentstraininghub.app.data.totalPages
import com.paymentstraininghub.app.data.visiblePage
import org.junit.Assert.assertEquals
import org.junit.Test

class PagingTest {

    @Test
    fun pageSizeIsTwenty() {
        assertEquals(20, PAGE_SIZE)
    }

    @Test
    fun visiblePageSlicesAtTwenty() {
        val items = (1..881).toList()
        assertEquals(20, visiblePage(items, 20).size)
        assertEquals((1..20).toList(), visiblePage(items, 20))
        assertEquals(40, visiblePage(items, 40).size)
    }

    @Test
    fun visiblePageNeverExceedsTheList() {
        val items = (1..5).toList()
        assertEquals(items, visiblePage(items, 20))
        assertEquals(emptyList<Int>(), visiblePage(emptyList<Int>(), 20))
    }

    @Test
    fun visiblePageClampsNegativeCounts() {
        val items = (1..30).toList()
        assertEquals(emptyList<Int>(), visiblePage(items, 0))
        assertEquals(emptyList<Int>(), visiblePage(items, -5))
    }

    @Test
    fun remainingCountIsTotalMinusShown() {
        assertEquals(861, remainingCount(881, 20))
        assertEquals(0, remainingCount(20, 20))
        assertEquals(0, remainingCount(5, 20))
    }

    @Test
    fun showingTextUsesSingularAndPlural() {
        assertEquals("Showing 1 of 1 result", showingText(1, 1, "result", "results"))
        assertEquals("Showing 20 of 881 results", showingText(20, 881, "result", "results"))
        assertEquals("Showing 20 of 616 cases", showingText(20, 616, "case", "cases"))
    }

    @Test
    fun totalPagesRoundsUp() {
        assertEquals(0, totalPages(0))
        assertEquals(1, totalPages(1))
        assertEquals(1, totalPages(20))
        assertEquals(2, totalPages(21))
        assertEquals(31, totalPages(616))
        assertEquals(45, totalPages(881))
    }

    @Test
    fun pageRangeCoversEachPage() {
        assertEquals(1 to 20, pageRange(616, 1))
        assertEquals(21 to 40, pageRange(616, 2))
        assertEquals(601 to 616, pageRange(616, 31))
        assertEquals(1 to 1, pageRange(1, 1))
        assertEquals(0 to 0, pageRange(0, 1))
    }

    @Test
    fun pageRangeClampsOutOfRangePages() {
        assertEquals(1 to 20, pageRange(616, 0))
        assertEquals(1 to 20, pageRange(616, -3))
        assertEquals(601 to 616, pageRange(616, 99))
    }

    @Test
    fun showingRangeTextReadsNaturally() {
        assertEquals("Showing 1-20 of 616 cases", showingRangeText(1, 20, 616, "case", "cases"))
        assertEquals("Showing 601-616 of 616 cases", showingRangeText(601, 616, 616, "case", "cases"))
        assertEquals("Showing 1 of 1 case", showingRangeText(1, 1, 1, "case", "cases"))
        assertEquals("Showing 0 of 0 cases", showingRangeText(0, 0, 0, "case", "cases"))
        assertEquals("Showing 1-20 of 881 results", showingRangeText(1, 20, 881, "result", "results"))
    }

    @Test
    fun pageItemsSlicesByPage() {
        val items = (1..616).toList()
        assertEquals((1..20).toList(), pageItems(items, 1))
        assertEquals((21..40).toList(), pageItems(items, 2))
        assertEquals((601..616).toList(), pageItems(items, 31))
        assertEquals(emptyList<Int>(), pageItems(items, 32))
        assertEquals((1..20).toList(), pageItems(items, 0))
        assertEquals(emptyList<Int>(), pageItems(emptyList<Int>(), 1))
    }

    @Test
    fun pageWindowShowsEveryPageForShortRanges() {
        assertEquals(
            (1..7).map { PageWindowItem.Page(it) },
            pageWindow(4, 7)
        )
        assertEquals(listOf(PageWindowItem.Page(1)), pageWindow(1, 1))
        assertEquals(emptyList<PageWindowItem>(), pageWindow(1, 0))
    }

    @Test
    fun pageWindowAtPageFourOfEightHidesNoPromisedPage() {
        // The off-by-one that hid a page on another platform: page 4
        // of 8 must show 1 2 3 4 5 6, then an ellipsis, then 8.
        assertEquals(
            listOf(1, 2, 3, 4, 5, 6).map { PageWindowItem.Page(it) } +
                listOf(PageWindowItem.Ellipsis, PageWindowItem.Page(8)),
            pageWindow(4, 8)
        )
    }

    @Test
    fun pageWindowCentresOnTheCurrentPageWithEllipses() {
        assertEquals(
            listOf(
                PageWindowItem.Page(1), PageWindowItem.Ellipsis,
                PageWindowItem.Page(3), PageWindowItem.Page(4), PageWindowItem.Page(5),
                PageWindowItem.Page(6), PageWindowItem.Page(7),
                PageWindowItem.Ellipsis, PageWindowItem.Page(20)
            ),
            pageWindow(5, 20)
        )
    }

    @Test
    fun pageWindowAtTheEndsCarriesOneEllipsis() {
        assertEquals(
            listOf(
                PageWindowItem.Page(1), PageWindowItem.Page(2), PageWindowItem.Page(3),
                PageWindowItem.Ellipsis, PageWindowItem.Page(20)
            ),
            pageWindow(1, 20)
        )
        assertEquals(
            listOf(
                PageWindowItem.Page(1), PageWindowItem.Ellipsis,
                PageWindowItem.Page(18), PageWindowItem.Page(19), PageWindowItem.Page(20)
            ),
            pageWindow(20, 20)
        )
        assertEquals(
            listOf(
                PageWindowItem.Page(1), PageWindowItem.Page(2), PageWindowItem.Page(3),
                PageWindowItem.Page(4), PageWindowItem.Ellipsis, PageWindowItem.Page(31)
            ),
            pageWindow(2, 31)
        )
    }

    @Test
    fun pageWindowClampsTheCurrentPage() {
        assertEquals(pageWindow(1, 20), pageWindow(0, 20))
        assertEquals(pageWindow(20, 20), pageWindow(99, 20))
    }
}
