package dev.localbench.batch

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
const val WINDOW_LIGHT = 0xFFF7F4FB.toInt()

/**
 * 两套调色板都补全到 surface 各层级。
 * 不补的话，NavigationBar、下拉、选中态会落到 Material 默认的紫灰，
 * 跟主色放一起就显得脏。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF6749B5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    inversePrimary = Color(0xFFCDB6F6),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    background = Color(0xFFF7F4FB),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFCF9FF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    surfaceTint = Color(0xFF6749B5),
    inverseSurface = Color(0xFF313033),
    inverseOnSurface = Color(0xFFF4EFF4),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
    scrim = Color(0xFF000000),
    surfaceDim = Color(0xFFDED8E1),
    surfaceBright = Color(0xFFFCF9FF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainer = Color(0xFFF1ECF4),
    surfaceContainerHigh = Color(0xFFECE6F0),
    surfaceContainerHighest = Color(0xFFE6E0E9)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFCDB6F6),
    onPrimary = Color(0xFF39275F),
    primaryContainer = Color(0xFF4D3983),
    onPrimaryContainer = Color(0xFFE9DDFF),
    inversePrimary = Color(0xFF6749B5),
    secondary = Color(0xFFC9C3D8),
    onSecondary = Color(0xFF322F41),
    secondaryContainer = Color(0xFF463F57),
    onSecondaryContainer = Color(0xFFE6DFF3),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF181B21),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF262B33),
    onSurfaceVariant = Color(0xFFA9AFBC),
    surfaceTint = Color(0xFFCDB6F6),
    inverseSurface = Color(0xFFE4E1E9),
    inverseOnSurface = Color(0xFF2E3036),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF747B89),
    outlineVariant = Color(0xFF3C424D),
    scrim = Color(0xFF000000),
    surfaceDim = Color(0xFF111318),
    surfaceBright = Color(0xFF383B42),
    surfaceContainerLowest = Color(0xFF0C0E12),
    surfaceContainerLow = Color(0xFF191C22),
    surfaceContainer = Color(0xFF1D2026),
    surfaceContainerHigh = Color(0xFF272A31),
    surfaceContainerHighest = Color(0xFF32353C)
)

/** 圆角统一收大一点，卡片和输入框不再像十年前的工具软件 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** 切换外观的过渡时长，Activity 那边等它走完再换窗口底色 */
const val THEME_FADE_MS = 460L

/** 字号不动，只把标题的字重提上来，层级更清楚 */
private val BaseType = Typography()
private val AppTypography = Typography(
    headlineSmall = BaseType.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseType.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = BaseType.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyMedium = BaseType.bodyMedium.copy(lineHeight = 22.sp),
    bodySmall = BaseType.bodySmall.copy(lineHeight = 19.sp)
)

/** 切换外观时给整套颜色做过渡，而不是一瞬间从白变黑 */
@Composable
private fun animatedColors(target: ColorScheme): ColorScheme {
    val spec = tween<Color>(durationMillis = THEME_FADE_MS.toInt(), easing = FastOutSlowInEasing)
    val primary by animateColorAsState(target.primary, spec, label = "c-primary")
    val onPrimary by animateColorAsState(target.onPrimary, spec, label = "c-onPrimary")
    val primaryContainer by animateColorAsState(target.primaryContainer, spec, label = "c-primaryContainer")
    val onPrimaryContainer by animateColorAsState(target.onPrimaryContainer, spec, label = "c-onPrimaryContainer")
    val inversePrimary by animateColorAsState(target.inversePrimary, spec, label = "c-inversePrimary")
    val secondary by animateColorAsState(target.secondary, spec, label = "c-secondary")
    val onSecondary by animateColorAsState(target.onSecondary, spec, label = "c-onSecondary")
    val secondaryContainer by animateColorAsState(target.secondaryContainer, spec, label = "c-secondaryContainer")
    val onSecondaryContainer by animateColorAsState(target.onSecondaryContainer, spec, label = "c-onSecondaryContainer")
    val tertiary by animateColorAsState(target.tertiary, spec, label = "c-tertiary")
    val onTertiary by animateColorAsState(target.onTertiary, spec, label = "c-onTertiary")
    val tertiaryContainer by animateColorAsState(target.tertiaryContainer, spec, label = "c-tertiaryContainer")
    val onTertiaryContainer by animateColorAsState(target.onTertiaryContainer, spec, label = "c-onTertiaryContainer")
    val background by animateColorAsState(target.background, spec, label = "c-background")
    val onBackground by animateColorAsState(target.onBackground, spec, label = "c-onBackground")
    val surface by animateColorAsState(target.surface, spec, label = "c-surface")
    val onSurface by animateColorAsState(target.onSurface, spec, label = "c-onSurface")
    val surfaceVariant by animateColorAsState(target.surfaceVariant, spec, label = "c-surfaceVariant")
    val onSurfaceVariant by animateColorAsState(target.onSurfaceVariant, spec, label = "c-onSurfaceVariant")
    val surfaceTint by animateColorAsState(target.surfaceTint, spec, label = "c-surfaceTint")
    val inverseSurface by animateColorAsState(target.inverseSurface, spec, label = "c-inverseSurface")
    val inverseOnSurface by animateColorAsState(target.inverseOnSurface, spec, label = "c-inverseOnSurface")
    val error by animateColorAsState(target.error, spec, label = "c-error")
    val onError by animateColorAsState(target.onError, spec, label = "c-onError")
    val errorContainer by animateColorAsState(target.errorContainer, spec, label = "c-errorContainer")
    val onErrorContainer by animateColorAsState(target.onErrorContainer, spec, label = "c-onErrorContainer")
    val outline by animateColorAsState(target.outline, spec, label = "c-outline")
    val outlineVariant by animateColorAsState(target.outlineVariant, spec, label = "c-outlineVariant")
    val scrim by animateColorAsState(target.scrim, spec, label = "c-scrim")
    val surfaceDim by animateColorAsState(target.surfaceDim, spec, label = "c-surfaceDim")
    val surfaceBright by animateColorAsState(target.surfaceBright, spec, label = "c-surfaceBright")
    val surfaceContainerLowest by animateColorAsState(target.surfaceContainerLowest, spec, label = "c-cLowest")
    val surfaceContainerLow by animateColorAsState(target.surfaceContainerLow, spec, label = "c-cLow")
    val surfaceContainer by animateColorAsState(target.surfaceContainer, spec, label = "c-c")
    val surfaceContainerHigh by animateColorAsState(target.surfaceContainerHigh, spec, label = "c-cHigh")
    val surfaceContainerHighest by animateColorAsState(target.surfaceContainerHighest, spec, label = "c-cHighest")

    return target.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = surfaceTint,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = scrim,
        surfaceDim = surfaceDim,
        surfaceBright = surfaceBright,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest
    )
}

@Composable
fun AppTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = mode.resolveDark(isSystemInDarkTheme())
    val colors = animatedColors(if (dark) DarkColors else LightColors)
    MaterialTheme(colorScheme = colors, shapes = AppShapes, typography = AppTypography, content = content)
}
