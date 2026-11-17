// SituationsPagingTest.kt
// Unit tests for Payment Cases pagination: the first page shows
// 20, Load more extends it, and the free-samples partition
// invariant (each id rendered once) holds at full size.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.data.model.Situation
import com.paymentstraininghub.app.ui.cases.pagedSituationDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SituationsPagingTest {

    private fun situation(id: String, stage: Int = 1, free: Boolean = false) = Situation(
        id = id,
        title = "Situation $id",
        stage = stage,
        stageName = "Stage $stage",
        level = "Core",
        explain = "Explain $id.",
        takeaway = "Takeaway $id.",
        isFreeSample = free,
        isStageAuthoritative = true
    )

    /** 616 cases shaped like production: 2 free samples + 614 staged cases. */
    private fun fullSizeList(): List<Situation> {
        val list = mutableListOf<Situation>()
        list.add(situation("ABH-01", free = true))
        list.add(situation("JEB-C01", free = true))
        for (i in 3..616) {
            list.add(situation("CASE-%03d".format(i), stage = (i % 6) + 1))
        }
        return list
    }

    private fun displayedIds(display: com.paymentstraininghub.app.ui.cases.PagedSituationDisplay): List<String> {
        return display.freeSamples.map { it.id } +
            display.groups.flatMap { (_, _, list) -> list.map { it.id } }
    }

    @Test
    fun firstPageShowsTwenty() {
        val filtered = fullSizeList()
        val display = pagedSituationDisplay(
            filtered = filtered,
            freeSamples = filtered.filter { it.isFreeSample },
            showFreeSamplesSection = true,
            visibleCount = 20
        )
        assertEquals(20, displayedIds(display).size)
    }

    @Test
    fun loadMoreExtendsThePage() {
        val filtered = fullSizeList()
        val free = filtered.filter { it.isFreeSample }
        val first = pagedSituationDisplay(filtered, free, true, 20)
        val second = pagedSituationDisplay(filtered, free, true, 40)
        val firstIds = displayedIds(first)
        val secondIds = displayedIds(second)
        assertEquals(20, firstIds.size)
        assertEquals(40, secondIds.size)
        // Slicing happens before grouping, so page 2 regroups:
        // the display list is not an ordered prefix (earlier
        // stage groups grow and push later groups down). The
        // invariants are: nothing disappears, nothing repeats,
        // and the relative order of page-1 ids is unchanged.
        assertEquals(40, secondIds.toSet().size)
        assertTrue(secondIds.containsAll(firstIds))
        assertEquals(firstIds, secondIds.filter { it in firstIds.toSet() })
    }

    @Test
    fun fullListRendersEveryIdExactlyOnce() {
        val filtered = fullSizeList()
        val display = pagedSituationDisplay(
            filtered = filtered,
            freeSamples = filtered.filter { it.isFreeSample },
            showFreeSamplesSection = true,
            visibleCount = 616
        )
        val ids = displayedIds(display)
        assertEquals(616, ids.size)
        assertEquals(616, ids.toSet().size)
    }

    @Test
    fun noDuplicateKeysOnAnyPage() {
        val filtered = fullSizeList()
        val free = filtered.filter { it.isFreeSample }
        for (page in listOf(20, 40, 100, 616)) {
            val ids = displayedIds(pagedSituationDisplay(filtered, free, true, page))
            assertEquals("duplicate keys at page $page", ids.size, ids.toSet().size)
        }
    }

    @Test
    fun hiddenFreeSamplesSectionKeepsSamplesInStageGroups() {
        val filtered = fullSizeList()
        val display = pagedSituationDisplay(
            filtered = filtered,
            freeSamples = filtered.filter { it.isFreeSample },
            showFreeSamplesSection = false,
            visibleCount = 20
        )
        assertTrue(display.freeSamples.isEmpty())
        val ids = displayedIds(display)
        assertEquals(20, ids.size)
        assertTrue("ABH-01" in ids)
    }

    @Test
    fun duplicateIdsInInputRenderOnce() {
        val filtered = listOf(
            situation("ABH-01", free = true),
            situation("ABH-01", free = true),
            situation("ABH-02"),
            situation("ABH-02")
        )
        val display = pagedSituationDisplay(
            filtered = filtered,
            freeSamples = listOf(situation("ABH-01", free = true)),
            showFreeSamplesSection = true,
            visibleCount = 20
        )
        val ids = displayedIds(display)
        assertEquals(2, ids.size)
        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun pageTwoShowsTheNextTwentyWithNoOverlap() {
        val filtered = fullSizeList()
        val free = filtered.filter { it.isFreeSample }
        val firstIds = displayedIds(pagedSituationDisplay(filtered, free, true, 1, 20))
        val secondIds = displayedIds(pagedSituationDisplay(filtered, free, true, 2, 20))
        assertEquals(20, firstIds.size)
        assertEquals(20, secondIds.size)
        assertTrue(firstIds.intersect(secondIds.toSet()).isEmpty())
        assertEquals(40, (firstIds + secondIds).toSet().size)
    }

    @Test
    fun lastNumberedPageShowsTheRemainder() {
        val filtered = fullSizeList()
        val free = filtered.filter { it.isFreeSample }
        val last = pagedSituationDisplay(filtered, free, true, 31, 20)
        assertEquals(16, displayedIds(last).size)
    }

    @Test
    fun noDuplicateKeysOnAnyNumberedPage() {
        val filtered = fullSizeList()
        val free = filtered.filter { it.isFreeSample }
        for (page in 1..31) {
            val ids = displayedIds(pagedSituationDisplay(filtered, free, true, page, 20))
            assertEquals("duplicate keys at page $page", ids.size, ids.toSet().size)
        }
    }

    @Test
    fun everyNumberedPageTogetherCoversTheFullListOnce() {
        val filtered = fullSizeList()
        val free = filtered.filter { it.isFreeSample }
        val all = (1..31).flatMap { page ->
            displayedIds(pagedSituationDisplay(filtered, free, true, page, 20))
        }
        assertEquals(616, all.size)
        assertEquals(616, all.toSet().size)
    }

    @Test
    fun pageBeyondTheEndIsEmpty() {
        val filtered = fullSizeList()
        val free = filtered.filter { it.isFreeSample }
        val beyond = pagedSituationDisplay(filtered, free, true, 32, 20)
        assertTrue(displayedIds(beyond).isEmpty())
    }

    @Test
    fun pageFormMatchesPrefixFormOnPageOne() {
        val filtered = fullSizeList()
        val free = filtered.filter { it.isFreeSample }
        val prefix = displayedIds(pagedSituationDisplay(filtered, free, true, 20))
        val page = displayedIds(pagedSituationDisplay(filtered, free, true, 1, 20))
        assertEquals(prefix, page)
    }
}
