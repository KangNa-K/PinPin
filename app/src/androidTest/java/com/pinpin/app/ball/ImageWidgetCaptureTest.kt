package com.pinpin.app.ball

import android.app.UiAutomation
import android.content.*
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.pinpin.app.a11y.PinAccessibilityService
import com.pinpin.app.capture.CaptureProtocol as P
import com.pinpin.app.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ImageWidgetCaptureTest {
    @Test fun suppliedWhiteMarginArtworkUsesWidgetBoundsNotCaptionPixels() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val automation = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val oldServices = shell("settings get secure enabled_accessibility_services")
        val oldEnabled = shell("settings get secure accessibility_enabled")
        val result = CountDownLatch(1)
        var response: Intent? = null
        val id = "widget-crop-${SystemClock.uptimeMillis()}"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getStringExtra(P.ID) != id) return
                if (intent.action == P.HIDE) context.sendBroadcast(Intent(P.HIDDEN).setPackage(context.packageName).putExtra(P.ID, id))
                else if (intent.action == P.RESULT) { response = intent; result.countDown() }
            }
        }
        context.registerReceiver(receiver, IntentFilter(P.RESULT).apply { addAction(P.HIDE) }, Context.RECEIVER_NOT_EXPORTED)
        var activity: MainActivity? = null
        val expected = Rect()
        val artwork = inst.context.assets.open("white_margin_artwork.png").use(BitmapFactory::decodeStream)
        try {
            val service = "${context.packageName}/com.pinpin.app.a11y.PinAccessibilityService"
            shell("settings put secure enabled_accessibility_services $service")
            shell("settings put secure accessibility_enabled 1")
            activity = inst.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            lateinit var image: ImageView
            inst.runOnMainSync {
                val frame = FrameLayout(activity!!).apply { setBackgroundColor(Color.WHITE) }
                image = ImageView(activity!!).apply { setImageBitmap(artwork); importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES }
                frame.addView(image, FrameLayout.LayoutParams(480, 642).apply { leftMargin = 30; topMargin = 100 })
                frame.addView(TextView(activity!!).apply { text = "Caption and actions must not be included" },
                    FrameLayout.LayoutParams(480, 100).apply { leftMargin = 30; topMargin = 745 })
                activity!!.setContentView(frame)
            }
            SystemClock.sleep(1200)
            inst.runOnMainSync {
                val origin = IntArray(2)
                image.getLocationOnScreen(origin)
                expected.set(origin[0], origin[1], origin[0] + image.width, origin[1] + image.height)
                assertEquals(expected, PinAccessibilityService.current()!!.imageBoundsAt(expected.centerX(), expected.centerY()))
            }
            context.sendBroadcast(Intent(PinAccessibilityService.ACTION_CAPTURE_SCREENSHOT).setPackage(context.packageName)
                .putExtra(P.ID, id).putExtra(P.ACTION, P.ACTION_AUTO_CARRY)
                .putExtra(P.SEED_X, expected.centerX()).putExtra(P.SEED_Y, expected.centerY())
                .putExtra("rect_left", 0).putExtra("rect_top", 0).putExtra("rect_right", 1).putExtra("rect_bottom", 1))
            assertTrue(result.await(10, TimeUnit.SECONDS))
            assertTrue(response?.getStringExtra(P.ERROR), response!!.getBooleanExtra(P.SUCCESS, false))
            @Suppress("DEPRECATION")
            assertEquals(expected, response!!.getParcelableExtra<Rect>(P.BOUNDS))
            val cropped = BitmapFactory.decodeFile(response!!.getStringExtra(P.HISTORY_PATH))
            try {
                assertEquals(480, cropped.width)
                assertEquals(642, cropped.height)
                assertTrue("Original white margin was lost", Color.red(cropped.getPixel(240, 20)) > 245)
            } finally { cropped.recycle() }
        } finally {
            inst.runOnMainSync { activity?.finish() }
            artwork.recycle()
            context.unregisterReceiver(receiver)
            shell(if (oldServices == "null") "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services $oldServices")
            shell(if (oldEnabled == "null") "settings delete secure accessibility_enabled" else "settings put secure accessibility_enabled $oldEnabled")
        }
    }
}
