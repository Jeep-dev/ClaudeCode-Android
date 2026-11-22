package com.jeep.claudecode

import android.os.Trace

/**
 * A named stretch of main-thread work in the system's trace (atrace -a com.jeep.claudecode view), so a
 * stop can be told apart: the app's own code shows there as nothing otherwise. Next to free when nothing traces.
 */
inline fun <T> traced(name: String, block: () -> T): T {
    Trace.beginSection(if (name.length > 127) name.substring(0, 127) else name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}
