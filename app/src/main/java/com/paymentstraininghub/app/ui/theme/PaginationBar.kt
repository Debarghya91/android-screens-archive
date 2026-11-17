// PaginationBar.kt
// The shared numbered pagination bar for search results and
// Payment Cases: a range line ("Showing 1-20 of 616 cases") with
// the total pages, a row of numbered buttons with ellipses for
// long ranges, and Previous and Next buttons. The current page
// is highlighted; Previous is disabled on page 1 and Next on
// the last page. Two rows keep the bar compact and tappable on
// a narrow phone: the numbers never squeeze against the
// Previous and Next labels. The bar renders only its range line
// when there is a single page or nothing to show. No em dashes
// in any user-facing text.

package com.paymentstraininghub.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.data.PageWindowItem
import com.paymentstraininghub.app.data.pageWindow

/**
 * Numbered pagination for a list. [rangeText] is the caller's
 * "Showing A-B of N" line, computed with [com.paymentstraininghub.app.data.pageRange]
 * and [com.paymentstraininghub.app.data.showingRangeText]. Pass
 * [showRangeLine] = false when the caller already shows the range
 * line elsewhere, so the text appears exactly once. Tapping a
 * number, Previous or Next calls [onPageChange] with the new
 * 1-based page; the caller scrolls the list to the top.
 */
@Composable
fun PfPaginationBar(
    currentPage: Int,
    totalPages: Int,
    rangeText: String,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showRangeLine: Boolean = true
) {
    // A single page with the range line shown elsewhere needs no
    // bar at all: no numbers, no Previous or Next, nothing.
    if (totalPages <= 1 && !showRangeLine) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("pagination-bar"),
        verticalArrangement = Arrangement.spacedBy(PfSpacing.small)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showRangeLine) {
                Text(
                    rangeText,
                    style = PfTypography.body,
                    color = PfColors.secondaryText,
                    modifier = Modifier.testTag("pagination-range")
                )
            }
            if (totalPages > 0) {
                Text(
                    "Page $currentPage of $totalPages",
                    style = PfTypography.captionBold,
                    color = PfColors.secondaryText
                )
            }
        }
        if (totalPages > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                pageWindow(currentPage, totalPages).forEach { item ->
                    when (item) {
                        is PageWindowItem.Page -> {
                            val selected = item.number == currentPage
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .size(width = 36.dp, height = 36.dp)
                                    .clip(RoundedCornerShape(PfSpacing.smallRadius))
                                    .background(
                                        if (selected) PfColors.royal else PfColors.card
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (selected) PfColors.royal else PfColors.border,
                                        shape = RoundedCornerShape(PfSpacing.smallRadius)
                                    )
                                    .clickable { onPageChange(item.number) }
                                    .testTag("pagination-page-${item.number}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "${item.number}",
                                    style = PfTypography.body,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) androidx.compose.ui.graphics.Color.White else PfColors.royal
                                )
                            }
                        }
                        PageWindowItem.Ellipsis -> {
                            Box(
                                modifier = Modifier
                                    .width(20.dp)
                                    .height(36.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "...",
                                    style = PfTypography.body,
                                    color = PfColors.secondaryText
                                )
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { onPageChange(currentPage - 1) },
                    enabled = currentPage > 1,
                    modifier = Modifier.testTag("pagination-previous")
                ) {
                    Text(
                        "Previous",
                        style = PfTypography.body,
                        fontWeight = FontWeight.SemiBold,
                        color = if (currentPage > 1) PfColors.royal else PfColors.secondaryText
                    )
                }
                TextButton(
                    onClick = { onPageChange(currentPage + 1) },
                    enabled = currentPage < totalPages,
                    modifier = Modifier.testTag("pagination-next")
                ) {
                    Text(
                        "Next",
                        style = PfTypography.body,
                        fontWeight = FontWeight.SemiBold,
                        color = if (currentPage < totalPages) PfColors.royal else PfColors.secondaryText
                    )
                }
            }
        }
    }
}
