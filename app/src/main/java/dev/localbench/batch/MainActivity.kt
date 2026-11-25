package dev.localbench.batch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    var preset by remember { mutableStateOf(Preset.WECHAT) }
    var prefix by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var doneCount by remember { mutableStateOf(0) }
    var totalCount by remember { mutableStateOf(0) }
    var batch by remember { mutableStateOf<Batch?>(null) }
    var notice by remember { mutableStateOf("") }

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

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("极简批处理", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(2.dp))
        Text(
            "选图 → 选场景 → 处理。原图只读，新图写进 Pictures/极简批处理。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Preset.values().forEach { p ->
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
                Preset.WECHAT -> "长边缩到 1920 · JPEG q82 · 适合发微信不糊"
                Preset.ARCHIVE -> "保持原尺寸 · JPEG q90 · 只把 HEIC 换成通用 JPG"
                Preset.SOCIAL -> "中心裁成方图 1080 · q85 · 做头像/种草图"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { picker.launch(arrayOf("image/*")) },
            enabled = !running,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (picked.isEmpty()) "选择图片（可一次选多张）" else "重新选择（已选 ${picked.size} 张）")
        }

        if (picked.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = prefix,
                onValueChange = { prefix = it },
                enabled = !running,
                singleLine = true,
                label = { Text("重命名前缀（可留空，留空=原名加场景后缀）") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "共 ${mb(picked.sumOf { it.sizeKb })} · 输出尺寸见下方清单",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (picked.isEmpty()) return@Button
                running = true
                batch = null
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
            modifier = Modifier.fillMaxWidth()
        ) { Text("开始处理") }

        if (running) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (totalCount == 0) 0f else doneCount.toFloat() / totalCount.toFloat() },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text("$doneCount / $totalCount", style = MaterialTheme.typography.bodySmall)
        }

        val b = batch
        if (b != null) {
            Spacer(Modifier.height(10.dp))
            ResultCard(
                batch = b,
                notice = notice,
                onUndo = {
                    val n = BatchEngine.undo(ctx, b)
                    batch = null
                    notice = "已撤销，删除 $n 张新图；原图未动"
                }
            )
        }

        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.weight(1f)) {
            if (b != null) {
                items(b.items) { d -> DoneRow(d) }
            } else {
                items(picked) { p -> PickedRow(p, preset) }
            }
        }
    }
}

@Composable
fun ResultCard(batch: Batch, notice: String, onUndo: () -> Unit) {
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
            Text(
                "输出在 Pictures/极简批处理",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (notice.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onUndo, modifier = Modifier.fillMaxWidth()) {
                Text("撤销这批（删掉刚写的图）")
            }
        }
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
