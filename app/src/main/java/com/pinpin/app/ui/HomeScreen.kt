package com.pinpin.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.pinpin.app.R
import com.pinpin.app.ball.BallService
import com.pinpin.app.data.ScreenshotHistoryEntity
import com.pinpin.app.overlay.PinWindowManager
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home screen — the only screen for the MVP. Shows the current pin count,
 * a list of pinned image previews, and the action bar (add pin, pick from
 * gallery, request overlay permission, screen capture).
 *
 * State is read from [PinWindowManager.pins], which is a [kotlinx.coroutines.flow.StateFlow]
 * projection of the Room database. The home-screen view model
 * [HomeViewModel] is responsible for triggering the mutators (insert,
 * delete) on user actions.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pins by PinWindowManager.pins.collectAsState()
    val history by viewModel.history.collectAsState()

    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                ensureOverlayPermissionAsync(context) {
                    viewModel.addPinFromUri(uri, "photopicker")
                }
            }
        }
    }

    // The two-stage flow for picking a ball image: SAF picks the
    // source URI, then BallImageAdjustActivity shows a circular
    // preview that the user pans/zooms. We need TWO launchers
    // here: the second (ballImageAdjust) is launched from the
    // first's callback. Kotlin Composable ordering rules require
    // all rememberLauncherForActivityResult calls to happen in
    // declaration order, so we declare ballImageAdjust FIRST.
    val ballImageAdjustLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val data = result.data ?: return@rememberLauncherForActivityResult
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data.getParcelableExtra(
                com.pinpin.app.ball.BallImageAdjustActivity.EXTRA_RESULT_URI,
                Uri::class.java,
            )
        } else {
            @Suppress("DEPRECATION")
            data.getParcelableExtra(
                com.pinpin.app.ball.BallImageAdjustActivity.EXTRA_RESULT_URI,
            ) as? Uri
        }
        if (uri != null) {
            val scale = data.getFloatExtra(
                com.pinpin.app.ball.BallImageAdjustActivity.EXTRA_RESULT_SCALE, 1f,
            )
            val offsetX = data.getFloatExtra(
                com.pinpin.app.ball.BallImageAdjustActivity.EXTRA_RESULT_OFFSET_X, 0f,
            )
            val offsetY = data.getFloatExtra(
                com.pinpin.app.ball.BallImageAdjustActivity.EXTRA_RESULT_OFFSET_Y, 0f,
            )
            scope.launch {
                ensureOverlayPermissionAsync(context) {
                    // The ball service may not be running yet (e.g.
                    // the user skipped onboarding in a previous
                    // session). Start it first so the SET_BALL_IMAGE
                    // action has a target. start() is idempotent.
                    BallService.start(context)
                    BallService.setBallImage(context, uri, scale, offsetX, offsetY)
                }
            }
        }
    }

    // SAF (ACTION_OPEN_DOCUMENT) is used here instead of the photo
    // picker because we need a persistable URI permission — the ball
    // is a long-lived overlay that has to re-load the image every
    // time the service comes back, possibly days after the pick.
    // PickVisualMedia's grant is short-lived and not always
    // persistable across providers.
    val pickBallImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                ensureOverlayPermissionAsync(context) {
                    val intent = android.content.Intent(
                        context, com.pinpin.app.ball.BallImageAdjustActivity::class.java,
                    ).apply {
                        putExtra(
                            com.pinpin.app.ball.BallImageAdjustActivity.EXTRA_SOURCE_URI,
                            uri,
                        )
                    }
                    ballImageAdjustLauncher.launch(intent)
                }
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* ignore result; service will still run with whatever permission state */ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        AccessibilityStatusBanner()
        HomeHeader(count = pins.size)

        // History section takes whatever space is left between
        // the header, the (optional) active-pin list and the
        // action bar at the bottom — so the screenshot grid
        // fills the previously-empty middle of the screen
        // instead of being a one-row LazyRow.
        HistorySection(
            items = history,
            onRepin = { item ->
                ensureOverlayPermissionAsync(context) {
                    viewModel.repinFromHistory(item)
                }
            },
            onRepinMany = { items ->
                ensureOverlayPermissionAsync(context) {
                    viewModel.repinManyFromHistory(items)
                }
            },
            onDelete = { id -> viewModel.deleteHistory(id) },
            onDeleteMany = { ids -> viewModel.deleteHistories(ids) },
            modifier = Modifier.weight(1f, fill = true),
        )

        if (pins.isEmpty()) {
            EmptyState()
        } else {
            PinList(
                pins = pins,
                onClose = { id -> scope.launch { viewModel.removePin(id) } },
            )
        }

        HomeActionBar(
            onPickImage = {
                scope.launch {
                    ensureOverlayPermissionAsync(context) {
                        pickImageLauncher.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    }
                }
            },
            onPickBallImage = {
                scope.launch {
                    ensureOverlayPermissionAsync(context) {
                        // image/* — the user can pick any picture;
                        // we resize to a 2× ball on the service side.
                        pickBallImageLauncher.launch(arrayOf("image/*"))
                    }
                }
            },
            onClearBallImage = {
                BallService.start(context)
                BallService.clearBallImage(context)
            },
            onGrantOverlay = { openOverlaySettings(context) },
        )
    }
}


