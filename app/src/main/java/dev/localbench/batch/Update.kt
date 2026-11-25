package dev.localbench.batch

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** 一个镜像站：同一份 version.json 的不同线路 */
data class Mirror(val id: String, val name: String, val url: String)

/** 远端发布的版本信息 */
data class ReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long
)

/**
 * 更新中心的数据层。
 * 只有用户点「获取更新」才联网，这里不挂任何后台轮询。
 */
object Update {
    private const val PREF = "mb_update"
    private const val K_MODE = "mode"
    private const val K_MIRROR = "mirror"
    private const val K_CUSTOM = "custom_url"
    private const val K_PROXY_ON = "proxy_on"
    private const val K_PROXY_HOST = "proxy_host"
    private const val K_PROXY_PORT = "proxy_port"

    /** 内置镜像站，都是同一份 update/version.json 的不同线路 */
    val BUILTIN = listOf(
        Mirror(
            "jsdelivr",
            "jsDelivr",
            "https://cdn.jsdelivr.net/gh/suz20090912-hub/minimal-batch@main/update/version.json"
        ),
        Mirror(
            "ghproxy",
            "ghproxy 加速",
            "https://ghproxy.net/https://raw.githubusercontent.com/suz20090912-hub/minimal-batch/main/update/version.json"
        ),
        Mirror(
            "raw",
            "GitHub Raw",
            "https://raw.githubusercontent.com/suz20090912-hub/minimal-batch/main/update/version.json"
        )
    )

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun mode(ctx: Context): String = p(ctx).getString(K_MODE, "auto") ?: "auto"
    fun setMode(ctx: Context, v: String) = p(ctx).edit().putString(K_MODE, v).apply()

    fun mirrorId(ctx: Context): String = p(ctx).getString(K_MIRROR, "jsdelivr") ?: "jsdelivr"
    fun setMirrorId(ctx: Context, v: String) = p(ctx).edit().putString(K_MIRROR, v).apply()

    fun customUrl(ctx: Context): String = p(ctx).getString(K_CUSTOM, "") ?: ""
    fun setCustomUrl(ctx: Context, v: String) = p(ctx).edit().putString(K_CUSTOM, v.trim()).apply()

    fun proxyOn(ctx: Context): Boolean = p(ctx).getBoolean(K_PROXY_ON, false)
    fun setProxyOn(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean(K_PROXY_ON, v).apply()

    fun proxyHost(ctx: Context): String = p(ctx).getString(K_PROXY_HOST, "") ?: ""
    fun setProxyHost(ctx: Context, v: String) = p(ctx).edit().putString(K_PROXY_HOST, v.trim()).apply()

    fun proxyPort(ctx: Context): Int = p(ctx).getInt(K_PROXY_PORT, 7890)
    fun setProxyPort(ctx: Context, v: Int) = p(ctx).edit().putInt(K_PROXY_PORT, v).apply()

    fun proxy(ctx: Context): Proxy? {
        if (!proxyOn(ctx)) return null
        val host = proxyHost(ctx)
        val port = proxyPort(ctx)
        if (host.isBlank() || port !in 1..65535) return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
    }

    fun allMirrors(ctx: Context): List<Mirror> {
        val custom = customUrl(ctx)
        return if (custom.isBlank()) BUILTIN else BUILTIN + Mirror("custom", "自定义镜像", custom)
    }

    fun findByUrl(url: String): Mirror? = BUILTIN.firstOrNull { it.url == url }

    /** 测速：拉一次 version.json，返回毫秒 */
    suspend fun ping(url: String, proxy: Proxy?): Long = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val c = open(url, proxy, 5000, 6000)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
        System.currentTimeMillis() - start
    }

    /** 取版本信息 */
    suspend fun fetch(url: String, proxy: Proxy?): ReleaseInfo = withContext(Dispatchers.IO) {
        val c = open(url, proxy, 8000, 12000)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            parse(text)
        } finally {
            c.disconnect()
        }
    }

    fun parse(text: String): ReleaseInfo {
        val o = JSONObject(text)
        return ReleaseInfo(
            versionCode = o.optInt("versionCode", 0),
            versionName = o.optString("versionName", ""),
            notes = o.optString("notes", ""),
            apkUrl = o.optString("apk", ""),
            sizeBytes = o.optLong("size", 0L)
        )
    }

    /** 下载 APK 到缓存目录，进度 0–100 */
    suspend fun download(
        url: String,
        dest: File,
        proxy: Proxy?,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val c = open(url, proxy, 10000, 25000)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val total = c.contentLengthLong
            var read = 0L
            dest.parentFile?.mkdirs()
            c.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(((read * 100) / total).toInt())
                    }
                }
            }
            dest
        } finally {
            c.disconnect()
        }
    }

    /** 调系统安装器 */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
    }

    private fun open(url: String, proxy: Proxy?, connectMs: Int, readMs: Int): HttpURLConnection {
        val u = URL(url)
        val c = (if (proxy != null) u.openConnection(proxy) else u.openConnection()) as HttpURLConnection
        c.connectTimeout = connectMs
        c.readTimeout = readMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "MinimalBatch-Android")
        c.setRequestProperty("Cache-Control", "no-cache")
        return c
    }

    /** 把异常翻成人话，用户能看懂是哪一环出的问题 */
    fun friendly(t: Throwable): String = when (t) {
        is UnknownHostException -> "解析不到镜像站域名，可能断网或被网络限制"
        is java.net.SocketTimeoutException -> "连接镜像站超时，网络慢或这条线路被挡了"
        is SSLException -> "SSL 握手失败，可能被中间网络拦截"
        is IOException -> t.message ?: "网络请求失败"
        else -> t.message ?: t.javaClass.simpleName
    }
}
