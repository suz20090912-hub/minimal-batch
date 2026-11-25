package dev.localbench.batch

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
fun App() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BatchScreen()
        }
    }
}

private fun mb(kb: Int): String =
    if (kb >= 1024) "%.1fMB".format(kb / 1024.0) else "${kb}KB"

@Composable
fun BatchScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var picked by remember { mutableStateOf(emptyList<Picked>()) }
    var previews by remember { mutableStateOf(emptyList<BatchEngine.Preview>()) }
    var previewBusy by remember { mutableStateOf(false) }
    var previewDone by remember { mutableStateOf(0) }
    var previewTotal by remember { mutableStateOf(0) }

    var preset by remember { mutableStateOf(Preset.WECHAT) }
    var prefix by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var doneCount by remember { mutableStateOf(0) }
    var totalCount by remember { mutableStateOf(0) }
    var batch by remember { mutableStateOf<Batch?>(null) }
    var notice by remember { mutableStateOf("") }
    var showGallery by remember { mutableStateOf(false) }
    var zoomUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                picked = BatchEngine.inspectAll(ctx, uris)
                batch = null
                notice = ""
            }
        }
    }

    // 选图或换场景时重新生成真预览
    LaunchedEffect(picked, preset) {
        if (picked.isEmpty()) {
            previews = emptyList()
            return@LaunchedEffect
        }
        previewBusy = true
        previews = emptyList()
        previewDone = 0
        previewTotal = minOf(picked.size, 20)
        previews = BatchEngine.buildPreviews(ctx, picked.map { it.uri }, preset) { d, t ->
            previewDone = d
            previewTotal = t
        }
        previewBusy = false
    }

    if (showGallery && batch != null) {
        GalleryScreen(
            batch = batch!!,
            onClose = { showGallery = false },
            onOpen = { zoomUri = it }
        )
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text("极简批处理", style = MaterialTheme.typography.headlineSmall)
            Text(
                "选图 → 选场景 → 处理。原图只读，新图写进 Pictures/极简批处理。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Preset.entries.forEach { p ->
                    FilterChip(
                        selected = preset == p,
                        onClick = { preset = p },
                        enabled = !running,
                        label = { Text(p.label) }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                when (preset) {
                    Preset.WECHAT -> "长边 1920 · JPEG q82 · 发微信不糊"
                    Preset.ARCHIVE -> "保持原尺寸 · JPEG q90 · 只把 HEIC 换成 JPG"
                    Preset.SOCIAL -> "中心裁方 1080 · q85 · 头像/种草图"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("image/*")) },
                    enabled = !running,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (picked.isEmpty()) "选择图片" else "重选（${picked.size} 张）")
                }
                Button(
                    onClick = {
                        if (picked.isEmpty()) return@Button
                        running = true
                        batch = null
                        notice = ""
                        doneCount = 0
                        totalCount = picked.size
                        scope.launch {
                            val b = BatchEngine.runBatch(
                                ctx = ctx,
                                uris = picked.map { it.uri },
                                preset = preset,
                                lanes = 3,
                                prefix = prefix,
                                onProgress = { d, t -> doneCount = d; totalCount = t }
                            )
                            batch = b
                            running = false
                        }
                    },
                    enabled = picked.isNotEmpty() && !running,
                    modifier = Modifier.weight(1f)
                ) { Text("开始处理") }
            }

            if (picked.isNotEmpty() && !running && batch == null) {
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    singleLine = true,
                    label = { Text("重命名前缀（可留空）") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (previewBusy) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { if (previewTotal == 0) 0f else previewDone.toFloat() / previewTotal.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(2.dp))
                Text("生成预览 $previewDone / $previewTotal", style = MaterialTheme.typography.bodySmall)
            }

            if (batch == null && previews.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(previews) { p -> PreviewCard(p) }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "左：原图　右：处理后的样子（按当前场景真跑一遍，未写盘）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (running) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { if (totalCount == 0) 0f else doneCount.toFloat() / totalCount.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(2.dp))
                Text("处理中 $doneCount / $totalCount", style = MaterialTheme.typography.bodySmall)
            }

            val b = batch
            if (b != null) {
                Spacer(Modifier.height(10.dp))
                ResultCard(
                    batch = b,
                    notice = notice,
                    onBrowse = { showGallery = true },
                    onUndo = {
                        val n = BatchEngine.undo(ctx, b)
                        batch = null
                        notice = "已撤销，删除 $n 张新图；原图未动"
                    }
                )
            }

            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f)) {
                if (b != null) {
                    items(b.items) { d -> DoneRow(d) }
                } else {
                    items(picked) { p ->
                        PickedRow(p, preset, previews.firstOrNull { it.picked.uri == p.uri })
                    }
                }
            }
        }
    }

    val zu = zoomUri
    if (zu != null) {
        FullScreenImage(zu) { zoomUri = null }
    }
}

@Composable
fun PreviewCard(p: BatchEngine.Preview) {
    Card(Modifier.width(236.dp)) {
        Column(Modifier.padding(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ThumbBox(p.beforeThumb, "原")
                Text("→", style = MaterialTheme.typography.bodySmall)
                ThumbBox(p.afterThumb, "后")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${p.outW}×${p.outH} · 预计 ${mb(p.estKb)}",
                style = MaterialTheme.typography.bodySmall
            )
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
fun ThumbBox(bmp: Bitmap?, label: String) {
    Box(
        Modifier
            .size(92.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1A1F26))
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = label,
                modifier = Modifier.fillMaxSize().padding(2.dp),
                contentScale = ContentScale.Fit
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
            color = Color(0xFF8A94A6),
            modifier = Modifier.align(Alignment.BottomStart).padding(3.dp)
        )
    }
}

@Composable
fun ResultCard(batch: Batch, notice: String, onBrowse: () -> Unit, onUndo: () -> Unit) {
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
                OutlinedButton(onClick = onUndo, modifier = Modifier.weight(1f)) { Text("撤销这批") }
            }
        }
    }
}

@Composable
fun GalleryScreen(batch: Batch, onClose: () -> Unit, onOpen: (android.net.Uri) -> Unit) {
    val ctx = LocalContext.current
    val outs = batch.items.filter { it.outUri != null }
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("这批图片 · ${outs.size} 张", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Pictures/极简批处理 · 点图放大",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val first = outs.firstOrNull()?.outUri ?: return@OutlinedButton
                    try {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(first, "image/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        )
                    } catch (_: Throwable) {
                    }
                }) { Text("相册") }
                OutlinedButton(onClick = onClose) { Text("返回") }
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            gridItems(outs) { item ->
                OutputTile(item.outUri!!) { onOpen(item.outUri!!) }
            }
        }
    }
}

@Composable
fun OutputTile(uri: android.net.Uri, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        bmp = BatchEngine.loadThumb(ctx, uri, 480)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1A1F26))
            .clickable { onClick() }
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
fun FullScreenImage(uri: android.net.Uri, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        bmp = BatchEngine.loadThumb(ctx, uri, 1600)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
fun PickedRow(p: Picked, preset: Preset, preview: BatchEngine.Preview?) {
    val (ow, oh) = BatchEngine.expectedOut(p, preset)
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${p.w}×${p.h} → ${ow}×${oh} · ${mb(p.sizeKb)}" +
                (preview?.let { " · 预计 ${mb(it.estKb)}" } ?: ""),
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
