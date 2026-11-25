package dev.localbench.batch

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun mb(kb: Int): String =
    if (kb >= 1024) "%.1fMB".format(kb / 1024.0) else "${kb}KB"

fun toast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

/** 缩略图占位块：没加载出来时显示一块深色底 + 角标。 */
@Composable
fun ThumbBox(bmp: Bitmap?, label: String, side: Dp = 92.dp) {
    Box(
        Modifier
            .size(side)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1A1F26))
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
            color = Color(0xFF8A94A6),
            modifier = Modifier.align(Alignment.BottomStart).padding(3.dp)
        )
    }
}
