package app.metrokeys.keyboard

import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.customview.widget.ExploreByTouchHelper
import app.metrokeys.R
import app.metrokeys.ime.FieldState
import app.metrokeys.ime.Shift
import app.metrokeys.keyboard.layout.Key
import app.metrokeys.keyboard.layout.KeyRole
import app.metrokeys.keyboard.layout.PageKind

/**
 * Screen-reader support (03 §19): one virtual node per drawn element — picker cells first, the strip, the keys or
 * emoji cells or clipboard rows, the application bar. Keys, emoji cells and picker cells are text-entry keys, so the
 * screen reader types them itself (lift to type); every activation runs through the same touch path as a finger.
 */
class KeyboardAccessibility(private val v: KeyboardView) : ExploreByTouchHelper(v) {
    private class Node(val id: Int, val bounds: RectF)

    private fun c() = v.controller

    private fun nodes(): List<Node> {
        if (!v.isLaidOut) return emptyList()
        val out = ArrayList<Node>()
        val p = v.picker
        val pickerRects = if (p != null) p.items.indices.map { v.pickerCellRect(p, it) } else emptyList()
        for ((i, r) in pickerRects.withIndex()) out.add(Node(PICKER + i, r))
        for ((kind, i, r) in v.strip.accessibilityNodes()) out.add(Node(STRIP + (kind + 10) * 1000 + i, r))
        val ctl = c()
        if (ctl.hardwareMode && !ctl.miniKeyboardOpen) {
            // strip only
        } else if (ctl.clipboardPanelOpen) {
            for ((kind, i, r) in v.clipPanel.accessibilityNodes()) out.add(Node(CLIP + kind * 1000 + i, r))
        } else {
            if (v.page.kind == PageKind.EMOJI) for ((i, r) in v.emojiPanel.visibleCells()) out.add(Node(EMOJI + i, r))
            for ((i, k) in v.page.keys.withIndex()) {
                if (!k.hittable) continue
                val r = v.cellOf(k)
                if (pickerRects.any { RectF.intersects(it, r) && it.contains(r) }) continue
                out.add(Node(KEY + i, r))
            }
        }
        if (v.barHeight > 0) for ((kind, i, r) in v.appBar.accessibilityNodes(v.barTop)) out.add(Node(BAR + kind * 1000 + i, r))
        return out
    }

    override fun getVirtualViewAt(x: Float, y: Float): Int {
        for (n in nodes()) if (n.bounds.contains(x, y)) return n.id
        return INVALID_ID
    }

    override fun getVisibleVirtualViews(ids: MutableList<Int>) {
        for (n in nodes()) ids.add(n.id)
    }

