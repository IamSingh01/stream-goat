package com.lagradost.cloudstream3.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchPaginationStateTest {
    @Test
    fun `only providers advertising another page can be paginated`() {
        assertTrue(SearchPaginationState(firstHasNext = true).canLoadNext)
        assertFalse(SearchPaginationState(firstHasNext = false).canLoadNext)
    }

    @Test
    fun `loading cannot be started twice and next flag controls continuation`() {
        val first = SearchPaginationState(firstHasNext = true)
        val loading = first.beginLoading()
        assertFalse(loading.canLoadNext)
        assertEquals(loading, loading.beginLoading())

        val second = loading.pageLoaded(hasNext = true)
        assertEquals(2, second.pagesFetched)
        assertTrue(second.canLoadNext)

        val last = second.beginLoading().pageLoaded(hasNext = false)
        assertFalse(last.canLoadNext)
        assertEquals(3, last.pagesFetched)
    }

    @Test
    fun `failed page can be retried without advancing page count`() {
        val initial = SearchPaginationState(firstHasNext = true)
        val failed = initial.beginLoading().pageFailed()
        assertTrue(failed.canLoadNext)
        assertEquals(1, failed.pagesFetched)
    }

    @Test
    fun `pagination stops at the safety limit`() {
        val capped = SearchPaginationState(firstHasNext = true, pagesFetched = 100)
        assertFalse(capped.canLoadNext)
    }
}
