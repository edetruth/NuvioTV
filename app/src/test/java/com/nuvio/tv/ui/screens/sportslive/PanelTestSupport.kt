package com.nuvio.tv.ui.screens.sportslive

import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Panel tests (l28 hangs): an open player panel ticks for ever on the test scheduler, so a test that fails (or forgets to
 * close it) never ends: runTest's final advanceUntilIdle keeps running the panel's lineup. Open panels through [open] and
 * run the test with [runPanelTest]: every panel opened is closed when the body ends, whatever happens, so a failure fails
 * fast instead of hanging the suite.
 */
internal object PanelTests {
    private val opened = ThreadLocal.withInitial { mutableListOf<SportsLiveZapViewModel>() }

    /** Opens [viewModel]'s panel and has [runPanelTest] close it at the end. */
    fun open(viewModel: SportsLiveZapViewModel) {
        opened.get() += viewModel
        viewModel.openPanel()
    }

    fun forget() = opened.get().clear()

    fun closeAll() {
        val panels = opened.get().toList()
        opened.get().clear()
        panels.forEach { it.closePanel() }
    }
}

/** [runTest] on [dispatcher] that closes every panel opened through [PanelTests.open] when [body] ends, even by failure. */
internal fun runPanelTest(dispatcher: TestDispatcher, body: suspend TestScope.() -> Unit): TestResult {
    PanelTests.forget()
    return runTest(dispatcher) {
        try {
            body()
        } finally {
            PanelTests.closeAll()
        }
    }
}
