package app.metrokeys.keyboard

import app.metrokeys.engine.swipe.ShapeWriter
import app.metrokeys.keyboard.layout.Hit
import app.metrokeys.keyboard.layout.HitTester
import app.metrokeys.keyboard.layout.Hysteresis
import app.metrokeys.keyboard.layout.Key
import app.metrokeys.keyboard.layout.KeyCells
import app.metrokeys.keyboard.layout.KeyRole
import app.metrokeys.keyboard.layout.LetterCorrection
import app.metrokeys.keyboard.layout.Page
import app.metrokeys.keyboard.layout.PxRect
import kotlin.math.hypot

/** Time and delayed tasks of the touch state machine (the view's handler; a fake clock in tests). */
interface TouchClock {
    fun now(): Long
    fun post(task: Runnable, delayMs: Long)
    fun cancel(task: Runnable)
}

/**
 * The key-press popup to draw: [cell] is the key's cell (key-area px; kept for a released key, whose page may have
 * changed), [frozenLabel] the content kept during the release delay, null = the live label.
 */
class PopupState(val key: Key, val cell: PxRect, val frozenLabel: String?)

/**
 * The touch state machine of the key area (03 §1.1–§1.5, §2, §3, §5, §7.1; 02 §5.3, §5.5.6, §10; 06 §2), in key-area
 * pixels and event times. Pure Kotlin: everything outside goes through [Host], timers through [TouchClock].
 *
 * Visual state: [pressedKey] (pressed look), [popup], [picker]. The pressed look and popup of a popup key stay for
 * `popupHideDelay` = 75 ms after its release (unchanged, the label frozen); keys without a popup never show one. On a
 * trace candidate keys reached by sliding show no feedback.
 */
class KeyTouch(private val host: Host, private val clock: TouchClock) {
    interface Host {
        val page: Page
        val keyAreaWidth: Float
        val keyAreaHeight: Float

        /** Reference px → device px. */
        val scale: Float

        /** Input type text without the short-message variation (03 §1.1 rule 5). */
        val fastTypingField: Boolean

        /** Shape writing possible on this page and field (06 §1.2, includes "no touch exploration"). */
        val shapeWritingActive: Boolean

        /** Letter correction allowed now (03 §1.1a: letter page, TEXT field, inside a word, …). */
        fun keyChoiceAllowed(): Boolean

        /** Class of the best completion of the current word plus the key's letter, < 0 if impossible. */
        fun prefixClass(k: Key): Int

        /** Touch-down of a key: click/vibration and the keys that act on touch-down (03 §1.2, §4, §7.1, §8.2). */
        fun touchDown(k: Key)

        /** Click of a sticky picker tap (03 §3.4 item 6). */
        fun pickerTap()

        /** Release: the key's character is typed or its function runs. */
        fun commit(k: Key, x: Float, y: Float)
        fun repeat(k: Key)
        fun wordDelete()
        fun holdOutput(k: Key)
        fun pickerItems(k: Key): List<String>?

        /** Cell size (width, height) of a picker for [k] with [items] (02 §5.6.1, §5.7). */
        fun pickerCellSize(k: Key, items: List<String>): Pair<Float, Float>
        fun pickerOpened(items: List<String>)
        fun pickerClosed()
        fun pickerCommit(k: Key, item: String)

        /** The popup content of [k] as shown now (label, or the language tag of the language key). */
        fun popupSnapshot(k: Key): String

        /**
         * The touch became a trace (03 §1.5 item 2) on [startKey]: the samples since touch-down (times relative to
         * [downTime]); the drawn trace starts at sample [drawFrom] (0, or the swipe start for a touch that began on Shift
         * or Backspace, 06 §2.3). A trace that starts on Shift sets the shift state back (03 §4 item 8).
         */
        fun traceStart(startKey: Key, xs: FloatArrayList, ys: FloatArrayList, ts: LongArrayList, downTime: Long, drawFrom: Int)
        fun traceAdd(x: Float, y: Float, t: Long)

