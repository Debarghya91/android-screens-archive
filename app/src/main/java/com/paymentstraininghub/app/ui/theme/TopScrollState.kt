// Scroll-to-top rule (owner order, 15 Nov 2026): every scrollable
// screen opens at the very top on every entry, and Back lands at
// the top too. No scroll restoration anywhere.
//
// rememberScrollState() and rememberLazyListState() are backed by
// rememberSaveable, so Navigation Compose restores the old offset
// when a destination is popped back to or its tab state is
// restored, and a scroll state remembered in a composable that
// switches content in place (Studio detail views, ISO explorer
// tabs, quiz questions) carries the old offset into the new
// content. These helpers use plain remember instead: the state is
// dropped when the container leaves composition, so every entry
// starts at 0. The optional keys reset the state whenever the
// shown content changes in place; pass the content identity
// (lesson id, selected tab, question index) for containers whose
// content switches without leaving composition.
// No em dashes.
package com.paymentstraininghub.app.ui.theme

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

@Composable
fun rememberTopScrollState(vararg keys: Any?): ScrollState {
    val state = remember(*keys) { ScrollState(0) }
    LaunchedEffect(*keys) { state.scrollTo(0) }
    return state
}

@Composable
fun rememberTopLazyListState(vararg keys: Any?): LazyListState {
    val state = remember(*keys) { LazyListState(0, 0) }
    LaunchedEffect(*keys) { state.scrollToItem(0) }
    return state
}
