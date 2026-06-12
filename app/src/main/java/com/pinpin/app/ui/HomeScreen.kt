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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
                    // The ball service may not be running yet (e.g.
                    // the user skipped onboarding in a previous
                    // session). Start it first so the SET_BALL_IMAGE
                    // action has a target. start() is idempotent.
                    BallService.start(context)
                    BallService.setBallImage(context, uri)
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
        HomeHeader(count = pins.size)

        HistorySection(
            items = history,
            onRepin = { item ->
                ensureOverlayPermissionAsync(context) {
                    viewModel.repinFromHistory(item)
                }
            },
            onDelete = { id -> viewModel.deleteHistory(id) },
        )

        if (pins.isEmpty()) {
            EmptyState()
        } else {
            PinList(
                pins = pins,
                onClose = { id -> scope.launch { viewModel.removePin(id) } },
            )
        }

        Spacer(modifier = Modifier.weight(1f))
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
                contentDescription = null,
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
// History section — horizontal strip of historical screenshots.
// Tap a thumbnail to re-pin it onto the screen; long-press the
// close icon to delete it from history.
// ----------------------------------------------------------------
@Composable
private fun HistorySection(
    items: List<ScreenshotHistoryEntity>,
    onRepin: (ScreenshotHistoryEntity) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items = items, key = { it.id }) { item ->
                    HistoryThumb(
                        item = item,
                        onRepin = { onRepin(item) },
                        onDelete = { onDelete(item.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryThumb(
    item: ScreenshotHistoryEntity,
    onRepin: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(width = 120.dp, height = 90.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onRepin() },
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
        // Top-right delete badge.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable { onDelete() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "删除",
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
        // Bottom-left timestamp.
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        ) {
            Text(
                text = formatTimestamp(item.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
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