        /** The drawn trace ends (it fades). */
        fun traceEnd()

        /** The trace ends without recognition: cancel, a long press that won (06 §2.4), too few samples. */
        fun traceAborted()
        fun swipe(xs: FloatArray, ys: FloatArray, ts: LongArray)
        fun activeKeyChanged(k: Key)
        fun invalidate()
    }

    private enum class Activation { DOWN, SLIDE, PAGE_HANDOVER }

    private val tester = HitTester()
    private var cellsCache: KeyCells? = null

    /** Cells of the current page and size. */
    val cells: KeyCells
        get() {
            val c = cellsCache
            val p = host.page
            if (c != null && c.page === p && c.width == host.keyAreaWidth && c.height == host.keyAreaHeight) return c
            return KeyCells(p, host.keyAreaWidth, host.keyAreaHeight).also { cellsCache = it }
        }

    private val s: Float get() = host.scale

    // ---------------------------------------------------------------- state

    /** The active key (03 §1.2), also when it shows no feedback. */
    var activeKey: Key? = null
        private set
    private var activation = Activation.DOWN
    private var activeShown = false
    private var hysteresis = PxRect(0f, 0f, 0f, 0f)

    /** Key whose pressed look and popup stay for the release delay, with the frozen popup content. */
    private var lingerKey: Key? = null
    private var lingerLabel: String? = null
    private var lingerCell = PxRect(0f, 0f, 0f, 0f)
    private val lingerEnd = Runnable {
        lingerKey = null
        lingerLabel = null
        host.invalidate()
    }

    private var inTouch = false
    private var swallow = false
    private var consumed = false
    var repeating = false
        private set
    private var repeatStart = 0L

    /** The touch started as a trace candidate (03 §1.5 item 1): keys reached by sliding show no feedback. */
    private var traceCandidateTouch = false
    private var traceCandidate = false
    private var traceStartKey: Key? = null

    /** Cell of a non-letter start key (Shift, Backspace): no trace while the finger is inside it (03 §1.5 item 1b). */
    private var traceStartCell: PxRect? = null
    var tracing = false
        private set
    private val traceX = FloatArrayList()
    private val traceY = FloatArrayList()
    private val traceT = LongArrayList()
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var pageSwitchLock = false
    private var pageSwitchThreshold = 0f

    var picker: PickerModel? = null
        private set

    private val holdTask = Runnable { onHold() }
    private val repeatTask = object : Runnable {
        override fun run() {
            val k = activeKey ?: return
            if (k.role == KeyRole.BACKSPACE && clock.now() - repeatStart >= WORD_DELETE_AFTER_MS) {
                host.wordDelete()
                clock.post(this, WORD_DELETE_INTERVAL_MS)
            } else {
                host.repeat(k)
                clock.post(this, REPEAT_INTERVAL_MS)
            }
        }
    }

    // ---------------------------------------------------------------- visual state

    /** The key drawn in the pressed look (02 §5.3), apart from shift's caps lock and the emoji tabs. */
    val pressedKey: Key?
        get() {
            picker?.let { return it.key }
            val a = activeKey
            if (a != null && activeShown) return a
            return lingerKey
        }

    /** The key-press popup to draw (02 §5.5, 03 §2): never while a picker is open, only for popup keys. */
    val popup: PopupState?
        get() {
            if (picker != null) return null
            val a = activeKey
            if (a != null && activeShown && a.popup) return cellOf(a)?.let { PopupState(a, it, null) }
            val l = lingerKey ?: return null
            return PopupState(l, lingerCell, lingerLabel)
        }

    // ---------------------------------------------------------------- events

