package app.metrokeys.ime

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * The app's own read-only provider for the images of the clipboard history (03 §10.4). It is not exported; an
 * editor gets access to one image only through the read-permission grant of InputConnection.commitContent.
 * URIs: `content://app.metrokeys.clips/<file name>`.
 */
class ClipImageProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun fileOf(uri: Uri): File {
        val name = uri.lastPathSegment
        if (uri.authority != AUTHORITY || uri.pathSegments.size != 1 || name == null || !NAME.matches(name)) {
            throw FileNotFoundException(uri.toString())
        }
        val f = File(ClipImages.dir(context!!), name)
        if (!f.isFile) throw FileNotFoundException(uri.toString())
        return f
    }

    override fun getType(uri: Uri): String? = uri.lastPathSegment?.takeIf { NAME.matches(it) }?.let { ClipImages.mimeFor(it) }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        return ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileOf(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols, 1)
        c.addRow(cols.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> "image.${f.extension}"
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        })
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        const val AUTHORITY = "app.metrokeys.clips"
        private val NAME = Regex("[0-9a-f]{64}\\.[a-z]{3,4}")

        fun uriFor(name: String): Uri = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(name).build()
    }
}
