package dev.localbench.batch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 场景预设。outW=0 表示保持原尺寸，只转格式。 */
enum class Preset(
    val label: String,
    val tag: String,
    val outW: Int,
    val quality: Int,
    val square: Boolean
) {
    WECHAT("微信发送", "微信", 1920, 82, false),
    ARCHIVE("存档原图", "存档", 0, 90, false),
    SOCIAL("社交方图", "方图", 1080, 85, true)
}

/**
 * 输出约束：目标体积（KB，0 表示不限）和 WebP。
 * 目标体积是真的压：先降质量，压不下去再缩尺寸，直到进线或者到底线为止。
 */
data class OutputPlan(
    val targetKb: Int = 0,
    val webp: Boolean = false
) {
    val mime: String get() = if (webp) "image/webp" else "image/jpeg"
    val ext: String get() = if (webp) ".webp" else ".jpg"
    val label: String
        get() {
            val size = when {
                targetKb <= 0 -> "不限体积"
                targetKb >= 1024 -> "≤%.1fMB".format(targetKb / 1024f)
                else -> "≤${targetKb}KB"
            }
            return if (webp) "$size · WebP" else size
        }
}

/** 选图后的预览信息（只读头部，不真解码）。 */
data class Picked(
    val uri: Uri,
    val name: String,
    val w: Int,
    val h: Int,
    val sizeKb: Int
)

/** 一张处理完的结果。 */
data class Done(
    val name: String,
    val srcKb: Int,
    val outKb: Int,
    val outW: Int,
    val outH: Int,
    val ms: Long,
    val outUri: Uri?,
    val error: String? = null
)

/** 一批处理完的总账。 */
data class Batch(
    val preset: Preset,
    val wallMs: Long,
    val items: List<Done>,
    val plan: OutputPlan = OutputPlan()
) {
    val ok: List<Done> get() = items.filter { it.error == null }
    val srcTotalKb: Int get() = ok.sumOf { it.srcKb }
    val outTotalKb: Int get() = ok.sumOf { it.outKb }
    val savedKb: Int get() = max(0, srcTotalKb - outTotalKb)
    val savedPct: Int
        get() = if (srcTotalKb == 0) 0 else ((savedKb * 100.0) / srcTotalKb).roundToInt()
}

object BatchEngine {

    /** 预览产物：原图缩略图 + 处理后缩略图 + 预计体积。 */
    data class Preview(
        val picked: Picked,
        val beforeThumb: Bitmap?,
        val afterThumb: Bitmap?,
        val outW: Int,
        val outH: Int,
        val estKb: Int,
        val error: String? = null
    )

    // ---------------- 体积预估 ----------------

    /** 预估结果缓存，键是 图 + 预设 + 目标体积 + 格式，滚动来回不会再算一遍。 */
    private val estCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 按预设算出来的输出尺寸（与 applyTargetSize / buildOutput 同一口径）。 */
    fun outSizeOf(w: Int, h: Int, preset: Preset): Pair<Int, Int> {
        if (preset.square) {
            val side = min(w, h).coerceAtLeast(1)
            return if (preset.outW > 0 && side > preset.outW) preset.outW to preset.outW
            else side to side
        }
        return if (preset.outW == 0 || w <= preset.outW) w to h
        else preset.outW to max(1, (h.toFloat() * preset.outW.toFloat() / w.toFloat()).roundToInt())
    }

