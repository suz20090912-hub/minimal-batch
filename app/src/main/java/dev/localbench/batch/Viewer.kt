package dev.localbench.batch

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
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

/**
 * 全屏看图 + 轻编辑。
 *
 * 交互对齐系统相册：点开有按钮；点一下画面按钮全消失只剩照片；再点一下按钮回来。
 * 顶部三颗实心药丸按钮：返回 / 编辑 / 分享，压在任何底图上都看得见。
 * 编辑：左转 / 右转 / 镜像 / 裁剪 / 还原，保存另存一张新图，不动原图。
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

    fun restore() {
        work = src
        dirty = false
    }

    fun transform(m: Matrix) {
        val b = work ?: return
        val nb = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (nb !== b) work = nb
        dirty = true
    }

    fun saveNow(onDone: () -> Unit = {}) {
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
                onDone()
            } else {
                toast(ctx, "保存失败：${res.second ?: "未知"}")
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 图片层：仍然整块落在安全区内，不会被状态栏的时间和电量压住
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

        // 看图模式：顶部/底部按钮
        if (!editing) {
            if (chrome) {
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(scrimTop)
                        .safeDrawingPadding()
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill("‹  返回") { onDismiss() }
                        Spacer(Modifier.weight(1f))
                        Pill("编辑") {
                            chrome = true
                            cropping = false
                            editing = true
                        }
                        Spacer(Modifier.width(8.dp))
                        Pill("分享") { shareImage(ctx, shot.uri, shot.name) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        shot.name,
                        color = Color(0xE6FFFFFF),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "点一下画面隐藏按钮 · 双指缩放 · 双击放大",
                    color = Color(0xFFAEB6C4),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .safeDrawingPadding()
                        .padding(bottom = 16.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x99000000))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // 编辑模式：顶部 取消/保存，底部 工具
        if (editing) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(scrimTop)
                    .safeDrawingPadding()
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Pill("取消") {
                    work = src
                    dirty = false
                    cropping = false
                    editing = false
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (cropping) "拖动四角裁剪" else "编辑（不会覆盖原图）",
                    color = Color(0xFFAEB6C4),
                    fontSize = 11.sp
                )
                Spacer(Modifier.weight(1f))
                Pill(if (saving) "保存中…" else "保存") { if (!saving && !cropping) saveNow() }
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(scrimBottom)
                    .safeDrawingPadding()
                    .padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 10.dp)
            ) {
                if (cropping) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Pill("取消裁剪") { cropping = false }
                        Pill("应用裁剪") {
                            val b = work
                            if (b != null) {
                                val x = (crop.left * b.width).roundToInt().coerceIn(0, b.width - 16)
                                val y = (crop.top * b.height).roundToInt().coerceIn(0, b.height - 16)
                                val w = (crop.width * b.width).roundToInt().coerceIn(16, b.width - x)
                                val h = (crop.height * b.height).roundToInt().coerceIn(16, b.height - y)
                                work = Bitmap.createBitmap(b, x, y, w, h)
                                dirty = true
                                cropping = false
                                crop = Rect(0.06f, 0.06f, 0.94f, 0.94f)
                            }
                        }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Pill("左转 90°") { transform(Matrix().apply { postRotate(-90f) }) }
                        Pill("右转 90°") { transform(Matrix().apply { postRotate(90f) }) }
                        Pill("镜像") { transform(Matrix().apply { postScale(-1f, 1f) }) }
                        Pill("裁剪") {
                            crop = Rect(0.06f, 0.06f, 0.94f, 0.94f)
                            cropping = true
                        }
                        if (dirty) Pill("还原") { restore() }
                    }
                }
            }
        }
    }
}

/**
 * 药丸按钮：实心底 + 1dp 描边 + 纯白字，压在任何照片上都清楚。
 */
@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White,
        fontSize = 13.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xF21A1E25))
            .border(1.dp, Color(0x4DFFFFFF), RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 15.dp, vertical = 9.dp)
    )
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

