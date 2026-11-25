package dev.localbench.batch

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
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
    val items: List<Done>
) {
    val ok: List<Done> get() = items.filter { it.error == null }
    val srcTotalKb: Int get() = ok.sumOf { it.srcKb }
    val outTotalKb: Int get() = ok.sumOf { it.outKb }
    val savedKb: Int get() = max(0, srcTotalKb - outTotalKb)
    val savedPct: Int
        get() = if (srcTotalKb == 0) 0 else ((savedKb * 100.0) / srcTotalKb).roundToInt()
}

object BatchEngine {

    const val OUT_FOLDER = "Pictures/极简批处理"

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
        onProgress: (done: Int, total: Int) -> Unit
    ): Batch = coroutineScope {
        val sem = Semaphore(lanes.coerceIn(1, 8))
        val done = AtomicInteger(0)
        val t0 = SystemClock.elapsedRealtime()

        val items = uris.mapIndexed { index, uri ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val item = process(ctx, uri, preset, prefix, index + 1)
                    val n = done.incrementAndGet()
                    withContext(Dispatchers.Main) { onProgress(n, uris.size) }
                    item
                }
            }
        }.awaitAll()

        Batch(preset, SystemClock.elapsedRealtime() - t0, items)
    }

    private fun process(
        ctx: Context,
        uri: Uri,
        preset: Preset,
        prefix: String,
        seq: Int
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
                if (preset.square) {
                    val short = min(srcW, srcH)
                    if (short > preset.outW) {
                        val f = preset.outW.toFloat() / short.toFloat()
                        decoder.setTargetSize(max(1, (srcW * f).roundToInt()), preset.outW)
                    }
                } else if (preset.outW > 0 && srcW > preset.outW) {
                    val f = preset.outW.toFloat() / srcW.toFloat()
                    decoder.setTargetSize(preset.outW, max(1, (srcH * f).roundToInt()))
                }
            }

            val out = buildOutput(decoded, preset)

            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, preset.quality, bos)
            val data = bos.toByteArray()
            val outW = out.width
            val outH = out.height
            if (out !== decoded) out.recycle()

            val outName = buildName(name, preset, prefix, seq)
            val saved = saveToGallery(ctx, outName, data)
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

    private fun buildName(srcName: String, preset: Preset, prefix: String, seq: Int): String {
        val base = srcName.substringBeforeLast('.', srcName)
        return if (prefix.isBlank()) {
            base + "_" + preset.tag + ".jpg"
        } else {
            prefix.trim() + "_" + seq.toString().padStart(3, '0') + ".jpg"
        }
    }

    // ---------------- 撤销 ----------------

    /** 删掉这一批写进图库的文件，返回删除条数。原图不受影响。 */
    fun undo(ctx: Context, batch: Batch): Int {
        var n = 0
        batch.items.forEach { item ->
            val u = item.outUri ?: return@forEach
            try {
                if (ctx.contentResolver.delete(u, null, null) > 0) n++
            } catch (_: Exception) {
            }
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

    private fun saveToGallery(ctx: Context, outName: String, data: ByteArray): Pair<Uri?, String?> {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, outName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, OUT_FOLDER)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null to "图库写入被拒"
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
            uri to null
        } catch (t: Throwable) {
            null to (t.message ?: t.javaClass.simpleName)
        }
    }
}
