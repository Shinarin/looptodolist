package com.gametodolist.ui.home

import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalFoundationApi::class)
class HomeSwipeStateTest {
    private fun createState() = createSwipeRevealState(
        actionWidthPx = 220f,
        density = Density(1f),
        decayAnimationSpec = exponentialDecay()
    )

    private suspend fun settle(state: AnchoredDraggableState<SwipeAnchor>, velocity: Float) =
        withContext(IncrementingFrameClock()) { state.settle(velocity) }

    @Test
    fun startsClosedWithInitializedAnchors() {
        val state = createState()

        assertEquals(SwipeAnchor.Closed, state.currentValue)
        assertEquals(0f, state.requireOffset(), 0f)
        assertEquals(-220f, state.anchors.positionOf(SwipeAnchor.Revealed), 0f)
    }

    @Test
    fun keepsSnapAnimationAt200Millis() {
        val spec = createState().snapAnimationSpec as TweenSpec<*>

        assertEquals(200, spec.durationMillis)
    }

    @Test
    fun dragPastOldHalfThresholdButBelow65PercentSpringsBack() = runTest {
        val state = createState()
        state.dispatchRawDelta(-121f)

        settle(state, 0f)

        assertEquals(SwipeAnchor.Closed, state.currentValue)
    }

    @Test
    fun shortFlingBelow700DpPerSecondSpringsBack() = runTest {
        val state = createState()
        state.dispatchRawDelta(-20f)

        settle(state, -600f)

        assertEquals(SwipeAnchor.Closed, state.currentValue)
    }

    @Test
    fun deliberateFastFlingStillRevealsActions() = runTest {
        val state = createState()
        state.dispatchRawDelta(-20f)

        settle(state, -800f)

        assertEquals(SwipeAnchor.Revealed, state.currentValue)
    }

    private class IncrementingFrameClock : MonotonicFrameClock {
        private var frameTimeNanos = 0L

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            frameTimeNanos += 16_000_000L
            return onFrame(frameTimeNanos)
        }
    }
}
