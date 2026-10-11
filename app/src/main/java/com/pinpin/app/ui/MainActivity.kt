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
 * History remains available without special permissions. Shared images
 * are imported immediately; overlay services are restored in onResume.
 *
 * Also handles inbound [Intent.ACTION_SEND] requests from other apps
 * (gallery, browser, ...). The view model consumes the shared URI and
 * inserts a new pin via [com.pinpin.app.data.PinRepository].
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var shareRequestId = java.util.UUID.randomUUID().toString()

    @Inject lateinit var pinRepository: PinRepository

    private val viewModel: HomeViewModel by lazy {
        ViewModelProvider(this, defaultViewModelProviderFactory)[HomeViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shareRequestId = savedInstanceState?.getString("shareRequestId") ?: shareRequestId

        // Re-launch PinOverlayService in case it was killed by the
        // OS under memory pressure while we were backgrounded. The
        // service is idempotent on a hot start (it just rebinds the
        // existing pin list) so this is cheap. Doing it here — and
        // not in Application.onCreate — sidesteps the
        // ForegroundServiceStartNotAllowedException thrown on
        // Android 12+ when a process tries to startForegroundService
        // during cold start.
        // Also (re)launch BallService. OnboardingActivity starts it
        // the first time, but if the user has since updated the app
        // (and force-stop killed both services), or simply hasn't
        // opened the app since the last boot, the ball won't be on
        // screen until they do. This keeps the ball running any time
        // the user is in the app. start() is idempotent: the service
        // is sticky and a hot start is a no-op.

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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("shareRequestId", shareRequestId)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        (application as com.pinpin.app.PinApplication).appUpdateController.onForeground()
        if (!android.provider.Settings.canDrawOverlays(this)) return
        try {
            PinOverlayService.start(this)
            pinRepository.startOverlaySync()
            if (PinAccessibilityService.isEnabled(this)) BallService.start(this)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            viewModel.reportMessage("悬浮服务启动失败：${error.message ?: "请重试"}")
        }
    }

    override fun onPause() {
        (application as com.pinpin.app.PinApplication).appUpdateController.onBackground()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shareRequestId = java.util.UUID.randomUUID().toString()
        handleShareIntent(intent)
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
        if (uri == null) {
            viewModel.reportMessage("分享中没有可读取的图片")
            return
        }

        viewModel.importShare(shareRequestId, uri)
    }
}
