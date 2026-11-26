package dev.localbench.batch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

private val scrimTop = Brush.verticalGradient(
    listOf(Color(0xE6000000), Color(0x99000000), Color(0x00000000))
)
private val scrimBottom = Brush.verticalGradient(
    listOf(Color(0x00000000), Color(0x99000000), Color(0xE6000000))
)
private val accent = Color(0xFFC7B3FF)

/**
 * 全屏看图 + 轻编辑。
 *
 * 看图：左右滑动切上一张 / 下一张（原生相册手感），底部小字显示第几张；
 * 放大后（>1×）左右拖动变成看图内平移，不会误翻页；缩回 1× 再滑才翻页。
 * 顶栏：左圆形返回、中间文件名 + 张数、右 ⓘ 文件详情。
 * 点一下画面：按钮全消失只剩照片，再点一下回来。
 * 编辑：取消 / 编辑 / 保存；左转 / 右转 / 镜像 / 裁剪 / 还原；保存另存新图。
 */
@Composable
fun ImageViewer(
    shots: List<Shot>,
    startIndex: Int,
    viewOnly: Boolean = false,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val items = remember(shots) { mutableStateListOf<Shot>().apply { addAll(shots) } }
    val pager = rememberPagerState(
        initialPage = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    ) { items.size }

    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }

    var editShot by remember { mutableStateOf<Shot?>(null) }
    var src by remember { mutableStateOf<Bitmap?>(null) }
    var work by remember { mutableStateOf<Bitmap?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var cropping by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var crop by remember { mutableStateOf(Rect(0.06f, 0.06f, 0.94f, 0.94f)) }

    val current = items.getOrNull(pager.currentPage) ?: items.firstOrNull()

    LaunchedEffect(pager.currentPage) { zoomed = false }

    LaunchedEffect(editShot?.uri) {
        val s = editShot ?: return@LaunchedEffect
        src = null
        work = null
        dirty = false
        cropping = false
        val b = BatchEngine.loadThumb(ctx, s.uri, 4096)
        src = b
        work = b
    }

    BackHandler(enabled = cropping) { cropping = false }
    BackHandler(enabled = editShot != null && !cropping) {
        work = null
        src = null
        dirty = false
        editShot = null
    }

    fun transform(m: Matrix) {
        val b = work ?: return
        val nb = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (nb !== b) {
            work = nb
            if (b !== src) b.recycle()
        }
        dirty = true
    }

    fun applyCrop() {
        val b = work ?: return
        val x = (crop.left * b.width).roundToInt().coerceIn(0, b.width - 16)
        val y = (crop.top * b.height).roundToInt().coerceIn(0, b.height - 16)
        val w = (crop.width * b.width).roundToInt().coerceIn(16, b.width - x)
        val h = (crop.height * b.height).roundToInt().coerceIn(16, b.height - y)
        val nb = Bitmap.createBitmap(b, x, y, w, h)
        work = nb
        if (b !== src) b.recycle()
        dirty = true
        cropping = false
        crop = Rect(0.06f, 0.06f, 0.94f, 0.94f)
    }

    fun saveNow() {
        val b = work ?: return
        val s = editShot ?: return
        saving = true
        scope.launch {
            val base = s.name.substringBeforeLast('.', s.name)
            val df = SimpleDateFormat("MMdd_HHmmss", Locale.US)
            val outName = "${base}_edit_${df.format(Date())}.jpg"
            val dir = if (s.dir.isBlank()) "编辑" else s.dir
            val res = withContext(Dispatchers.IO) {
                val bytes = BatchEngine.encodeEdited(b, 92)
                Store.saveInto(ctx, dir, outName, bytes)
            }
            saving = false
            if (res.first != null) {
                dirty = false
                work = null
                src = null
                editShot = null
                onSaved()
                toast(ctx, "已保存 $outName")
            } else {
                toast(ctx, "保存失败：${res.second ?: "未知"}")
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 吞掉落在看图页上的所有触摸，避免穿透到下面的成品网格
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { }
        )

        if (editShot == null) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                HorizontalPager(
                    state = pager,
                    userScrollEnabled = !zoomed && !info,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val s = items.getOrNull(page)
                    if (s != null) {
                        ViewerPage(
                            shot = s,
                            onTap = { chrome = !chrome },
                            onZoomChange = { z -> if (page == pager.currentPage) zoomed = z }
                        )
                    }
                }
            }

            if (chrome) {
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(scrimTop)
                        .safeDrawingPadding()
                        .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 22.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircleIcon(Icons.Filled.ArrowBack) { onDismiss() }
                    Column(
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            current?.name ?: "",
                            color = Color.White,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${pager.currentPage + 1} / ${items.size}",
                            color = Color(0xFF98A1B0),
                            fontSize = 11.sp
                        )
                    }
                    if (!viewOnly) CircleIcon(Icons.Filled.Delete) { askDelete = true }
                    CircleIcon(Icons.Filled.Info) { info = true }
                }

                if (!viewOnly) {
                    Row(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(scrimBottom)
                            .safeDrawingPadding()
                            .padding(top = 26.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BarAction("分享", Icons.Filled.Share) {
                            current?.let { shareImage(ctx, it.uri, it.name) }
                        }
                        BarAction("编辑", Icons.Filled.Edit) {
                            zoomed = false
                            cropping = false
                            editShot = current
                        }
                    }
                }
            }
        } else {
            // ================= 编辑模式 =================
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                val b = work
                if (b == null) {
                    Text(
                        "载入中…",
                        color = Color(0xFF8A94A6),
                        fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    Image(
                        bitmap = b.asImageBitmap(),
                        contentDescription = editShot?.name ?: "",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().onSizeChanged { viewSize = it }
                    )
                    if (cropping) {
                        CropOverlay(b, viewSize, crop) { crop = it }
                    }
                }
            }

            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(scrimTop)
                    .safeDrawingPadding()
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextAction("取消") {
                    if (cropping) {
                        cropping = false
                    } else {
                        work = null
                        src = null
                        dirty = false
                        editShot = null
                    }
                }
                Text(
                    if (cropping) "裁剪" else "编辑",
                    modifier = Modifier.weight(1f),
                    color = Color(0xFF98A1B0),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                TextAction(
                    when {
                        saving -> "保存中…"
                        cropping -> "完成"
                        else -> "保存"
                    },
                    accent
                ) {
                    if (saving) return@TextAction
                    if (cropping) applyCrop() else saveNow()
                }
            }

            if (cropping) {
                Text(
                    "拖四角缩放范围 · 框内拖动可整体移动",
                    color = Color(0xFFB9C0CE),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .safeDrawingPadding()
                        .padding(bottom = 18.dp)
                )
            } else {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(scrimBottom)
                        .safeDrawingPadding()
                        .padding(top = 26.dp, bottom = 6.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlyphAction("左转 90°", "⟲") { transform(Matrix().apply { postRotate(-90f) }) }
                    GlyphAction("右转 90°", "⟳") { transform(Matrix().apply { postRotate(90f) }) }
                    GlyphAction("镜像", "⇋") { transform(Matrix().apply { postScale(-1f, 1f) }) }
                    GlyphAction("裁剪", "▣") {
                        crop = Rect(0.06f, 0.06f, 0.94f, 0.94f)
                        cropping = true
                    }
                    if (dirty) GlyphAction("还原", "↺") {
                        work = src
                        dirty = false
                    }
                }
            }
        }

        // ================= 文件详情面板 =================
        if (info && editShot == null && current != null) {
            val dims = remember(current.uri) { mutableStateOf<Pair<Int, Int>?>(null) }
            LaunchedEffect(current.uri) { dims.value = imageSize(ctx, current.uri) }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { info = false }
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .background(Color(0xFF17191F))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { }
                    .safeDrawingPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("文件详情", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    TextAction("关闭") { info = false }
                }
                Spacer(Modifier.height(6.dp))
                DetailRow("文件名", current.name)
                DetailRow(
                    "尺寸",
                    dims.value?.let { "${it.first} × ${it.second} 像素" } ?: "—"
                )
                DetailRow("大小", sizeText(current.sizeKb))
                DetailRow("格式", fmtOf(current.name))
                DetailRow("位置", if (viewOnly) "原图 · 只读" else positionOf(ctx, current))
            }
        }

        // ================= 删除确认 =================
        if (askDelete && current != null) {
            val s = current
            AlertDialog(
                onDismissRequest = { askDelete = false },
                title = { Text("删除这张？") },
                text = { Text("「${s.name}」会进「最近删除」，${Trash.KEEP_DAYS} 天内能还原；系统相册里立刻消失，原图不动。") },
                confirmButton = {
                    TextButton(onClick = {
                        askDelete = false
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                Trash.moveIn(ctx, Store.currentLocation(ctx), s)
                            }
                            if (!ok) {
                                toast(ctx, "删除失败，可能文件已经被移走了")
                                return@launch
                            }
                            val idx = items.indexOfFirst { it.uri == s.uri }
                            if (idx >= 0) items.removeAt(idx)
                            onDeleted()
                            if (items.isEmpty()) {
                                onDismiss()
                            } else {
                                toast(ctx, "已移入最近删除，${Trash.KEEP_DAYS} 天内可还原")
                                pager.scrollToPage(idx.coerceIn(0, items.size - 1))
                            }
                        }
                    }) { Text("删除") }
                },
                dismissButton = { TextButton(onClick = { askDelete = false }) { Text("取消") } }
            )
        }
    }
}

