package app.metrokeys.settings.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.navigation3.ui.LocalNavAnimatedContentScope

/** Durations and curves of the turnstile (07 §7.3); backward uses the same durations (07 §13 point 2). */
object TurnstileSpec {
    const val OUT_MS = 128
    const val IN_MS = 300
    const val FORWARD_OUT = 50f
    const val FORWARD_IN = -80f
    const val BACKWARD_OUT = -80f
    const val BACKWARD_IN = 50f
    val outEasing = MetroEasing.accelerate(150f)
    val inEasing = MetroEasing.decelerate(150f)

    /** The page display adds no animation of its own; each page animates itself with [Turnstile]. */
    val none: ContentTransform get() = EnterTransition.None togetherWith ExitTransition.None
}

/** Whether the last change of the back stack was a pop; set by the activity before it changes the stack. */
class NavDirection {
    var pop = false
}

/**
 * The turnstile page transition (07 §7.3): the page rotates about a vertical hinge at the left edge with
 * perspective. Forward: outgoing 0° → +50° in 128 ms A(150), then incoming −80° → 0° in 300 ms D(150); backward:
 * outgoing 0° → −80°, incoming +50° → 0°. Neither page is visible between the two phases. A predictive back
 * gesture seeks the same transition progressively (Navigation 3 seeks the scene transition).
 */
@Composable
fun Turnstile(key: String, backStack: List<String>, direction: NavDirection, content: @Composable () -> Unit) {
    val transition = LocalNavAnimatedContentScope.current.transition
    val seg = transition.segment
    // decided once per transition so a predictive back that commits mid-way keeps its direction
    val backward = remember(seg.initialState, seg.targetState) {
        val top = backStack.lastOrNull() == key
        when {
            seg.targetState == EnterExitState.Visible && seg.initialState == EnterExitState.PreEnter -> !top || direction.pop
            seg.targetState == EnterExitState.PostExit -> key !in backStack || top
            else -> false
        }
    }
    val angle by transition.animateFloat(
        transitionSpec = {
            when {
                initialState == EnterExitState.PreEnter && targetState == EnterExitState.Visible ->
                    tween(TurnstileSpec.IN_MS, TurnstileSpec.OUT_MS, TurnstileSpec.inEasing)
                targetState == EnterExitState.PostExit -> tween(TurnstileSpec.OUT_MS, 0, TurnstileSpec.outEasing)
                else -> snap()
            }
        },
        label = "turnstile",
    ) { s ->
        when (s) {
            EnterExitState.PreEnter -> if (backward) TurnstileSpec.BACKWARD_IN else TurnstileSpec.FORWARD_IN
            EnterExitState.Visible -> 0f
            EnterExitState.PostExit -> if (backward) TurnstileSpec.BACKWARD_OUT else TurnstileSpec.FORWARD_OUT
        }
    }
    val alpha by transition.animateFloat(
        transitionSpec = {
            if (initialState != targetState && (targetState == EnterExitState.Visible || targetState == EnterExitState.PostExit)) {
                snap(TurnstileSpec.OUT_MS)
            } else {
                snap()
            }
        },
        label = "turnstileVisible",
    ) { s -> if (s == EnterExitState.Visible) 1f else 0f }
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            rotationY = angle
            this.alpha = alpha
            transformOrigin = TransformOrigin(0f, 0.5f)
            cameraDistance = CAMERA_DISTANCE * density
        },
    ) { content() }
}
