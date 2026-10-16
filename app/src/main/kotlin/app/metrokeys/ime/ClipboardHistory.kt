package app.metrokeys.ime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * One clipboard history entry (spec 03 §10): a text, or an image (extension, §10.1 item 3) stored as the file
 * [image] in app storage with its MIME type; an image entry has an empty [text].
 */
data class ClipEntry(
    val text: String,
    val time: Long,
    val pinned: Boolean,
    val image: String? = null,
    val mime: String? = null,
) {
    val isImage: Boolean get() = image != null

    /** Identity of the entry: the image file for images, else the text. */
    val key: String get() = image?.let { "image:$it" } ?: "text:$text"
}

/**
 * The clipboard history model of spec 03 §10.1/§10.2: unpinned entries expire after one hour, at most 25 unpinned
 * entries, pinned entries are kept; copying a known text (or the same image) moves it to the top. Persistence is a
 * small binary format; the caller stores it in app-private storage excluded from backups. Image files of entries
 * that leave the history are collected in [takeDroppedImages] so the caller can delete them.
 */
class ClipboardHistory {
    private val entries = ArrayList<ClipEntry>()
    private val dropped = LinkedHashSet<String>()

    /** Timestamp of the last system clip that was recorded (so the same clip is not recorded twice). */
    var lastClipStamp: Long = 0
        private set

    /**
     * Marks the system clip [clipStamp] as handled without recording anything; returns false if it was handled
     * already (used before an image is copied in the background).
     */
    fun claim(clipStamp: Long): Boolean {
        if (clipStamp != 0L && clipStamp == lastClipStamp) return false
        lastClipStamp = clipStamp
        return true
    }

    /** Records [text] copied at [now]; [clipStamp] identifies the system clip. Returns true if the list changed. */
    fun record(text: String, now: Long, clipStamp: Long): Boolean {
        if (!claim(clipStamp)) return false
        if (text.isBlank()) return false
        val t = if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS) else text
        add(ClipEntry(t, now, false))
        return true
    }

    /**
     * Records an image stored as file [image] with MIME type [mime] (03 §10.1 item 3). The clip must have been
     * claimed with [claim] before its bytes were copied. The same file again moves the entry to the top.
     */
    fun recordImage(image: String, mime: String, now: Long) {
        dropped.remove(image)
        add(ClipEntry("", now, false, image, mime))
    }

    private fun add(e: ClipEntry) {
        val old = entries.indexOfFirst { it.key == e.key }
        val pinned = old >= 0 && entries[old].pinned
        if (old >= 0) entries.removeAt(old)
        entries.add(0, e.copy(pinned = pinned))
        trim(e.time)
    }

    /** Entries in panel order: pinned first (newest first), then unpinned (newest first); expired ones dropped. */
    fun list(now: Long): List<ClipEntry> {
        trim(now)
        return entries.filter { it.pinned }.sortedByDescending { it.time } + entries.filter { !it.pinned }.sortedByDescending { it.time }
    }

    /** The image entry stored as [image], or null. */
    fun imageEntry(image: String): ClipEntry? = entries.firstOrNull { it.image == image }

    fun togglePin(e: ClipEntry, now: Long): Boolean? {
        val i = entries.indexOfFirst { it.key == e.key }
        if (i < 0) return null
        val old = entries[i]
        entries[i] = old.copy(pinned = !old.pinned, time = now)
        return !old.pinned
    }

    fun delete(e: ClipEntry) {
        removeWhere { it.key == e.key }
    }

    fun clearUnpinned() {
        removeWhere { !it.pinned }
    }

    fun clearAll() {
        removeWhere { true }
    }

    fun isEmpty(now: Long): Boolean {
        trim(now)
        return entries.isEmpty()
    }

    /** Image files of entries removed since the last call (expiry, delete, clear). */
    fun takeDroppedImages(): List<String> {
        val out = dropped.toList()
        dropped.clear()
        return out
    }

    /** Image files referenced by the history. */
    fun imageFiles(): Set<String> = entries.mapNotNull { it.image }.toSet()

    private fun removeWhere(predicate: (ClipEntry) -> Boolean) {
        val it = entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (predicate(e)) {
                e.image?.let { f -> dropped.add(f) }
                it.remove()
            }
        }
    }

    private fun trim(now: Long) {
        removeWhere { !it.pinned && now - it.time > EXPIRY_MS }
        var unpinned = 0
        val drop = HashSet<String>()
        for (e in entries.sortedByDescending { e -> e.time }) if (!e.pinned && ++unpinned > MAX_UNPINNED) drop.add(e.key)
        if (drop.isNotEmpty()) removeWhere { it.key in drop }
    }

    fun serialize(): ByteArray {
        val bo = ByteArrayOutputStream()
        DataOutputStream(bo).use { o ->
            o.writeInt(VERSION)
            o.writeLong(lastClipStamp)
            o.writeInt(entries.size)
            for (e in entries) {
                o.writeUTF(e.text)
                o.writeLong(e.time)
                o.writeBoolean(e.pinned)
                o.writeUTF(e.image ?: "")
                o.writeUTF(e.mime ?: "")
            }
        }
        return bo.toByteArray()
    }

    companion object {
        const val EXPIRY_MS = 60 * 60 * 1000L
        const val MAX_UNPINNED = 25
        const val MAX_CHARS = 10_000

        /** Largest image that is copied into the history (03 §10.1 item 3). */
        const val MAX_IMAGE_BYTES = 15L * 1024 * 1024

        /** Version 2 adds the image file and MIME type per entry. */
        private const val VERSION = 2
        private const val VERSION_1 = 1

        /** Reads serialised data (version 1 or 2); unreadable data yields an empty history. */
        fun read(data: ByteArray?): ClipboardHistory {
            val h = ClipboardHistory()
            if (data == null) return h
            try {
                DataInputStream(ByteArrayInputStream(data)).use { i ->
                    val version = i.readInt()
                    if (version != VERSION && version != VERSION_1) return h
                    h.lastClipStamp = i.readLong()
                    repeat(i.readInt()) {
                        val text = i.readUTF()
                        val time = i.readLong()
                        val pinned = i.readBoolean()
                        var image: String? = null
                        var mime: String? = null
                        if (version >= VERSION) {
                            image = i.readUTF().ifEmpty { null }
                            mime = i.readUTF().ifEmpty { null }
                        }
                        h.entries.add(ClipEntry(text, time, pinned, image, mime))
                    }
                }
            } catch (_: IOException) {
                h.entries.clear()
            }
            return h
        }
    }
}
