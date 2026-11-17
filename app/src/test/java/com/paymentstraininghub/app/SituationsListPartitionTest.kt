package com.paymentstraininghub.app

import com.paymentstraininghub.app.data.model.Situation
import com.paymentstraininghub.app.ui.cases.stageGroupsForSituationsList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SituationsListPartitionTest {

    private fun situation(
        id: String,
        stage: Int = 1,
        free: Boolean = false
    ): Situation = Situation(
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

    private fun allIds(groups: List<Triple<Int, String, List<Situation>>>): List<String> =
        groups.flatMap { (_, _, list) -> list.map { it.id } }

    @Test
    fun freeSamplesExcludedFromStageGroupsWhenSectionShown() {
        val filtered = listOf(
            situation("ABH-01", free = true),
            situation("JEB-C01", free = true),
            situation("ABH-02"),
            situation("RUN4-008", stage = 2)
        )
        val groups = stageGroupsForSituationsList(
            filtered = filtered,
            freeSampleIds = setOf("ABH-01", "JEB-C01"),
            showFreeSamplesSection = true
        )
        val ids = allIds(groups)
        assertTrue("ABH-01 must not appear in a stage group when the free-samples section shows it", "ABH-01" !in ids)
        assertTrue("JEB-C01 must not appear in a stage group when the free-samples section shows it", "JEB-C01" !in ids)
        assertEquals(listOf("ABH-02", "RUN4-008"), ids.sorted())
    }

    @Test
    fun freeSamplesIncludedInStageGroupsWhenSectionHidden() {
        val filtered = listOf(
            situation("ABH-01", free = true),
            situation("ABH-02")
        )
        val groups = stageGroupsForSituationsList(
            filtered = filtered,
            freeSampleIds = setOf("ABH-01"),
            showFreeSamplesSection = false
        )
        assertEquals(listOf("ABH-01", "ABH-02"), allIds(groups).sorted())
    }

    @Test
    fun noDuplicateIdsAcrossFreeSectionAndStageGroups() {
        val filtered = listOf(
            situation("ABH-01", free = true),
            situation("JEB-C01", free = true),
            situation("ABH-02"),
            situation("ABH-03")
        )
        val freeSampleIds = setOf("ABH-01", "JEB-C01")
        val groups = stageGroupsForSituationsList(
            filtered = filtered,
            freeSampleIds = freeSampleIds,
            showFreeSamplesSection = true
        )
        val rendered = freeSampleIds.toList() + allIds(groups)
        assertEquals("every rendered id must be unique in the one LazyColumn", rendered.size, rendered.toSet().size)
        assertEquals(filtered.map { it.id }.toSet(), rendered.toSet())
    }

    @Test
    fun emptyFreeSamplesLeavesGroupsUnchanged() {
        val filtered = listOf(situation("ABH-02"), situation("RUN4-008", stage = 2))
        val groups = stageGroupsForSituationsList(
            filtered = filtered,
            freeSampleIds = emptySet(),
            showFreeSamplesSection = true
        )
        assertEquals(listOf("ABH-02", "RUN4-008"), allIds(groups).sorted())
        assertEquals(2, groups.size)
    }

    @Test
    fun groupsStaySortedByStage() {
        val filtered = listOf(
            situation("S-3", stage = 3),
            situation("S-1", stage = 1),
            situation("S-2", stage = 2)
        )
        val groups = stageGroupsForSituationsList(
            filtered = filtered,
            freeSampleIds = emptySet(),
            showFreeSamplesSection = true
        )
        assertEquals(listOf(1, 2, 3), groups.map { it.first })
    }

    @Test
    fun duplicateIdsInLoadedListRenderOnce() {
        val filtered = listOf(
            situation("ABH-02"),
            situation("ABH-02"),
            situation("ABH-03")
        )
        val groups = stageGroupsForSituationsList(
            filtered = filtered,
            freeSampleIds = emptySet(),
            showFreeSamplesSection = true
        )
        assertEquals(listOf("ABH-02", "ABH-03"), allIds(groups).sorted())
    }

    @Test
    fun duplicateFreeSampleIdRendersOnceWhenSectionHidden() {
        val filtered = listOf(
            situation("ABH-01", free = true),
            situation("ABH-01", free = true)
        )
        val groups = stageGroupsForSituationsList(
            filtered = filtered,
            freeSampleIds = setOf("ABH-01"),
            showFreeSamplesSection = false
        )
        assertEquals(listOf("ABH-01"), allIds(groups))
    }
}
