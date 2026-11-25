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
            previewBusy = false, previewDone = 0, previewTotal = 0
        )
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

    /** 预览：同一时刻只跑一份，换场景/重选图会取消上一轮。 */
    fun refreshPreviews(context: Context) {
        bind(context)
        previewJob?.cancel()
        val cur = _state.value
        if (cur.picked.isEmpty()) {
            _state.value = cur.copy(previews = emptyList(), previewBusy = false)
            return
        }
        previewJob = scope.launch {
            val total = minOf(cur.picked.size, 20)
            _state.value = _state.value.copy(
                previews = emptyList(), previewBusy = true, previewDone = 0, previewTotal = total
            )
            val list = BatchEngine.buildPreviews(
                context, cur.picked.map { it.uri }, cur.preset
            ) { d, t -> _state.value = _state.value.copy(previewDone = d, previewTotal = t) }
            _state.value = _state.value.copy(previews = list, previewBusy = false)
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
        _state.value = _state.value.copy(notice = "已撤销，删除 $n 张新图；原图未动")
        return n
    }

    fun cancelRun() {
        runJob?.cancel()
        runJob = null
        _state.value = _state.value.copy(running = false)
    }
}
