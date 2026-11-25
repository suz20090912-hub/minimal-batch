package dev.localbench.batch

import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * 更新中心：检查更新 / 镜像站测速 / 代理。
 * 只在用户点「获取更新」时联网，且必须先确认。
 */
@Composable
fun UpdateScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler { onClose() }

    val currentName = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    val currentCode = remember { versionCodeOf(ctx) }

    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var stage by remember { mutableIntStateOf(0) }      // 1 连镜像 2 读版本 3 对比
    var err by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<ReleaseInfo?>(null) }
    var upToDate by remember { mutableStateOf(false) }
    var viaMirror by remember { mutableStateOf("") }

    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var apkFile by remember { mutableStateOf<File?>(null) }

    var mode by remember { mutableStateOf(Update.mode(ctx)) }
    var selectedId by remember { mutableStateOf(Update.mirrorId(ctx)) }
    var customUrl by remember { mutableStateOf(Update.customUrl(ctx)) }
    var pingMs by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var testingId by remember { mutableStateOf<String?>(null) }
    var proxyOn by remember { mutableStateOf(Update.proxyOn(ctx)) }
    var proxyHost by remember { mutableStateOf(Update.proxyHost(ctx)) }
    var proxyPort by remember { mutableStateOf(Update.proxyPort(ctx).toString()) }

    fun testOne(m: Mirror) {
        if (testingId != null) return
        if (m.url.isBlank()) {
            toast(ctx, "这个镜像还没填地址")
            return
        }
        testingId = m.id
        scope.launch {
            val r = runCatching { Update.ping(m.url, Update.proxy(ctx)) }
            pingMs = pingMs + (m.id to (r.getOrNull() ?: -1L))
            testingId = null
        }
    }

    fun runCheck() {
        if (busy) return
        busy = true
        err = null
        release = null
        upToDate = false
        apkFile = null
        progress = 0
        stage = 1
        scope.launch {
            val px = Update.proxy(ctx)
            try {
                val all = Update.allMirrors(ctx)
                val candidates = if (mode == "manual") all.filter { it.id == selectedId } else all
                if (candidates.isEmpty()) throw IOException("没有可用的镜像站")
                var best: Mirror? = null
                var bestMs = Long.MAX_VALUE
                var last: Throwable? = null
                var map = pingMs
                // 并发测速：一条挂了不拖住别的，镜像多也不会等很久
                val results = coroutineScope {
                    candidates.filter { it.url.isNotBlank() }.map { m ->
                        async {
                            try {
                                Triple(m, Update.ping(m.url, px), null)
                            } catch (t: Throwable) {
                                Triple(m, -1L, t)
                            }
                        }
                    }.awaitAll()
                }
                results.forEach { (m, ms, t) ->
                    if (ms >= 0) {
                        map = map + (m.id to ms)
                        if (ms < bestMs) {
                            bestMs = ms
                            best = m
                        }
                    } else {
                        map = map + (m.id to -1L)
                        if (t != null) last = t
                    }
                }
                pingMs = map
                val pick = best ?: throw (last ?: IOException("所有镜像站都连不上"))
                viaMirror = "${pick.name} · ${bestMs}ms"
                stage = 2
                val info = Update.fetch(pick.url, px)
                stage = 3
                if (info.versionCode > currentCode) {
                    release = info
                } else {
                    upToDate = true
                }
                stage = 0
            } catch (t: Throwable) {
                err = Update.friendly(t)
                stage = 0
            } finally {
                busy = false
            }
        }
    }

    fun startDownload(info: ReleaseInfo) {
        if (downloading) return
        if (info.apkUrl.isBlank()) {
            toast(ctx, "版本信息里没有下载地址")
            return
        }
        downloading = true
        err = null
        progress = 0
        scope.launch {
            try {
                val f = File(ctx.cacheDir, "mb-${info.versionName}.apk")
                Update.download(info.apkUrl, f, Update.proxy(ctx)) { p -> progress = p }
                apkFile = f
            } catch (t: Throwable) {
                err = "下载失败：" + Update.friendly(t)
            } finally {
                downloading = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 吞掉所有触摸，避免穿透到下面的 tab 页
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { }
        )
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(6.dp))
            Text("检查更新", style = MaterialTheme.typography.headlineSmall)
        }

        Spacer(Modifier.height(12.dp))

        // ---------- 当前版本 + 获取更新 ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("当前版本", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "v$currentName（versionCode $currentCode）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { confirm = true },
                    enabled = !busy && !downloading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "检查中…" else "获取更新") }
                Spacer(Modifier.height(6.dp))
                Text(
                    "只有点这个按钮才会联网，平时不做任何检查。会先连镜像站，再读版本信息。",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---------- 运行状态 / 结果 ----------
        if (busy || err != null || upToDate || release != null) {
            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    when {
                        busy -> {
                            Text("正在检查…", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(8.dp))
                            StepRow("连接镜像站", stage, 1)
                            StepRow("读取版本信息", stage, 2)
                            StepRow("对比版本", stage, 3)
                        }

                        err != null -> {
                            Text("没检查成功", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                err ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "可以换个镜像站，或者到下面配代理再试。",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        upToDate -> {
                            Text("已经是最新版本", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "v$currentName，不用更新。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        else -> {
                            val r = release
                            if (r != null) {
                                Text("发现新版本 v${r.versionName}", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "versionCode ${r.versionCode}" +
                                        if (r.sizeBytes > 0) " · ${fmtSize(r.sizeBytes)}" else "" +
                                        if (viaMirror.isNotBlank()) " · 来自 $viaMirror" else "",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (r.notes.isNotBlank()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        r.notes,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                when {
                                    apkFile != null -> {
                                        Button(
                                            onClick = {
                                                try {
                                                    Update.install(ctx, apkFile!!)
                                                } catch (t: Throwable) {
                                                    toast(ctx, "打不开安装器：${t.message}")
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("安装已下载的安装包") }
                                    }

                                    downloading -> {
                                        LinearProgressIndicator(
                                            progress = { progress / 100f },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            "下载中 $progress%",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    else -> {
                                        Button(
                                            onClick = { startDownload(r) },
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("下载新版安装包") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---------- 镜像站 ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("镜像站", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "更新信息放在 GitHub 上，国内直连不一定通。这里内置几条加速线路，" +
                        "自动=逐个测速挑最快的，手动=只用你选的那条。",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == "auto",
                        onClick = { mode = "auto"; Update.setMode(ctx, "auto") },
                        label = { Text("自动选最快") }
                    )
                    FilterChip(
                        selected = mode == "manual",
                        onClick = { mode = "manual"; Update.setMode(ctx, "manual") },
                        label = { Text("手动指定") }
                    )
                }
                Spacer(Modifier.height(6.dp))

                val mirrors = Update.allMirrors(ctx)
                mirrors.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (mode == "manual") {
                            RadioButton(
                                selected = selectedId == m.id,
                                onClick = {
                                    selectedId = m.id
                                    Update.setMirrorId(ctx, m.id)
                                }
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                when {
                                    m.url.isBlank() -> "未填地址"
                                    pingMs[m.id] == null -> "未测速"
                                    (pingMs[m.id] ?: -1L) < 0 -> "连不上"
                                    else -> "${pingMs[m.id]}ms"
                                },
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = if ((pingMs[m.id] ?: 0L) < 0) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = { testOne(m) },
                            enabled = testingId == null
                        ) {
                            Text(if (testingId == m.id) "测速中…" else "测速")
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = {
                        customUrl = it
                        Update.setCustomUrl(ctx, it)
                    },
                    singleLine = true,
                    label = { Text("自定义镜像的 version.json 地址") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "填了就会多出一条「自定义镜像」，地址要指向 version.json 本体。",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---------- 代理 ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("使用代理", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "只给更新检查用，处理图片不走网络。",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = proxyOn,
                        onCheckedChange = {
                            proxyOn = it
                            Update.setProxyOn(ctx, it)
                        }
                    )
                }
                if (proxyOn) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = proxyHost,
                            onValueChange = {
                                proxyHost = it
                                Update.setProxyHost(ctx, it)
                            },
                            singleLine = true,
                            label = { Text("主机") },
                            placeholder = { Text("127.0.0.1") },
                            modifier = Modifier.weight(1.6f)
                        )
                        OutlinedTextField(
                            value = proxyPort,
                            onValueChange = { v ->
                                proxyPort = v.filter { it.isDigit() }.take(5)
                                proxyPort.toIntOrNull()?.let { Update.setProxyPort(ctx, it) }
                            },
                            singleLine = true,
                            label = { Text("端口") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "HTTP 代理，常见是本机转发的 7890 / 1080。填错会连不上，关掉开关就恢复直连。",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("获取最新版本？") },
            text = {
                Text(
                    "接下来会联网：先连镜像站，再读版本信息，有新版才会下载。\n" +
                        "不会自己装，也不会后台上传任何东西。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    runCheck()
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("取消") }
            }
        )
    }
    }
}

@Composable
private fun StepRow(label: String, stage: Int, index: Int) {
    val (mark, color) = when {
        stage > index -> "✓" to MaterialTheme.colorScheme.primary
        stage == index -> "…" to MaterialTheme.colorScheme.onSurface
        else -> "·" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(mark, color = color, modifier = Modifier.width(22.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

private fun versionCodeOf(ctx: Context): Int = runCatching {
    val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
}.getOrDefault(0)

private fun fmtSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / 1024f / 1024f / 1024f)
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024f)
    else -> "$bytes B"
}



