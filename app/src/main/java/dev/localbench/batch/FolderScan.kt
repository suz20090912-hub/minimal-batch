package dev.localbench.batch

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * 把一个文件夹（SAF 授权出来的树）里的图片捞成 Uri 列表。
 *
 * 不引 androidx.documentfile，直接用平台的 DocumentsContract 批量查子项：
 * 一次 query 拿一整层，比 DocumentFile 一个个问快得多，也不会多带一个依赖。
 */
object FolderScan {

    private val IMG_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif", "dng")

    /** 子文件夹最多往下钻这么多层，防止异常结构一直在里面转。 */
    private const val MAX_DEPTH = 6

    /** 一次导入的上限，再多就不适合一屏一屏看了。 */
    const val MAX_FILES = 2000

    fun collect(ctx: Context, tree: Uri, onFound: (Int) -> Unit): List<Uri> {
        val out = ArrayList<Uri>()
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return out
        walk(ctx, tree, rootId, 0, out, onFound)
        onFound(out.size)
        return out
    }

    private fun walk(
        ctx: Context,
        tree: Uri,
        docId: String,
        depth: Int,
        out: ArrayList<Uri>,
        onFound: (Int) -> Unit
    ) {
        if (depth > MAX_DEPTH || out.size >= MAX_FILES) return

        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cursor = runCatching {
            ctx.contentResolver.query(children, columns, null, null, null)
        }.getOrNull() ?: return

        cursor.use { c ->
            val iId = c.getColumnIndex(columns[0])
            val iName = c.getColumnIndex(columns[1])
            val iMime = c.getColumnIndex(columns[2])
            if (iId < 0) return
            while (c.moveToNext()) {
                if (out.size >= MAX_FILES) return
                val id = c.getString(iId) ?: continue
                val name = if (iName >= 0) c.getString(iName) else null
                val mime = if (iMime >= 0) c.getString(iMime) else null
                when {
                    mime == DocumentsContract.Document.MIME_TYPE_DIR ->
                        walk(ctx, tree, id, depth + 1, out, onFound)

                    isImage(mime, name) -> {
                        out.add(DocumentsContract.buildDocumentUriUsingTree(tree, id))
                        if (out.size % 20 == 0) onFound(out.size)
                    }
                }
            }
        }
        onFound(out.size)
    }

    /** 有的网盘/第三方 provider 不给 mime，只能靠扩展名兜底。 */
    private fun isImage(mime: String?, name: String?): Boolean {
        if (mime != null && mime.startsWith("image/")) return true
        if (mime == null || mime == "application/octet-stream") {
            val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
            return ext in IMG_EXT
        }
        return false
    }
}
