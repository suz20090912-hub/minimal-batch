package dev.localbench.batch

import android.graphics.Bitmap
import android.graphics.Matrix
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
 * 全屏看图 + 轻编辑，布局照手机原生相册来：
 *
 * 看图：顶栏 = 左侧圆形返回 + 中间文件名；底栏 = 分享 / 编辑（图标在上、字在下）。
 * 点一下画面：上面两条全部隐藏，只剩照片；再点一下回来。
 * 编辑：顶栏 取消 / 编辑 / 保存；底栏 左转 / 右转 / 镜像 / 裁剪 / 还原。
 * 裁剪：拖四角改范围，框内拖动整体平移，顶栏 取消 / 完成。
 */
@Composable
fun ImageViewer(shot: Shot, onSaved: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var src by remember(shot.uri) { mutableStateOf<Bitmap?>(null) }
    var work by remember(shot.uri) { mutableStateOf<Bitmap?>(null) }
    var dirty by remember(shot.uri) { mutableStateOf(false) }
    var editing by remember(shot.uri) { mutableStateOf(false) }
    var cropping by remember(shot.uri) { mutableStateOf(false) }
    var chrome by remember(shot.uri) { mutableStateOf(true) }
    var saving by remember(shot.uri) { mutableStateOf(false) }
    var info by remember(shot.uri) { mutableStateOf(false) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var crop by remember(shot.uri) { mutableStateOf(Rect(0.06f, 0.06f, 0.94f, 0.94f)) }

    LaunchedEffect(shot.uri) {
        val b = BatchEngine.loadThumb(ctx, shot.uri, 4096)
        src = b
        work = b
    }

    BackHandler(enabled = cropping) { cropping = false }
    BackHandler(enabled = editing && !cropping) {
        work = src
        dirty = false
        editing = false
    }

    fun transform(m: Matrix) {
        val b = work ?: return
        val nb = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (nb !== b) work = nb
        dirty = true
    }

    fun applyCrop() {
        val b = work ?: return
        val x = (crop.left * b.width).roundToInt().coerceIn(0, b.width - 16)
        val y = (crop.top * b.height).roundToInt().coerceIn(0, b.height - 16)
        val w = (crop.width * b.width).roundToInt().coerceIn(16, b.width - x)
        val h = (crop.height * b.height).roundToInt().coerceIn(16, b.height - y)
        work = Bitmap.createBitmap(b, x, y, w, h)
        dirty = true
        cropping = false
        crop = Rect(0.06f, 0.06f, 0.94f, 0.94f)
    }

    fun saveNow() {
        val b = work ?: return
        saving = true
        scope.launch {
            val base = shot.name.substringBeforeLast('.', shot.name)
            val df = SimpleDateFormat("MMdd_HHmmss", Locale.US)
            val outName = "${base}_edit_${df.format(Date())}.jpg"
            val dir = if (shot.dir.isBlank()) "编辑" else shot.dir
            val res = withContext(Dispatchers.IO) {
                val bytes = BatchEngine.encodeEdited(b, 92)
                Store.saveInto(ctx, dir, outName, bytes)
            }
            saving = false
            if (res.first != null) {
                dirty = false
                editing = false
                cropping = false
                onSaved()
                toast(ctx, "已保存 $outName")
            } else {
                toast(ctx, "保存失败：${res.second ?: "未知"}")
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 图片层：仍然整块落在安全区内，不会被状态栏压住
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            val b = work
            when {
                b == null -> Text(
                    "载入中…",
                    color = Color(0xFF8A94A6),
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center)
                )

                editing -> {
                    Image(
                        bitmap = b.asImageBitmap(),
                        contentDescription = shot.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().onSizeChanged { viewSize = it }
                    )
                    if (cropping) {
                        CropOverlay(b, viewSize, crop) { crop = it }
                    }
                }

                else -> ZoomableImage(b, shot.name) { chrome = !chrome }
            }
        }

        // ================= 看图模式 =================
        if (!editing && chrome) {
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
                Text(
                    shot.name,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    color = Color.White,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                CircleIcon(Icons.Filled.Info) { info = true }
            }

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
                BarAction("分享", Icons.Filled.Share) { shareImage(ctx, shot.uri, shot.name) }
                BarAction("编辑", Icons.Filled.Edit) {
                    cropping = false
                    editing = true
                }
            }
        }

        // ================= 编辑模式 =================
        if (editing) {
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
                        work = src
                        dirty = false
                        editing = false
                    }
                }
                Text(
                    if (cropping) "裁剪" else "编辑",
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    fontSize = 15.sp,
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
                    if (dirty) GlyphAction("还原", "↺") { work = src; dirty = false }
                }
            }
        }

        // ================= 文件详情面板 =================
        if (info && !editing) {
            val b = work
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
                    Text(
                        "文件详情",
                        color = Color.White,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextAction("关闭") { info = false }
                }
                Spacer(Modifier.height(6.dp))
                DetailRow("文件名", shot.name)
                DetailRow("尺寸", if (b != null) "${b.width} × ${b.height} 像素" else "—")
                DetailRow("大小", sizeText(shot.sizeKb))
                DetailRow("格式", fmtOf(shot.name))
                DetailRow("位置", positionOf(ctx, shot))
            }
        }
    }
}

/** 详情面板的一行 */
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

/** 圆形图标按钮：实心底 + 白描边，压在任何图上都能看见 */
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

/** 顶栏纯文字按钮 */
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

/** 底栏 图标 + 文字（原生相册那种上下结构） */
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

/** 底栏 符号 + 文字（旋转/镜像/裁剪没有内置图标，用几何符号，视觉统一） */
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

/**
 * 裁剪框。拖动状态必须放在内部 remember 里：
 * 拿外部传入的 crop 当唯一来源，手势里读到的会是打开裁剪那一帧的旧值，
 * 每帧只加一个很小的增量，看起来就是"划不动、划了也没用"。
 * 四角手柄拖动 → 改大小；框内拖动 → 整体平移。
 */
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
 * 双指缩放（1×–5×）、双击 2.5×/还原、放大后拖动（带边界）。
 */
@Composable
fun ZoomableImage(bmp: Bitmap, label: String, onTap: () -> Unit) {
    var scale by remember(bmp) { mutableFloatStateOf(1f) }
    var offset by remember(bmp) { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

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
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, 5f)
                    scale = next
                    offset = if (next <= 1.02f) {
                        Offset.Zero
                    } else {
                        val maxX = (viewSize.width * (next - 1f)) / 2f
                        val maxY = (viewSize.height * (next - 1f)) / 2f
                        Offset(
                            (offset.x + pan.x).coerceIn(-maxX, maxX),
                            (offset.y + pan.y).coerceIn(-maxY, maxY)
                        )
                    }
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
                    }
                )
            }
    )
}

