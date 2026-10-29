package com.gametodolist.ui.home

import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.ExperimentalFoundationApi
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalFoundationApi::class)
class HomeSwipeStateTest {
    private fun createState() = createSwipeRevealState(
        actionWidthPx = 220f,
        velocityThresholdPx = { 500f },
        decayAnimationSpec = exponentialDecay()
    )

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
}
