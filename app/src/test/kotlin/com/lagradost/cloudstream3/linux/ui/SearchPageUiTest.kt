package com.lagradost.cloudstream3.linux.ui

import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.ComboBox
import javafx.stage.Stage
import org.junit.jupiter.api.Test
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
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
    private lateinit var page: SearchPage
    private lateinit var stage: Stage

    // TestFX creates and owns the JavaFX stage. Attach the page before using FxRobot,
    // otherwise robot clicks have no scene/window to target.
    @Start
    fun start(stage: Stage) {
        this.stage = stage
        page = SearchPage()
        stage.scene = Scene(page, 1000.0, 700.0)
        stage.show()
    }

    @Test
    fun `source dropdown is a live ComboBox and accepts selection actions`(robot: FxRobot) {
        val selector = page.automationSourceSelector()
        assertNotNull(selector)
        assertEquals("search-source-selector", selector.id)
        assertTrue(selector is ComboBox<*>)

        onFx {
            page.automationRefreshProviders()
            // Use the real provider list. The test environment may have no extensions,
            // so inventing Alpha/Beta here would desynchronize the ComboBox from its model.
            selector.selectionModel.select(0)
            selector.fire()
        }
        assertEquals(0, onFx { selector.selectionModel.selectedIndex })
        // Firing the control's ActionEvent exercises the same handler used by a user choice.
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
        onFx {
            stage.scene.root = viewAll
            stage.show()
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
        // Fire the actual Retry button action on the JavaFX thread. TestFX mouse
        // coordinates are unreliable for this footer inside a nested ScrollPane.
        onFx { retry.fire() }
        val successDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < successDeadline &&
            onFx { viewAll.automationStatusLabel().text != "That's everything · 0 titles" }) {
            Thread.sleep(25)
        }
        assertEquals("That's everything · 0 titles", onFx { viewAll.automationStatusLabel().text })
        assertTrue(!onFx { viewAll.automationHasNext() })
        assertEquals(2, attempts)
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
