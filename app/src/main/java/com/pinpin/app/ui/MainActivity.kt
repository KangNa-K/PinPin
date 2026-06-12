package com.pinpin.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.pinpin.app.a11y.PinAccessibilityService
import com.pinpin.app.ball.BallService
import com.pinpin.app.data.PinRepository
import com.pinpin.app.onboarding.OnboardingActivity
import com.pinpin.app.overlay.PinOverlayService
import com.pinpin.app.ui.theme.PinPinTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Main entry activity. Hosts the Compose UI and is the launch target
 * from the home screen launcher icon. The actual floating pins are
 * owned by [com.pinpin.app.overlay.PinOverlayService], not by this
 * activity — closing the app does not remove them.
 *
 * First-launch routing: if the user has not yet completed the
 * onboarding wizard (and therefore does not have the floating ball
 * running), this activity forwards them to [OnboardingActivity] instead
 * of rendering the home screen.
 *
 * Also handles inbound [Intent.ACTION_SEND] requests from other apps
 * (gallery, browser, ...). The view model consumes the shared URI and
 * inserts a new pin via [com.pinpin.app.data.PinRepository].
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var pinRepository: PinRepository

    private val viewModel: HomeViewModel by lazy {
        ViewModelProvider(this, defaultViewModelProviderFactory)[HomeViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Forward to onboarding if any of the three required
        // permissions is missing.
        if (!allReady()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        // Re-launch PinOverlayService in case it was killed by the
        // OS under memory pressure while we were backgrounded. The
        // service is idempotent on a hot start (it just rebinds the
        // existing pin list) so this is cheap. Doing it here — and
        // not in Application.onCreate — sidesteps the
        // ForegroundServiceStartNotAllowedException thrown on
        // Android 12+ when a process tries to startForegroundService
        // during cold start.
        PinOverlayService.start(this)
        pinRepository.startOverlaySync()
        // Also (re)launch BallService. OnboardingActivity starts it
        // the first time, but if the user has since updated the app
        // (and force-stop killed both services), or simply hasn't
        // opened the app since the last boot, the ball won't be on
        // screen until they do. This keeps the ball running any time
        // the user is in the app. start() is idempotent: the service
        // is sticky and a hot start is a no-op.
        BallService.start(this)

        enableEdgeToEdge()
        setContent {
            PinPinTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(viewModel = viewModel)
                }
            }
        }
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun allReady(): Boolean {
        val notif = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val overlay = android.provider.Settings.canDrawOverlays(this)
        val a11y = PinAccessibilityService.isEnabled(this)
        android.util.Log.d("MainActivity", "allReady: notif=$notif overlay=$overlay a11y=$a11y")
        return notif && overlay && a11y
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_SEND) return
        if (intent.type?.startsWith("image/") != true) return

        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
        if (uri == null) return

        viewModel.addPinFromUri(uri, "share")
    }
}
