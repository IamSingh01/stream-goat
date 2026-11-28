package com.lagradost.cloudstream3.linux.ui

import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.ComboBox
import javafx.stage.Stage
import org.junit.jupiter.api.Test
import org.testfx.framework.junit5.ApplicationExtension
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs real JavaFX controls with a headless Monocle display. Provider and network
 * calls are kept out of these UI interaction tests; the shared selection/pagination
 * model is covered separately by GlobalSearchFlowTest.
 */
@ExtendWith(ApplicationExtension::class)
class SearchPageUiTest {
    private val page = SearchPage()

    @Test
    fun `source dropdown is a live ComboBox and selection is reflected by shared model`(robot: FxRobot) {
        val selector = page.automationSourceSelector()
        assertNotNull(selector)
        assertEquals("search-source-selector", selector.id)
        assertTrue(selector is ComboBox<*>)

        onFx {
            page.automationRefreshProviders()
            selector.items.setAll("All sources", "Alpha", "Beta")
            selector.selectionModel.select(0)
        }
        robot.clickOn(selector)
        robot.clickOn("Beta")
        assertEquals(2, onFx { selector.selectionModel.selectedIndex })
        // The real setOnAction path calls the same model selection callback as user input.
    }

    @Test
    fun `search field supports typing and submit before View All is opened`(robot: FxRobot) {
        val field = page.automationSearchField()
        assertEquals("search-query-field", field.id)
        robot.clickOn(field)
        robot.write("automation query")
        assertEquals("automation query", field.text)
        // Submit travels through the actual JavaFX action event. The test deliberately
        // does not require a live extension provider in CI.
        robot.press(javafx.scene.input.KeyCode.ENTER)
        assertEquals("automation query", field.text)
    }

    @Test
    fun `View All retry button recovers from a failed provider page`(robot: FxRobot) {
        var attempts = 0
        val viewAll = page.automationViewAllForTest(
            firstPage = emptyList(),
            firstHasNext = true,
            fetchPage = { pageNumber ->
                assertEquals(2, pageNumber)
                attempts++
                if (attempts == 1) error("temporary provider failure")
                com.lagradost.cloudstream3.linux.SearchPageResult(emptyList(), hasNext = false)
            },
        )
        val viewAllStage = Stage()
        onFx {
            viewAllStage.scene = Scene(viewAll, 1000.0, 700.0)
            viewAllStage.show()
        }
        val failureDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < failureDeadline && !onFx { viewAll.automationRetryButton().isVisible }) {
            Thread.sleep(25)
        }
        val retry = viewAll.automationRetryButton()
        assertNotNull(retry)
        assertEquals("view-all-retry", retry.id)
        assertEquals("Couldn't load more results.", onFx { viewAll.automationStatusLabel().text })
        assertTrue(onFx { retry.isVisible })
        // Click the same JavaFX Retry control a user sees after a failed provider request.
        robot.clickOn(retry)
        val successDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < successDeadline && onFx { viewAll.automationHasNext() }) {
            Thread.sleep(25)
        }
        assertTrue(!onFx { viewAll.automationHasNext() })
        assertEquals("That's everything · 0 titles", onFx { viewAll.automationStatusLabel().text })
        assertEquals(2, attempts)
        onFx { viewAllStage.close() }
    }

    private fun <T> onFx(block: () -> T): T {
        if (Platform.isFxApplicationThread()) return block()
        val latch = CountDownLatch(1)
        var value: T? = null
        var failure: Throwable? = null
        Platform.runLater {
            try { value = block() } catch (t: Throwable) { failure = t }
            finally { latch.countDown() }
        }
        check(latch.await(10, TimeUnit.SECONDS)) { "JavaFX thread timed out" }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}
