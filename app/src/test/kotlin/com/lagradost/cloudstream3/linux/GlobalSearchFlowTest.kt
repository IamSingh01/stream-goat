package com.lagradost.cloudstream3.linux

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobalSearchFlowTest {
    private data class Provider(val name: String)

    @Test
    fun `dropdown selection targets only selected provider and refresh preserves selection`() {
        val selection = GlobalSearchSourceSelection(listOf(Provider("Alpha"), Provider("Beta"))) { it.name }

        assertEquals(listOf("All sources", "Alpha", "Beta"), selection.options)
        assertEquals(listOf("Alpha", "Beta"), selection.targets().map { it.name })

        selection.selectIndex(2)
        assertEquals(listOf("Beta"), selection.targets().map { it.name })

        selection.refresh(listOf(Provider("Beta"), Provider("Gamma")))
        assertEquals("Beta", selection.selectedName)
        assertEquals(listOf("Beta"), selection.targets().map { it.name })

        selection.refresh(listOf(Provider("Gamma")))
        assertEquals(null, selection.selectedName)
        assertEquals(listOf("Gamma"), selection.targets().map { it.name })
    }

    @Test
    fun `View All appends later pages removes duplicate URLs and stops at last page`() = runBlocking {
        val calls = mutableListOf<Int>()
        val session = ViewAllSearchSession(
            firstPage = listOf("a", "b"),
            firstHasNext = true,
            keyOf = { it },
        ) { page ->
            calls += page
            when (page) {
                2 -> SearchPageResult(listOf("b", "c"), hasNext = true)
                3 -> SearchPageResult(listOf("d"), hasNext = false)
                else -> error("Unexpected page $page")
            }
        }

        assertTrue(session.loadNext())
        assertEquals(listOf("a", "b", "c"), session.items)
        assertTrue(session.hasNext)

        assertTrue(session.loadNext())
        assertEquals(listOf("a", "b", "c", "d"), session.items)
        assertFalse(session.hasNext)

        assertFalse(session.loadNext())
        assertEquals(listOf(2, 3), calls)
    }

    @Test
    fun `failed View All page can be retried without skipping a page`() = runBlocking {
        var attempts = 0
        val session = ViewAllSearchSession(
            firstPage = listOf("first"),
            firstHasNext = true,
            keyOf = { it },
        ) { page ->
            assertEquals(2, page)
            attempts++
            if (attempts == 1) error("temporary failure")
            SearchPageResult(listOf("second"), hasNext = false)
        }

        assertFalse(session.loadNext())
        assertEquals(listOf("first"), session.items)
        assertTrue(session.hasNext)
        assertTrue(session.loadNext())
        assertEquals(listOf("first", "second"), session.items)
        assertEquals(2, attempts)
    }
    @Test
    fun `1000 independent search sessions complete without cross talk`() = runBlocking {
        val flows = (0 until 1_000).map { user ->
            async {
                val providerName = "provider-$user"
                val selection = GlobalSearchSourceSelection(
                    listOf(Provider(providerName), Provider("other-$user"))
                ) { it.name }
                selection.selectIndex(1)

                val session = ViewAllSearchSession(
                    firstPage = listOf("$user-1"),
                    firstHasNext = true,
                    keyOf = { it },
                ) { page ->
                    delay(1)
                    SearchPageResult(listOf("$user-$page"), hasNext = false)
                }

                Triple(selection.targets().single().name, session.loadNext(), session.items)
            }
        }

        val results = flows.awaitAll()
        assertEquals(1_000, results.size)
        results.forEachIndexed { user, (provider, loaded, items) ->
            assertEquals("provider-$user", provider)
            assertTrue(loaded)
            assertEquals(listOf("$user-1", "$user-2"), items)
        }
    }

}
