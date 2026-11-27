package com.lagradost.cloudstream3.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchPaginationTest {
    @Test
    fun `requests only pages advertised by provider`() {
        val pagination = SearchPagination(firstHasNext = true)

        assertEquals(2, pagination.nextPage())
        pagination.pageLoaded(hasNext = true)
        assertEquals(3, pagination.nextPage())
        pagination.pageLoaded(hasNext = false)

        assertNull(pagination.nextPage())
        assertTrue(pagination.finished)
    }

    @Test
    fun `does not request another page when first response has no next page`() {
        val pagination = SearchPagination(firstHasNext = false)

        assertNull(pagination.nextPage())
        assertTrue(pagination.finished)
    }

    @Test
    fun `failed page can be retried without skipping it`() {
        val pagination = SearchPagination(firstHasNext = true)

        assertEquals(2, pagination.nextPage())
        pagination.pageFailed()
        assertEquals(2, pagination.nextPage())
        pagination.pageLoaded(hasNext = false)

        assertNull(pagination.nextPage())
        assertTrue(pagination.finished)
    }

    @Test
    fun `does not issue duplicate concurrent page requests`() {
        val pagination = SearchPagination(firstHasNext = true)

        assertEquals(2, pagination.nextPage())
        assertNull(pagination.nextPage())
        pagination.pageLoaded(hasNext = true)
        assertEquals(3, pagination.nextPage())
        assertFalse(pagination.finished)
    }
}
