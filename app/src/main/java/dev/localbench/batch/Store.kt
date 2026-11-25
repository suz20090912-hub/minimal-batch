package dev.localbench.batch

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore

/** 成品库里的一条记录。 */
data class Shot(
    val uri: Uri,
    val name: String,
    val sizeKb: Int,
    val dir: String
)

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

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---------------- 位置配置 ----------------

    fun root(ctx: Context): String =
        prefs(ctx).getString(K_ROOT, DEFAULT_ROOT)?.takeIf { it.isNotBlank() } ?: DEFAULT_ROOT

    fun setRoot(ctx: Context, value: String) {
        prefs(ctx).edit().putString(K_ROOT, value.trim().ifBlank { DEFAULT_ROOT }).apply()
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
        val t = tree(ctx)
        return if (t != null) saveToTree(ctx, t, preset.label, fileName, data)
        else saveToMedia(ctx, "${root(ctx)}/${preset.label}", fileName, data)
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
            target to null
        } catch (t: Throwable) {
            null to (t.message ?: t.javaClass.simpleName)
        }
    }

    fun delete(ctx: Context, uri: Uri): Boolean {
        return try {
            if (DocumentsContract.isDocumentUri(ctx, uri)) {
                DocumentsContract.deleteDocument(ctx.contentResolver, uri)
            } else {
                ctx.contentResolver.delete(uri, null, null) > 0
            }
        } catch (_: Throwable) {
            false
        }
    }

    // ---------------- 成品列表 ----------------

    fun list(ctx: Context): List<Shot> {
        val t = tree(ctx)
        return try {
            if (t != null) listTree(ctx, t) else listMedia(ctx)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun listMedia(ctx: Context): List<Shot> {
        val rootName = root(ctx)
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
