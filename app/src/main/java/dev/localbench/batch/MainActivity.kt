package dev.localbench.batch

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.bind(applicationContext)
        ThemeStore.init(applicationContext)
        setContent {
            val mode = ThemeStore.mode
            val systemDark = isSystemInDarkTheme()
            val dark = mode.resolveDark(systemDark)
            val view = androidx.compose.ui.platform.LocalView.current
            LaunchedEffect(dark) {
                window.setBackgroundDrawable(
                    ColorDrawable(if (dark) WINDOW_DARK else WINDOW_LIGHT)
                )
                val c = WindowCompat.getInsetsController(window, view)
                c.isAppearanceLightStatusBars = !dark
                c.isAppearanceLightNavigationBars = !dark
            }
            AppTheme(mode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BatchApp()
                }
            }
        }
    }
}

@Composable
fun BatchApp() {
    val ctx = LocalContext.current
    val st by Session.state.collectAsState()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var exitAsk by remember { mutableStateOf(false) }
    var zoomList by remember { mutableStateOf<List<Shot>>(emptyList()) }
    var zoomIndex by remember { mutableIntStateOf(0) }
    var focusDir by remember { mutableStateOf<String?>(null) }
    var galleryKey by remember { mutableIntStateOf(0) }
    var logOpen by remember { mutableStateOf(false) }
    var updateOpen by remember { mutableStateOf(false) }
    var noticeOpen by remember { mutableStateOf(false) }
    var noticeUnread by remember { mutableStateOf(false) }
    var lastZoom by remember { mutableStateOf<List<Shot>>(emptyList()) }

    LaunchedEffect(Unit) {
        Session.init(ctx)
        noticeUnread = !NoticeStore.seen(ctx)
    }
    LaunchedEffect(zoomList) { if (zoomList.isNotEmpty()) lastZoom = zoomList }
    LaunchedEffect(st.batch) { if (st.batch != null) galleryKey++ }

    BackHandler {
        when {
            // 一层一层退：全屏图 → 更新中心/公告/更新日志 → 非转换页回转换页 → 根页才问退不退出
            zoomList.isNotEmpty() -> zoomList = emptyList()
            updateOpen -> updateOpen = false
            noticeOpen -> noticeOpen = false
            logOpen -> logOpen = false
            st.running && tab == 0 -> toast(ctx, "正在处理 ${st.doneCount}/${st.totalCount} 张，先别退")
            tab != 0 -> tab = 0
            else -> exitAsk = true
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Build, contentDescription = null) },
                    label = { Text("转换") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1; galleryKey++ },
                    icon = { Icon(Icons.Filled.List, contentDescription = null) },
                    label = { Text("成品") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("设置") }
                )
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            // 三个页签之间淡入 + 轻微上浮，不再硬切
            AnimatedContent(
                targetState = tab,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(tween(280)) { it / 28 }) togetherWith
                        fadeOut(tween(150))
                },
                label = "tab"
            ) { t ->
                when (t) {
                    0 -> ConvertScreen(
                        st = st,
                        showNoticeDot = noticeUnread,
                        onNotice = {
                            noticeOpen = true
                            NoticeStore.markSeen(ctx)
                            noticeUnread = false
                        },
                        onPicked = { uris -> Session.setPicked(ctx, uris) },
                        onPreset = { p -> Session.setPreset(ctx, p) },
                        onPrefix = { Session.setPrefix(it) },
                        onStart = { Session.start(ctx) },
                        onClear = { Session.clearPicked(ctx) },
                        onUndo = { b -> Session.undo(ctx, b); galleryKey++ },
                        onBrowse = { dir -> focusDir = dir; tab = 1; galleryKey++ }
                    )

                    1 -> GalleryScreen(
                        focus = focusDir,
                        refreshKey = galleryKey,
                        onOpen = { list, idx -> zoomList = list; zoomIndex = idx }
                    )

                    else -> SettingsScreen(
                        onOpenLog = { logOpen = true },
                        onChanged = { galleryKey++ },
                        onOpenUpdate = { updateOpen = true }
                    )
                }
            }
        }
    }

    AnimatedVisibility(
        visible = zoomList.isNotEmpty(),
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(150))
    ) {
        ImageViewer(
            if (zoomList.isNotEmpty()) zoomList else lastZoom,
            zoomIndex,
            onSaved = { galleryKey++ }
        ) { zoomList = emptyList() }
    }

    AnimatedVisibility(
        visible = updateOpen,
        enter = fadeIn(tween(220)) + slideInVertically(tween(300)) { it / 14 },
        exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { it / 20 }
    ) {
        UpdateScreen { updateOpen = false }
    }

    AnimatedVisibility(
        visible = noticeOpen,
        enter = fadeIn(tween(220)) + slideInVertically(tween(300)) { it / 14 },
        exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { it / 20 }
    ) {
        NoticeScreen { noticeOpen = false }
    }

    AnimatedVisibility(
        visible = logOpen,
        enter = fadeIn(tween(220)) + slideInVertically(tween(300)) { it / 14 },
        exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { it / 20 }
    ) {
        ChangelogScreen { logOpen = false }
    }

    if (exitAsk) ExitDialog(st) { exitAsk = false }
}