/** 裁剪框：拖四角，框外压暗，九宫格辅助线 */
@Composable
private fun CropOverlay(
    bmp: Bitmap,
    viewSize: IntSize,
    crop: Rect,
    onChange: (Rect) -> Unit
) {
    var active by remember { mutableIntStateOf(-1) }

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(bmp, viewSize) {
                detectDragGestures(
                    onDragStart = { p ->
                        val r = displayedRect(bmp, viewSize)
                        if (r.width <= 0f) {
                            active = -1
                            return@detectDragGestures
                        }
                        val pts = corners(r, crop)
                        val idx = pts.indices.minByOrNull { (pts[it] - p).getDistance() } ?: -1
                        active = if (idx >= 0 && (pts[idx] - p).getDistance() < 120f) idx else -1
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        if (active < 0) return@detectDragGestures
                        val r = displayedRect(bmp, viewSize)
                        if (r.width <= 0f) return@detectDragGestures
                        val nx = drag.x / r.width
                        val ny = drag.y / r.height
                        var l = crop.left
                        var t = crop.top
                        var ri = crop.right
                        var bo = crop.bottom
                        when (active) {
                            0 -> {
                                l = (l + nx).coerceIn(0f, ri - 0.06f)
                                t = (t + ny).coerceIn(0f, bo - 0.06f)
                            }
                            1 -> {
                                ri = (ri + nx).coerceIn(l + 0.06f, 1f)
                                t = (t + ny).coerceIn(0f, bo - 0.06f)
                            }
                            2 -> {
                                l = (l + nx).coerceIn(0f, ri - 0.06f)
                                bo = (bo + ny).coerceIn(t + 0.06f, 1f)
                            }
                            else -> {
                                ri = (ri + nx).coerceIn(l + 0.06f, 1f)
                                bo = (bo + ny).coerceIn(t + 0.06f, 1f)
                            }
                        }
                        onChange(Rect(l, t, ri, bo))
                    },
                    onDragEnd = { active = -1 },
                    onDragCancel = { active = -1 }
                )
            }
    ) {
        val r = displayedRect(bmp, viewSize)
        if (r.width <= 0f) return@Canvas
        val cr = Rect(
            r.left + crop.left * r.width,
            r.top + crop.top * r.height,
            r.left + crop.right * r.width,
            r.top + crop.bottom * r.height
        )
        val dim = Color(0xB3000000)
        drawRect(dim, Offset(r.left, r.top), Size(r.width, cr.top - r.top))
        drawRect(dim, Offset(r.left, cr.bottom), Size(r.width, r.bottom - cr.bottom))
        drawRect(dim, Offset(r.left, cr.top), Size(cr.left - r.left, cr.height))
        drawRect(dim, Offset(cr.right, cr.top), Size(r.right - cr.right, cr.height))

        // 九宫格
        val lw = 1.dp.toPx()
        val g = Color(0x40FFFFFF)
        for (i in 1..2) {
            val x = cr.left + cr.width * i / 3f
            val y = cr.top + cr.height * i / 3f
            drawLine(g, Offset(x, cr.top), Offset(x, cr.bottom), strokeWidth = lw)
            drawLine(g, Offset(cr.left, y), Offset(cr.right, y), strokeWidth = lw)
        }
        drawRect(Color.White, cr.topLeft, Size(cr.width, cr.height), style = Stroke(2.dp.toPx()))

        val hd = 11.dp.toPx()
        for (p in corners(r, crop)) {
            drawCircle(Color(0x66000000), hd * 2f, p)
            drawCircle(Color.White, hd, p)
        }
    }
}

private fun corners(r: Rect, c: Rect): List<Offset> = listOf(
    Offset(r.left + c.left * r.width, r.top + c.top * r.height),
    Offset(r.left + c.right * r.width, r.top + c.top * r.height),
    Offset(r.left + c.left * r.width, r.top + c.bottom * r.height),
    Offset(r.left + c.right * r.width, r.top + c.bottom * r.height)
)

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


