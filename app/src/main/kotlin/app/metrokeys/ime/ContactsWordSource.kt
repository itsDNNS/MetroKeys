package app.metrokeys.ime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import app.metrokeys.engine.predict.ContactNameSource
import app.metrokeys.engine.predict.Params

/**
 * Contact names as a word source (spec 05 §13, setting `contacts_word_source`): harvests all contacts when turned
 * on, then again on every change notification (debounced 1 s); if the contacts store is not ready, up to 5 retries
 * 30 s apart. Per contact one string "given middle family" (else the display name) and each nickname. The names
 * stay in memory only. [onChanged] is called on the main thread with the new names (null = source off).
 */
class ContactsWordSource(
    private val context: Context,
    private val scheduler: Scheduler,
    private val onChanged: (ContactsWordSource) -> Unit,
) : ContactNameSource {
    private var active = false
    private var observer: ContentObserver? = null
    private var pending: (() -> Unit)? = null
    private var retries = 0
    private var generation = 0

    @Volatile
    private var names: List<String> = emptyList()

    /** The names of the last harvest; empty while off. */
    override fun contactNames(): List<String> = names

    val isActive: Boolean get() = active

    private fun permitted(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Applies the setting; the source runs only while it is on and the permission is granted. */
    fun update(enabled: Boolean) {
        val want = enabled && permitted()
        if (want == active) return
        active = want
        generation++
        pending?.invoke()
        pending = null
        if (want) {
            retries = 0
            register()
            harvestLater(0)
        } else {
            unregister()
            names = emptyList()
            onChanged(this)
        }
    }

    fun release() = update(false)

    private fun register() {
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                retries = 0
                harvestLater(DEBOUNCE_MS)
            }
        }
        try {
            context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, o)
            observer = o
        } catch (e: SecurityException) {
            Log.w("MetroKeys", "contacts observer not registered", e)
        }
    }

    private fun unregister() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    private fun harvestLater(delayMs: Long) {
        pending?.invoke()
        val gen = generation
        pending = scheduler.main(delayMs) {
            pending = null
            scheduler.background {
                val result = harvest()
                scheduler.main(0) { finished(gen, result) }
            }
        }
    }

    private fun finished(gen: Int, result: List<String>?) {
        if (gen != generation || !active) return
        if (result == null) {
            if (retries++ < MAX_RETRIES) harvestLater(RETRY_MS)
            return
        }
        names = result
        onChanged(this)
    }

    /** Reads the name strings; null when the contacts store is not ready. */
    private fun harvest(): List<String>? {
        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
            ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME,
            ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
        )
        val nameType = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
        val nickType = ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE
        val cursor = try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI, projection,
                "${ContactsContract.Data.MIMETYPE} IN (?, ?)", arrayOf(nameType, nickType),
                ContactsContract.Data.CONTACT_ID,
            )
        } catch (e: SecurityException) {
            Log.w("MetroKeys", "no access to contacts", e)
            return emptyList()
        } catch (e: RuntimeException) {
            Log.w("MetroKeys", "contacts not readable", e)
            null
        } ?: return null
        val nameOf = LinkedHashMap<Long, String>()
        val nicknames = LinkedHashMap<Long, LinkedHashSet<String>>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val display = c.getString(2)?.trim().orEmpty()
                if (c.getString(1) == nickType) {
                    // the nickname is stored in DATA1, the same column as the structured display name
                    if (display.isNotEmpty()) nicknames.getOrPut(id) { LinkedHashSet() }.add(display)
                    continue
                }
                if (id in nameOf) continue
                val parts = listOf(c.getString(3), c.getString(4), c.getString(5)).mapNotNull { it?.trim() }.filter { it.isNotEmpty() }
                val name = if (parts.isNotEmpty()) parts.joinToString(" ") else display
                if (name.isNotEmpty()) nameOf[id] = name
            }
        }
        val out = ArrayList<String>()
        for ((id, n) in nameOf) {
            out.add(n)
            nicknames[id]?.let { out.addAll(it) }
        }
        for ((id, nicks) in nicknames) if (id !in nameOf) out.addAll(nicks)
        return if (out.size > Params.NAME_MAX) out.subList(0, Params.NAME_MAX) else out
    }

    companion object {
        const val DEBOUNCE_MS = 1000L
        const val RETRY_MS = 30_000L
        const val MAX_RETRIES = 5
    }
}
