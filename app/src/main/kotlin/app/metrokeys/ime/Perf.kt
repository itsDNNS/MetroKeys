package app.metrokeys.ime

import android.os.Build
import android.os.Debug
import android.os.Trace

/**
 * Trace sections for performance measurements (docs/APP.md "Performance"). The sections cost a flag check unless the
 * app is traced (Perfetto/atrace with this app's tracing enabled), so they stay in the release build.
 */
object Perf {
    inline fun <T> section(name: String, block: () -> T): T {
        Trace.beginSection(name)
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }

    /** Counter track of the bytes allocated by the process so far (KiB), sampled per key while tracing. */
    fun allocCounter() {
        if (Build.VERSION.SDK_INT < 29 || !Trace.isEnabled()) return
        val kb = (Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: return) / 1024
        Trace.setCounter("mk:allocKiB", kb)
    }
}
