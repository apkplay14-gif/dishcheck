package ua.starlink.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.starlink.reader.data.ReviewPromptState

private const val MIN_SCANS = 2
private const val COOLDOWN_MS = 1_000L
private const val MAX_PROMPTS = 3

class ReviewPromptLogicTest {

    @Test
    fun `does not ask after the very first successful scan`() {
        val decision = decideReviewPrompt(ReviewPromptState(), now = 0L, MIN_SCANS, COOLDOWN_MS, MAX_PROMPTS)
        assertFalse(decision.shouldRequestReview)
        assertEquals(1, decision.nextState.completedScans)
        assertEquals(0L, decision.nextState.lastPromptAtMillis)
        assertEquals(0, decision.nextState.promptCount)
    }

    @Test
    fun `asks on the second successful scan`() {
        val afterFirst = ReviewPromptState(completedScans = 1)
        val decision = decideReviewPrompt(afterFirst, now = 500L, MIN_SCANS, COOLDOWN_MS, MAX_PROMPTS)
        assertTrue(decision.shouldRequestReview)
        assertEquals(2, decision.nextState.completedScans)
        assertEquals(500L, decision.nextState.lastPromptAtMillis)
        assertEquals(1, decision.nextState.promptCount)
    }

    @Test
    fun `does not ask again immediately within the cooldown window`() {
        val justAsked = ReviewPromptState(completedScans = 2, lastPromptAtMillis = 500L, promptCount = 1)
        val decision = decideReviewPrompt(justAsked, now = 600L, MIN_SCANS, COOLDOWN_MS, MAX_PROMPTS)
        assertFalse(decision.shouldRequestReview)
        // Лічильник сканувань все одно росте — це не блокується кулдауном.
        assertEquals(3, decision.nextState.completedScans)
        assertEquals(500L, decision.nextState.lastPromptAtMillis)
        assertEquals(1, decision.nextState.promptCount)
    }

    @Test
    fun `asks again once the cooldown window has fully passed`() {
        val justAsked = ReviewPromptState(completedScans = 2, lastPromptAtMillis = 500L, promptCount = 1)
        val decision = decideReviewPrompt(
            justAsked,
            now = 500L + COOLDOWN_MS,
            MIN_SCANS,
            COOLDOWN_MS,
            MAX_PROMPTS,
        )
        assertTrue(decision.shouldRequestReview)
        assertEquals(2, decision.nextState.promptCount)
    }

    @Test
    fun `never asks again once the lifetime prompt limit is reached`() {
        val exhausted = ReviewPromptState(
            completedScans = 50,
            lastPromptAtMillis = 0L,
            promptCount = MAX_PROMPTS,
        )
        val decision = decideReviewPrompt(exhausted, now = Long.MAX_VALUE / 2, MIN_SCANS, COOLDOWN_MS, MAX_PROMPTS)
        assertFalse(decision.shouldRequestReview)
        assertEquals(MAX_PROMPTS, decision.nextState.promptCount)
    }
}

class MergeNotesTest {

    @Test
    fun `blank fresh note keeps the existing one`() {
        assertEquals("old note", mergeNotes("old note", "  "))
    }

    @Test
    fun `blank existing note is replaced by the fresh one`() {
        assertEquals("new note", mergeNotes("  ", "new note"))
    }

    @Test
    fun `identical notes are not duplicated`() {
        assertEquals("same", mergeNotes("same", "  same  "))
    }

    @Test
    fun `different notes are concatenated with a blank line between them`() {
        assertEquals("first\n\nsecond", mergeNotes("first", "second"))
    }
}
