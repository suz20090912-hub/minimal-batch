package dev.localbench.batch

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 成品页。按场景子文件夹分类，全部从磁盘现场扫，不依赖数据库。
 */
@Composable
fun GalleryScreen(focus: String?, refreshKey: Int, onOpen: (List<Shot>, Int) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var shots by remember { mutableStateOf(emptyList<Shot>()) }
    var loading by remember { mutableStateOf(true) }
    var dir by remember { mutableStateOf(focus ?: "全部") }
    var reload by remember { mutableStateOf(0) }
    var loc by remember { mutableStateOf(Store.currentLocation(ctx)) }
    val locs = remember(refreshKey, reload) { Store.locations(ctx) }
    // 选择模式：长按一张进来，点图片加减，顶部出现删除
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var askDelete by remember { mutableStateOf(false) }

    // 选择模式下按返回，先退出选择，不要直接跳回转换页
    BackHandler(enabled = selecting) {
        selecting = false
        selected = emptySet()
    }

    LaunchedEffect(focus) { if (focus != null) dir = focus }
    LaunchedEffect(refreshKey, reload, loc) {
        loading = true
        shots = withContext(Dispatchers.IO) { Store.listAt(ctx, loc) }
        loading = false
    }

    val dirs = remember(shots) {
        val map = LinkedHashMap<String, Int>()
        shots.forEach { map[it.dir] = (map[it.dir] ?: 0) + 1 }
        map
    }
    val filtered = if (dir == "全部") shots else shots.filter { it.dir == dir }
    val totalKb = filtered.sumOf { it.sizeKb }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (selecting) "已选 ${selected.size} 张" else "成品 · ${filtered.size} 张",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    if (selecting) "再点图片可以加减选择" else "${loc.label} · 共 ${mb(totalKb)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selecting) {
                val allOn = filtered.isNotEmpty() && filtered.all { it.uri.toString() in selected }
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    TextButton(onClick = {
                        selected = if (allOn) emptySet() else filtered.map { it.uri.toString() }.toSet()
                    }) { Text(if (allOn) "清空" else "全选") }
                    TextButton(onClick = { askDelete = true }, enabled = selected.isNotEmpty()) {
                        Text("删除")
                    }
                    TextButton(onClick = { selecting = false; selected = emptySet() }) {
                        Text("取消")
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        if (!openGallery(ctx)) {
                            toast(ctx, "这台机器没有能打开的系统相册，请到手机相册里找")
                        }
                    }) { Text("相册") }
                    OutlinedButton(onClick = { reload++ }) { Text("刷新") }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        if (locs.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                locs.forEach { l ->
                    FilterChip(
                        selected = l.kind == loc.kind && l.value == loc.value,
                        onClick = { loc = l; dir = "全部" },
                        label = { Text(l.label) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = dir == "全部",
                onClick = { dir = "全部" },
                label = { Text("全部 ${shots.size}") }
            )
            dirs.forEach { (name, count) ->
                FilterChip(
                    selected = dir == name,
                    onClick = { dir = name },
                    label = { Text("$name $count") }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("这里还没有成品", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "去「转换」页选图、处理一批，回来就能在这里按场景查看。\n当前位置：${loc.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(filtered, key = { _, s -> s.uri.toString() }) { i, s ->
                    val key = s.uri.toString()
                    ShotTile(
                        shot = s,
                        selecting = selecting,
                        selected = key in selected,
                        onClick = {
                            if (selecting) {
                                selected = if (key in selected) selected - key else selected + key
                            } else {
                                onOpen(filtered, i)
                            }
                        },
                        onLongPress = {
                            if (!selecting) {
                                selecting = true
                                selected = setOf(key)
                            }
                        }
                    )
                }
            }
        }

        if (askDelete) {
            AlertDialog(
                onDismissRequest = { askDelete = false },
                title = { Text("删除选中的 ${selected.size} 张？") },
                text = { Text("只会删这些新生成的图，原图不动；删完系统相册里也会一起消失。") },
                confirmButton = {
                    TextButton(onClick = {
                        askDelete = false
                        val victims = filtered.filter { it.uri.toString() in selected }
                        selecting = false
                        selected = emptySet()
                        scope.launch {
                            val n = withContext(Dispatchers.IO) {
                                victims.count { Store.delete(ctx, it.uri) }
                            }
                            toast(ctx, "已删除 $n 张")
                            reload++
                        }
                    }) { Text("删除") }
                },
                dismissButton = { TextButton(onClick = { askDelete = false }) { Text("取消") } }
            )
        }
    }
}

@Composable
fun ShotTile(
    shot: Shot,
    selecting: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    val ctx = LocalContext.current
    var bmp by remember(shot.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(shot.uri) { bmp = BatchEngine.loadThumb(ctx, shot.uri, 360) }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .alpha(if (selecting && !selected) 0.72f else 1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(shot.uri, selecting) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongPress() }
                )
            }
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = shot.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
            )
        }
        if (selecting) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else Color.Black.copy(alpha = 0.28f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) Text("✓", color = Color.White, fontSize = 11.sp)
            }
        } else {
            Text(
                mb(shot.sizeKb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
            )
        }
    }
}












