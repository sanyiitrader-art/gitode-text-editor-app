package com.gitode.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gitode.core.Fs
import kotlin.math.cos
import kotlin.math.sin

/** Gitode's own line icons, drawn on a 24 x 24 grid so no icon library is needed. */
enum class Ic {
    Menu, More, Close, Back, Chevron, Folder, File, Image, Plus, Search, Trash, TrashX, Check, Copy,
    Settings, Code, Contrast, Hammer, GitBranch, Flask, Braces, Gamepad, Layout, Star,
    // glyphs for special folders
    Cube, Tray, Book, Library, Sliders, Grid, Terminal, Database, Plug, Layers, TypeA, Note,
    Film, Globe, Android, Apple, Monitor, Archive,
    // editor controls
    Undo, Redo, Tabs,
}

@Composable
fun Ico(ic: Ic, tint: Color = G.c.text, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawGlyph(ic, tint, this.size.width / 24f, 2f) }
}

/** Draws [ic] at scale [s] (pixels per grid unit) with stroke width [sw] grid units. */
fun DrawScope.drawGlyph(ic: Ic, color: Color, s: Float, sw: Float) {
    val stroke = Stroke(width = sw * s, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(color, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), strokeWidth = sw * s, cap = StrokeCap.Round)

    fun poly(vararg p: Float, close: Boolean = false) {
        val path = Path()
        path.moveTo(p[0] * s, p[1] * s)
        var i = 2
        while (i < p.size) {
            path.lineTo(p[i] * s, p[i + 1] * s)
            i += 2
        }
        if (close) path.close()
        drawPath(path, color, style = stroke)
    }

    fun dot(x: Float, y: Float, r: Float) = drawCircle(color, r * s, Offset(x * s, y * s))
    fun ring(x: Float, y: Float, r: Float) = drawCircle(color, r * s, Offset(x * s, y * s), style = stroke)
    fun box(l: Float, t: Float, r: Float, b: Float, rad: Float) =
        drawRoundRect(color, Offset(l * s, t * s), Size((r - l) * s, (b - t) * s), CornerRadius(rad * s), style = stroke)

    when (ic) {
        Ic.Menu -> { line(4f, 6f, 20f, 6f); line(4f, 12f, 20f, 12f); line(4f, 18f, 20f, 18f) }
        Ic.More -> { dot(12f, 5f, 1.8f); dot(12f, 12f, 1.8f); dot(12f, 19f, 1.8f) }
        Ic.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
        Ic.Back -> { line(20f, 12f, 5f, 12f); poly(11f, 6f, 5f, 12f, 11f, 18f) }
        Ic.Chevron -> poly(9f, 6f, 15f, 12f, 9f, 18f)
        Ic.Folder -> {
            val p = Path()
            p.moveTo(3.5f * s, 5.5f * s); p.lineTo(9.5f * s, 5.5f * s); p.lineTo(11.5f * s, 8f * s)
            p.lineTo(20.5f * s, 8f * s); p.lineTo(20.5f * s, 19f * s); p.lineTo(3.5f * s, 19f * s)
            p.close()
            drawPath(p, color.copy(alpha = 0.22f))
            drawPath(p, color, style = stroke)
        }
        Ic.File -> {
            poly(6f, 3f, 13f, 3f, 18f, 8f, 18f, 21f, 6f, 21f, close = true)
            poly(13f, 3f, 13f, 8f, 18f, 8f)
        }
        Ic.Image -> {
            box(4f, 5f, 20f, 19f, 2f)
            dot(9f, 10f, 1.6f)
            poly(4.5f, 17.5f, 10f, 12.5f, 14f, 16.5f, 16.5f, 14f, 19.5f, 17f)
        }
        Ic.Plus -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
        Ic.Search -> { ring(10.5f, 10.5f, 6f); line(15.2f, 15.2f, 20f, 20f) }
        Ic.Trash -> {
            line(4f, 7f, 20f, 7f)
            poly(9f, 7f, 9f, 4f, 15f, 4f, 15f, 7f)
            poly(6f, 7f, 7f, 20f, 17f, 20f, 18f, 7f)
            line(10f, 11f, 10f, 16f); line(14f, 11f, 14f, 16f)
        }
        Ic.TrashX -> {
            line(4f, 7f, 20f, 7f)
            poly(9f, 7f, 9f, 4f, 15f, 4f, 15f, 7f)
            poly(6f, 7f, 7f, 20f, 17f, 20f, 18f, 7f)
            line(10f, 11f, 14f, 16f); line(14f, 11f, 10f, 16f)
        }
        Ic.Check -> poly(5f, 12f, 10f, 17f, 19f, 7f)
        Ic.Copy -> { box(4f, 4f, 15f, 15f, 2f); box(9f, 9f, 20f, 20f, 2f) }
        Ic.Settings -> {
            ring(12f, 12f, 6f); ring(12f, 12f, 2.4f)
            for (k in 0 until 8) {
                val a = Math.toRadians(k * 45.0)
                val c = cos(a).toFloat()
                val sn = sin(a).toFloat()
                line(12f + 6f * c, 12f + 6f * sn, 12f + 9f * c, 12f + 9f * sn)
            }
        }
        Ic.Code -> { poly(8f, 7f, 3f, 12f, 8f, 17f); poly(16f, 7f, 21f, 12f, 16f, 17f); line(14f, 5f, 10f, 19f) }
        Ic.Contrast -> {
            ring(12f, 12f, 8f)
            drawArc(color, 90f, 180f, true, Offset(4f * s, 4f * s), Size(16f * s, 16f * s))
        }
        Ic.Hammer -> {
            drawLine(color, Offset(5f * s, 19f * s), Offset(13f * s, 11f * s), strokeWidth = 2.6f * s, cap = StrokeCap.Round)
            withTransform({ rotate(45f, Offset(14.5f * s, 9.5f * s)) }) {
                drawRoundRect(color, Offset(9f * s, 7f * s), Size(11f * s, 5f * s), CornerRadius(1.2f * s))
            }
        }
        Ic.GitBranch -> {
            ring(7f, 5.5f, 2f); ring(7f, 18.5f, 2f); ring(17f, 8.5f, 2f)
            line(7f, 7.5f, 7f, 16.5f)
            val p = Path()
            p.moveTo(17f * s, 10.5f * s)
            p.quadraticTo(17f * s, 14f * s, 7f * s, 14.5f * s)
            drawPath(p, color, style = stroke)
        }
        Ic.Flask -> {
            poly(10f, 3f, 10f, 10f, 5f, 19f, 19f, 19f, 14f, 10f, 14f, 3f)
            line(9f, 3f, 15f, 3f); line(7.5f, 15f, 16.5f, 15f)
        }
        Ic.Braces -> {
            val l = Path()
            l.moveTo(10f * s, 4f * s); l.quadraticTo(7f * s, 4f * s, 7f * s, 7.5f * s); l.lineTo(7f * s, 9.5f * s)
            l.quadraticTo(7f * s, 12f * s, 4.5f * s, 12f * s); l.quadraticTo(7f * s, 12f * s, 7f * s, 14.5f * s)
            l.lineTo(7f * s, 16.5f * s); l.quadraticTo(7f * s, 20f * s, 10f * s, 20f * s)
            drawPath(l, color, style = stroke)
            val r = Path()
            r.moveTo(14f * s, 4f * s); r.quadraticTo(17f * s, 4f * s, 17f * s, 7.5f * s); r.lineTo(17f * s, 9.5f * s)
            r.quadraticTo(17f * s, 12f * s, 19.5f * s, 12f * s); r.quadraticTo(17f * s, 12f * s, 17f * s, 14.5f * s)
            r.lineTo(17f * s, 16.5f * s); r.quadraticTo(17f * s, 20f * s, 14f * s, 20f * s)
            drawPath(r, color, style = stroke)
        }
        Ic.Gamepad -> {
            box(3f, 8f, 21f, 17f, 4f)
            line(7f, 12.5f, 10f, 12.5f); line(8.5f, 11f, 8.5f, 14f)
            dot(15f, 12f, 1.1f); dot(17.5f, 13.8f, 1.1f)
        }
        Ic.Layout -> { box(3f, 4f, 21f, 20f, 2f); line(3f, 9f, 21f, 9f); line(9f, 9f, 9f, 20f) }
        Ic.Star -> {
            val p = Path()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 8.5f else 3.6f
                val a = Math.toRadians(-90.0 + i * 36.0)
                val x = (12f + r * cos(a).toFloat()) * s
                val y = (12f + r * sin(a).toFloat()) * s
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            drawPath(p, color, style = stroke)
        }

        // ---- special-folder glyphs
        Ic.Cube -> { // assets
            poly(12f, 3f, 20.5f, 7.8f, 20.5f, 16.2f, 12f, 21f, 3.5f, 16.2f, 3.5f, 7.8f, close = true)
            poly(3.5f, 7.8f, 12f, 12.6f, 20.5f, 7.8f)
            line(12f, 12.6f, 12f, 21f)
        }
        Ic.Tray -> { // dist
            line(12f, 15f, 12f, 4f)
            poly(7.5f, 8.5f, 12f, 4f, 16.5f, 8.5f)
            poly(4f, 14.5f, 4f, 20f, 20f, 20f, 20f, 14.5f)
        }
        Ic.Book -> { // docs
            poly(3.5f, 5.5f, 11.5f, 7.2f, 11.5f, 19.5f, 3.5f, 17.8f, close = true)
            poly(20.5f, 5.5f, 12.5f, 7.2f, 12.5f, 19.5f, 20.5f, 17.8f, close = true)
        }
        Ic.Library -> { // lib
            box(4f, 4.5f, 8.5f, 19.5f, 1f)
            box(10f, 4.5f, 14.5f, 19.5f, 1f)
            poly(16f, 6.5f, 19.8f, 5.5f, 21.5f, 18.3f, 17.7f, 19.3f, close = true)
        }
        Ic.Sliders -> { // config
            line(4f, 6f, 7f, 6f); line(11f, 6f, 20f, 6f); ring(9f, 6f, 2f)
            line(4f, 12f, 13f, 12f); line(17f, 12f, 20f, 12f); ring(15f, 12f, 2f)
            line(4f, 18f, 6f, 18f); line(10f, 18f, 20f, 18f); ring(8f, 18f, 2f)
        }
        Ic.Grid -> { // components
            box(4f, 4f, 10.5f, 10.5f, 1.2f); box(13.5f, 4f, 20f, 10.5f, 1.2f)
            box(4f, 13.5f, 10.5f, 20f, 1.2f); box(13.5f, 13.5f, 20f, 20f, 1.2f)
        }
        Ic.Terminal -> { // scripts
            poly(5f, 7.5f, 10.5f, 12f, 5f, 16.5f)
            line(12.5f, 17f, 19.5f, 17f)
        }
        Ic.Database -> { // data
            drawOval(color, Offset(5f * s, 3.5f * s), Size(14f * s, 5.5f * s), style = stroke)
            line(5f, 6.3f, 5f, 17.7f); line(19f, 6.3f, 19f, 17.7f)
            drawArc(color, 0f, 180f, false, Offset(5f * s, 9.2f * s), Size(14f * s, 5.6f * s), style = stroke)
            drawArc(color, 0f, 180f, false, Offset(5f * s, 14.9f * s), Size(14f * s, 5.6f * s), style = stroke)
        }
        Ic.Plug -> { // plugins
            line(9.5f, 3.5f, 9.5f, 8f); line(14.5f, 3.5f, 14.5f, 8f)
            box(6.5f, 8f, 17.5f, 14.5f, 2.2f)
            line(12f, 14.5f, 12f, 20.5f)
        }
        Ic.Layers -> { // resources
            poly(12f, 3.5f, 21f, 8.3f, 12f, 13.1f, 3f, 8.3f, close = true)
            poly(3f, 12.3f, 12f, 17.1f, 21f, 12.3f)
            poly(3f, 16.2f, 12f, 21f, 21f, 16.2f)
        }
        Ic.TypeA -> { // fonts
            poly(5.5f, 20f, 12f, 4.5f, 18.5f, 20f)
            line(8f, 14.5f, 16f, 14.5f)
        }
        Ic.Note -> { // audio
            ring(7.5f, 17.5f, 2.5f); ring(15.5f, 15.5f, 2.5f)
            line(10f, 17.5f, 10f, 6.5f); line(18f, 15.5f, 18f, 4.5f)
            line(10f, 6.5f, 18f, 4.5f)
        }
        Ic.Film -> { // video
            box(3f, 5.5f, 21f, 18.5f, 2.5f)
            poly(10f, 9.2f, 15.5f, 12f, 10f, 14.8f, close = true)
        }
        Ic.Globe -> { // public
            ring(12f, 12f, 8.5f)
            line(3.5f, 12f, 20.5f, 12f)
            drawOval(color, Offset(8.3f * s, 3.5f * s), Size(7.4f * s, 17f * s), style = stroke)
        }
        Ic.Android -> { // android
            drawArc(color, 180f, 180f, false, Offset(5.5f * s, 6.5f * s), Size(13f * s, 13f * s), style = stroke)
            line(5.5f, 13f, 18.5f, 13f)
            line(8.2f, 6.2f, 9.6f, 8.4f); line(15.8f, 6.2f, 14.4f, 8.4f)
            dot(9.6f, 10.2f, 0.95f); dot(14.4f, 10.2f, 0.95f)
            box(6f, 14.8f, 18f, 20.5f, 1.8f)
        }
        Ic.Apple -> { // ios
            val a = Path()
            a.moveTo(12f * s, 8.3f * s)
            a.cubicTo(10.4f * s, 6.8f * s, 5.8f * s, 7.4f * s, 5.8f * s, 12.6f * s)
            a.cubicTo(5.8f * s, 16.8f * s, 8.4f * s, 20.8f * s, 10.5f * s, 20.8f * s)
            a.cubicTo(11.5f * s, 20.8f * s, 11.6f * s, 20.2f * s, 12f * s, 20.2f * s)
            a.cubicTo(12.4f * s, 20.2f * s, 12.5f * s, 20.8f * s, 13.5f * s, 20.8f * s)
            a.cubicTo(15.6f * s, 20.8f * s, 18.2f * s, 16.8f * s, 18.2f * s, 12.6f * s)
            a.cubicTo(18.2f * s, 7.4f * s, 13.6f * s, 6.8f * s, 12f * s, 8.3f * s)
            a.close()
            drawPath(a, color, style = stroke)
            val leaf = Path()
            leaf.moveTo(12f * s, 7.6f * s)
            leaf.cubicTo(12f * s, 5.4f * s, 13.2f * s, 3.9f * s, 15.2f * s, 3.5f * s)
            drawPath(leaf, color, style = stroke)
        }
        Ic.Monitor -> { // web
            box(3f, 4f, 21f, 16f, 2f)
            line(8f, 20f, 16f, 20f)
            line(12f, 16f, 12f, 20f)
        }
        Ic.Archive -> { // vendor
            box(3.5f, 4f, 20.5f, 9f, 1.5f)
            poly(5f, 9f, 5f, 19.5f, 19f, 19.5f, 19f, 9f)
            line(10f, 13f, 14f, 13f)
        }

        // ---- editor controls
        Ic.Undo -> {
            poly(9f, 5f, 4f, 10f, 9f, 15f)
            val p = Path()
            p.moveTo(4f * s, 10f * s)
            p.lineTo(14f * s, 10f * s)
            p.cubicTo(18f * s, 10f * s, 20.5f * s, 12.5f * s, 20.5f * s, 15.5f * s)
            p.cubicTo(20.5f * s, 18.5f * s, 18f * s, 20.5f * s, 14.5f * s, 20.5f * s)
            p.lineTo(10f * s, 20.5f * s)
            drawPath(p, color, style = stroke)
        }
        Ic.Redo -> {
            poly(15f, 5f, 20f, 10f, 15f, 15f)
            val p = Path()
            p.moveTo(20f * s, 10f * s)
            p.lineTo(10f * s, 10f * s)
            p.cubicTo(6f * s, 10f * s, 3.5f * s, 12.5f * s, 3.5f * s, 15.5f * s)
            p.cubicTo(3.5f * s, 18.5f * s, 6f * s, 20.5f * s, 9.5f * s, 20.5f * s)
            p.lineTo(14f * s, 20.5f * s)
            drawPath(p, color, style = stroke)
        }
        Ic.Tabs -> { // open files: a square with a downward arrow inside
            box(3.5f, 3.5f, 20.5f, 20.5f, 3f)
            line(12f, 8f, 12f, 16f)
            poly(8.5f, 12.5f, 12f, 16f, 15.5f, 12.5f)
        }
    }
}