    /** Touch-down at ([x], [y]) in key-area px; for a sticky picker anywhere on the keyboard (also y < 0). */
    fun down(x: Float, y: Float, t: Long) {
        endLinger()
        reset()
        inTouch = true
        val p = picker
        if (p != null) {
            val i = if (p.sticky && p.inBand(y)) p.slotAt(x, y) else -1
            if (i >= 0) {
                p.sticky = false
                p.drag = true
                p.highlighted = i
                host.pickerTap()
                host.invalidate()
            } else {
                closePicker()
                swallow = true
            }
            return
        }
        downX = x
        downY = y
        downTime = t
        val c = cells
        val hit = tester.hit(c, x, y, t, touchDown = true, outside = OUTSIDE_TOLERANCE * s, fastTyping = host.fastTypingField)
        if (hit == null) {
            inTouch = false
            return
        }
        if (hit.inert) return
        val k = choose(c, hit, x, y)
        if (k.role == KeyRole.PAGE || k.role == KeyRole.EMOJI) {
            pageSwitchThreshold = c.cell(k).width / 2
            host.touchDown(k)
            pageSwitchLock = true
            host.invalidate()
            return
        }
        host.touchDown(k)
        activate(k, Activation.DOWN, x, y, corrected = k !== hit.key, show = true)
        consumed = k.role == KeyRole.SHIFT || k.role == KeyRole.EMOJI_CATEGORY
        if (!consumed) clock.post(holdTask, k.longPressMs.toLong())
        // whatever key it lands on (03 §1.5 item 1, 06 §2.1): on the letter pages also Shift and Backspace
        traceCandidate = host.shapeWritingActive && ShapeWriter.isSwipeCandidate(y, host.keyAreaHeight)
        traceCandidateTouch = traceCandidate
        traceStartKey = k
        traceStartCell = if (k.isLetter) null else cells.cell(k)
        if (traceCandidate) addTrace(x, y, t)
    }

    fun move(x: Float, y: Float, t: Long) {
        if (!inTouch || swallow) return
        val p = picker
        if (p != null) {
            if (p.move(x, y)) host.invalidate()
            return
        }
        if (traceCandidate) {
            addTrace(x, y, t)
            if (tracing) {
                host.traceAdd(x, y, t)
            } else if (ShapeWriter.isSwipeStart(x - downX, y - downY, host.keyAreaWidth, host.keyAreaHeight) &&
                traceStartCell?.contains(x, y) != true
            ) {
                // the hold timer keeps running while the finger stays in the start key's area (03 §1.5 item 2)
                tracing = true
                val from = if (traceStartCell == null) 0 else traceX.size - 1
                host.traceStart(traceStartKey!!, traceX, traceY, traceT, downTime, from)
                host.invalidate()
            }
        }
        if (pageSwitchLock) {
            if (hypot(x - downX, y - downY) < pageSwitchThreshold) return
            val h = moveHit(x, y, t) ?: return
            if (h.inert) return
            pageSwitchLock = false
            activate(h.key, Activation.PAGE_HANDOVER, x, y, corrected = false, show = true)
            return
        }
        if (activeKey?.let { cellOf(it) } == null) activeKey = null // the page changed under the finger
        val a = activeKey
        if (a != null) {
            if (hysteresis.contains(x, y)) return
            // left the hysteresis area: released without output, hit-tested again (03 §1.2, §1.1 rule 8)
            stopTimers()
            repeating = false
            consumed = false
            val h = moveHit(x, y, t)?.takeUnless { it.inert }
            val handOver = h != null && !traceCandidateTouch
            if (activeShown && !handOver) startLinger(a)
            activeKey = null
            activeShown = false
            if (h != null) activate(h.key, Activation.SLIDE, x, y, corrected = false, show = !traceCandidateTouch)
            host.invalidate()
            return
        }
        // no active key (outside the key area or on an inert key): the key under the finger becomes active by sliding
        val h = moveHit(x, y, t) ?: return
        if (h.inert) return
        activate(h.key, Activation.SLIDE, x, y, corrected = false, show = !traceCandidateTouch)
    }

