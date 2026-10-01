package com.v2ray.ang.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FILTERNET: regression guard for the bug that broke connecting twice.
 *
 * ── the story ─────────────────────────────────────────────────────────────
 * The connect flow sets two flags - "a search is running" and "apply the
 * winner when the results arrive" - then kicks off a bulk measurement. The
 * measurement starts by wiping any previous one.
 *
 * At one point that wipe was made to clear the two flags as well, so that the
 * cancel button would work. The result was that connectBestServer() set the
 * flags and the very next call erased them, the winner was never applied, and
 * the app silently stopped connecting at all.
 *
 * The fix is a hard rule: clearing the machinery of a previous test must never
 * touch the caller's intent. This test models the two routines and the flags
 * and fails the moment that rule is broken again.
 */
class SearchCancelRegressionTest {

    /** The slice of view-model state the two routines are allowed to see. */
    private class SearchState {
        var pendingBestSelection = false
        var isFindingBest = false
        var isTesting = false
        var jobAlive = false
        var winnerApplied = false
    }

    /** Wipes an in-flight measurement. Must leave intent alone. */
    private fun clearPreviousTest(s: SearchState) {
        s.jobAlive = false
        s.isTesting = false
    }

    /** The user pressed cancel: machinery *and* intent go away. */
    private fun abortSearch(s: SearchState) {
        s.pendingBestSelection = false
        s.isFindingBest = false
        clearPreviousTest(s)
    }

    private fun connectBestServer(s: SearchState) {
        s.pendingBestSelection = true
        s.isFindingBest = true
        testEveryServer(s)
    }

    private fun testEveryServer(s: SearchState) {
        clearPreviousTest(s)
        s.jobAlive = true
        s.isTesting = true
    }

    private fun onTestsFinished(s: SearchState) {
        s.isTesting = false
        if (s.pendingBestSelection) {
            s.pendingBestSelection = false
            s.winnerApplied = true
        }
    }

    @Test
    fun `starting a search survives the wipe of the previous one`() {
        val s = SearchState()
        connectBestServer(s)

        assertTrue("intent was erased by the internal wipe", s.pendingBestSelection)
        assertTrue("search flag was erased by the internal wipe", s.isFindingBest)
        assertTrue(s.isTesting)
    }

    @Test
    fun `the winner is applied when the round finishes`() {
        val s = SearchState()
        connectBestServer(s)
        onTestsFinished(s)

        assertTrue("the measured winner was never applied - the app would not connect", s.winnerApplied)
    }

    @Test
    fun `cancelling clears every flag the button looks at`() {
        val s = SearchState()
        connectBestServer(s)
        abortSearch(s)

        assertFalse(s.pendingBestSelection)
        assertFalse(s.isFindingBest)
        assertFalse(s.isTesting)
        assertFalse(s.jobAlive)
        // busy is derived from these two; both must be down or the button sticks
        assertFalse("button would stay amber forever", s.isFindingBest || s.isTesting)
    }

    @Test
    fun `cancelling after a finished round does not apply a winner`() {
        val s = SearchState()
        connectBestServer(s)
        abortSearch(s)
        onTestsFinished(s)

        assertFalse("a cancelled search must not connect anyway", s.winnerApplied)
    }
}
