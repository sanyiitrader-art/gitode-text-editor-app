package com.gitode.ui

import android.content.Context
import android.graphics.Typeface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Gitode's main accent: a muted warm rust/terracotta. */
val Accent = Color(0xFFB05D42)

@Immutable
class GitodeColors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val card: Color,
    val outline: Color,
    val text: Color,
    val dim: Color,
    val accent: Color,
    val onAccent: Color,
    val glow: Color,
    val error: Color,
) {
    val editorBg: Color get() = bg
    val gutterBg: Color get() = surface
    val gutterText: Color get() = dim
    val currentLine: Color get() = text.copy(alpha = 0.06f)
    val selection: Color get() = accent.copy(alpha = 0.35f)
    val matchHighlight: Color get() = accent.copy(alpha = 0.45f)
}

class ThemeInfo(
    val id: String,
    val name: String,
    val dark: Boolean,
    val bg: Long,
    val surface: Long,
    val card: Long,
    val outline: Long,
    val text: Long,
    val dim: Long,
)

val THEMES: List<ThemeInfo> = listOf(
    // ---- dark
    ThemeInfo("obsidian", "Obsidian", true, 0xFF0F0F11, 0xFF151518, 0xFF1B1B1F, 0xFF2A2A30, 0xFFE6E3E0, 0xFF8E8B88),
    ThemeInfo("charcoal", "Charcoal", true, 0xFF1A1A1A, 0xFF202020, 0xFF262626, 0xFF333333, 0xFFE4E4E4, 0xFF8A8A8A),
    ThemeInfo("midnight", "Midnight", true, 0xFF0B1020, 0xFF101730, 0xFF151E3A, 0xFF232E52, 0xFFDDE3F5, 0xFF7C88AD),
    ThemeInfo("nord", "Nord", true, 0xFF2E3440, 0xFF343B49, 0xFF3B4252, 0xFF4C566A, 0xFFECEFF4, 0xFF9AA5B8),
    ThemeInfo("dracula", "Dracula", true, 0xFF282A36, 0xFF2F3140, 0xFF343746, 0xFF44475A, 0xFFF8F8F2, 0xFF8F93B0),
    ThemeInfo("one-dark", "One Dark", true, 0xFF282C34, 0xFF2C313A, 0xFF313640, 0xFF3E4451, 0xFFABB2BF, 0xFF7F8796),
    ThemeInfo("amoled-black", "AMOLED Black", true, 0xFF000000, 0xFF060606, 0xFF0E0E0E, 0xFF1C1C1C, 0xFFE8E8E8, 0xFF808080),
    ThemeInfo("forest", "Forest", true, 0xFF0E1511, 0xFF131C17, 0xFF18241D, 0xFF263529, 0xFFDCE8DF, 0xFF85988B),
    ThemeInfo("graphite", "Graphite", true, 0xFF16181B, 0xFF1C1F23, 0xFF22262B, 0xFF2F343A, 0xFFDADDE1, 0xFF868C94),
    ThemeInfo("warm-ember", "Warm Ember", true, 0xFF1A1210, 0xFF211715, 0xFF281C19, 0xFF3A2A25, 0xFFEEDFD6, 0xFFA08C80),
    // ---- light
    ThemeInfo("paper", "Paper", false, 0xFFF5F1EA, 0xFFEFEAE1, 0xFFFBF8F3, 0xFFDDD6C9, 0xFF2A2724, 0xFF7A7469),
    ThemeInfo("github-light", "GitHub Light", false, 0xFFF6F8FA, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFD0D7DE, 0xFF1F2328, 0xFF656D76),
    ThemeInfo("solarized-light", "Solarized Light", false, 0xFFFDF6E3, 0xFFF5EEDA, 0xFFEEE8D5, 0xFFDDD6C1, 0xFF586E75, 0xFF93A1A1),
    ThemeInfo("rose-pine-dawn", "Rosé Pine Dawn", false, 0xFFFAF4ED, 0xFFFFFAF3, 0xFFF2E9E1, 0xFFDFDAD9, 0xFF575279, 0xFF9893A5),
)

fun themeById(id: String): ThemeInfo = THEMES.firstOrNull { it.id == id } ?: THEMES[0]

/** Shifts a colour towards warm (t > 0, reddish) or cool (t < 0, bluish). [k] scales the effect. */
private fun Color.shift(t: Float, k: Float): Color {
    if (t == 0f) return this
    val d = 0.06f * t * k
    return Color(
        (red + d).coerceIn(0f, 1f),
        (green + d * 0.2f).coerceIn(0f, 1f),
        (blue - d).coerceIn(0f, 1f),
        alpha,
    )
}