// ----------------------------------------------------------------
// Accessibility-status banner. Shown at the top of the home
// screen when the system lists our a11y service as enabled but
// the service process is not actually running. This is the
// post-crash silent state that Android does NOT auto-recover
// from: the user has to toggle the service in Settings
// (off, then on) for the system to bind it again. We surface
// the symptom here so they know what to do, and give them a
// one-tap deep-link into the a11y settings screen.
// ----------------------------------------------------------------
@Composable
private fun AccessibilityStatusBanner() {
    val context = LocalContext.current
    var isHealthy by remember { mutableStateOf(isA11yServiceHealthy(context)) }
    if (isHealthy) return

    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    // Re-check on tap, in case the user just
                    // toggled the service in Settings and
                    // came back.
                    isHealthy = isA11yServiceHealthy(context)
                    if (isHealthy) return@clickable
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS,
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "未启用无障碍服务",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "点击前往 设置 → 辅助功能 → 钉图，启用后可恢复截图功能",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Returns true if our a11y service is in a usable state.
 *
 * "Usable" means BOTH:
 *  1. The service is listed in Settings.Secure as enabled
 *     (the user has toggled it on at some point), AND
 *  2. The service process is currently running on the device.
 *
 * The case where (1) holds but (2) does not is exactly the
 * "crashed, system won't restart" failure mode that users
 * hit when their phone runs out of memory or the system
 * hard-kills the a11y process.
 */
private fun isA11yServiceHealthy(context: android.content.Context): Boolean {
    // We only need to answer ONE question here: "is the a11y
    // service enabled in the user's settings?". If the user
    // hasn't enabled it yet, we want the banner to nudge them
    // to do so. If they HAVE enabled it, the banner is useless
    // (and was previously noisy: it kept showing even when
    // the service was actually running, because
    // ActivityManager.runningAppProcesses is restricted to the
    // caller's own process on Android 8+ and could never see
    // our a11y process). If the process dies after that, the
    // failure mode the user actually notices is "screenshot
    // doesn't work", and BallService already surfaces that via
    // a Toast pointing at Settings.
    val am = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
        as android.view.accessibility.AccessibilityManager
    return am.getEnabledAccessibilityServiceList(
        android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
    ).any { it.resolveInfo.serviceInfo.packageName == context.packageName }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun HomeHeader(count: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (count == 0) {
                stringResource(R.string.home_subtitle_empty)
            } else {
                stringResource(R.string.home_count_format, count)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics {
                contentDescription = if (count == 0) {
                    "当前没有钉图"
                } else {
                    "当前共有 ${count} 张钉图"
                }
            },
        )
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.Layers,
                contentDescription = "暂无钉图",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(56.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "点下方按钮开始",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PinList(
    pins: List<PinWindowManager.PinState>,
    onClose: (String) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items = pins, key = { it.id }) { pin ->
            PinRow(pin = pin, onClose = { onClose(pin.id) })
        }
    }
}

@Composable
private fun PinRow(
    pin: PinWindowManager.PinState,
    onClose: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pin.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${pin.width.toInt()}×${pin.height.toInt()}  ·  ${pin.positionLabel()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (pin.locked) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = "已锁",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun HomeActionBar(
    onPickImage: () -> Unit,
    onPickBallImage: () -> Unit,
    onClearBallImage: () -> Unit,
    onGrantOverlay: () -> Unit,
) {
    // Single Material3 Card with 3 logical rows:
    //   1. Primary action (Filled tonal button, full width).
    //   2. Ball-image row: two compact tonal buttons side-by-side.
    //   3. Footer: low-emphasis text button.
    // Folding the 4 actions into a single card saves ~60dp of
    // vertical space versus the old stacked full-width column.
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Primary: pick image from gallery.
            FilledTonalButton(
                onClick = onPickImage,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Image,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.home_action_pick_image))
            }

            // Ball-image controls on one row, equal weight.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onPickBallImage,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Layers,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.home_action_pick_ball_image),
                        maxLines = 1,
                    )
                }
                FilledTonalButton(
                    onClick = onClearBallImage,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.home_action_reset_ball_image),
                        maxLines = 1,
                    )
                }
            }

            // Footer: secondary permission action as a centered text button.
            TextButton(
                onClick = onGrantOverlay,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_action_grant_overlay),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