// ---------------------------------------------------------------- file and folder icons

object LogoCache {
    private val map = HashMap<String, ImageBitmap>()
    private val missing = HashSet<String>()

    @Synchronized
    fun get(context: Context, key: String): ImageBitmap? {
        map[key]?.let { return it }
        if (key in missing) return null
        val bmp = try {
            context.assets.open("icons/lang/$key.png").use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        } catch (e: Exception) {
            null
        }
        if (bmp == null) missing.add(key) else map[key] = bmp
        return bmp
    }
}

/** Language logo for recognised files, otherwise a generic file or image icon. */
@Composable
fun FileIcon(name: String, size: Dp = 22.dp, tint: Color = G.c.dim) {
    val context = LocalContext.current
    val key = Fs.iconKeyOf(name)
    val logo = if (key != null) remember(key) { LogoCache.get(context, key) } else null
    when {
        logo != null -> Image(bitmap = logo, contentDescription = null, modifier = Modifier.size(size))
        Fs.isImageName(name) -> Ico(Ic.Image, tint, size)
        else -> Ico(Ic.File, tint, size)
    }
}

/**
 * The glyph that says what a special folder is for. Every folder name has its own glyph.
 * (".github" and "tests" are the same folders as "github" and "test", so they share theirs.)
 */
fun folderGlyph(name: String): Ic? = when (name.lowercase()) {
    "github", ".github" -> Ic.GitBranch
    "test", "tests" -> Ic.Flask
    "main" -> Ic.Star
    "src" -> Ic.Braces
    "game" -> Ic.Gamepad
    "ui" -> Ic.Layout
    "assets" -> Ic.Cube
    "build" -> Ic.Hammer
    "dist" -> Ic.Tray
    "docs" -> Ic.Book
    "lib" -> Ic.Library
    "config" -> Ic.Sliders
    "components" -> Ic.Grid
    "scripts" -> Ic.Terminal
    "data" -> Ic.Database
    "plugins" -> Ic.Plug
    "resources" -> Ic.Layers
    "fonts" -> Ic.TypeA
    "images" -> Ic.Image
    "audio" -> Ic.Note
    "video" -> Ic.Film
    "public" -> Ic.Globe
    "android" -> Ic.Android
    "ios" -> Ic.Apple
    "web" -> Ic.Monitor
    "vendor" -> Ic.Archive
    else -> null
}

/**
 * Plain folders are drawn as an outline. Special folders are solid accent-coloured folders with a
 * large glyph in white (or black if the accent were a light colour).
 */
@Composable
fun FolderIcon(name: String, size: Dp = 28.dp, tint: Color = G.c.dim) {
    val glyph = folderGlyph(name)
    val accent = G.c.accent
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 24f
        if (glyph == null) {
            drawGlyph(Ic.Folder, tint, s, 1.8f)
        } else {
            val body = Path()
            body.moveTo(2.5f * s, 4.5f * s)
            body.lineTo(9.5f * s, 4.5f * s)
            body.lineTo(11.5f * s, 7f * s)
            body.lineTo(21.5f * s, 7f * s)
            body.lineTo(21.5f * s, 20f * s)
            body.lineTo(2.5f * s, 20f * s)
            body.close()
            drawPath(body, accent)
            drawPath(body, accent, style = Stroke(width = 1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
            withTransform({ translate(5.75f * s, 7.25f * s); scale(0.52f, 0.52f, Offset.Zero) }) {
                drawGlyph(glyph, onAccent, s, 3.2f)
            }
        }
    }
}