    /**
     * 选图时给每张标个"大概多大"。
     *
     * 设了目标体积就不算了——那本来就是承诺压到这个数以内。
     * 没设目标体积时不真跑全图（50MP 一张几百毫秒，几十张太慢），
     * 而是解码到长边 480 的小图、用同一个编码器和同一个质量真压一次，
     * 再按面积比例外推。小图每像素开销比大图略高，乘 0.9 修一下，界面标"约"。
     */
    suspend fun estimateKb(
        ctx: Context,
        picked: Picked,
        preset: Preset,
        plan: OutputPlan
    ): Int? = withContext(Dispatchers.IO) {
        if (plan.targetKb > 0) return@withContext plan.targetKb
        if (picked.w <= 0 || picked.h <= 0) return@withContext null
        val key = picked.uri.toString() + "|" + preset.name + "|" + plan.targetKb + "|" + plan.webp
        estCache[key]?.let { return@withContext it }

        val bytes = runCatching { readAll(ctx, picked.uri) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) return@withContext null

        val (ow, oh) = outSizeOf(picked.w, picked.h, preset)
        val long = max(ow, oh).coerceAtLeast(1)
        val scale = min(long, 480).toFloat() / long.toFloat()

        var sw = 0
        var sh = 0
        val bmp = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, info, _ ->
                val w = info.size.width
                val h = info.size.height
                sw = max(1, (w * scale).roundToInt())
                sh = max(1, (h * scale).roundToInt())
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                d.isMutableRequired = false
                d.setTargetSize(sw, sh)
            }
        }.getOrNull() ?: return@withContext null

        val data = try {
            val bos = ByteArrayOutputStream()
            val fmt = if (plan.webp && canWebp()) Bitmap.CompressFormat.WEBP_LOSSY
            else Bitmap.CompressFormat.JPEG
            bmp.compress(fmt, preset.quality.coerceIn(1, 100), bos)
            bos.toByteArray()
        } catch (_: Throwable) {
            ByteArray(0)
        } finally {
            bmp.recycle()
        }
        if (data.isEmpty() || sw <= 0 || sh <= 0) return@withContext null

        val samplePixels = sw.toLong() * sh.toLong()
        val outPixels = ow.toLong() * oh.toLong()
        val est = (data.size.toDouble() * outPixels / samplePixels * 0.9 / 1024.0).roundToInt()
        if (est > 0) estCache[key] = est
        est
    }

    // ---------------- 预览 ----------------

    suspend fun inspectAll(ctx: Context, uris: List<Uri>): List<Picked> = coroutineScope {
        uris.map { uri ->
            async(Dispatchers.IO) { inspect(ctx, uri) }
        }.awaitAll()
    }

    fun inspect(ctx: Context, uri: Uri): Picked {
        val name = displayName(ctx, uri)
        var sizeKb = 0
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0) sizeKb = (c.getLong(idx) / 1024L).toInt()
                }
            }
        } catch (_: Exception) {
        }

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {
        }
        return Picked(uri, name, opts.outWidth, opts.outHeight, sizeKb)
    }

    /** 预览用：算出目标输出尺寸。 */
    fun expectedOut(p: Picked, preset: Preset): Pair<Int, Int> {
        if (p.w <= 0 || p.h <= 0) return 0 to 0
        if (preset.square) {
            val short = min(p.w, p.h)
            if (short <= preset.outW) return short to short
            val f = preset.outW.toFloat() / short.toFloat()
            return max(1, (p.w * f).roundToInt()) to preset.outW
        }
        if (preset.outW == 0 || p.w <= preset.outW) return p.w to p.h
        val f = preset.outW.toFloat() / p.w.toFloat()
        return preset.outW to max(1, (p.h * f).roundToInt())
    }

    // ---------------- 处理 ----------------

    suspend fun runBatch(
        ctx: Context,
        uris: List<Uri>,
        preset: Preset,
        lanes: Int,
        prefix: String,
        plan: OutputPlan = OutputPlan(),
        /** 上次已经做完的那些（以图片地址为键），这些不再重复处理。 */
        resume: Map<String, Done> = emptyMap(),
        /** 每做完一张回调一次，用于写断点文件。 */
        onItem: (uri: Uri, done: Done) -> Unit = { _, _ -> },
        onProgress: (done: Int, total: Int) -> Unit
    ): Batch = coroutineScope {
        val sem = Semaphore(lanes.coerceIn(1, 8))
        val keys = uris.map { it.toString() }
        val finished = AtomicInteger(keys.count { it in resume })
        val t0 = SystemClock.elapsedRealtime()
        val total = uris.size
        withContext(Dispatchers.Main) { onProgress(finished.get(), total) }

        val fresh = uris.mapIndexedNotNull { index, uri ->
            val key = uri.toString()
            if (key in resume) null
            else async(Dispatchers.IO) {
                sem.withPermit {
                    val item = process(ctx, uri, preset, prefix, index + 1, plan)
                    runCatching { onItem(uri, item) }
                    val n = finished.incrementAndGet()
                    withContext(Dispatchers.Main) { onProgress(n, total) }
                    key to item
                }
            }
        }.awaitAll()

        val freshMap = fresh.toMap()
        // 按当前清单顺序拼回去，上次做完的插在原来的位置
        val all = uris.mapNotNull { uri -> resume[uri.toString()] ?: freshMap[uri.toString()] }
        Batch(preset, SystemClock.elapsedRealtime() - t0, all, plan)
    }

    private fun process(
        ctx: Context,
        uri: Uri,
        preset: Preset,
        prefix: String,
        seq: Int,
        plan: OutputPlan
    ): Done {
        val name = displayName(ctx, uri)
        var srcW = 0
        var srcH = 0
        var srcKb = 0
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0) srcKb = (c.getLong(idx) / 1024L).toInt()
                }
            }
        } catch (_: Exception) {
        }

        val tStart = System.nanoTime()
        var decoded: Bitmap? = null
        try {
            val bytes = readAll(ctx, uri)
            if (bytes.isEmpty()) {
                return Done(name, srcKb, 0, 0, 0, 0, null, "读取失败")
            }

            decoded = ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            ) { decoder, info, _ ->
                srcW = info.size.width
                srcH = info.size.height
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                applyTargetSize(decoder, info.size.width, info.size.height, preset)
            }

            val out = buildOutput(decoded, preset)
            val data = compressToTarget(out, preset.quality, plan)
            val outW = out.width
            val outH = out.height
            if (out !== decoded) out.recycle()

            val outName = buildName(name, preset, prefix, seq, plan.ext)
            val saved = Store.save(ctx, preset, outName, data, plan.mime)
            val ms = (System.nanoTime() - tStart) / 1_000_000L

            return if (saved.first != null) {
                Done(name, srcKb, data.size / 1024, outW, outH, ms, saved.first, null)
            } else {
                Done(name, srcKb, data.size / 1024, outW, outH, ms, null, saved.second)
            }
        } catch (t: Throwable) {
            val ms = (System.nanoTime() - tStart) / 1_000_000L
            return Done(name, srcKb, 0, srcW, srcH, ms, null, t.message ?: t.javaClass.simpleName)
        } finally {
            decoded?.recycle()
        }
    }

    /** 裁方 + 缩放。绝不回收入参 src（由调用方的 finally 负责）。 */
    private fun buildOutput(src: Bitmap, preset: Preset): Bitmap {
        if (!preset.square) {
            if (preset.outW == 0 || src.width <= preset.outW) return src
            val f = preset.outW.toFloat() / src.width.toFloat()
            val h = max(1, (src.height * f).roundToInt())
            return Bitmap.createScaledBitmap(src, preset.outW, h, true)
        }
        var cur = src
        val side = min(cur.width, cur.height)
        if (side < cur.width || side < cur.height) {
            cur = Bitmap.createBitmap(cur, (cur.width - side) / 2, (cur.height - side) / 2, side, side)
        }
        if (cur.width > preset.outW) {
            val scaled = Bitmap.createScaledBitmap(cur, preset.outW, preset.outW, true)
            if (cur !== src) cur.recycle()
            return scaled
        }
        return cur
    }

    private fun buildName(srcName: String, preset: Preset, prefix: String, seq: Int, ext: String): String {
        val base = srcName.substringBeforeLast('.', srcName)
        return if (prefix.isBlank()) {
            base + "_" + preset.tag + ext
        } else {
            prefix.trim() + "_" + seq.toString().padStart(3, '0') + ext
        }
    }

    /** 编辑后的图另存，质量 92 */
    fun encodeEdited(src: Bitmap, quality: Int = 92): ByteArray = encode(src, quality, false)

    /**
     * 真正压到目标体积：先逐档降质量，压不下去再缩尺寸（长边最低 640），
     * 到底线还压不下来就返回最小那一版，不硬凑。
     */
    private fun compressToTarget(src: Bitmap, quality: Int, plan: OutputPlan): ByteArray {
        val limit = plan.targetKb.toLong() * 1024L
        if (plan.targetKb <= 0) return encode(src, quality, plan.webp && canWebp())

        var best = encode(src, quality, plan.webp && canWebp())
        if (best.size.toLong() <= limit) return best

        var q = quality
        while (q > 40) {
            q = max(40, q - 8)
            best = encode(src, q, plan.webp && canWebp())
            if (best.size.toLong() <= limit) return best
        }

        var cur = src
        var mine = false
        var scale = 0.85f
        try {
            repeat(4) {
                val nw = max(1, (cur.width * scale).roundToInt())
                val nh = max(1, (cur.height * scale).roundToInt())
                if (max(nw, nh) < 640) return best
                val scaled = Bitmap.createScaledBitmap(cur, nw, nh, true)
                if (mine) cur.recycle()
                cur = scaled
                mine = true
                val b = encode(cur, 40, plan.webp && canWebp())
                if (b.size.toLong() <= limit) {
                    best = b
                    return best
                }
                scale *= 0.85f
            }
        } finally {
            if (mine) cur.recycle()
        }
        return best
    }

    /** WebP 有损编码从 API 30 才有；低版本悄悄回落成 JPEG。 */
    private fun canWebp(): Boolean = android.os.Build.VERSION.SDK_INT >= 30

    private fun encode(src: Bitmap, quality: Int, webp: Boolean): ByteArray {
        // WebP 支持透明通道；JPEG 要先合白底，否则透明区域变黑块
        val flat = if (!webp && src.hasAlpha()) flattenOnWhite(src) else src
        val bos = ByteArrayOutputStream()
        val fmt = if (webp) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.JPEG
        flat.compress(fmt, quality.coerceIn(1, 100), bos)
        if (flat !== src) flat.recycle()
        return bos.toByteArray()
    }

    private fun encodeJpeg(src: Bitmap, quality: Int): ByteArray = encode(src, quality, false)

    private fun flattenOnWhite(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, 0f, 0f, null)
        return out
    }

    // ---------------- 预览 ----------------

    /**
     * 真预览：每张图按当前场景跑一遍完整链路（解码→缩放/裁方→编码），
     * 只是不写文件，把缩略图和"预计体积"带回来。
     */
    suspend fun buildPreviews(
        ctx: Context,
        uris: List<Uri>,
        preset: Preset,
        limit: Int = 20,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<Preview> = coroutineScope {
        val list = uris.take(limit)
        val sem = Semaphore(3)
        val done = AtomicInteger(0)
        list.map { uri ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val p = previewOne(ctx, uri, preset)
                    val n = done.incrementAndGet()
                    withContext(Dispatchers.Main) { onProgress(n, list.size) }
                    p
                }
            }
        }.awaitAll()
    }

    private fun previewOne(ctx: Context, uri: Uri, preset: Preset): Preview {
        val p = inspect(ctx, uri)
        val bytes = readAll(ctx, uri)
        if (bytes.isEmpty()) return Preview(p, null, null, 0, 0, 0, "读取失败")

        var beforeThumb: Bitmap? = null
        var decoded: Bitmap? = null
        var out: Bitmap? = null
        try {
            beforeThumb = decodeSampled(bytes, 360)

            decoded = decodeForPreset(bytes, preset)
            out = buildOutput(decoded, preset)
            val data = encodeJpeg(out, preset.quality)
            val after = decodeSampled(data, 360)

            return Preview(p, beforeThumb, after, out.width, out.height, data.size / 1024)
        } catch (t: Throwable) {
            return Preview(p, beforeThumb, null, 0, 0, 0, t.message ?: t.javaClass.simpleName)
        } finally {
            if (out != null && out !== decoded) out.recycle()
            decoded?.recycle()
        }
    }

    private fun decodeForPreset(bytes: ByteArray, preset: Preset): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            applyTargetSize(decoder, info.size.width, info.size.height, preset)
        }

    private fun decodeSampled(bytes: ByteArray, maxSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val w = info.size.width
            val h = info.size.height
            val long = max(w, h)
            if (long > maxSide) {
                val f = maxSide.toFloat() / long.toFloat()
                decoder.setTargetSize(max(1, (w * f).roundToInt()), max(1, (h * f).roundToInt()))
            }
        }

    private fun applyTargetSize(decoder: ImageDecoder, w: Int, h: Int, preset: Preset) {
        if (preset.square) {
            val short = min(w, h)
            if (short > preset.outW) {
                val f = preset.outW.toFloat() / short.toFloat()
                decoder.setTargetSize(max(1, (w * f).roundToInt()), preset.outW)
            }
        } else if (preset.outW > 0 && w > preset.outW) {
            val f = preset.outW.toFloat() / w.toFloat()
            decoder.setTargetSize(preset.outW, max(1, (h * f).roundToInt()))
        }
    }

    /** 从图库 Uri 读一张缩略图（结果浏览用）。 */
    suspend fun loadThumb(ctx: Context, uri: Uri, maxSide: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                val bytes = readAll(ctx, uri)
                if (bytes.isEmpty()) null else decodeSampled(bytes, maxSide)
            } catch (_: Throwable) {
                null
            }
        }

    // ---------------- 撤销 ----------------

    /** 删掉这一批写进图库的文件，返回删除条数。原图不受影响。 */
    fun undo(ctx: Context, batch: Batch): Int {
        var n = 0
        batch.items.forEach { item ->
            val u = item.outUri ?: return@forEach
            if (Store.delete(ctx, u)) n++
        }
        return n
    }

    // ---------------- 工具 ----------------

    private fun readAll(ctx: Context, uri: Uri): ByteArray {
        val input = ctx.contentResolver.openInputStream(uri) ?: return ByteArray(0)
        return input.use { it.readBytes() }
    }

    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val v = c.getString(idx)
                        if (!v.isNullOrBlank()) return v
                    }
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "unknown"
    }
}






