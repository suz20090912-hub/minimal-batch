package dev.localbench.batch

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 断点续跑：把这一批的"待办清单 + 已完成项"落在文件里。
 *
 * 文件格式（纯文本，第一行是表头，之后每行是一条已完成记录）：
 *
 *   {"uris":[...],"preset":"...","targetKb":0,"webp":false,"prefix":"","total":N}
 *   {"u":"content://....","name":"a.jpg","srcKb":..,"outKb":..,"outW":..,"outH":..,"ms":..,"out":"content://.."}
 *
 * 已完成项按"图片地址"记，不按序号——清单顺序一变（冷启动恢复、重新排序），
 * 按序号记就会跳错图，按地址记永远不会。
 */
object ResumeStore {

    private const val NAME = "batch_resume.txt"
    private val lock = Any()

    private fun file(ctx: Context) =
        File((ctx.applicationContext ?: ctx).filesDir, NAME)

    data class Header(
        val uris: List<Uri>,
        val preset: Preset,
        val targetKb: Int,
        val webp: Boolean,
        val prefix: String
    )

    fun begin(ctx: Context, header: Header) {
        synchronized(lock) {
            runCatching {
                val o = JSONObject()
                o.put("uris", JSONArray(header.uris.map { it.toString() }))
                o.put("preset", header.preset.name)
                o.put("targetKb", header.targetKb)
                o.put("webp", header.webp)
                o.put("prefix", header.prefix)
                o.put("total", header.uris.size)
                o.put("at", System.currentTimeMillis())
                file(ctx).writeText(o.toString())
            }
        }
    }

    fun append(ctx: Context, uri: Uri, d: Done) {
        synchronized(lock) {
            runCatching {
                val o = JSONObject()
                o.put("u", uri.toString())
                o.put("name", d.name)
                o.put("srcKb", d.srcKb)
                o.put("outKb", d.outKb)
                o.put("outW", d.outW)
                o.put("outH", d.outH)
                o.put("ms", d.ms)
                o.put("out", d.outUri?.toString() ?: "")
                o.put("err", d.error ?: "")
                file(ctx).appendText("\n" + o.toString())
            }
        }
    }

    /**
     * 读回上次没跑完的那批。文件不存在、表头坏了、或者已经全做完，都返回 null。
     * 第二个返回值以「图片地址」为键。
     */
    fun load(ctx: Context): Pair<Header, Map<String, Done>>? {
        val f = file(ctx)
        if (!f.exists()) return null
        return runCatching {
            val lines = f.readLines()
            if (lines.isEmpty()) return null
            val head = JSONObject(lines.first())
            val uris = ArrayList<Uri>()
            val arr = head.optJSONArray("uris") ?: return null
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "")
                if (s.isNotEmpty()) uris.add(Uri.parse(s))
            }
            if (uris.isEmpty()) return null
            val preset = runCatching { Preset.valueOf(head.optString("preset", "")) }
                .getOrDefault(Preset.WECHAT)
            val header = Header(
                uris = uris,
                preset = preset,
                targetKb = head.optInt("targetKb", 0),
                webp = head.optBoolean("webp", false),
                prefix = head.optString("prefix", "")
            )
            val doneMap = LinkedHashMap<String, Done>()
            lines.drop(1).forEach { line ->
                if (line.isBlank()) return@forEach
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                val key = o.optString("u", "")
                if (key.isEmpty()) return@forEach
                val out = o.optString("out", "")
                doneMap[key] = Done(
                    name = o.optString("name", ""),
                    srcKb = o.optInt("srcKb", 0),
                    outKb = o.optInt("outKb", 0),
                    outW = o.optInt("outW", 0),
                    outH = o.optInt("outH", 0),
                    ms = o.optLong("ms", 0L),
                    outUri = if (out.isEmpty()) null else runCatching { Uri.parse(out) }.getOrNull(),
                    error = o.optString("err", "").ifEmpty { null }
                )
            }
            // 全都做完了，这份断点没有意义
            if (uris.all { it.toString() in doneMap }) return null
            header to doneMap
        }.getOrNull()
    }

    fun clear(ctx: Context) {
        synchronized(lock) {
            runCatching { file(ctx).delete() }
        }
    }
}
