package studio.cluvex.aether.transport

import studio.cluvex.aether.core.DiagnosticsLog

internal object ConnectionLog {
    fun record(message: String) = DiagnosticsLog.i("Transport", message)
}