/** 单页看图：自己载图，缩放/拖动；1× 时把横向拖动让给外层翻页 */
@Composable
private fun ViewerPage(shot: Shot, onTap: () -> Unit, onZoomChange: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember(shot.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(shot.uri) { bmp = BatchEngine.loadThumb(ctx, shot.uri, 4096) }
    val b = bmp
    if (b != null) {
        ZoomableImage(b, shot.name, onTap, onZoomChange)
    }
}

/** 圆形图标按钮 */
@Composable
private fun CircleIcon(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0x66000000))
            .border(1.dp, Color(0x40FFFFFF), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun TextAction(label: String, color: Color = Color.White, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
private fun BarAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(5.dp))
        Text(label, color = Color(0xFFDCE1EA), fontSize = 11.sp)
    }
}

@Composable
private fun GlyphAction(label: String, glyph: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(glyph, color = Color.White, fontSize = 22.sp)
        Spacer(Modifier.height(3.dp))
        Text(label, color = Color(0xFFDCE1EA), fontSize = 11.sp)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(label, color = Color(0xFF98A1B0), fontSize = 13.sp, modifier = Modifier.width(60.dp))
        Text(value, color = Color(0xFFE8EBF2), fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

private fun sizeText(kb: Int): String {
    if (kb <= 0) return "—"
    return if (kb >= 1024) String.format(Locale.US, "%.1f MB", kb / 1024f) else "$kb KB"
}

private fun fmtOf(name: String): String {
    val ext = name.substringAfterLast('.', "").uppercase(Locale.US)
    return when (ext) {
        "JPG", "JPEG" -> "JPEG"
        "PNG" -> "PNG"
        "WEBP" -> "WebP"
        "HEIC", "HEIF" -> "HEIC"
        "" -> "未知"
        else -> ext
    }
}

private fun positionOf(ctx: Context, shot: Shot): String =
    if (Store.isCustom(ctx)) Store.where(ctx)
    else "Pictures/${Store.root(ctx)}/${shot.dir}"

/** 只读图片头拿尺寸，不解整张图 */
private fun imageSize(ctx: Context, uri: Uri): Pair<Int, Int>? {
    return try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    } catch (_: Throwable) {
        null
    }
}

/** 图片在容器内按 Fit 之后的实际显示矩形（本地坐标） */
private fun displayedRect(bmp: Bitmap, viewSize: IntSize): Rect {
    if (viewSize.width <= 0 || viewSize.height <= 0) return Rect.Zero
    val f = min(
        viewSize.width.toFloat() / bmp.width,
        viewSize.height.toFloat() / bmp.height
    )
    val w = bmp.width * f
    val h = bmp.height * f
    val sw = viewSize.width.toFloat()
    val sh = viewSize.height.toFloat()
    return Rect((sw - w) / 2f, (sh - h) / 2f, (sw + w) / 2f, (sh + h) / 2f)
}

private fun corners(r: Rect, c: Rect): List<Offset> = listOf(
    Offset(r.left + c.left * r.width, r.top + c.top * r.height),
    Offset(r.left + c.right * r.width, r.top + c.top * r.height),
    Offset(r.left + c.left * r.width, r.top + c.bottom * r.height),
    Offset(r.left + c.right * r.width, r.top + c.bottom * r.height)
)

@Composable
private fun CropOverlay(
    bmp: Bitmap,
    viewSize: IntSize,
    initial: Rect,
    onChange: (Rect) -> Unit
) {
    var cur by remember(bmp) { mutableStateOf(initial) }
    var active by remember(bmp) { mutableIntStateOf(-1) }

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(bmp, viewSize) {
                val hit = 56.dp.toPx()
                detectDragGestures(
                    onDragStart = { p ->
                        val r = displayedRect(bmp, viewSize)
                        if (r.width <= 0f) {
                            active = -1
                            return@detectDragGestures
                        }
                        val pts = corners(r, cur)
                        var idx = -1
                        var nearest = Float.MAX_VALUE
                        pts.forEachIndexed { i, pt ->
                            val d = (pt - p).getDistance()
                            if (d < nearest) {
                                nearest = d
                                idx = i
                            }
                        }
                        val innerL = r.left + cur.left * r.width
                        val innerT = r.top + cur.top * r.height
                        val innerR = r.left + cur.right * r.width
                        val innerB = r.top + cur.bottom * r.height
                        active = when {
                            nearest <= hit -> idx
                            p.x in innerL..innerR && p.y in innerT..innerB -> 4
                            else -> -1
                        }
                    },
                    onDrag = { change, drag ->
                        if (active < 0) return@detectDragGestures
                        change.consume()
                        val r = displayedRect(bmp, viewSize)
                        if (r.width <= 0f) return@detectDragGestures
                        val dx = drag.x / r.width
                        val dy = drag.y / r.height
                        var l = cur.left
                        var t = cur.top
                        var ri = cur.right
                        var bo = cur.bottom
                        when (active) {
                            0 -> {
                                l = (l + dx).coerceIn(0f, ri - 0.05f)
                                t = (t + dy).coerceIn(0f, bo - 0.05f)
                            }
                            1 -> {
                                ri = (ri + dx).coerceIn(l + 0.05f, 1f)
                                t = (t + dy).coerceIn(0f, bo - 0.05f)
                            }
                            2 -> {
                                l = (l + dx).coerceIn(0f, ri - 0.05f)
                                bo = (bo + dy).coerceIn(t + 0.05f, 1f)
                            }
                            3 -> {
                                ri = (ri + dx).coerceIn(l + 0.05f, 1f)
                                bo = (bo + dy).coerceIn(t + 0.05f, 1f)
                            }
                            else -> {
                                val w = cur.width
                                val h = cur.height
                                l = (l + dx).coerceIn(0f, 1f - w)
                                t = (t + dy).coerceIn(0f, 1f - h)
                                ri = l + w
                                bo = t + h
                            }
                        }
                        cur = Rect(l, t, ri, bo)
                        onChange(cur)
                    },
                    onDragEnd = { active = -1 },
                    onDragCancel = { active = -1 }
                )
            }
    ) {
        val r = displayedRect(bmp, viewSize)
        if (r.width <= 0f) return@Canvas
        val cr = Rect(
            r.left + cur.left * r.width,
            r.top + cur.top * r.height,
            r.left + cur.right * r.width,
            r.top + cur.bottom * r.height
        )
        val dim = Color(0xB3000000)
        drawRect(dim, Offset(r.left, r.top), Size(r.width, cr.top - r.top))
        drawRect(dim, Offset(r.left, cr.bottom), Size(r.width, r.bottom - cr.bottom))
        drawRect(dim, Offset(r.left, cr.top), Size(cr.left - r.left, cr.height))
        drawRect(dim, Offset(cr.right, cr.top), Size(r.right - cr.right, cr.height))

        val lw = 1.dp.toPx()
        val g = Color(0x40FFFFFF)
        for (i in 1..2) {
            val x = cr.left + cr.width * i / 3f
            val y = cr.top + cr.height * i / 3f
            drawLine(g, Offset(x, cr.top), Offset(x, cr.bottom), strokeWidth = lw)
            drawLine(g, Offset(cr.left, y), Offset(cr.right, y), strokeWidth = lw)
        }
        drawRect(Color.White, cr.topLeft, Size(cr.width, cr.height), style = Stroke(2.dp.toPx()))

        val pts = corners(r, cur)
        val hd = 13.dp.toPx()
        pts.forEachIndexed { i, p ->
            drawCircle(
                if (i == active) Color(0xCC7B5CE6) else Color(0x66000000),
                hd * 1.9f,
                p
            )
            drawCircle(Color.White, hd, p)
        }
    }
}

