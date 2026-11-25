package dev.localbench.batch

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** 外观三选一：跟随系统 / 白色 / 黑色 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("白色"),
    DARK("黑色");

    companion object {
        fun of(raw: String?): ThemeMode = entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}

object ThemeStore {
    private const val PREF = "ui_prefs"
    private const val KEY = "theme_mode"

    /** 直接读这个 state，改完立刻重组，不需要重启 */
    var mode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    fun init(ctx: Context) {
        mode = ThemeMode.of(
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
        )
    }

    fun set(ctx: Context, m: ThemeMode) {
        mode = m
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, m.name).apply()
    }
}

fun ThemeMode.resolveDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** 窗口底色：Compose 之外（转场、边角）露出来的那一层 */
const val WINDOW_DARK = 0xFF111318.toInt()
const val WINDOW_LIGHT = 0xFFF7F5FA.toInt()

private val LightColors = lightColorScheme(
    primary = Color(0xFF6749B5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8DEFF),
    onPrimaryContainer = Color(0xFF24105C),
    secondary = Color(0xFF5F5B70),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF7F5FA),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFCFAFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE9E4F0),
    onSurfaceVariant = Color(0xFF5A5563),
    outline = Color(0xFF7A7583),
    error = Color(0xFFB3261E)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFCDB6F6),
    onPrimary = Color(0xFF39275F),
    primaryContainer = Color(0xFF4D3983),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFFC9C3D8),
    onSecondary = Color(0xFF322F41),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF181B21),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF262B33),
    onSurfaceVariant = Color(0xFFA9AFBC),
    outline = Color(0xFF747B89),
    error = Color(0xFFF2B8B5)
)

@Composable
fun AppTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = mode.resolveDark(isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
