package com.pinpin.app.capture

object CaptureProtocol {
    const val RESULT = "com.pinpin.app.capture.RESULT"
    const val HIDE = "com.pinpin.app.capture.HIDE"
    const val HIDDEN = "com.pinpin.app.capture.HIDDEN"
    const val RESTORE = "com.pinpin.app.capture.RESTORE"
    const val CANCEL = "com.pinpin.app.capture.CANCEL"
    const val SAVING = "com.pinpin.app.capture.SAVING"
    const val ID = "request_id"
    const val ERROR = "error"
    const val SUCCESS = "success"
    const val ACTION = "capture_action"
    const val HISTORY_PATH = "history_path"
    const val ACTION_PIN = "pin"
    const val ACTION_COPY = "copy"
    const val ACTION_SHARE = "share"
    const val ACTION_SAVE = "save"
    const val ACTION_ANNOTATE = "annotate"
    const val ACTION_CARRY = "carry"
    const val ACTION_AUTO_CARRY = "auto_carry"
    const val SEED_X = "seed_x"
    const val SEED_Y = "seed_y"
    const val BOUNDS = "captured_bounds"
    const val TIMEOUT_MS = 15_000L
    fun validId(id: String?): Boolean =
        id != null && id.length in 1..64 && id.all { it.isLetterOrDigit() || it == '-' }
}

/** Main-thread owned lease; a cancelled worker retains it until completion. */
class CaptureGate {
    var activeId: String? = null
        private set
    fun acquire(id: String): Boolean {
        if (!CaptureProtocol.validId(id) || activeId != null) return false
        activeId = id
        return true
    }
    fun release(id: String): Boolean {
        if (activeId != id) return false
        activeId = null
        return true
    }
}