    override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
        val n = nodes().firstOrNull { it.id == id }
        val r = n?.bounds ?: RectF(0f, 0f, 1f, 1f)
        @Suppress("DEPRECATION")
        node.setBoundsInParent(Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()))
        node.className = "android.view.View"
        node.addAction(AccessibilityActionCompat.ACTION_CLICK)
        val ctl = c()
        when {
            id >= BAR -> {
                val kind = (id - BAR) / 1000
                val i = (id - BAR) % 1000
                node.className = "android.widget.Button"
                when (kind) {
                    0 -> v.appBar.button(i)?.let { b ->
                        node.contentDescription = b.description.ifEmpty { b.label }
                        node.isEnabled = b.enabled
                        if (b.hold != "none") node.addAction(AccessibilityActionCompat(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK, b.hold))
                    }
                    1 -> node.contentDescription = ctl.str(R.string.a11y_more)
                    else -> node.contentDescription = ctl.str(listOf(R.string.menu_switch, R.string.menu_clipboard, R.string.menu_settings)[i])
                }
            }
            id >= CLIP -> {
                val kind = (id - CLIP) / 1000
                val i = (id - CLIP) % 1000
                when (kind) {
                    0 -> v.clipPanel.entry(i)?.let { e ->
                        node.contentDescription = if (e.isImage) ctl.str(R.string.a11y_image) else e.text.take(200)
                        if (e.pinned) node.stateDescription = ctl.str(R.string.a11y_pinned)
                        node.addAction(AccessibilityActionCompat(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK,
                            ctl.str(if (e.pinned) R.string.a11y_unpin else R.string.a11y_pin)))
                    }
                    1 -> node.contentDescription = ctl.str(R.string.a11y_delete_entry)
                    2 -> node.contentDescription = ctl.str(listOf(R.string.a11y_letters, R.string.clip_hint, R.string.a11y_clear_unpinned)[i])
                    else -> node.contentDescription = ctl.str(R.string.clip_empty)
                }
            }
            id >= EMOJI -> {
                node.contentDescription = v.emojiPanel.item(id - EMOJI) ?: ""
                node.isTextEntryKey = true
            }
            id >= KEY -> v.page.keys.getOrNull(id - KEY)?.let { k -> populateKey(k, node) }
            id >= STRIP -> {
                val kind = (id - STRIP) / 1000 - 10
                val i = (id - STRIP) % 1000
                node.className = "android.widget.Button"
                node.contentDescription = when (kind) {
                    StripBar.HEADER -> ctl.str(R.string.a11y_paste).also {
                        // dimmed with an empty clipboard: disabled (03 §19.2, §6.6 item 8)
                        if (!ctl.canPaste) {
                            node.isEnabled = false
                            node.removeAction(AccessibilityActionCompat.ACTION_CLICK)
                        }
                    }
                    StripBar.CHIP -> {
                        node.removeAction(AccessibilityActionCompat.ACTION_CLICK)
                        node.addAction(AccessibilityActionCompat(AccessibilityNodeInfoCompat.ACTION_CLICK, ctl.str(R.string.a11y_paste_action)))
                        ctl.str(R.string.a11y_recent_clip, v.strip.chipDescription ?: "")
                    }
                    StripBar.FORGET -> when (i) {
                        0 -> {
                            node.className = "android.widget.TextView"
                            node.removeAction(AccessibilityActionCompat.ACTION_CLICK)
                            ctl.str(R.string.a11y_forget_question, v.strip.forgetting ?: "")
                        }
                        1 -> ctl.str(R.string.forget)
                        else -> ctl.str(R.string.forget_cancel)
                    }
                    StripBar.CLIP -> ctl.str(R.string.a11y_clipboard).also { node.isSelected = ctl.clipboardPanelOpen }
                    StripBar.DICTATION -> ctl.str(R.string.a11y_dictation)
                    else -> v.strip.items.getOrNull(i)?.let { item ->
                        if (v.strip.canForget(i)) {
                            node.addAction(AccessibilityActionCompat(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK, ctl.str(R.string.a11y_forget_word)))
                        }
                        when {
                            item.addWord -> ctl.str(R.string.a11y_add_word, item.text)
                            item.autocorrection -> ctl.str(R.string.a11y_autocorrection, item.text)
                            else -> ctl.str(R.string.a11y_suggestion, item.text)
                        }
                    } ?: ""
                }
            }
            id >= PICKER -> {
                val p = v.picker
                val item = p?.items?.getOrNull(id - PICKER) ?: ""
                node.contentDescription = if (p?.language == true) ctl.languageName(item) else item
                node.isTextEntryKey = true
                node.isSelected = p?.highlighted == id - PICKER
            }
        }
    }

    private fun populateKey(k: Key, node: AccessibilityNodeInfoCompat) {
        val ctl = c()
        node.isTextEntryKey = k.role != KeyRole.EMOJI_CATEGORY
        val keyRole = ctl.str(R.string.a11y_key)
        node.contentDescription = when (k.role) {
            KeyRole.CHAR -> {
                val label = v.labelOf(k)
                if (v.page.kind == PageKind.DIALPAD && k.hint != null) "$label ${k.hint}" else label
            }
            KeyRole.SHIFT -> {
                node.roleDescription = keyRole
                node.stateDescription = ctl.str(when (ctl.shift) {
                    Shift.OFF -> R.string.a11y_state_off
                    Shift.ONE_SHOT -> R.string.a11y_state_on
                    Shift.CAPS_LOCK -> R.string.a11y_state_locked
                })
                ctl.str(R.string.a11y_shift)
            }
            KeyRole.BACKSPACE -> ctl.str(R.string.a11y_delete).also { node.roleDescription = keyRole }
            KeyRole.SPACE -> ctl.str(R.string.a11y_space)
            KeyRole.EMOJI -> ctl.str(R.string.a11y_emoji)
            KeyRole.ENTER -> enterName()
            KeyRole.LANGUAGE -> ctl.str(R.string.a11y_language, ctl.languageName(ctl.language))
            KeyRole.PAGE -> {
                node.roleDescription = keyRole
                val target = k.target?.let { v.controller.layout.pages[it] }
                if (target != null) ctl.pageName(target) else v.labelOf(k)
            }
            KeyRole.DOMAIN -> v.labelOf(k)
            KeyRole.EMOJI_CATEGORY -> {
                node.roleDescription = ctl.str(R.string.a11y_category)
                node.isSelected = ctl.emojiCategory == k.category
                categoryName(k.category)
            }
            else -> v.labelOf(k)
        }
        val longLabel = when {
            k.holdOutput != null -> ctl.str(R.string.a11y_type_plus)
            k.role == KeyRole.SHIFT -> ctl.str(R.string.a11y_lock_shift)
            k.role == KeyRole.LANGUAGE && ctl.settings.languages.size > 1 -> ctl.str(R.string.a11y_languages)
            k.role == KeyRole.DOMAIN -> ctl.str(R.string.a11y_more_domains)
            ctl.pickerItems(k) != null -> ctl.str(R.string.a11y_alternatives)
            else -> null
        }
        if (longLabel != null) node.addAction(AccessibilityActionCompat(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK, longLabel))
    }

    private fun enterName(): String {
        val ctl = c()
        val f = ctl.field
        if (!f.enterPerformsAction) return ctl.str(R.string.a11y_enter)
        return ctl.str(when (f.action) {
            FieldState.ACTION_GO -> R.string.a11y_action_go
            FieldState.ACTION_SEARCH -> R.string.a11y_action_search
            FieldState.ACTION_SEND -> R.string.a11y_action_send
            FieldState.ACTION_NEXT -> R.string.a11y_action_next
            FieldState.ACTION_PREVIOUS -> R.string.a11y_action_previous
            FieldState.ACTION_DONE -> R.string.a11y_action_done
            else -> R.string.a11y_enter
        })
    }

    private fun categoryName(id: String?): String = c().str(when (id) {
        "recent" -> R.string.a11y_cat_recent
        "emoticons" -> R.string.a11y_cat_emoticons
        "people" -> R.string.a11y_cat_people
        "activities" -> R.string.a11y_cat_activities
        "food-objects" -> R.string.a11y_cat_food
        "travel" -> R.string.a11y_cat_travel
        "nature-weather" -> R.string.a11y_cat_nature
        else -> R.string.a11y_cat_symbols
    })

    override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
        val long = action == AccessibilityNodeInfoCompat.ACTION_LONG_CLICK
        if (action != AccessibilityNodeInfoCompat.ACTION_CLICK && !long) return false
        when {
            id >= BAR -> {
                val kind = (id - BAR) / 1000
                val i = (id - BAR) % 1000
                when (kind) {
                    0 -> v.appBar.button(i)?.let { if (long) c().appBarHold(it) else if (it.enabled) c().appBarTap(it) }
                    1 -> c().toggleAppBarExpanded()
                    else -> c().menuAction(i)
                }
            }
            id >= CLIP -> v.clipPanel.activate((id - CLIP) / 1000, (id - CLIP) % 1000, long)
            id >= EMOJI -> v.emojiPanel.pick(id - EMOJI)
            id >= KEY -> v.page.keys.getOrNull(id - KEY)?.let { v.activateKey(it, long) }
            id >= STRIP -> v.strip.activate((id - STRIP) / 1000 - 10, (id - STRIP) % 1000, long)
            id >= PICKER -> v.activatePickerItem(id - PICKER)
        }
        invalidateRoot()
        return true
    }

    override fun onPopulateNodeForHost(node: AccessibilityNodeInfoCompat) {
        super.onPopulateNodeForHost(node)
        node.contentDescription = c().str(R.string.a11y_keyboard)
        node.addAction(AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
        node.addAction(AccessibilityActionCompat.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(host: android.view.View, action: Int, args: Bundle?): Boolean {
        if (action == AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD) {
            val fwd = action == AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD
            when {
                c().clipboardPanelOpen -> v.clipPanel.scrollPage(fwd)
                v.page.kind == PageKind.EMOJI -> v.emojiPanel.scrollPage(fwd)
                else -> v.strip.scrollBy(fwd)
            }
            invalidateRoot()
            return true
        }
        return super.performAccessibilityAction(host, action, args)
    }

    /** Focus follows the active key under a passed-through finger. */
    fun onActiveKey(@Suppress("UNUSED_PARAMETER") k: Key) {}

    fun announce(msg: String) {
        @Suppress("DEPRECATION")
        v.announceForAccessibility(msg)
    }

    companion object {
        private const val PICKER = 0
        private const val STRIP = 100_000
        private const val KEY = 200_000
        private const val EMOJI = 300_000
        private const val CLIP = 400_000
        private const val BAR = 500_000
    }
}