@Composable
fun ExitDialog(st: Session.State, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("确定退出？") },
        text = {
            Text(
                when {
                    st.running -> "正在处理 ${st.doneCount}/${st.totalCount} 张，退出会中断这一批。"
                    st.picked.isNotEmpty() -> "已选 ${st.picked.size} 张图。退出后下次打开会自动恢复这些选择，但当前这轮的进度会丢。"
                    else -> "退出后下次打开还是回到这里，输出位置和设置都会保留。"
                }
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onCancel()
                (ctx as? Activity)?.finish()
            }) { Text("退出") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } }
    )
}

@Composable
fun ConvertScreen(
    st: Session.State,
    showNoticeDot: Boolean,
    onNotice: () -> Unit,
    onPicked: (List<Uri>) -> Unit,
    onPreset: (Preset) -> Unit,
    onPrefix: (String) -> Unit,
    onStart: () -> Unit,
    onClear: () -> Unit,
    onUndo: (Batch) -> Unit,
    onBrowse: (String) -> Unit
) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) onPicked(uris) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text("极简批处理", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "原图只读，新图写进 ${Store.where(ctx)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                TextButton(onClick = onNotice) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("公告")
                }
                if (showNoticeDot) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 8.dp)
                            .size(7.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Preset.entries.forEach { p ->
                FilterChip(
                    selected = st.preset == p,
                    onClick = { onPreset(p) },
                    enabled = !st.running,
                    label = { Text(p.label) }
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            when (st.preset) {
                Preset.WECHAT -> "长边 1920 · JPEG q82 · 发微信不糊"
                Preset.ARCHIVE -> "保持原尺寸 · JPEG q90 · 只把 HEIC 换成 JPG"
                Preset.SOCIAL -> "中心裁方 1080 · q85 · 头像/种草图"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(10.dp))
        val pickSrc = remember { MutableInteractionSource() }
        val startSrc = remember { MutableInteractionSource() }
        val previewRatio by animateFloatAsState(
            targetValue = if (st.previewTotal == 0) 0f
            else st.previewDone.toFloat() / st.previewTotal.toFloat(),
            animationSpec = tween(320),
            label = "previewProgress"
        )
        val batchRatio by animateFloatAsState(
            targetValue = if (st.totalCount == 0) 0f
            else st.doneCount.toFloat() / st.totalCount.toFloat(),
            animationSpec = tween(320),
            label = "batchProgress"
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { picker.launch(arrayOf("image/*")) },
                enabled = !st.running,
                interactionSource = pickSrc,
                modifier = Modifier.weight(1f).pressScale(pickSrc)
            ) {
                Text(if (st.picked.isEmpty()) "选择图片" else "重选（${st.picked.size} 张）")
            }
            Button(
                onClick = onStart,
                enabled = st.picked.isNotEmpty() && !st.running,
                interactionSource = startSrc,
                modifier = Modifier.weight(1f).pressScale(startSrc)
            ) { Text("开始处理") }
        }

        if (st.picked.isNotEmpty() && !st.running && st.batch == null) {
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = st.prefix,
                onValueChange = onPrefix,
                singleLine = true,
                label = { Text("重命名前缀（可留空）") },
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = onClear) { Text("清空已选") }
        }

        if (st.previewBusy) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { previewRatio },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(2.dp))
            Text("生成预览 ${st.previewDone} / ${st.previewTotal}", style = MaterialTheme.typography.bodySmall)
        }

        if (st.batch == null && st.previews.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(st.previews) { p -> PreviewCard(p) }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "左：原图　右：处理后的样子（按当前场景真跑一遍，未写盘）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (st.running) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { batchRatio },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "处理中 ${st.doneCount} / ${st.totalCount}　可以切到别的页，不会中断",
                style = MaterialTheme.typography.bodySmall
            )
        }

        val batch = st.batch
        AnimatedVisibility(
            visible = batch != null,
            enter = fadeIn(tween(220)) + expandVertically(tween(300)),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(220))
        ) {
            Column {
                Spacer(Modifier.height(10.dp))
                if (batch != null) {
                    ResultCard(
                        batch = batch,
                        notice = st.notice,
                        onBrowse = { onBrowse(batch.preset.label) },
                        onUndo = { onUndo(batch) }
                    )
                }
            }
        }

        if (batch == null) {
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(st.picked) { p -> PickedRow(p, st.preset) }
            }
        }
    }
}

