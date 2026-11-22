package com.b3ther.app.transport

import com.b3ther.app.core.DiagnosticsLog

internal object ConnectionLog {
    fun record(message: String) = DiagnosticsLog.i("Transport", message)
}
