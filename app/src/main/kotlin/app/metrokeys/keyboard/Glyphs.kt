package app.metrokeys.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Our own vector drawings: the key glyphs of spec 02 §6 (coordinates in em relative to the face centre, x right,
 * y down; mitred joins, flat caps) and the line pictograms of the application bar and the strip buttons.
 */
class Glyphs {
    private companion object {
        /** Half-size of the app-bar icon box in ref px (0.45 × the 41 px button radius). */
        const val ICON_HALF = 0.45f * 41f
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.MITER
        strokeCap = Paint.Cap.BUTT
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val rect = RectF()

    /** Draws key glyph [name] (`shift`, `backspace`, `enter`, `emoji`, `go`, `arrow-right`, `arrow-left`, `space`). */
    fun drawKeyGlyph(c: Canvas, name: String, cx: Float, cy: Float, em: Float, color: Int) {
        stroke.color = color
        fill.color = color
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeJoin = Paint.Join.MITER
        fun x(v: Float) = cx + v * em
        fun y(v: Float) = cy + v * em
        when (name) {
            "shift" -> {
                stroke.strokeWidth = 0.135f * em
                path.reset()
                path.moveTo(x(0f), y(0.34f)); path.lineTo(x(0f), y(-0.49f))
                c.drawPath(path, stroke)
                path.reset()
                path.moveTo(x(-0.29f), y(-0.21f)); path.lineTo(x(0f), y(-0.50f)); path.lineTo(x(0.29f), y(-0.21f))
                c.drawPath(path, stroke)
            }
            "backspace" -> {
                stroke.strokeWidth = 0.068f * em
                path.reset()
                path.moveTo(x(-0.49f), y(0f)); path.lineTo(x(-0.25f), y(-0.35f)); path.lineTo(x(0.49f), y(-0.35f))
                path.lineTo(x(0.49f), y(0.35f)); path.lineTo(x(-0.25f), y(0.35f)); path.close()
                c.drawPath(path, stroke)
                stroke.strokeWidth = 0.085f * em
                c.drawLine(x(-0.03f), y(-0.14f), x(0.25f), y(0.14f), stroke)
                c.drawLine(x(-0.03f), y(0.14f), x(0.25f), y(-0.14f), stroke)
            }
            "enter" -> {
                stroke.strokeWidth = 0.115f * em
                path.reset()
                path.moveTo(x(0.38f), y(-0.31f)); path.lineTo(x(0.38f), y(0.09f)); path.lineTo(x(-0.36f), y(0.09f))
                c.drawPath(path, stroke)
                path.reset()
                path.moveTo(x(-0.13f), y(-0.16f)); path.lineTo(x(-0.39f), y(0.09f)); path.lineTo(x(-0.13f), y(0.34f))
                c.drawPath(path, stroke)
            }
            "emoji" -> {
                stroke.strokeWidth = 0.075f * em
                c.drawCircle(x(0f), y(-0.05f), 0.39f * em, stroke)
                c.drawCircle(x(-0.135f), y(-0.16f), 0.055f * em, fill)
                c.drawCircle(x(0.135f), y(-0.16f), 0.055f * em, fill)
                stroke.strokeWidth = 0.07f * em
                rect.set(x(-0.22f), y(-0.25f), x(0.22f), y(0.17f))
                c.drawArc(rect, 25f, 130f, false, stroke)
            }
            "go", "arrow-right" -> arrow(c, cx, cy, em, 1f)
            "arrow-left" -> arrow(c, cx, cy, em, -1f)
            "space" -> {
                stroke.strokeWidth = 0.07f * em
                path.reset()
                path.moveTo(x(-0.40f), y(-0.12f)); path.lineTo(x(-0.40f), y(0.12f)); path.lineTo(x(0.40f), y(0.12f))
                path.lineTo(x(0.40f), y(-0.12f))
                c.drawPath(path, stroke)
            }
            "keyboard" -> keyboardPictogram(c, cx, cy, em, color)
        }
    }

    private fun arrow(c: Canvas, cx: Float, cy: Float, em: Float, dir: Float) {
        stroke.strokeWidth = 0.13f * em
        c.drawLine(cx - 0.50f * em * dir, cy, cx + 0.44f * em * dir, cy, stroke)
        path.reset()
        path.moveTo(cx + 0.06f * em * dir, cy - 0.37f * em)
        path.lineTo(cx + 0.45f * em * dir, cy)
        path.lineTo(cx + 0.06f * em * dir, cy + 0.37f * em)
        c.drawPath(path, stroke)
    }

    /** Language sub-icon `keyboard` (spec 02 §6.9, own design). */
    fun keyboardPictogram(c: Canvas, cx: Float, cy: Float, em: Float, color: Int) {
        stroke.color = color
        fill.color = color
        stroke.strokeWidth = 0.06f * em
        stroke.strokeJoin = Paint.Join.ROUND
        rect.set(cx - 0.40f * em, cy - 0.25f * em, cx + 0.40f * em, cy + 0.25f * em)
        c.drawRoundRect(rect, 0.06f * em, 0.06f * em, stroke)
        stroke.strokeJoin = Paint.Join.MITER
        val h = 0.065f * em / 2
        for (yy in floatArrayOf(-0.11f, 0.01f)) for (xx in floatArrayOf(-0.24f, -0.08f, 0.08f, 0.24f)) {
            c.drawRect(cx + xx * em - h, cy + yy * em - h, cx + xx * em + h, cy + yy * em + h, fill)
        }
        c.drawRect(cx - 0.18f * em, cy + 0.12f * em - h, cx + 0.18f * em, cy + 0.12f * em + h, fill)
    }

    /**
     * Paste pictogram (spec 02 §6.10, ref px relative to the button centre, × [s]): a filled board, a sheet with a
     * 5 px gap in the background colour [bg].
     */
    fun pastePictogram(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, bg: Int) {
        fill.color = color
        c.drawRect(cx - 26 * s, cy - 24 * s, cx + 8 * s, cy + 26 * s, fill)
        fill.color = bg
        c.drawRect(cx - 4 * s, cy - 6 * s, cx + 28 * s, cy + 30 * s, fill)
        fill.color = color
        c.drawRect(cx + 1 * s, cy - 1 * s, cx + 23 * s, cy + 25 * s, fill)
    }

    /** Clipboard-history pictogram (own design, same style as the paste pictogram): a board with a clip and lines. */
    fun clipboardPictogram(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, bg: Int) {
        fill.color = color
        c.drawRect(cx - 20 * s, cy - 22 * s, cx + 20 * s, cy + 28 * s, fill)
        fill.color = bg
        c.drawRect(cx - 14 * s, cy - 14 * s, cx + 14 * s, cy + 22 * s, fill)
        fill.color = color
        c.drawRect(cx - 10 * s, cy - 28 * s, cx + 10 * s, cy - 16 * s, fill)
        for (i in 0..2) c.drawRect(cx - 9 * s, cy - 6 * s + i * 9 * s, cx + 9 * s, cy - 2 * s + i * 9 * s, fill)
    }

    /**
     * Outline clipboard of the recently-copied chip (02 §5.8.9, own design), ref px × [s]: a board outline 30 × 40
     * with stroke 5 and square corners, and a clip tab 12 wide centred on the top edge protruding 4 above it; ink
     * 30 × 43 centred on ([cx], [cy]).
     */
    fun clipboardOutline(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        stroke.color = color
        fill.color = color
        stroke.strokeWidth = 5 * s
        stroke.strokeJoin = Paint.Join.MITER
        val top = cy - 21.5f * s + 3 * s // board top edge (the tab protrudes 3…4 px above it)
        rect.set(cx - 12.5f * s, top + 2.5f * s, cx + 12.5f * s, top + 37.5f * s)
        c.drawRect(rect, stroke)
        c.drawRect(cx - 6 * s, cy - 21.5f * s, cx + 6 * s, top + 5 * s, fill)
    }

    /** Delete cross of the clipboard rows: two 5 px strokes, ±18 px (spec 02 §5.12). */
    fun cross(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = 5 * s
        stroke.strokeCap = Paint.Cap.BUTT
        c.drawLine(cx - 18 * s, cy - 18 * s, cx + 18 * s, cy + 18 * s, stroke)
        c.drawLine(cx - 18 * s, cy + 18 * s, cx + 18 * s, cy - 18 * s, stroke)
    }

    /** Microphone in a filled circle for the dictation strip. */
    fun microphone(c: Canvas, cx: Float, cy: Float, h: Float, width: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = width
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        rect.set(cx - 0.35f * h, cy - 0.95f * h, cx + 0.35f * h, cy + 0.25f * h)
        c.drawRoundRect(rect, 0.35f * h, 0.35f * h, stroke)
        rect.set(cx - 0.7f * h, cy - 0.65f * h, cx + 0.7f * h, cy + 0.55f * h)
        c.drawArc(rect, 0f, 180f, false, stroke)
        c.drawLine(cx, cy + 0.55f * h, cx, cy + 0.95f * h, stroke)
        c.drawLine(cx - 0.4f * h, cy + 0.95f * h, cx + 0.4f * h, cy + 0.95f * h, stroke)
    }

    /**
     * Smiley of the application bar (02 §5.11, own design), ref px × [u] around the centre: ink 36 × 35, circle
     * outline and smile with stroke 3.5, two filled eye dots.
     */
    private fun smileyPictogram(c: Canvas, cx: Float, cy: Float, u: Float, color: Int) {
        stroke.color = color
        fill.color = color
        stroke.strokeWidth = 3.5f * u
        stroke.strokeCap = Paint.Cap.ROUND
        rect.set(cx - 16.25f * u, cy - 15.75f * u, cx + 16.25f * u, cy + 15.75f * u)
        c.drawOval(rect, stroke)
        c.drawCircle(cx - 6f * u, cy - 5f * u, 2.6f * u, fill)
        c.drawCircle(cx + 6f * u, cy - 5f * u, 2.6f * u, fill)
        rect.set(cx - 9f * u, cy - 7f * u, cx + 9f * u, cy + 9.5f * u)
        c.drawArc(rect, 25f, 130f, false, stroke)
    }

    /**
     * Speech bubble of the send / field action button (02 §5.11, own design), ref px × [u]: ink 40 × 28; an outline
     * rectangle 29 × 23 with stroke 5 and square corners, a solid tail under its right end (vertical side right,
     * point at the bottom) and three motion bars on the left running into the rectangle's left edge.
     */
    private fun sendPictogram(c: Canvas, cx: Float, cy: Float, u: Float, color: Int) {
        stroke.color = color
        fill.color = color
        stroke.strokeWidth = 5f * u
        stroke.strokeJoin = Paint.Join.MITER
        // outer edges x −9…20, y −14…9
        rect.set(cx - 6.5f * u, cy - 11.5f * u, cx + 17.5f * u, cy + 6.5f * u)
        c.drawRect(rect, stroke)
        path.reset()
        path.moveTo(cx + 9.5f * u, cy + 8f * u)
        path.lineTo(cx + 15f * u, cy + 8f * u)
        path.lineTo(cx + 15f * u, cy + 14f * u)
        path.close()
        c.drawPath(path, fill)
        for (i in 0..2) {
            val top = cy - 10.75f * u + i * 6f * u
            c.drawRect(cx - 20f * u, top, cx - 6.5f * u, top + 4.5f * u, fill)
        }
        stroke.strokeJoin = Paint.Join.ROUND
    }

    /**
     * Microphone of the voice input button (02 §5.11, own design), ref px × [u]: ink 28 × 39; a capsule outline
     * 16 × 27, a U-shaped arc around its lower part (28 wide) and a stem below; no foot. Stroke 5.
     */
    private fun microphonePictogram(c: Canvas, cx: Float, cy: Float, u: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = 5f * u
        stroke.strokeCap = Paint.Cap.ROUND
        // capsule outer x −8…8, y −19.5…7.5
        rect.set(cx - 5.5f * u, cy - 17f * u, cx + 5.5f * u, cy + 5f * u)
        c.drawRoundRect(rect, 5.5f * u, 5.5f * u, stroke)
        // arc centre line radius 11.5 around (0, −0.5): outer bottom at 13.5
        rect.set(cx - 11.5f * u, cy - 12f * u, cx + 11.5f * u, cy + 11f * u)
        c.drawArc(rect, 0f, 180f, false, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
        c.drawLine(cx, cy + 13.5f * u, cx, cy + 19.5f * u, stroke)
    }

    /**
     * Line pictograms of the application bar (own designs): [h] = half-size of the icon box, [width] = stroke width,
     * round caps (spec 02 §5.11).
     */
    fun appBarIcon(c: Canvas, name: String, cx: Float, cy: Float, h: Float, width: Float, color: Int) {
        stroke.color = color
        fill.color = color
        stroke.strokeWidth = width
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        fun x(v: Float) = cx + v * h
        fun y(v: Float) = cy + v * h
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = c.drawLine(x(x1), y(y1), x(x2), y(y2), stroke)
        fun poly(vararg p: Float, close: Boolean = false) {
            path.reset()
            path.moveTo(x(p[0]), y(p[1]))
            var i = 2
            while (i < p.size) {
                path.lineTo(x(p[i]), y(p[i + 1])); i += 2
            }
            if (close) path.close()
            c.drawPath(path, stroke)
        }
        when (name) {
            "send" -> sendPictogram(c, cx, cy, h / ICON_HALF, color)
            "search" -> {
                c.drawCircle(x(-0.2f), y(-0.2f), 0.6f * h, stroke)
                line(0.25f, 0.25f, 0.9f, 0.9f)
            }
            "go", "next" -> {
                line(-0.9f, 0f, 0.9f, 0f)
                poly(0.3f, -0.6f, 0.9f, 0f, 0.3f, 0.6f)
            }
            "previous" -> {
                line(-0.9f, 0f, 0.9f, 0f)
                poly(-0.3f, -0.6f, -0.9f, 0f, -0.3f, 0.6f)
            }
            "done" -> poly(-0.85f, 0f, -0.3f, 0.6f, 0.85f, -0.6f)
            "emoji" -> smileyPictogram(c, cx, cy, h / ICON_HALF, color)
            "voice" -> microphonePictogram(c, cx, cy, h / ICON_HALF, color)
            "hide" -> poly(-0.7f, -0.3f, 0f, 0.35f, 0.7f, -0.3f)
            "switch" -> {
                rect.set(x(-0.95f), y(-0.6f), x(0.95f), y(0.6f))
                c.drawRoundRect(rect, 0.12f * h, 0.12f * h, stroke)
                for (xx in floatArrayOf(-0.5f, 0f, 0.5f)) c.drawPoint(x(xx), y(-0.2f), stroke)
                line(-0.4f, 0.25f, 0.4f, 0.25f)
            }
            "home" -> {
                val g = 0.12f
                for ((ox, oy) in listOf(-1f to -1f, g to -1f, -1f to g, g to g)) {
                    rect.set(x(ox * 0.85f), y(oy * 0.85f), x(ox * 0.85f + 0.88f - g), y(oy * 0.85f + 0.88f - g))
                    c.drawRect(rect, stroke)
                }
            }
            "web" -> {
                c.drawCircle(x(-0.15f), y(-0.15f), 0.65f * h, stroke)
                line(-0.8f, -0.15f, 0.5f, -0.15f)
                rect.set(x(-0.45f), y(-0.8f), x(0.15f), y(0.5f))
                c.drawOval(rect, stroke)
                line(0.35f, 0.35f, 0.95f, 0.95f)
            }
            "clipboard" -> {
                rect.set(x(-0.65f), y(-0.75f), x(0.65f), y(0.95f))
                c.drawRect(rect, stroke)
                rect.set(x(-0.3f), y(-0.95f), x(0.3f), y(-0.6f))
                c.drawRect(rect, stroke)
                line(-0.3f, -0.15f, 0.3f, -0.15f)
                line(-0.3f, 0.25f, 0.3f, 0.25f)
                line(-0.3f, 0.6f, 0.1f, 0.6f)
            }
            "language" -> {
                c.drawCircle(cx, cy, 0.9f * h, stroke)
                rect.set(x(-0.4f), y(-0.9f), x(0.4f), y(0.9f))
                c.drawOval(rect, stroke)
                line(-0.9f, 0f, 0.9f, 0f)
                line(0f, -0.9f, 0f, 0.9f)
            }
            "left" -> {
                line(0.9f, -0.7f, 0.9f, 0.7f)
                line(-0.9f, 0f, 0.5f, 0f)
                poly(-0.35f, -0.55f, -0.9f, 0f, -0.35f, 0.55f)
            }
            "right" -> {
                line(-0.9f, -0.7f, -0.9f, 0.7f)
                line(-0.5f, 0f, 0.9f, 0f)
                poly(0.35f, -0.55f, 0.9f, 0f, 0.35f, 0.55f)
            }
            "undo" -> {
                rect.set(x(-0.8f), y(-0.6f), x(0.8f), y(0.9f))
                c.drawArc(rect, 180f, 230f, false, stroke)
                poly(-1.05f, -0.35f, -0.8f, 0.15f, -0.35f, -0.15f)
            }
            "settings" -> {
                c.drawCircle(cx, cy, 0.42f * h, stroke)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val ca = Math.cos(a).toFloat()
                    val sa = Math.sin(a).toFloat()
                    line(0.62f * ca, 0.62f * sa, 0.95f * ca, 0.95f * sa)
                }
                c.drawCircle(cx, cy, 0.66f * h, stroke)
            }
            "app" -> {
                // open app: a box open at its top-right corner with an arrow leaving it
                poly(-0.1f, -0.8f, -0.8f, -0.8f, -0.8f, 0.8f, 0.8f, 0.8f, 0.8f, 0.1f)
                line(-0.1f, 0.1f, 0.85f, -0.85f)
                poly(0.2f, -0.85f, 0.85f, -0.85f, 0.85f, -0.2f)
            }
            "none" -> {}
            else -> c.drawCircle(cx, cy, width, fill)
        }
    }
}