private fun ensureOverlayPermissionAsync(context: Context, onGranted: () -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
        !Settings.canDrawOverlays(context)
    ) {
        openOverlaySettings(context)
        return
    }
    onGranted()
}

// ----------------------------------------------------------------
// History section — Material 3 photo grid with multi-select.
//
// Two visual modes:
//
//   NORMAL mode (default): a 3-column grid of thumbnails. Each
//   thumb shows the screenshot plus a small timestamp at the
//   bottom-left. A single tap re-pins the screenshot to the
//   screen; a long-press enters multi-select.
//
//   MULTI-SELECT mode: a TopAppBar slides down from the top
//   showing "已选 N / M" with a back arrow and a "全选" action.
//   The BottomAppBar slides up from the bottom with "删除" and
//   "全部重新钉" actions. Tapping a thumb in this mode toggles
//   its selection state. The mode auto-exits when the user
//   leaves the screen or taps the back arrow / 区 全选 action.
// ----------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun HistorySection(
    items: List<ScreenshotHistoryEntity>,
    onRepin: (ScreenshotHistoryEntity) -> Unit,
    onRepinMany: (List<ScreenshotHistoryEntity>) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteMany: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Multi-select state. The set of selected ids; null when
    // we're not in multi-select mode at all. Kept here in the
    // section so the selection state lives as long as the user
    // is on the home screen — leaving and returning clears it.
    var selectedIds by remember { mutableStateOf<Set<String>?>(null) }
    val inMultiSelect = selectedIds != null
    // Convenience: the subset of the list that is currently
    // selected, so the bottom bar's "全部重新钉" can pass
    // concrete entities to the caller.
    val selectedItems = remember(items, selectedIds) {
        selectedIds?.let { ids -> items.filter { it.id in ids } } ?: emptyList()
    }

    // [Column] instead of [Box] this time: the multi-select
    // TopAppBar lives ABOVE the grid, the BottomAppBar lives
    // BELOW it, and the grid expands to fill the middle via
    // weight(1f). The previous FrameLayout trick had no place
    // to mount the bottom bar cleanly.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        // Multi-select TopAppBar — slides in from the top of
        // the section when at least one item is selected.
        AnimatedVisibility(
            visible = inMultiSelect,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            val current = selectedIds ?: emptySet()
            TopAppBar(
                title = {
                    Text(
                        text = "已选 ${current.size} / ${items.size}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { selectedIds = null }) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "取消多选",
                        )
                    }
                },
                actions = {
                    val allSelected = current.size == items.size
                    TextButton(onClick = {
                        selectedIds = if (allSelected) emptySet() else items.map { it.id }.toSet()
                    }) {
                        Text(if (allSelected) "取消全选" else "全选")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }

        // The grid. Fills the middle (weight=1f) so the
        // thumbnails expand into whatever vertical space is
        // left between the active-pin list (or header) and the
        // bottom action bar.
        Column(modifier = Modifier
            .fillMaxWidth()
            .weight(1f, fill = true)) {
            // The header row — always visible, labels the section.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (items.isEmpty()) "暂无历史截图" else "历史截图 (${items.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (items.isNotEmpty()) {
                // 4:3 aspect-ratio thumbs in an ADAPTIVE grid with a
                // 90dp min cell — that puts 4-5 columns on phones
                // (depending on screen width) and gives us ~7-8
                // visible rows instead of the previous 1-2 with
                // GridCells.Fixed(3) + aspectRatio(1f). The lower
                // visual weight of smaller thumbs also makes the
                // "历史截图" section feel like a proper scrolling
                // list rather than a few big tiles dominating the
                // whole screen.
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 90.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items = items, key = { it.id }) { item ->
                        HistoryThumb(
                            item = item,
                            inMultiSelect = inMultiSelect,
                            isSelected = selectedIds?.contains(item.id) == true,
                            onSingleTap = {
                                if (inMultiSelect) {
                                    // Toggle selection.
                                    val current = selectedIds ?: emptySet()
                                    selectedIds = if (item.id in current) {
                                        current - item.id
                                    } else {
                                        current + item.id
                                    }
                                } else {
                                    // Normal mode: re-pin immediately.
                                    onRepin(item)
                                }
                            },
                            onLongPress = {
                                if (!inMultiSelect) {
                                    // Enter multi-select and seed
                                    // the selection with this item.
                                    selectedIds = setOf(item.id)
                                }
                            },
                        )
                    }
                }
            }
        }

        // Multi-select BottomAppBar — slides in from the bottom
        // when at least one item is selected. Mirrors M3's
        // standard multi-select list pattern (Files, Photos).
        AnimatedVisibility(
            visible = inMultiSelect,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            BottomAppBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp,
                actions = {
                    val current = selectedIds ?: emptySet()
                    IconButton(
                        onClick = {
                            onDeleteMany(current.toList())
                            selectedIds = null
                        },
                        enabled = current.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    IconButton(
                        onClick = {
                            onRepinMany(selectedItems)
                            selectedIds = null
                        },
                        enabled = current.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PushPin,
                            contentDescription = "全部重新钉",
                        )
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryThumb(
    item: ScreenshotHistoryEntity,
    inMultiSelect: Boolean,
    isSelected: Boolean,
    onSingleTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // M3 select-state styling: 2dp rounded outline in the
    // primary color when selected, transparent otherwise. The
    // outline is layered ON TOP of the thumbnail, so the image
    // stays visible — only the rim changes.
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "history-thumb-border",
    )
    Box(
        modifier = modifier
            // 4:3 thumbs are ~33% shorter than the previous 1:1
            // squares, so the grid packs more rows on screen and
            // the section no longer dominates the home screen.
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            // combinedClickable wires up BOTH the tap and the
            // long-press handlers with the correct Material
            // ripple / haptic feedback semantics. Use this
            // rather than `Modifier.clickable` because the
            // latter only fires on tap.
            .combinedClickable(
                onClick = onSingleTap,
                onLongClick = onLongPress,
            )
            .border(
                width = 2.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp),
            ),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(File(item.filePath))
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Bottom-left timestamp strip. M3-style: small monospaced
        // text on a translucent scrim so it stays legible against
        // any image color.
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            Text(
                text = formatTimestamp(item.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }

        // Selection check badge — M3 pattern: a filled circle
        // with a check inside. Only visible in multi-select mode.
        if (inMultiSelect) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else Color.Black.copy(alpha = 0.45f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "已选",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

private val historyDateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

private fun formatTimestamp(epochMs: Long): String =
    historyDateFormat.format(Date(epochMs))

private fun openOverlaySettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun requestNotificationPermissionIfNeeded(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<String>,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val granted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
    if (granted) return
    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
}
