package dev.localbench.batch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun mb(kb: Int): String =
    if (kb >= 1024) "%.1fMB".format(kb / 1024.0) else "${kb}KB"

fun toast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

fun copyText(ctx: Context, text: String, msg: String = "已复制") {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (cm == null) {
        toast(ctx, "这个系统不支持剪贴板")
        return
    }
    cm.setPrimaryClip(ClipData.newPlainText("text", text))
    toast(ctx, msg)
}

/**
 * 按下时轻微缩小：让"点了"这件事有回手感。
 * 用的时候把同一个 interactionSource 传给按钮。
 */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = tween(durationMillis = 110),
        label = "pressScale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** 缩略图占位块：没加载出来时显示一块底色 + 角标。 */
@Composable
fun ThumbBox(bmp: Bitmap?, label: String, side: Dp = 92.dp) {
    Box(
        Modifier
            .size(side)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = label,
                modifier = Modifier.fillMaxSize().padding(2.dp),
                contentScale = ContentScale.Fit
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomStart).padding(3.dp)
        )
    }
}

/**
 * 打开系统相册，而不是"拿一张图丢给某个看图应用"。
 * 先按 CATEGORY_APP_GALLERY 唤起相册 App 主页；不行再用图片集合的目录 URI；都失败返回 false，
 * 由调用方提示用户自己去系统相册里找。
 */
fun openGallery(ctx: Context): Boolean {
    val tries = listOf(
        Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_GALLERY) },
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "vnd.android.cursor.dir/image")
        },
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
        }
    )
    tries.forEach { i ->
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(i)
            return true
        } catch (_: Throwable) {
        }
    }
    return false
}

/** 把本机图片交给系统分享面板。文件不经过我们，由用户选择目标 App。 */
fun shareImage(ctx: Context, uri: Uri, name: String) {
    try {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "分享这张图"))
    } catch (_: Throwable) {
        toast(ctx, "没有可用的分享应用")
    }
}