    /** Lift of the tracked finger; [secondFinger] when another finger touched down (03 §1.3, same handling). */
    @Suppress("UNUSED_PARAMETER")
    fun up(x: Float, y: Float, t: Long, secondFinger: Boolean = false) {
        if (!inTouch) return
        inTouch = false
        stopTimers()
        if (swallow) {
            swallow = false
            return
        }
        val p = picker
        if (p != null) {
            activeKey = null
            if (!p.drag) {
                p.sticky = true
                host.invalidate()
            } else {
                val i = p.highlighted
                closePicker()
                if (i >= 0) host.pickerCommit(p.key, p.items[i])
            }
            reset()
            return
        }
        if (tracing) {
            tracing = false
            host.traceEnd()
            activeKey?.let { if (activeShown) startLinger(it) }
            activeKey = null
            if (traceX.size >= 2) host.swipe(traceX.toArray(), traceY.toArray(), traceT.toArray()) else host.traceAborted()
            reset()
            host.invalidate()
            return
        }
        val k = activeKey
        activeKey = null
        if (k != null) {
            if (activeShown) startLinger(k)
            activeShown = false
            val commit = when (activation) {
                Activation.DOWN -> !consumed && !repeating
                Activation.SLIDE -> k.role == KeyRole.CHAR
                Activation.PAGE_HANDOVER -> k.role in HANDOVER_COMMITS
            }
            if (commit) host.commit(k, x, y)
        }
        reset()
        host.invalidate()
    }

    /** System cancel, keyboard hidden, panel or size change: nothing is committed (03 §1.2). */
    fun cancel() {
        stopTimers()
        if (tracing) {
            host.traceEnd()
            host.traceAborted()
        }
        reset()
        inTouch = false
        swallow = false
        activeKey = null
        activeShown = false
        endLinger()
        closePicker()
        host.invalidate()
    }

    /** Fires the hold timer at once (accessibility long press, 03 §19.3 item 4). */
    fun holdNow() {
        clock.cancel(holdTask)
        onHold()
    }

    fun closePicker() {
        if (picker == null) return
        picker = null
        host.pickerClosed()
        host.invalidate()
    }

    /** Selects item [i] of the open picker and commits it (accessibility). */
    fun commitPickerItem(i: Int) {
        val p = picker ?: return
        closePicker()
        if (i in p.items.indices) host.pickerCommit(p.key, p.items[i])
    }

    // ---------------------------------------------------------------- internals

    /** The cell of [k] on the current page, null if the key is not on it. */
    private fun cellOf(k: Key): PxRect? {
        val c = cells
        val i = c.indexOf(k)
        return if (i < 0) null else c.cell(i)
    }

    private fun moveHit(x: Float, y: Float, t: Long): Hit? =
        tester.hit(cells, x, y, t, touchDown = false, outside = 0f, fastTyping = host.fastTypingField)

    private fun choose(c: KeyCells, hit: Hit, x: Float, y: Float): Key {
        if (!hit.key.isLetter || !host.keyChoiceAllowed()) return hit.key
        return LetterCorrection.choose(c, hit, x, y, HIT_MAX * s, HIT_CLOSE * s) { host.prefixClass(it) }
    }

    private fun activate(k: Key, kind: Activation, px: Float, py: Float, corrected: Boolean, show: Boolean) {
        activeKey = k
        activation = kind
        activeShown = show
        val cell = cells.cell(k)
        hysteresis = if (corrected) Hysteresis.ofCorrected(cell, px, py, MOVE_TOLERANCE * s)
        else Hysteresis.of(cell, px, py, MOVE_TOLERANCE * s, REHIT_BEYOND * s)
        if (show) endLinger()
        host.activeKeyChanged(k)
        host.invalidate()
    }

