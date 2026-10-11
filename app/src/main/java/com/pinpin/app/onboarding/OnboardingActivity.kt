package com.pinpin.app.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.net.toUri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pinpin.app.R
import com.pinpin.app.a11y.PinAccessibilityService
import com.pinpin.app.ball.BallService
import com.pinpin.app.data.PinRepository
import com.pinpin.app.overlay.PinOverlayService
import com.pinpin.app.ui.theme.PinPinTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * First-launch wizard. Walks the user through granting the three
 * permissions the ball needs:
 *
 *  1. Notification (Android 13+) — for the foreground-service
 *     notification that keeps the ball alive.
 *  2. Overlay (SYSTEM_ALERT_WINDOW) — for drawing the ball on the
 *     screen.
 *  3. Accessibility — for taking screenshots without the
 *     "share this screen" system dialog. We use
 *     [android.accessibilityservice.AccessibilityService.takeScreenshot]
 *     (API 30+) which is the same trick fooView (FV 悬浮球) uses.
 *
 * Why a wizard rather than per-feature requests: SYSTEM_ALERT_WINDOW
 * and Accessibility are both special permissions that have to be
 * granted in the system Settings app; bundling everything into a
 * single wizard makes the path clear and gives the user a "done"
 * moment before the ball appears.
 */
@AndroidEntryPoint
class OnboardingActivity : ComponentActivity() {

    @Inject lateinit var pinRepository: PinRepository

    private val notificationLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            triggerRefresh()
        }

    private val overlayLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            triggerRefresh()
        }

    private var refreshTick by mutableIntStateOf(0)

    private fun triggerRefresh() { refreshTick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PinPinTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .systemBarsPadding(),
                ) {
                    // Auto-skip to ball launch if everything is
                    // already granted.
                    LaunchedEffect(refreshTick) {
                        if (allGranted()) {
                            launchBallAndFinish()
                        }
                    }
                    WizardContent(
                        refreshTick = refreshTick,
                        onRequestNotification = { requestNotification() },
                        onRequestOverlay = { requestOverlay() },
                        onRequestAccessibility = { requestAccessibility() },
                        onFinish = { launchBallAndFinish() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        triggerRefresh()
    }

    private fun allGranted(): Boolean {
        val notif = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        val overlay = Settings.canDrawOverlays(this)
        val a11y = PinAccessibilityService.isEnabled(this)
        return notif && overlay && a11y
    }

    private fun requestNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            triggerRefresh()
        }
    }

    private fun requestOverlay() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:$packageName".toUri(),
        )
        overlayLauncher.launch(intent)
    }

    private fun requestAccessibility() {
        startActivity(PinAccessibilityService.buildSettingsIntent())
    }

    private fun launchBallAndFinish() {
        // Order matters: PinOverlayService first so it is alive and
        // observing the pin list before BallService comes up and
        // possibly triggers a capture. Both must be started from
        // an Activity context (not Application.onCreate) to avoid
        // ForegroundServiceStartNotAllowedException on Android 12+.
        try {
            if (Settings.canDrawOverlays(this)) {
                PinOverlayService.start(this)
                pinRepository.startOverlaySync()
                if (PinAccessibilityService.isEnabled(this)) BallService.start(this)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.widget.Toast.makeText(
                this, "悬浮服务暂不可用：${error.message}", android.widget.Toast.LENGTH_LONG,
            ).show()
        }
        startActivity(Intent(this, com.pinpin.app.ui.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }
}

@Composable
private fun WizardContent(
    refreshTick: Int,
    onRequestNotification: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current

    val notifGranted = remember(refreshTick) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    }
    val overlayGranted = remember(refreshTick) { Settings.canDrawOverlays(context) }
    val a11yGranted = remember(refreshTick) { PinAccessibilityService.isEnabled(context) }

    // Step 0 = welcome, 1..3 = permission steps, 4 = done.
    var step by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(step, notifGranted, overlayGranted, a11yGranted) {
        // Auto-advance when current step is granted.
        when (step) {
            1 -> if (notifGranted) step = 2
            2 -> if (overlayGranted) step = 3
            3 -> if (a11yGranted) step = 4
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        StepIndicator(current = step, total = 4)
        androidx.compose.material3.TextButton(onClick = onFinish) {
            Text("暂不设置，浏览首页")
        }
        when (step) {
            0 -> WelcomeStep(onNext = { step = if (notifGranted) 2 else 1 })
            1 -> PermissionStep(
                title = stringResource(R.string.onboarding_step_notification_title),
                body = stringResource(R.string.onboarding_step_notification_body),
                granted = notifGranted,
                actionLabel = stringResource(R.string.onboarding_step_notification_action),
                onAction = onRequestNotification,
                onSkip = { step = 2 },
            )
            2 -> PermissionStep(
                title = stringResource(R.string.onboarding_step_overlay_title),
                body = stringResource(R.string.onboarding_step_overlay_body),
                granted = overlayGranted,
                actionLabel = stringResource(R.string.onboarding_step_overlay_action),
                onAction = onRequestOverlay,
                onSkip = { step = 3 },
            )
            3 -> PermissionStep(
                title = stringResource(R.string.onboarding_step_a11y_title),
                body = stringResource(R.string.onboarding_step_a11y_body),
                granted = a11yGranted,
                actionLabel = stringResource(R.string.onboarding_step_a11y_action),
                onAction = onRequestAccessibility,
                onSkip = { step = 4 },
            )
            4 -> DoneStep(onFinish = onFinish)
        }
    }
}

@Composable
private fun StepIndicator(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(total) { i ->
            val active = i <= current
            Box(
                modifier = Modifier
                    .size(if (active) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
            )
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(40.dp))
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(stringResource(R.string.onboarding_welcome_action))
        }
    }
}

@Composable
private fun PermissionStep(
    title: String,
    body: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (granted)
                    MaterialTheme.colorScheme.surface
                else
                    MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (granted) Icons.Filled.Check else Icons.Filled.Close,
                    contentDescription = null,
                    tint = if (granted) Color(0xFF7CD992) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (granted)
                        stringResource(R.string.onboarding_perm_granted)
                    else
                        stringResource(R.string.onboarding_perm_denied),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (!granted) {
            Button(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(actionLabel)
            }
            androidx.compose.material3.TextButton(onClick = onSkip) {
                Text("暂时跳过")
            }
        } else {
            Button(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("继续")
            }
        }
    }
}

@Composable
private fun DoneStep(onFinish: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.onboarding_done_title),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onboarding_done_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(40.dp))
        Button(
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(stringResource(R.string.onboarding_done_action))
        }
    }
}
