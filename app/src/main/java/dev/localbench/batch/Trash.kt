package dev.localbench.batch

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 最近删除（回收站）。
 *
 * 成品页/看图页删掉的图不直接消失，先搬进 App 私有目录，留 7 天。
 * 放私有目录而不是 Pictures 下，是因为那里不进系统相册：删掉就真的从相册里消失，
 * 不会在相册里留一个"看不见"的隐藏文件夹；也不受自定义目录授权失效影响。
 *
 * 元数据写在 filesDir/trash/index.json，实体文件是 filesDir/trash/<id>。
 */
object Trash {

    /** 一条回收站记录。 */
    data class Item(
        val id: String,
        val name: String,
        /** 原来在哪个场景（Pictures/<根>/<场景>） */
        val scene: String,
        val sizeKb: Int,
        /** 移进来的时间 */
        val at: Long,
        /** 移进来时用的是哪个输出位置（media / tree） */
        val kind: String,
        val locValue: String
    ) {
        /** 还剩几天（0 表示今天到期） */
        fun daysLeft(now: Long): Int {
            val left = KEEP_MS - (now - at)
            return if (left <= 0) 0 else ((left + 86399_999L) / 86400_000L).toInt()
        }
    }

    const val KEEP_DAYS = 7L
    private const val KEEP_MS = KEEP_DAYS * 24L * 60L * 60L * 1000L
    /** 超过这个大小就不搬了，直接删（成品图正常不会到这） */
    private const val MAX_BYTES = 32L * 1024 * 1024

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, "trash").apply { if (!exists()) mkdirs() }

    private fun indexFile(ctx: Context): File = File(dir(ctx), "index.json")

    private fun bodyFile(ctx: Context, id: String): File = File(dir(ctx), id)

    // ---------------- 索引读写 ----------------

    @Synchronized
    private fun readIndex(ctx: Context): MutableList<Item> {
        val out = ArrayList<Item>()
        val f = indexFile(ctx)
        if (!f.exists()) return out
        runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isEmpty()) continue
                out += Item(
                    id = id,
                    name = o.optString("name", "image.jpg"),
                    scene = o.optString("scene", "未分类（旧版）"),
                    sizeKb = o.optInt("size", 0),
                    at = o.optLong("at", 0L),
                    kind = o.optString("kind", "media"),
                    locValue = o.optString("loc", Store.DEFAULT_ROOT)
                )
            }
        }
        return out
    }

    @Synchronized
    private fun writeIndex(ctx: Context, items: List<Item>) {
        val arr = JSONArray()
        items.forEach { it ->
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("scene", it.scene)
                    .put("size", it.sizeKb)
                    .put("at", it.at)
                    .put("kind", it.kind)
                    .put("loc", it.locValue)
            )
        }
        runCatching { indexFile(ctx).writeText(arr.toString()) }
    }

    // ---------------- 对外 ----------------

    /** 当前回收站里有多少张。 */
    fun count(ctx: Context): Int = readIndex(ctx).count { bodyFile(ctx, it.id).exists() }

    /** 列出来给页面用；顺带把过期和实体已丢的条目清掉。 */
    @Synchronized
    fun list(ctx: Context): List<Item> {
        val now = System.currentTimeMillis()
        val all = readIndex(ctx)
        val keep = ArrayList<Item>(all.size)
        all.forEach { item ->
            val f = bodyFile(ctx, item.id)
            if (!f.exists()) return@forEach
            if (now - item.at > KEEP_MS) {
                runCatching { f.delete() }
                return@forEach
            }
            keep += item
        }
        if (keep.size != all.size) writeIndex(ctx, keep)
        return keep.sortedByDescending { it.at }
    }

    /** 到期就自动清掉，App 启动时跑一次。返回清了几张。 */
    @Synchronized
    fun sweep(ctx: Context): Int {
        val now = System.currentTimeMillis()
        val all = readIndex(ctx)
        var n = 0
        val keep = ArrayList<Item>(all.size)
        all.forEach { item ->
            val f = bodyFile(ctx, item.id)
            if (!f.exists()) return@forEach
            if (now - item.at > KEEP_MS) {
                if (runCatching { f.delete() }.getOrDefault(false)) n++
                return@forEach
            }
            keep += item
        }
        if (keep.size != all.size) writeIndex(ctx, keep)
        return n
    }

    /**
     * 把一张成品搬进回收站。
     *
     * 顺序是"先写副本、再记索引、最后删原图"：任何一步失败都不会丢图——
     * 副本写入失败就什么都没发生；删原图失败就把索引和副本一起收回。
     */
    fun moveIn(ctx: Context, loc: Location, shot: Shot): Boolean {
        val bytes = try {
            ctx.contentResolver.openInputStream(shot.uri)?.use { it.readBytes() }
        } catch (_: Throwable) {
            null
        }
        if (bytes == null) {
            // 读不出来（文件已经不在之类），只能按原样删掉，别卡住用户
            return Store.delete(ctx, shot.uri)
        }
        if (bytes.size > MAX_BYTES) {
            return Store.delete(ctx, shot.uri)
        }

        val id = "${System.currentTimeMillis()}_${(1000..9999).random()}"
        val body = bodyFile(ctx, id)
        val written = runCatching { body.writeBytes(bytes) }.isSuccess
        if (!written) {
            toast(ctx, "存储空间不够，这张直接删掉了")
            return Store.delete(ctx, shot.uri)
        }

        val item = Item(
            id = id,
            name = shot.name,
            scene = shot.dir,
            sizeKb = bytes.size / 1024,
            at = System.currentTimeMillis(),
            kind = loc.kind,
            locValue = loc.value
        )
        val all = readIndex(ctx)
        all += item
        writeIndex(ctx, all)

        val ok = Store.delete(ctx, shot.uri)
        if (!ok) {
            // 原图没删掉，把副本和索引收回，保持"要么两边都在，要么两边都没有"
            runCatching { body.delete() }
            writeIndex(ctx, all.filterNot { it.id == id })
        }
        return ok
    }

    /** 还原回成品目录。会放回它原来的场景子文件夹，但位置用当前选的输出位置。 */
    fun restore(ctx: Context, item: Item): Boolean {
        val body = bodyFile(ctx, item.id)
        if (!body.exists()) return false
        val bytes = runCatching { body.readBytes() }.getOrNull() ?: return false
        val (uri, err) = Store.saveInto(ctx, item.scene, item.name, bytes, mimeOf(item.name))
        if (uri == null) {
            toast(ctx, "还原失败：${err ?: "写不进去"}")
            return false
        }
        runCatching { body.delete() }
        writeIndex(ctx, readIndex(ctx).filterNot { it.id == item.id })
        return true
    }

    /** 彻底删掉一条。 */
    fun purge(ctx: Context, item: Item): Boolean {
        val ok = runCatching { bodyFile(ctx, item.id).delete() }.getOrDefault(false)
        writeIndex(ctx, readIndex(ctx).filterNot { it.id == item.id })
        return ok
    }

    /** 清空回收站，返回删掉几张。 */
    fun purgeAll(ctx: Context): Int {
        val all = readIndex(ctx)
        var n = 0
        all.forEach { item ->
            if (runCatching { bodyFile(ctx, item.id).delete() }.getOrDefault(false)) n++
        }
        writeIndex(ctx, emptyList())
        return n
    }

    private fun mimeOf(name: String): String {
        val n = name.lowercase()
        return when {
            n.endsWith(".webp") -> "image/webp"
            n.endsWith(".png") -> "image/png"
            else -> "image/jpeg"
        }
    }

    /** 回收站里的文件地址，给缩略图用。 */
    fun uriOf(ctx: Context, item: Item): Uri = Uri.fromFile(bodyFile(ctx, item.id))
}