    /** The release delay of 02 §5.5.6: a popup key keeps look and popup, frozen; other keys end at once. */
    private fun startLinger(k: Key) {
        endLinger()
        if (!k.popup) return
        val cell = cellOf(k) ?: return
        lingerKey = k
        lingerLabel = host.popupSnapshot(k)
        lingerCell = cell
        clock.post(lingerEnd, POPUP_HIDE_DELAY_MS)
    }

    private fun endLinger() {
        if (lingerKey == null) return
        clock.cancel(lingerEnd)
        lingerKey = null
        lingerLabel = null
        host.invalidate()
    }

    private fun stopTimers() {
        clock.cancel(holdTask)
        clock.cancel(repeatTask)
    }

    private fun reset() {
        consumed = false
        repeating = false
        tracing = false
        traceCandidate = false
        traceCandidateTouch = false
        traceStartKey = null
        traceStartCell = null
        pageSwitchLock = false
        traceX.clear()
        traceY.clear()
        traceT.clear()
    }

    private fun addTrace(x: Float, y: Float, t: Long) {
        traceX.add(x)
        traceY.add(y)
        traceT.add(t - downTime)
    }

    /**
     * The hold timer fired on the touched-down key, also while the touch is a trace that has not left the key (03 §1.5
     * item 2, §3.2): a long press that opens the picker or starts a key repeat wins over the trace.
     */
    private fun onHold() {
        val k = activeKey ?: return
        if (activation != Activation.DOWN || !inTouch) return
        val cell = cellOf(k) ?: return
        if (k.holdOutput != null) {
            abortTrace()
            host.holdOutput(k)
            consumed = true
            host.invalidate()
            return
        }
        val items = host.pickerItems(k)
        if (items != null) {
            abortTrace()
            traceCandidate = false
            val (cw, ch) = host.pickerCellSize(k, items)
            picker = PickerModel.place(k, cell, items, cw, ch, FRAME * s, host.keyAreaWidth, k.role == KeyRole.LANGUAGE, clock.now())
            host.pickerOpened(picker!!.items)
            host.invalidate()
            return
        }
        if (k.repeat) {
            abortTrace()
            repeating = true
            traceCandidate = false
            repeatStart = clock.now()
            host.repeat(k)
            clock.post(repeatTask, REPEAT_INTERVAL_MS)
        }
    }

    /** A running trace ends without recognition: the drawn trace fades (06 §2.3, §2.4). */
    private fun abortTrace() {
        if (!tracing) return
        tracing = false
        host.traceEnd()
        host.traceAborted()
    }

    companion object {
        const val POPUP_HIDE_DELAY_MS = 75L
        const val REPEAT_INTERVAL_MS = 83L
        const val WORD_DELETE_AFTER_MS = 3000L
        const val WORD_DELETE_INTERVAL_MS = 200L

        // reference px (03 §1.1, §1.1a, §1.2; 02 §5.6.5)
        const val OUTSIDE_TOLERANCE = 40f
        const val MOVE_TOLERANCE = 10f
        const val REHIT_BEYOND = 20f
        const val HIT_MAX = 13f
        const val HIT_CLOSE = 7f
        const val FRAME = 9f

        /** Keys a finger reaches after a page switch that are committed on release (03 §7.1 item 2). */
        private val HANDOVER_COMMITS = setOf(KeyRole.CHAR, KeyRole.SPACE, KeyRole.ENTER, KeyRole.BACKSPACE, KeyRole.DOMAIN, KeyRole.LANGUAGE)
    }
}

/** Growable primitive arrays for trace samples. */
class FloatArrayList {
    private var a = FloatArray(256)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    operator fun get(i: Int): Float = a[i]

    fun clear() {
        size = 0
    }

    fun toArray(): FloatArray = a.copyOf(size)
}

class LongArrayList {
    private var a = LongArray(256)
    var size = 0
        private set

    fun add(v: Long) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    operator fun get(i: Int): Long = a[i]

    fun clear() {
        size = 0
    }

    fun toArray(): LongArray = a.copyOf(size)
}