/**
 * 双指缩放（1×–5×）、双击 2.5×/还原、放大后拖动。
 * 1× 时单指横向拖动不消费事件，交给外层 HorizontalPager 翻页；
 * 放大后横向拖动变成图内平移，不会误翻页。
 */
@Composable
fun ZoomableImage(bmp: Bitmap, label: String, onTap: () -> Unit, onZoomChange: (Boolean) -> Unit) {
    var scale by remember(bmp) { mutableFloatStateOf(1f) }
    var offset by remember(bmp) { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var reported by remember(bmp) { mutableStateOf(false) }

    fun report() {
        val z = scale > 1.02f
        if (z != reported) {
            reported = z
            onZoomChange(z)
        }
    }

    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = label,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y
            )
            .pointerInput(bmp) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        if (pressed >= 2) {
                            val next = (scale * zoomChange).coerceIn(1f, 5f)
                            scale = next
                            offset = if (next <= 1.02f) {
                                Offset.Zero
                            } else {
                                clampOffset(offset + panChange, next, viewSize)
                            }
                            event.changes.forEach { it.consume() }
                        } else if (scale > 1.02f && panChange != Offset.Zero) {
                            offset = clampOffset(offset + panChange, scale, viewSize)
                            event.changes.forEach { it.consume() }
                        }
                        report()
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(bmp) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                        report()
                    }
                )
            }
    )
}

private fun clampOffset(o: Offset, s: Float, viewSize: IntSize): Offset {
    val maxX = (viewSize.width * (s - 1f)) / 2f
    val maxY = (viewSize.height * (s - 1f)) / 2f
    return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
}





