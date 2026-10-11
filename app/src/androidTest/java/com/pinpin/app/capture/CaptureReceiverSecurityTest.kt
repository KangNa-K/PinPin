package com.pinpin.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Disposable random-action receivers only: never starts a service, takes a screenshot,
 * opens a repository, or sends the application's real capture actions.
 */
@RunWith(AndroidJUnit4::class)
class CaptureReceiverSecurityTest {
    private fun receiver(events: LinkedBlockingQueue<String>) = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra(CaptureProtocol.ID)?.let(events::offer)
        }
    }

    @Test fun sameUidCanDeliverRequestIdToProtectedReceiver() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val action = "${context.packageName}.capture.test.${UUID.randomUUID()}"
        val events = LinkedBlockingQueue<String>()
        val receiver = receiver(events)
        registerCaptureReceiver(context, receiver, IntentFilter(action))
        try {
            context.sendBroadcast(Intent(action).setPackage(context.packageName)
                .putExtra(CaptureProtocol.ID, "same-uid"))
            assertEquals("same-uid", events.poll(3, TimeUnit.SECONDS))
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    @Test fun shellUidCannotSpoofRequestEvenWithPackageAndValidId() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val action = "${context.packageName}.capture.test.${UUID.randomUUID()}"
        val protectedEvents = LinkedBlockingQueue<String>()
        val controlEvents = LinkedBlockingQueue<String>()
        val protectedReceiver = receiver(protectedEvents)
        val controlReceiver = receiver(controlEvents)
        registerCaptureReceiver(context, protectedReceiver, IntentFilter(action))
        try {
            // An exported TEST-ONLY control proves the shell broadcast really arrived.
            ContextCompat.registerReceiver(context, controlReceiver, IntentFilter(action),
                ContextCompat.RECEIVER_EXPORTED)
            try {
                val command = "am broadcast --user current -a $action -p ${context.packageName} " +
                    "--es ${CaptureProtocol.ID} external-valid-id"
                ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand(command),
                ).bufferedReader().use { it.readText() }
                assertEquals("external-valid-id", controlEvents.poll(3, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                assertNull(protectedEvents.poll(500, TimeUnit.MILLISECONDS))
            } finally {
                context.unregisterReceiver(controlReceiver)
            }
        } finally {
            context.unregisterReceiver(protectedReceiver)
        }
    }
}
