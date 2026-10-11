package com.pinpin.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/** Used by both capture endpoints and the isolated instrumentation security tests. */
internal fun registerCaptureReceiver(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
}
