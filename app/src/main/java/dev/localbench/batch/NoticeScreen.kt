package dev.localbench.batch

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object NoticeStore {
    private const val PREF = "ui_prefs"
    private const val KEY = "notice_seen"

    /** 公告编号：改大这个数，所有人都会重新看到右上角的小圆点 */
    const val ID = 19

    fun seen(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY, 0) >= ID

    fun markSeen(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(KEY, ID).apply()
    }

    /** 让公告里的版本号跟着安装包走，省得每次发版忘改。 */
    private fun versionName(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    fun read(ctx: Context): String = runCatching {
        ctx.assets.open("notice.md").bufferedReader().use { it.readText() }
            .replace("{version}", versionName(ctx))
    }.getOrDefault("公告没打包进安装包。")
}

@Composable
fun NoticeScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val text = remember { NoticeStore.read(ctx) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("公告", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClose) { Text("关闭") }
            }
            HorizontalDivider()
            MarkdownLite(text, Modifier.weight(1f))
        }
    }
}

/** 极简 markdown 渲染：只认标题、引用行、空行、正文。公告和更新日志共用。 */
@Composable
fun MarkdownLite(text: String, modifier: Modifier = Modifier) {
    LazyColumn(modifier) {
        items(text.lines()) { line ->
            when {
                line.startsWith("## ") -> Text(
                    line.removePrefix("## "),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
                )

                line.startsWith("# ") -> Text(
                    line.removePrefix("# "),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                line.startsWith("> ") -> Text(
                    line.removePrefix("> "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 2.dp)
                )

                line.startsWith("|") -> Text(
                    line,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                line.isBlank() -> Spacer(Modifier.height(4.dp))

                else -> Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
    }
}









