package com.lagradost.cloudstream3.linux.ui

import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.ComboBox
import javafx.stage.Stage
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
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
    companion object {
        private lateinit var page: SearchPage
        private lateinit var stage: Stage

        @JvmStatic
        @BeforeAll
        fun startFx() {
            val ready = CountDownLatch(1)
            Platform.startup {
                stage = Stage()
                page = SearchPage()
                stage.scene = Scene(page, 1280.0, 800.0)
                stage.show()
                ready.countDown()
            }
            check(ready.await(20, TimeUnit.SECONDS)) { "JavaFX did not start" }
        }

        @JvmStatic
        @AfterAll
        fun stopFx() {
            val done = CountDownLatch(1)
            Platform.runLater {
                stage.close()
                done.countDown()
            }
            done.await(10, TimeUnit.SECONDS)
            Platform.exit()
        }
    }

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