@Composable
fun PreviewCard(p: BatchEngine.Preview) {
    Card(Modifier.width(236.dp)) {
        Column(Modifier.padding(8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThumbBox(p.beforeThumb, "原")
                Text("→", style = MaterialTheme.typography.bodySmall)
                ThumbBox(p.afterThumb, "后")
            }
            Spacer(Modifier.height(6.dp))
            Text("${p.outW}×${p.outH} · 预计 ${mb(p.estKb)}", style = MaterialTheme.typography.bodySmall)
            Text(
                p.picked.name,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ResultCard(batch: Batch, notice: String, onBrowse: () -> Unit, onUndo: () -> Unit) {
    var expanded by remember(batch) { mutableStateOf(false) }
    var askDelete by remember(batch) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("${batch.preset.label} · 完成", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "成功 ${batch.ok.size}/${batch.items.size} · 用时 %.1fs".format(batch.wallMs / 1000.0),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "原 ${mb(batch.srcTotalKb)} → 现 ${mb(batch.outTotalKb)} · 省 ${mb(batch.savedKb)}（${batch.savedPct}%）",
                style = MaterialTheme.typography.bodyMedium
            )
            if (notice.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onBrowse, modifier = Modifier.weight(1f)) { Text("看这批图") }
                OutlinedButton(onClick = { askDelete = true }, modifier = Modifier.weight(1f)) { Text("删除这批") }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(200)) + expandVertically(tween(260)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(200))
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 210.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp)
                ) {
                    batch.items.forEach { d -> DoneRow(d) }
                }
            }
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text(if (expanded) "收起每张明细" else "展开每张明细（${batch.items.size} 张）")
            }
        }
    }
    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("删除这一批？") },
            text = {
                Text(
                    "会删掉这次新生成的 ${batch.ok.size} 张图，原图不动。" +
                        "删完系统相册里也会一起消失；以后想留就得重新处理一遍。"
                )
            },
            confirmButton = {
                TextButton(onClick = { askDelete = false; onUndo() }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("取消") } }
        )
    }
}

@Composable
fun PickedRow(p: Picked, preset: Preset) {
    val (ow, oh) = BatchEngine.expectedOut(p, preset)
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${p.w}×${p.h} → ${ow}×${oh} · ${mb(p.sizeKb)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        HorizontalDivider()
    }
}

@Composable
fun DoneRow(d: Done) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(d.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${d.outW}×${d.outH} · ${mb(d.srcKb)} → ${mb(d.outKb)} · ${d.ms}ms",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        d.error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider()
    }
}








