package com.paymentstraininghub.app.data

// Paging.kt
// One page size for every long list: search results and Payment
// Cases show 20 items per numbered page. Lists never render
// everything at once. Pure, so the slicing and the page-window
// arithmetic are pinned by unit tests. No em dashes in any
// user-facing text.

/** Items shown per page on search results and Payment Cases. */
const val PAGE_SIZE = 20

/** The visible slice of a list: the first [visibleCount] items, never more than the list holds. */
fun <T> visiblePage(items: List<T>, visibleCount: Int): List<T> =
    items.take(visibleCount.coerceAtLeast(0))

/** Items still hidden after [shownCount] of [totalCount] are visible. Never negative. */
fun remainingCount(totalCount: Int, shownCount: Int): Int =
    (totalCount - shownCount).coerceAtLeast(0)

/** "Showing 20 of 616 cases": the honest count line above a paged list. */
fun showingText(shownCount: Int, totalCount: Int, singular: String, plural: String): String {
    val noun = if (totalCount == 1) singular else plural
    return "Showing $shownCount of $totalCount $noun"
}

/** Pages needed for [totalCount] items at [pageSize] per page. Zero when there is nothing to show. */
fun totalPages(totalCount: Int, pageSize: Int = PAGE_SIZE): Int {
    if (totalCount <= 0 || pageSize <= 0) return 0
    return (totalCount + pageSize - 1) / pageSize
}

/**
 * The 1-based inclusive range shown on [page]: "Showing A-B of N"
 * uses this pair. The page is clamped into range; an empty list
 * reports (0, 0), never a range starting at 1.
 */
fun pageRange(totalCount: Int, page: Int, pageSize: Int = PAGE_SIZE): Pair<Int, Int> {
    if (totalCount <= 0 || pageSize <= 0) return 0 to 0
    val pages = totalPages(totalCount, pageSize)
    val safePage = page.coerceIn(1, pages)
    val start = (safePage - 1) * pageSize + 1
    val end = minOf(safePage * pageSize, totalCount)
    return start to end
}

/**
 * "Showing 1-20 of 616 cases": the range line above a numbered
 * page. A single item reads "Showing 1 of 1 case", never a
 * range to itself. An empty list reads "Showing 0 of 0 cases".
 */
fun showingRangeText(
    start: Int,
    end: Int,
    totalCount: Int,
    singular: String,
    plural: String
): String {
    val noun = if (totalCount == 1) singular else plural
    if (totalCount <= 0) return "Showing 0 of 0 $noun"
    if (start == end) return "Showing $start of $totalCount $noun"
    return "Showing $start-$end of $totalCount $noun"
}

/** The items on [page] (1-based): the page slice, never more than the list holds. Pages below 1 read as page 1. */
fun <T> pageItems(items: List<T>, page: Int, pageSize: Int = PAGE_SIZE): List<T> {
    if (items.isEmpty() || pageSize <= 0) return emptyList()
    val safePage = page.coerceAtLeast(1)
    return items.drop((safePage - 1) * pageSize).take(pageSize)
}

/** One element of the numbered page bar: a tappable page number, or an ellipsis gap. */
sealed interface PageWindowItem {
    data class Page(val number: Int) : PageWindowItem
    data object Ellipsis : PageWindowItem
}

/**
 * The numbered buttons for the page bar: the first page, the last
 * page, and the current page plus or minus two, with an ellipsis
 * wherever consecutive numbers are more than one apart. Short
 * ranges show every page with no ellipsis. The current page is
 * clamped into range; an empty range shows nothing.
 *
 * Edge cases pinned by tests: page 4 of 8 shows 1 2 3 4 5 6 ... 8
 * (no page hidden that the window promises), page 5 of 20 shows
 * 1 ... 3 4 5 6 7 ... 20, and the first and last pages never
 * carry a leading or trailing ellipsis.
 */
fun pageWindow(currentPage: Int, totalPages: Int): List<PageWindowItem> {
    if (totalPages <= 0) return emptyList()
    val current = currentPage.coerceIn(1, totalPages)
    if (totalPages <= 7) {
        return (1..totalPages).map { PageWindowItem.Page(it) }
    }
    val numbers = sortedSetOf<Int>()
    numbers.add(1)
    numbers.add(totalPages)
    for (page in (current - 2)..(current + 2)) {
        if (page in 1..totalPages) numbers.add(page)
    }
    val items = mutableListOf<PageWindowItem>()
    var previous: Int? = null
    for (number in numbers) {
        if (previous != null && number - previous > 1) {
            items.add(PageWindowItem.Ellipsis)
        }
        items.add(PageWindowItem.Page(number))
        previous = number
    }
    return items
}
