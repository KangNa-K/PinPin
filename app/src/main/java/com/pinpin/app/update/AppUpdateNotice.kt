package com.pinpin.app.update

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AppUpdateNotice(controller: AppUpdateController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.message) {
        state.message?.let {
            if (!state.noticeVisible) Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            controller.consumeMessage()
        }
    }
    val manifest = state.manifest ?: return
    if (!state.noticeVisible) return
    var ignore by remember(manifest.versionCode) { mutableStateOf(false) }
    val busy = state.downloading || state.preparing
    AlertDialog(
        onDismissRequest = { if (!busy) controller.later() },
        title = { Text("发现新版本 ${manifest.versionName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    manifest.notes.ifBlank { "功能优化与问题修复" },
                    Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                )
                if (state.downloading) {
                    LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("正在下载 ${state.progress}%")
                } else if (state.preparing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("正在校验安装包")
                }
                state.message?.let { Text(it) }
                if (!busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = ignore, onCheckedChange = { ignore = it })
                    Text("忽略此版本")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (state.readyApk != null) controller.installReady() else controller.downloadAndInstall()
            }) { Text(if (state.readyApk != null) "安装更新" else "下载更新") }
        },
        dismissButton = {
            TextButton(enabled = !state.preparing, onClick = {
                if (state.downloading) controller.cancelDownload()
                else if (ignore) controller.ignoreCurrentVersion() else controller.later()
            }) { Text(if (state.downloading) "取消下载" else "稍后") }
        },
    )
}
