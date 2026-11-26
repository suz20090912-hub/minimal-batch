package dev.localbench.batch

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Bitmap

/**
 * 最近删除。删掉的成品先在这里躺 7 天，能还原，也能手动清干净。
 */
@Composable
fun TrashScreen(onClose: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler { onClose() }

    var items by remember { mutableStateOf(emptyList<Trash.Item>()) }
    var loading by remember { mutableStateOf(true) }
    var actionFor by remember { mutableStateOf<Trash.Item?>(null) }
    var askPurgeAll by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        items = withContext(Dispatchers.IO) { Trash.list(ctx) }
        loading = false
    }

    val now = System.currentTimeMillis()
    val totalKb = items.sumOf { it.sizeKb }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { }
        )
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text("最近删除", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (items.isEmpty()) "空着" else "共 ${items.size} 张 · ${mb(totalKb)} · 留 ${Trash.KEEP_DAYS} 天",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (items.isNotEmpty()) {
                    TextButton(onClick = { askPurgeAll = true }) {
                        Text("清空", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("这里空着", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "成品页删掉的图会先到这，\n${Trash.KEEP_DAYS} 天内能还原。",
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
                    items(items, key = { it.id }) { item ->
                        TrashTile(item = item, now = now, onClick = { actionFor = item })
                    }
                }
            }
        }
    }

    val target = actionFor
    if (target != null) {
        AlertDialog(
            onDismissRequest = { actionFor = null },
            title = { Text(target.name, maxLines = 1) },
            text = { Text("还原到「${target.scene}」。彻底删除后找不回来。") },
            confirmButton = {
                TextButton(onClick = {
                    actionFor = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { Trash.restore(ctx, target) }
                        toast(ctx, if (ok) "已还原 1 张" else "还原失败")
                        if (ok) {
                            onChanged()
                            reload++
                        }
                    }
                }) { Text("还原") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { Trash.purge(ctx, target) }
                            actionFor = null
                            toast(ctx, "已彻底删除 1 张")
                            reload++
                        }
                    }) { Text("彻底删除", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { actionFor = null }) { Text("取消") }
                }
            }
        )
    }

    if (askPurgeAll) {
        AlertDialog(
            onDismissRequest = { askPurgeAll = false },
            title = { Text("清空最近删除？") },
            text = { Text("这 ${items.size} 张会彻底删掉，找不回来。") },
            confirmButton = {
                TextButton(onClick = {
                    askPurgeAll = false
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { Trash.purgeAll(ctx) }
                        toast(ctx, "已彻底删除 $n 张")
                        reload++
                    }
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askPurgeAll = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun TrashTile(item: Trash.Item, now: Long, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.id) { bmp = BatchEngine.loadThumb(ctx, Trash.uriOf(ctx, item), 360) }
    val lastDay = item.daysLeft(now) <= 1
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
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.62f))
                .padding(horizontal = 5.dp, vertical = 1.dp)
        ) {
            Text(
                if (lastDay) "今天到期" else "还剩 ${item.daysLeft(now)} 天",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.inverseOnSurface
            )
        }
        if (lastDay) {
            Box(
                Modifier
                    .fillMaxSize()
                    .alpha(0.06f)
                    .background(MaterialTheme.colorScheme.error)
            )
        }
    }
}
