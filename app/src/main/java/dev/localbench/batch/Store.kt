package dev.localbench.batch

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore

/** 成品库里的一条记录。 */
data class Shot(
    val uri: Uri,
    val name: String,
    val sizeKb: Int,
    val dir: String
)

/** 一个用过的输出位置（Pictures 下的根目录，或某个自定义文件夹）。 */
data class Location(val kind: String, val value: String, val label: String)

/**
 * 输出位置的唯一入口。
 *
 * 两种模式：
 *  - 图库模式（默认）：Pictures/<根名>/<场景>，走 MediaStore，系统相册可见、无需权限。
 *  - 自定义文件夹：用户通过系统文件夹选择器选的任意目录，走 SAF（DocumentsContract），
 *    系统相册不保证索引，App 内「成品」页兜底。
 */
object Store {

    const val PREF = "minimal_batch"
    const val DEFAULT_ROOT = "极简批处理"
    private const val K_ROOT = "out_root"
    private const val K_TREE = "out_tree"
    private const val K_LOCS = "out_locs"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---------------- 位置历史 ----------------

    /** 当前位置。 */
    fun currentLocation(ctx: Context): Location {
        val t = tree(ctx)
        return if (t != null) {
            val seg = t.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() } ?: "文件夹"
            Location("tree", t.toString(), "自定义：$seg")
        } else {
            Location("media", root(ctx), "Pictures/${root(ctx)}")
        }
    }

    /** 当前位置在最前面，后面挂着用过的最多 5 个。 */
    fun locations(ctx: Context): List<Location> {
        val cur = currentLocation(ctx)
        val stored = prefs(ctx).getStringSet(K_LOCS, emptySet()) ?: emptySet()
        val parsed = stored.mapNotNull { raw ->
            val p = raw.split("|", limit = 3)
            if (p.size < 3) null else Location(p[0], p[1], p[2])
        }
        val out = ArrayList<Location>()
        out += cur
        parsed.forEach { if (it.kind != cur.kind || it.value != cur.value) out += it }
        return out.distinctBy { "${it.kind}|${it.value}" }.take(6)
    }

    private fun rememberLocation(ctx: Context, loc: Location) {
        val stored = prefs(ctx).getStringSet(K_LOCS, emptySet())?.toMutableSet() ?: mutableSetOf()
        stored.removeAll { raw ->
            val p = raw.split("|", limit = 3)
            p.size >= 2 && p[0] == loc.kind && p[1] == loc.value
        }
        stored += "${loc.kind}|${loc.value}|${loc.label}"
        while (stored.size > 5) stored.remove(stored.first())
        prefs(ctx).edit().putStringSet(K_LOCS, stored).apply()
    }

    // ---------------- 位置配置 ----------------

    fun root(ctx: Context): String =
        prefs(ctx).getString(K_ROOT, DEFAULT_ROOT)?.takeIf { it.isNotBlank() } ?: DEFAULT_ROOT

    fun setRoot(ctx: Context, value: String) {
        prefs(ctx).edit().putString(K_ROOT, value.trim().ifBlank { DEFAULT_ROOT }).apply()
        rememberLocation(ctx, currentLocation(ctx))
    }

    fun tree(ctx: Context): Uri? =
        prefs(ctx).getString(K_TREE, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun setTree(ctx: Context, uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Throwable) {
        }
        prefs(ctx).edit().putString(K_TREE, uri.toString()).apply()
        rememberLocation(ctx, currentLocation(ctx))
    }

    fun clearTree(ctx: Context) {
        tree(ctx)?.let { u ->
            try {
                ctx.contentResolver.releasePersistableUriPermission(
                    u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Throwable) {
            }
        }
        prefs(ctx).edit().remove(K_TREE).apply()
    }

    /** 给用户看的当前位置。 */
    fun where(ctx: Context): String =
        if (tree(ctx) != null) "自定义文件夹（已授权）" else "Pictures/${root(ctx)}"

    fun isCustom(ctx: Context): Boolean = tree(ctx) != null

    // ---------------- 写入 / 删除 ----------------

    fun save(ctx: Context, preset: Preset, fileName: String, data: ByteArray): Pair<Uri?, String?> {
        return saveInto(ctx, preset.label, fileName, data)
    }

    /** 指定场景子目录写入，编辑后的新图也走这里 */
    fun saveInto(ctx: Context, dir: String, fileName: String, data: ByteArray): Pair<Uri?, String?> {
        val t = tree(ctx)
        return if (t != null) saveToTree(ctx, t, dir, fileName, data)
        else saveToMedia(ctx, "${root(ctx)}/$dir", fileName, data)
    }

    private fun saveToMedia(
        ctx: Context,
        relDir: String,
        name: String,
        data: ByteArray
    ): Pair<Uri?, String?> {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$relDir")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null to "图库写入被拒"
            val os = ctx.contentResolver.openOutputStream(uri)
                ?: return null to "无法打开输出流"
            os.use { it.write(data) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
            uri to null
        } catch (t: Throwable) {
            null to (t.message ?: t.javaClass.simpleName)
        }
    }

    private fun saveToTree(
        ctx: Context,
        tree: Uri,
        dir: String,
        name: String,
        data: ByteArray
    ): Pair<Uri?, String?> {
        val r = ctx.contentResolver
        return try {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            var dirId = childId(r, tree, rootId, dir, true)
            if (dirId == null) {
                val created = DocumentsContract.createDocument(
                    r, docUri(tree, rootId), DocumentsContract.Document.MIME_TYPE_DIR, dir
                ) ?: return null to "子文件夹创建失败"
                dirId = DocumentsContract.getDocumentId(created)
            }
            val docId = childId(r, tree, dirId, name, false)
            val target = if (docId != null) {
                docUri(tree, docId)
            } else {
                DocumentsContract.createDocument(r, docUri(tree, dirId), "image/jpeg", name)
                    ?: return null to "文件创建失败"
            }
            val os = r.openOutputStream(target) ?: return null to "无法打开输出流"
            os.use { it.write(data) }
            // 自定义目录不在 MediaStore 里，尽力通知一次扫描：系统相册能看见，就不用只靠 App 内看
            runCatching {
                val docId = DocumentsContract.getDocumentId(target)
                if (docId.startsWith("primary:")) {
                    val path = Environment.getExternalStorageDirectory().absolutePath +
                        "/" + docId.removePrefix("primary:")
                    MediaScannerConnection.scanFile(ctx, arrayOf(path), arrayOf("image/jpeg"), null)
                }
            }
            target to null
        } catch (t: Throwable) {
            null to (t.message ?: t.javaClass.simpleName)
        }
    }

    fun delete(ctx: Context, uri: Uri): Boolean {
        return try {
            if (DocumentsContract.isDocumentUri(ctx, uri)) {
                // 自定义目录的文件：文档删掉后，MediaStore 里的记录还在，相册就会继续显示一张已经不存在的图。
                // 先算路径，删完再扫一次这个路径 —— 扫描器发现文件不在，会把那条记录一并清掉。
                val path = runCatching {
                    val id = DocumentsContract.getDocumentId(uri)
                    if (id.startsWith("primary:")) {
                        Environment.getExternalStorageDirectory().absolutePath +
                            "/" + id.removePrefix("primary:")
                    } else null
                }.getOrNull()
                val ok = DocumentsContract.deleteDocument(ctx.contentResolver, uri)
                if (ok && path != null) {
                    runCatching { MediaScannerConnection.scanFile(ctx, arrayOf(path), null, null) }
                }
                ok
            } else {
                ctx.contentResolver.delete(uri, null, null) > 0
            }
        } catch (_: Throwable) {
            false
        }
    }

    // ---------------- 成品列表 ----------------

    fun list(ctx: Context): List<Shot> = listAt(ctx, currentLocation(ctx))

    /** 按某个位置扫成品；老目录也能翻回来看看。 */
    fun listAt(ctx: Context, loc: Location): List<Shot> {
        return try {
            when (loc.kind) {
                "tree" -> listTree(ctx, Uri.parse(loc.value))
                else -> listMediaRoot(ctx, loc.value)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun listMediaRoot(ctx: Context, rootName: String): List<Shot> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_ADDED
        )
        val out = ArrayList<Shot>()
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("Pictures/$rootName/%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idIdx = c.getColumnIndex(MediaStore.Images.Media._ID)
            val nameIdx = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(MediaStore.Images.Media.SIZE)
            val pathIdx = c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                if (out.size >= 1000) break
                val path = if (pathIdx >= 0) c.getString(pathIdx) ?: "" else ""
                val seg = path.trimEnd('/').substringAfterLast('/')
                val dir = if (seg.isBlank() || seg == rootName) "未分类（旧版）" else seg
                val id = if (idIdx >= 0) c.getLong(idIdx) else -1L
                if (id <= 0) continue
                out += Shot(
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                    if (nameIdx >= 0) c.getString(nameIdx) ?: "image.jpg" else "image.jpg",
                    if (sizeIdx >= 0) (c.getLong(sizeIdx) / 1024L).toInt() else 0,
                    dir
                )
            }
        }
        return out
    }

    private data class Node(val id: String, val name: String, val isDir: Boolean, val sizeKb: Int)

    private fun listTree(ctx: Context, tree: Uri): List<Shot> {
        val r = ctx.contentResolver
        val out = ArrayList<Shot>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        children(r, tree, rootId).forEach { node ->
            if (node.isDir) {
                children(r, tree, node.id).forEach { c ->
                    if (!c.isDir && isJpeg(c.name)) {
                        out += Shot(docUri(tree, c.id), c.name, c.sizeKb, node.name)
                    }
                }
            } else if (isJpeg(node.name)) {
                out += Shot(docUri(tree, node.id), node.name, node.sizeKb, "未分类（根目录）")
            }
        }
        return out
    }

    private fun isJpeg(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".jpg") || n.endsWith(".jpeg")
    }

    private fun docUri(tree: Uri, id: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private fun children(r: ContentResolver, tree: Uri, parentId: String): List<Node> {
        val kidsUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val list = ArrayList<Node>()
        r.query(
            kidsUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (list.size >= 2000) break
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                list += Node(
                    id, name,
                    mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    (c.getLong(3) / 1024L).toInt()
                )
            }
        }
        return list
    }

    private fun childId(
        r: ContentResolver,
        tree: Uri,
        parentId: String,
        name: String,
        wantDir: Boolean
    ): String? {
        children(r, tree, parentId).forEach { n ->
            if (n.name == name && n.isDir == wantDir) return n.id
        }
        return null
    }
}


