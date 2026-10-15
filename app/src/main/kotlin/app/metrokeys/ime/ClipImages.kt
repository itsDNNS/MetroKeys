package app.metrokeys.ime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Image files of the clipboard history (03 §10.1 item 3, §10.3 item 7, §10.4): copying an image clip into
 * app-private, no-backup storage, down-sampled thumbnails with a cache, deleting files and the content URIs of the
 * own provider ([ClipImageProvider]). File names are the SHA-256 of the bytes plus an extension, so the same image
 * copied twice is one entry.
 */
class ClipImages(private val context: Context) {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "metrokeys-clips").apply { isDaemon = true } }
    private val thumbs = LruCache<String, Bitmap>(THUMB_CACHE)
    private val loading = HashSet<String>()

    val dir: File get() = dir(context)

    /** Runs [task] on the image thread. */
    fun background(task: () -> Unit) {
        io.execute {
            try {
                task()
            } catch (e: Exception) {
                android.util.Log.w("MetroKeys", "clipboard image task failed", e)
            }
        }
    }

    /**
     * Copies the image at [uri] (MIME type [mime]) into the history directory: at most 15 MB, and only if it can be
     * decoded. Returns the file name, or null (too large, not decodable, not readable). Call on the image thread.
     */
    fun copy(uri: Uri, mime: String): String? {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > ClipboardHistory.MAX_IMAGE_BYTES) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            // e.g. a missing read permission (03 §10.1 item 3: read errors are ignored)
            null
        } ?: return null
        if (!decodable(bytes)) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val name = "$digest.${extensionFor(mime)}"
        val f = File(dir.apply { mkdirs() }, name)
        if (!f.isFile || f.length() != bytes.size.toLong()) {
            val tmp = File(dir, "$name.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) {
                tmp.delete()
                return null
            }
        }
        return name
    }

    private fun decodable(bytes: ByteArray): Boolean {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return false
        val probe = BitmapFactory.Options().apply { inSampleSize = sampleSize(o.outWidth, o.outHeight, 64) }
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe) ?: return false
        b.recycle()
        return true
    }

    /** Deletes the image files [names] (entries that left the history). */
    fun delete(names: Collection<String>) {
        if (names.isEmpty()) return
        for (n in names) thumbs.remove(n)
        background { for (n in names) File(dir, n).delete() }
    }

    /** Deletes every file in the directory that is not in [keep] (stale files after a crash or history off). */
    fun sweep(keep: Set<String>) {
        background {
            dir.listFiles()?.forEach { f -> if (f.name !in keep && !f.name.endsWith(".tmp")) f.delete() }
        }
    }

    /**
     * The cached thumbnail of [name] at most [height] px high, or null; a missing thumbnail is decoded on the image
     * thread and [onReady] is called on the main thread when it is there.
     */
    fun thumbnail(name: String, height: Int, onReady: () -> Unit): Bitmap? {
        thumbs.get(name)?.let { return it }
        if (!loading.add(name)) return null
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        background {
            val f = File(dir, name)
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            val bmp = if (o.outWidth > 0 && o.outHeight > 0) {
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sampleSize(o.outWidth, o.outHeight, height) })
            } else null
            val scaled = bmp?.let { b ->
                if (b.height <= height) b else Bitmap.createScaledBitmap(b, maxOf(1, b.width * height / b.height), height, true)
                    .also { if (it !== b) b.recycle() }
            }
            main.post {
                loading.remove(name)
                if (scaled != null) {
                    thumbs.put(name, scaled)
                    onReady()
                }
            }
        }
        return null
    }

    fun uriFor(name: String): Uri = ClipImageProvider.uriFor(name)

    fun release() {
        io.shutdown()
    }

    companion object {
        private const val THUMB_CACHE = 32

        fun dir(context: Context): File = File(context.noBackupFilesDir, "clips")

        /** Largest power of two that keeps the shorter side at least [target] px. */
        fun sampleSize(w: Int, h: Int, target: Int): Int {
            var s = 1
            while (minOf(w, h) / (s * 2) >= target) s *= 2
            return s
        }

        fun extensionFor(mime: String): String = when (mime.lowercase()) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/bmp" -> "bmp"
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            "image/avif" -> "avif"
            else -> "img"
        }

        fun mimeFor(name: String): String = when (name.substringAfterLast('.', "")) {
            "png" -> "image/png"
            "jpg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "avif" -> "image/avif"
            else -> "application/octet-stream"
        }
    }
}
