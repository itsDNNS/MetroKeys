package app.metrokeys.settings.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Practical maximum tilt angles per control (spec 07 §7.2). */
object TiltAngles {
    const val LIST_ITEM = 6f
    const val CHECK_BOX = 6f
    const val LIST_PICKER = 4f
    const val BUTTON = 10f
    const val ACCENT_CELL = 12f
}

/**
 * The tilt effect of spec 07 §7.2 instead of a ripple: while [source] reports a press, the content rotates in 3D
 * toward the touch point (the touched side goes into the screen) with at most [maxDegrees] at the element's edge and
 * a push-back scale of 0.985. Tilt in 50 ms; after release the tilt is held until 300 ms after the press began and
 * returns to rest at 500 ms with D(10). Place it after the clickable so the touch area is not transformed.
 */
fun Modifier.tilt(source: InteractionSource, maxDegrees: Float, enabled: Boolean = true): Modifier =
    if (enabled) this then TiltElement(source, maxDegrees) else this

private data class TiltElement(val source: InteractionSource, val maxDegrees: Float) : ModifierNodeElement<TiltNode>() {
    override fun create() = TiltNode(source, maxDegrees)

    override fun update(node: TiltNode) {
        node.maxDegrees = maxDegrees
        if (node.source != source) {
            node.source = source
            node.restart()
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "tilt"
        properties["maxDegrees"] = maxDegrees
    }
}

private class TiltNode(var source: InteractionSource, var maxDegrees: Float) : Modifier.Node(), LayoutModifierNode {
    private val rotX = Animatable(0f)
    private val rotY = Animatable(0f)
    private val scale = Animatable(1f)
    private var width = 0
    private var height = 0
    private var collector: Job? = null
    private var animation: Job? = null

    override fun onAttach() = restart()

    override fun onDetach() {
        collector = null
        animation = null
    }

    fun restart() {
        if (!isAttached) return
        collector?.cancel()
        collector = coroutineScope.launch {
            var pressedAt = 0L
            source.interactions.collect { i ->
                when (i) {
                    is PressInteraction.Press -> {
                        pressedAt = SystemClock.uptimeMillis()
                        animation?.cancel()
                        animation = launch { tiltTo(i.pressPosition) }
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        val previous = animation
                        animation = launch {
                            previous?.join()
                            val held = SystemClock.uptimeMillis() - pressedAt
                            if (held < HOLD_MS) delay(HOLD_MS - held)
                            rest()
                        }
                    }
                }
            }
        }
    }

    private suspend fun tiltTo(p: Offset) {
        if (width <= 0 || height <= 0) return
        val nx = (p.x / width * 2f - 1f).coerceIn(-1f, 1f)
        val ny = (p.y / height * 2f - 1f).coerceIn(-1f, 1f)
        val spec = tween<Float>(TILT_IN_MS, easing = LinearEasing)
        coroutineScope.launch { rotY.animateTo(nx * maxDegrees, spec) }
        coroutineScope.launch { rotX.animateTo(-ny * maxDegrees, spec) }
        scale.animateTo(PUSH_BACK, spec)
    }

    private suspend fun rest() {
        val spec = tween<Float>(RETURN_MS, easing = MetroEasing.decelerate(10f))
        coroutineScope.launch { rotY.animateTo(0f, spec) }
        coroutineScope.launch { rotX.animateTo(0f, spec) }
        scale.animateTo(1f, spec)
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val p = measurable.measure(constraints)
        width = p.width
        height = p.height
        return layout(p.width, p.height) {
            p.placeWithLayer(0, 0) {
                rotationX = rotX.value
                rotationY = rotY.value
                scaleX = scale.value
                scaleY = scale.value
                cameraDistance = CAMERA_DISTANCE * density
            }
        }
    }

    companion object {
        const val TILT_IN_MS = 50
        const val HOLD_MS = 300L
        const val RETURN_MS = 200
        const val PUSH_BACK = 0.985f
    }
}

/**
 * Camera distance of tilt and turnstile, multiplied by the density (07 §7.2: "about 16 × density"). Compose's unit is
 * 72 px, so at 2.625 the camera is ≈ 3000 px away; this reproduces the measured ~17 px shift of a pressed list item.
 */
const val CAMERA_DISTANCE = 16f
