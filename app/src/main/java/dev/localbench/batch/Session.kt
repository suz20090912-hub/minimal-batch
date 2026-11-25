package dev.localbench.batch

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 全 App 共享的会话状态。
 *
 * 放在单例里而不是 Activity 里，是为了做到三件事：
 *  1. 转屏 / 退回桌面再回来，选好的图和进度都还在；
 *  2. 返回键拦得住（界面层只读状态，不持有任务）；
 *  3. 下次冷启动能把上次选的图恢复回来（URI 长期授权 + SharedPreferences）。
 */
object Session {

    data class State(
        val picked: List<Picked> = emptyList(),
        val previews: List<BatchEngine.Preview> = emptyList(),
        val previewBusy: Boolean = false,
        val previewDone: Int = 0,
        val previewTotal: Int = 0,
        val preset: Preset = Preset.WECHAT,
        val prefix: String = "",
        val running: Boolean = false,
        val doneCount: Int = 0,
        val totalCount: Int = 0,
        val batch: Batch? = null,
        val notice: String = "",
        val restored: Boolean = false
    )

    private const val K_SEL = "selection"
    private const val K_PRESET = "preset"

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var previewJob: Job? = null
    private var runJob: Job? = null
    private var appCtx: Context? = null
    private var started = false

    fun bind(ctx: Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Store.PREF, Context.MODE_PRIVATE)

    private fun ctx(): Context? = appCtx

    /**
     * 冷启动调用一次：把上次的选图和场景恢复回来，并顺带重算预览。
     * 已经不存在的图（被用户删了）会被静默剔除。
     */
    fun init(context: Context) {
        bind(context)
        if (started) return
        started = true
        val p = prefs(context)
        val savedPreset = p.getString(K_PRESET, null)
            ?.let { runCatching { Preset.valueOf(it) }.getOrNull() } ?: Preset.WECHAT
        _state.value = _state.value.copy(preset = savedPreset, restored = true)

        val saved = p.getStringSet(K_SEL, emptySet()).orEmpty()
            .mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
        if (saved.isEmpty()) return

        scope.launch {
            val alive = saved.filter { readable(it) }
            if (alive.size != saved.size) {
                p.edit().putStringSet(K_SEL, alive.map { it.toString() }.toSet()).apply()
            }
            if (alive.isEmpty()) return@launch
            val items = BatchEngine.inspectAll(context, alive)
            _state.value = _state.value.copy(picked = items)
            refreshPreviews(context)
        }
    }

    private fun readable(uri: Uri): Boolean {
        val c = appCtx ?: return false
        return try {
            c.contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { it.moveToFirst() } ?: false
        } catch (_: Throwable) {
            false
        }
    }

    fun setPicked(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        bind(context)
        scope.launch {
            uris.forEach {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Throwable) {
                }
            }
            val items = BatchEngine.inspectAll(context, uris)
            prefs(context).edit()
                .putStringSet(K_SEL, uris.map { u -> u.toString() }.toSet())
                .apply()
            _state.value = _state.value.copy(
                picked = items, batch = null, notice = ""
            )
            refreshPreviews(context)
        }
    }

    fun clearPicked(context: Context) {
        previewJob?.cancel()
        prefs(context).edit().remove(K_SEL).apply()
        _state.value = _state.value.copy(
            picked = emptyList(), previews = emptyList(),
            previewBusy = false, previewDone = 0, previewTotal = 0,
            batch = null, notice = ""
        )
    }

    /**
     * 从这批里拿掉几张，但不动文件、也不动授权。
     * 用在"选了三张只想转两张"的场合，省得重新去相册翻一遍。
     */
    fun removePicked(context: Context, uris: Set<Uri>) {
        if (uris.isEmpty()) return
        bind(context)
        val left = _state.value.picked.filterNot { it.uri in uris }
        if (left.size == _state.value.picked.size) return
        prefs(context).edit()
            .putStringSet(K_SEL, left.map { it.uri.toString() }.toSet())
            .apply()
        _state.value = _state.value.copy(picked = left, batch = null, notice = "")
    }

    /**
     * 只清掉转换页那张结果记录，让页面回到选图状态。
     * 不碰任何文件：成品还在相册和成品页里，要删图得去成品页选。
     */
    fun dismissBatch() {
        if (_state.value.batch == null && _state.value.notice.isEmpty()) return
        _state.value = _state.value.copy(batch = null, notice = "")
    }

    fun setPreset(context: Context, preset: Preset) {
        if (_state.value.preset == preset) return
        prefs(context).edit().putString(K_PRESET, preset.name).apply()
        _state.value = _state.value.copy(preset = preset)
        refreshPreviews(context)
    }

    fun setPrefix(value: String) {
        _state.value = _state.value.copy(prefix = value)
    }

    fun setNotice(text: String) {
        _state.value = _state.value.copy(notice = text)
    }

    /**
     * 以前这里会为每张图真跑一遍生成"处理后样子"的预览卡，费时又占内存。
     * 现在选完图直接列出原图本身，点开能放大看、能看文件详情，不再生成预览。
     */
    fun refreshPreviews(context: Context) {
        previewJob?.cancel()
        previewJob = null
        if (_state.value.previews.isNotEmpty() || _state.value.previewBusy ||
            _state.value.previewDone != 0 || _state.value.previewTotal != 0
        ) {
            _state.value = _state.value.copy(
                previews = emptyList(),
                previewBusy = false,
                previewDone = 0,
                previewTotal = 0
            )
        }
    }

    fun start(context: Context) {
        bind(context)
        val cur = _state.value
        if (cur.running || cur.picked.isEmpty()) return
        previewJob?.cancel()
        runJob = scope.launch {
            _state.value = _state.value.copy(
                running = true, doneCount = 0, totalCount = cur.picked.size,
                batch = null, notice = "", previewBusy = false
            )
            val b = BatchEngine.runBatch(
                context, cur.picked.map { it.uri }, cur.preset, 3, _state.value.prefix
            ) { d, t -> _state.value = _state.value.copy(doneCount = d, totalCount = t) }
            _state.value = _state.value.copy(running = false, batch = b)
        }
    }

    fun undo(context: Context, batch: Batch): Int {
        var n = 0
        batch.items.forEach { item ->
            val u = item.outUri ?: return@forEach
            if (Store.delete(context, u)) n++
        }
        if (_state.value.batch === batch) {
            _state.value = _state.value.copy(batch = null)
        }
        _state.value = _state.value.copy(notice = "已删除这一批新图 $n 张；原图未动，系统相册里也会消失")
        return n
    }

    fun cancelRun() {
        runJob?.cancel()
        runJob = null
        _state.value = _state.value.copy(running = false)
    }
}
