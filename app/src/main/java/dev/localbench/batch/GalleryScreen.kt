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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.text.style.TextOverflow
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
fun GalleryScreen(
    focus: String?,
    refreshKey: Int,
    onOpen: (List<Shot>, Int) -> Unit,
    onOpenTrash: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var shots by remember { mutableStateOf(emptyList<Shot>()) }
    var loading by remember { mutableStateOf(true) }
    var dir by remember { mutableStateOf(focus ?: "全部") }
    var reload by remember { mutableStateOf(0) }
    var loc by remember { mutableStateOf(Store.currentLocation(ctx)) }
    var trashN by remember { mutableStateOf(0) }
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
        trashN = withContext(Dispatchers.IO) { Trash.count(ctx) }
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
                val outside = if (selecting) selected.count { key -> shots.none { it.uri.toString() == key } } else 0
                Text(
                    if (selecting) "已选 ${selected.size} 张" else "成品 · ${filtered.size} 张",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    if (selecting) {
                        if (outside > 0) "另有 $outside 张在别的场景"
                        else "再点图片可以加减"
                    } else if (shots.isEmpty()) loc.label else "${loc.label} · 共 ${mb(totalKb)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (selecting) {
                // 全选是按"全部成品"算，不是当前这个场景：切了场景也能一次选完
                val allOn = shots.isNotEmpty() && shots.all { it.uri.toString() in selected }
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    TextButton(onClick = {
                        if (allOn) {
                            selected = emptySet()
                        } else {
                            // 选完把视图切到「全部」，让人看见自己到底选了什么
                            dir = "全部"
                            selected = shots.map { it.uri.toString() }.toSet()
                        }
                    }) { Text(if (allOn) "清空" else "全选 ${shots.size}") }
                    TextButton(onClick = { askDelete = true }, enabled = selected.isNotEmpty()) {
                        Text("删除")
                    }
                    TextButton(onClick = { selecting = false; selected = emptySet() }) {
                        Text("取消")
                    }
                }
            } else {
                // 三个文字按钮以前占掉近 300dp，标题被挤成两三行。
                // 文字留着（换成图标反而要认），把内边距和字号收小，总宽压到 180dp 左右。
                Row(
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val tight = Modifier.defaultMinSize(minWidth = 0.dp)
                    if (trashN > 0) {
                        TextButton(
                            onClick = onOpenTrash,
                            modifier = tight,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("最近删除 $trashN", fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    TextButton(
                        onClick = {
                            if (!openGallery(ctx)) {
                                toast(ctx, "没有能打开相册的应用")
                            }
                        },
                        modifier = tight,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("相册", fontSize = 12.sp, maxLines = 1)
                    }
                    TextButton(
                        onClick = { reload++ },
                        modifier = tight,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("刷新", fontSize = 12.sp, maxLines = 1)
                    }
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
                    Text("还没有成品", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "去「转换」页处理一批，回来按场景看。\n位置：${loc.label}",
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
            val victims = shots.filter { it.uri.toString() in selected }
            AlertDialog(
                onDismissRequest = { askDelete = false },
                title = { Text("删除选中的 ${victims.size} 张？") },
                text = {
                    Column {
                        Text("会进「最近删除」，${Trash.KEEP_DAYS} 天内能还原。相册里立刻消失，原图不动。")
                        if (victims.map { it.dir }.distinct().size > 1) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                victims.groupBy { it.dir }
                                    .map { "${it.key} ${it.value.size} 张" }
                                    .joinToString("、"),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        askDelete = false
                        selecting = false
                        selected = emptySet()
                        // 跨场景删完，视图留在单个场景里会看到空，干脆切回全部
                        if (victims.map { it.dir }.distinct().size > 1) dir = "全部"
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                victims.forEach { Trash.moveIn(ctx, loc, it) }
                            }
                            toast(ctx, "已放进最近删除，${Trash.KEEP_DAYS} 天内能还原")
                            reload++
                        }
                    }) { Text("删除") }
                },
                dismissButton = { TextButton(onClick = { askDelete = false }) { Text("取消") } }
            )
        }
    }
}

/** 给选图网格用的"预计体积"参数。 */
data class EstSpec(val preset: Preset, val plan: OutputPlan, val w: Int, val h: Int)

@Composable
fun ShotTile(
    shot: Shot,
    selecting: Boolean = false,
    selected: Boolean = false,
    est: EstSpec? = null,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    val ctx = LocalContext.current
    var bmp by remember(shot.uri) { mutableStateOf<Bitmap?>(null) }
    var estKb by remember(shot.uri) { mutableStateOf<Int?>(null) }
    var estReady by remember(shot.uri) { mutableStateOf(false) }
    LaunchedEffect(shot.uri) { bmp = BatchEngine.loadThumb(ctx, shot.uri, 360) }
    // 只算屏幕上真出现的那些，划走就取消；算过的结果在 BatchEngine 里有缓存
    LaunchedEffect(shot.uri, est) {
        val e = est ?: return@LaunchedEffect
        estReady = false
        estKb = BatchEngine.estimateKb(
            ctx,
            Picked(shot.uri, shot.name, e.w, e.h, shot.sizeKb),
            e.preset,
            e.plan
        )
        estReady = true
    }
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
            val e = est
            if (e != null) {
                TileBadge(
                    if (!estReady) "算…"
                    else estKb?.let {
                        if (e.plan.targetKb > 0) "≤${mb(it)}" else "约 ${mb(it)}"
                    } ?: "—",
                    Modifier.align(Alignment.BottomStart).padding(4.dp)
                )
            }
            TileBadge(
                "原 ${mb(shot.sizeKb)}",
                Modifier.align(Alignment.BottomEnd).padding(4.dp)
            )
        }
    }
}

@Composable
private fun TileBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color.Black.copy(alpha = 0.56f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(text, fontSize = 10.sp, color = Color.White, maxLines = 1)
    }
}












