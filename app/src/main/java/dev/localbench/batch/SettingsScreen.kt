package dev.localbench.batch

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val REPO_URL = "https://github.com/suz20090912-hub/minimal-batch"

@Composable
fun SettingsScreen(onOpenLog: () -> Unit, onChanged: () -> Unit, onOpenUpdate: () -> Unit) {
    val ctx = LocalContext.current
    var rootName by remember { mutableStateOf(Store.root(ctx)) }
    var custom by remember { mutableStateOf(Store.isCustom(ctx)) }

    val treePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            Store.setTree(ctx, uri)
            custom = true
            onChanged()
            toast(ctx, "已改到自定义文件夹")
        }
    }

    val version = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))

        // ---------- 外观 ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("外观", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "白色、黑色，或者跟系统走。改完立刻生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { m ->
                        FilterChip(
                            selected = ThemeStore.mode == m,
                            onClick = { ThemeStore.set(ctx, m) },
                            label = { Text(m.label) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---------- 输出位置 ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("输出位置", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (custom) "当前：自定义文件夹" else "当前：Pictures/${Store.root(ctx)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!custom) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = rootName,
                        onValueChange = {
                            rootName = it
                            Store.setRoot(ctx, it)
                        },
                        singleLine = true,
                        label = { Text("Pictures 下的文件夹名") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { treePicker.launch(null) },
                        modifier = Modifier.weight(1f)
                    ) { Text("选其他文件夹") }
                    OutlinedButton(
                        onClick = {
                            Store.clearTree(ctx)
                            Store.setRoot(ctx, Store.DEFAULT_ROOT)
                            rootName = Store.root(ctx)
                            custom = false
                            onChanged()
                            toast(ctx, "已恢复默认：Pictures/${Store.DEFAULT_ROOT}")
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("恢复默认") }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "默认放在 Pictures，系统相册能直接看到。换到别处只影响以后处理的图，老图不动；" +
                        "相册里找不到就到「成品」页看。",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---------- 更新中心 ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("检查更新", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "只在你点的时候联网。可以选镜像站、测速、配代理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onOpenUpdate, modifier = Modifier.fillMaxWidth()) {
                    Text("打开检查更新")
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---------- 交流群 ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("交流群", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text("QQ 群：1135338332", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "遇到 bug、有建议，或者只是想聊天，都可以加。群里不卖课、不推广。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { copyText(ctx, "1135338332", "群号已复制，去 QQ 搜索加入") }
                ) { Text("复制群号") }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---------- 更新日志 / 关于 ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("更新日志", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "每版改了什么、修了什么，都在安装包里，离线能看。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onOpenLog) { Text("查看更新日志") }
            }
        }

        Spacer(Modifier.height(10.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("关于", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                InfoRow("版本", "v$version")
                InfoRow("协议", "MIT")
                InfoRow("权限", "只有手动检查更新时联网")
                InfoRow("费用", "完全免费 · 不设赞助")
                Spacer(Modifier.height(6.dp))
                Text(
                    "完全开源免费，没有赞助渠道，也没有付费版。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "图片只在本机处理，不上传。只有你点「获取更新」时才联网。",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    try {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))
                        )
                    } catch (_: Throwable) {
                        toast(ctx, "没有能打开链接的应用")
                    }
                }) { Text("在浏览器打开项目主页") }

                // 只在真崩过之后才出现，平时看不到
                val crash = remember { Crashes.read(ctx) }
                if (crash.isNotBlank()) {
                    TextButton(onClick = {
                        copyText(ctx, crash, "崩溃日志已复制，可以贴到群里")
                    }) { Text("上次崩溃日志") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodySmall)
    }
}

/** 离线更新日志：直接读 assets 里打包的 markdown。 */
@Composable
fun ChangelogScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val text = remember {
        runCatching {
            ctx.assets.open("update-log.md").bufferedReader().use { it.readText() }
        }.getOrDefault("更新日志没打进安装包。")
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("更新日志", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClose) { Text("关闭") }
            }
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(text.lines()) { raw ->
                    val line = raw.replace("**", "").replace("`", "").trimEnd()
                    when {
                        line.startsWith("## ") -> Text(
                            line.removePrefix("## "),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 22.dp, bottom = 8.dp)
                        )

                        line.startsWith("### ") -> Text(
                            line.removePrefix("### "),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 12.dp, bottom = 3.dp)
                        )

                        line.startsWith("# ") -> Text(
                            line.removePrefix("# "),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        // 表格在手机窄屏上挤成一团，App 内直接不显示
                        line.startsWith("|") || line.startsWith("---") -> Unit

                        line.startsWith("- ") || line.startsWith("* ") -> Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        ) {
                            Text(
                                "·",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                line.removePrefix("- ").removePrefix("* "),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 15.sp,
                                    lineHeight = 25.sp
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        line.isBlank() -> Spacer(Modifier.height(8.dp))

                        else -> Text(
                            line,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 15.sp,
                                lineHeight = 25.sp
                            ),
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}




