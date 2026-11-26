package dev.localbench.batch

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把最后一次崩溃的堆栈落到文件里，方便事后查。
 *
 * 只在缺省处理器之外挂一层记录，记录完照旧交给系统，不改变崩溃行为。
 * 文件放在 filesDir/last_crash.txt，设置「关于」里能一键复制出来。
 */
object Crashes {

    private const val NAME = "last_crash.txt"

    fun install(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        val out = File(app.filesDir, NAME)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                out.writeText(
                    buildString {
                        append("time   : ")
                        append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                        append('\n')
                        append("version: ")
                        append(
                            runCatching {
                                app.packageManager.getPackageInfo(app.packageName, 0).versionName
                            }.getOrNull() ?: "?"
                        )
                        append('\n')
                        append("thread : ").append(t.name).append('\n')
                        append('\n')
                        append(Log.getStackTraceString(e))
                    }
                )
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun read(ctx: Context): String =
        runCatching { File((ctx.applicationContext ?: ctx).filesDir, NAME).readText() }
            .getOrDefault("")

    fun clear(ctx: Context) {
        runCatching { File((ctx.applicationContext ?: ctx).filesDir, NAME).delete() }
    }
}