fun buildColors(info: ThemeInfo, temperature: Float): GitodeColors {
    val t = temperature.coerceIn(-1f, 1f)
    fun c(v: Long, k: Float) = Color(v).shift(t, k)
    return GitodeColors(
        isDark = info.dark,
        bg = c(info.bg, 1f),
        surface = c(info.surface, 1f),
        card = c(info.card, 1f),
        outline = c(info.outline, 1f),
        text = c(info.text, 0.5f), // gentle on text so contrast stays intact
        dim = c(info.dim, 0.6f),
        accent = Accent,
        onAccent = Color.White,
        glow = Accent.copy(alpha = 0.45f),
        error = if (info.dark) Color(0xFFE0614F) else Color(0xFFC0392B),
    )
}

val LocalColors = staticCompositionLocalOf { buildColors(THEMES[0], 0f) }

/** Shortcut for the current colours: `G.c.text`. */
object G {
    val c: GitodeColors
        @Composable @ReadOnlyComposable get() = LocalColors.current
}

@Composable
fun GitodeTheme(themeId: String, temperature: Float, content: @Composable () -> Unit) {
    val colors = remember(themeId, temperature) { buildColors(themeById(themeId), temperature) }
    val scheme = if (colors.isDark) {
        darkColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent,
            background = colors.bg, onBackground = colors.text,
            surface = colors.surface, onSurface = colors.text,
            surfaceVariant = colors.card, onSurfaceVariant = colors.dim,
            outline = colors.outline, error = colors.error,
        )
    } else {
        lightColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent,
            background = colors.bg, onBackground = colors.text,
            surface = colors.surface, onSurface = colors.text,
            surfaceVariant = colors.card, onSurfaceVariant = colors.dim,
            outline = colors.outline, error = colors.error,
        )
    }
    CompositionLocalProvider(LocalColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

// ---------------------------------------------------------------- syntax themes

class SyntaxThemeInfo(val id: String, val name: String, val dark: Boolean)

val SYNTAX_THEMES: List<SyntaxThemeInfo> = listOf(
    SyntaxThemeInfo("dark-plus", "Dark+", true),
    SyntaxThemeInfo("one-dark-pro", "One Dark", true),
    SyntaxThemeInfo("dracula", "Dracula", true),
    SyntaxThemeInfo("monokai", "Monokai", true),
    SyntaxThemeInfo("nord", "Nord", true),
    SyntaxThemeInfo("github-dark", "GitHub Dark", true),
    SyntaxThemeInfo("solarized-dark", "Solarized Dark", true),
    SyntaxThemeInfo("light-plus", "Light+", false),
    SyntaxThemeInfo("github-light", "GitHub Light", false),
    SyntaxThemeInfo("solarized-light", "Solarized Light", false),
)

fun syntaxThemeName(id: String): String = SYNTAX_THEMES.firstOrNull { it.id == id }?.name ?: id

// ---------------------------------------------------------------- editor fonts

class FontOption(val id: String, val name: String)

val FONT_OPTIONS: List<FontOption> = listOf(
    FontOption("jetbrains-mono", "JetBrains Mono"),
    FontOption("fira-code", "Fira Code"),
    FontOption("source-code-pro", "Source Code Pro"),
    FontOption("ibm-plex-mono", "IBM Plex Mono"),
    FontOption("roboto-mono", "Roboto Mono"),
    FontOption("ubuntu-mono", "Ubuntu Mono"),
    FontOption("cascadia-code", "Cascadia Code"),
    FontOption("inconsolata", "Inconsolata"),
    FontOption("noto-sans-mono", "Noto Sans Mono"),
    FontOption("system", "System Monospace"),
)

fun fontName(id: String): String = FONT_OPTIONS.firstOrNull { it.id == id }?.name ?: id

/** Fonts that were actually bundled by the build (plus the always-present system font). */
fun availableFonts(context: Context): List<FontOption> {
    val present = try { context.assets.list("fonts")?.toSet().orEmpty() } catch (e: Exception) { emptySet() }
    return FONT_OPTIONS.filter { it.id == "system" || "${it.id}.ttf" in present }
}

private val typefaceCache = HashMap<String, Typeface>()

fun loadTypeface(context: Context, id: String): Typeface = synchronized(typefaceCache) {
    typefaceCache.getOrPut(id) {
        if (id == "system") {
            Typeface.MONOSPACE
        } else {
            try { Typeface.createFromAsset(context.assets, "fonts/$id.ttf") } catch (e: Exception) { Typeface.MONOSPACE }
        }
    }
}