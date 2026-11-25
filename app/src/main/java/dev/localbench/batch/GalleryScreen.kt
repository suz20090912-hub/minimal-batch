package dev.localbench.batch

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 成品页。按场景子文件夹分类，全部从磁盘现场扫，不依赖数据库。
 */
@Composable
fun GalleryScreen(focus: String?, refreshKey: Int, onOpen: (List<Shot>, Int) -> Unit) {
    val ctx = LocalContext.current
    var shots by remember { mutableStateOf(emptyList<Shot>()) }
    var loading by remember { mutableStateOf(true) }
    var dir by remember { mutableStateOf(focus ?: "全部") }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey, focus, reload) {
        if (focus != null) dir = focus
        loading = true
        shots = withContext(Dispatchers.IO) { Store.list(ctx) }
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
                Text("成品 · ${filtered.size} 张", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${Store.where(ctx)} · 共 ${mb(totalKb)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    filtered.firstOrNull()?.let { s ->
                        try {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(s.uri, "image/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                            )
                        } catch (_: Throwable) {
                            toast(ctx, "没有能打开这张图的应用")
                        }
                    }
                }) { Text("相册") }
                OutlinedButton(onClick = { reload++ }) { Text("刷新") }
            }
        }

        Spacer(Modifier.height(8.dp))
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
                        "去「转换」页选图、处理一批，回来就能在这里按场景查看。\n输出位置：${Store.where(ctx)}",
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
                    ShotTile(s) { onOpen(filtered, i) }
                }
            }
        }
    }
}

@Composable
fun ShotTile(shot: Shot, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember(shot.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(shot.uri) { bmp = BatchEngine.loadThumb(ctx, shot.uri, 360) }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() }
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
        Text(
            mb(shot.sizeKb),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
        )
    }
}




