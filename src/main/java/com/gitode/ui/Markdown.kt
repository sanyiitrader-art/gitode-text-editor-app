package com.gitode.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
import org.commonmark.node.Image as MdImageNode
import org.commonmark.node.Text as MdTextNode
import org.commonmark.parser.Parser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// ====================================================================== the small on/off switch

/** A compact switch with no text, used for the Markdown preview toggle in the file-name sheet. */
@Composable
fun MiniSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = G.c
    Box(
        modifier.width(38.dp).height(22.dp).clip(CircleShape)
            .background(if (checked) c.accent else c.outline)
            .clickable { onChange(!checked) }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(if (checked) c.onAccent else c.dim))
    }
}

// ====================================================================== parsing

object MdParser {
    private val parser: Parser = Parser.builder()
        .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create()))
        .build()

    /** The document's top-level blocks. */
    fun parse(text: String): List<Node> {
        val doc = parser.parse(text)
        val out = ArrayList<Node>()
        var n = doc.firstChild
        while (n != null) {
            out.add(n)
            n = n.next
        }
        return out
    }
}

private fun kids(n: Node): List<Node> {
    val out = ArrayList<Node>()
    var c = n.firstChild
    while (c != null) {
        out.add(c)
        c = c.next
    }
    return out
}

private fun plain(n: Node): String {
    val sb = StringBuilder()
    var ch = n.firstChild
    while (ch != null) {
        when (ch) {
            is MdTextNode -> sb.append(ch.literal)
            is Code -> sb.append(ch.literal)
            else -> sb.append(plain(ch))
        }
        ch = ch.next
    }
    return sb.toString()
}

private fun hasImage(n: Node): Boolean {
    var ch = n.firstChild
    while (ch != null) {
        if (ch is MdImageNode || hasImage(ch)) return true
        ch = ch.next
    }
    return false
}

// ====================================================================== raw HTML (images and plain text only)

private val IMG_TAG = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
private val ANY_TAG = Regex("""<[^>]+>""")
private val HTML_COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
private val BR_TAG = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

private fun attr(tag: String, name: String): String? {
    val m = Regex("""\b$name\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE).find(tag) ?: return null
    return m.groupValues[1].ifEmpty { m.groupValues[2] }
}

private fun decodeEntities(s: String): String =
    s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

/** Raw HTML is shown as its text; <img> tags become images. Other tags are dropped. */
private fun htmlSegments(html: String): List<Seg> {
    val src = BR_TAG.replace(HTML_COMMENT.replace(html, ""), "\n")
    val segs = ArrayList<Seg>()
    var pos = 0
    fun text(chunk: String) {
        val t = decodeEntities(ANY_TAG.replace(chunk, "")).trim()
        if (t.isNotEmpty()) segs.add(Seg.T(AnnotatedString(t)))
    }
    for (m in IMG_TAG.findAll(src)) {
        text(src.substring(pos, m.range.first))
        val url = attr(m.value, "src")
        if (url != null) segs.add(Seg.I(url, attr(m.value, "alt") ?: "", null))
        pos = m.range.last + 1
    }
    text(src.substring(pos))
    return segs
}

// ====================================================================== inline content

private sealed interface Seg {
    class T(val text: AnnotatedString) : Seg
    class I(val url: String, val alt: String, val link: String?) : Seg
}

/** Turns a node's inline children into text pieces and images. */
private class InlineWalker(private val c: GitodeColors) {
    private val segs = ArrayList<Seg>()
    private var b = AnnotatedString.Builder()
    private var linkDest: String? = null

    fun walk(first: Node?) {
        var n = first
        while (n != null) {
            visit(n)
            n = n.next
        }
    }

    fun finish(): List<Seg> {
        flush()
        return segs
    }

    private fun flush() {
        if (b.length > 0) {
            segs.add(Seg.T(b.toAnnotatedString()))
            b = AnnotatedString.Builder()
        }
    }

    private fun visit(n: Node) {
        when (n) {
            is MdTextNode -> b.append(n.literal)
            is SoftLineBreak -> b.append(' ')
            is HardLineBreak -> b.append('\n')
            is Code -> b.withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, background = c.outline.copy(alpha = 0.45f)),
            ) { append(n.literal) }
            is Emphasis -> b.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { walk(n.firstChild) }
            is StrongEmphasis -> b.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { walk(n.firstChild) }
            is Strikethrough -> b.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { walk(n.firstChild) }
            is Link -> link(n)
            is MdImageNode -> {
                flush()
                segs.add(Seg.I(n.destination ?: "", plain(n), linkDest))
            }
            is TaskListItemMarker -> b.append(if (n.isChecked) "☑ " else "☐ ")
            is HtmlInline -> html(n.literal)
            else -> walk(n.firstChild)
        }
    }

    private fun link(n: Link) {
        if (hasImage(n)) {
            // a linked picture (a badge, for example): the picture itself is the link
            val prev = linkDest
            linkDest = n.destination
            walk(n.firstChild)
            linkDest = prev
        } else {
            val bb = b
            bb.pushStringAnnotation("URL", n.destination ?: "")
            bb.pushStyle(SpanStyle(color = c.accent, textDecoration = TextDecoration.Underline))
            walk(n.firstChild)
            bb.pop()
            bb.pop()
        }
    }

    private fun html(literal: String) {
        val lit = literal.trim()
        if (BR_TAG.matches(lit)) {
            b.append('\n')
        } else if (IMG_TAG.matches(lit)) {
            flush()
            val u = attr(lit, "src")
            if (u != null) segs.add(Seg.I(u, attr(lit, "alt") ?: "", linkDest))
        }
    }
}

// ====================================================================== images (web, local files, SVG)

class MdImg(val bitmap: ImageBitmap, val naturalDp: Float?)

object MdImages {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private val cache = LruCache<String, MdImg>(32)
    private val failed = HashSet<String>()

    suspend fun load(url: String, baseDir: File?): MdImg? {
        val remote = url.startsWith("http://", true) || url.startsWith("https://", true)
        val key = if (remote) url else (local(url, baseDir) ?: return null).path
        cache.get(key)?.let { return it }
        if (synchronized(failed) { key in failed }) return null
        val img = withContext(Dispatchers.IO) {
            try {
                val bytes = if (remote) download(url) else File(key).readBytes()
                decode(bytes)
            } catch (e: Throwable) {
                null
            }
        }
        if (img != null) cache.put(key, img) else synchronized(failed) { failed.add(key) }
        return img
    }

    private fun local(url: String, base: File?): File? {
        val clean = Uri.decode(url.substringBefore('#').substringBefore('?'))
        val f = if (clean.startsWith("/")) File(clean) else File(base ?: return null, clean)
        return if (f.isFile) f else null
    }

    private fun download(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 12000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Gitode")
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) throw IOException("Image too large")
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    private fun decode(bytes: ByteArray): MdImg? {
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.UTF_8)
        if (head.contains("<svg", ignoreCase = true)) return decodeSvg(String(bytes, Charsets.UTF_8))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return MdImg(bmp.asImageBitmap(), null)
    }

    private fun decodeSvg(text: String): MdImg? {
        val svg = SVG.getFromString(text)
        val picture = svg.renderToPicture()
        val w = picture.width
        val h = picture.height
        if (w <= 0 || h <= 0) return null
        val scale = minOf(3f, 2000f / w, 2000f / h)
        val bmp = Bitmap.createBitmap(
            (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bmp)
        canvas.scale(scale, scale)
        canvas.drawPicture(picture)
        return MdImg(bmp.asImageBitmap(), w.toFloat())
    }
}

// ====================================================================== drawing

private class MdCtx(val baseDir: File?, val onLink: (String) -> Unit)

private fun openLink(context: Context, url: String) {
    val u = url.trim()
    val ok = u.startsWith("http://", true) || u.startsWith("https://", true) ||
        u.startsWith("mailto:", true) || u.startsWith("tel:", true)
    if (!ok) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        // No app can open it.
    }
}

/**
 * The rendered Markdown. It scrolls vertically only: lines wrap, nothing scrolls sideways,
 * there are no line numbers and nothing can be edited.
 */
@Composable
fun MarkdownPreview(text: String, baseDir: File?, modifier: Modifier = Modifier) {
    val c = G.c
    val context = LocalContext.current
    var blocks by remember { mutableStateOf<List<Node>?>(null) }
    LaunchedEffect(text) { blocks = withContext(Dispatchers.Default) { MdParser.parse(text) } }
    val ctx = remember(baseDir) { MdCtx(baseDir) { url -> openLink(context, url) } }
    val list = blocks

    Box(modifier.fillMaxSize().background(c.editorBg)) {
        if (list != null) {
            if (list.isEmpty()) {
                Txt("Nothing to preview", Modifier.align(Alignment.Center), color = c.dim)
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(list) { node -> MdBlock(node, ctx, 0, false) }
                }
            }
        }
    }
}

@Composable
private fun MdBlock(node: Node, ctx: MdCtx, depth: Int, dim: Boolean) {
    val c = G.c
    val body = TextStyle(color = if (dim) c.dim else c.text, fontSize = 15.sp, lineHeight = 23.sp)
    when (node) {
        is Heading -> MdHeading(node, ctx)
        is Paragraph -> MdInline(node, body, ctx)
        is FencedCodeBlock -> MdCode(node.literal)
        is IndentedCodeBlock -> MdCode(node.literal)
        is BlockQuote -> MdQuote(node, ctx, depth)
        is BulletList -> MdList(node, false, ctx, depth)
        is OrderedList -> MdList(node, true, ctx, depth)
        is ThematicBreak -> Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(c.outline))
        is TableBlock -> MdTable(node, ctx)
        is HtmlBlock -> MdSegments(htmlSegments(node.literal), body, ctx)
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (k in kids(node)) MdBlock(k, ctx, depth, dim)
        }
    }
}

@Composable
private fun MdHeading(h: Heading, ctx: MdCtx) {
    val c = G.c
    val size = when (h.level) {
        1 -> 27.sp
        2 -> 22.sp
        3 -> 19.sp
        4 -> 17.sp
        5 -> 16.sp
        else -> 15.sp
    }
    val style = TextStyle(color = c.text, fontSize = size, lineHeight = size * 1.3f, fontWeight = FontWeight.Bold)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MdInline(h, style, ctx)
        if (h.level <= 2) Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
    }
}

@Composable
private fun MdInline(parent: Node, style: TextStyle, ctx: MdCtx, align: TextAlign? = null) {
    val c = G.c
    val segs = remember(parent, c) {
        val walker = InlineWalker(c)
        walker.walk(parent.firstChild)
        walker.finish()
    }
    MdSegments(segs, style, ctx, align)
}

@Composable
private fun MdSegments(segs: List<Seg>, style: TextStyle, ctx: MdCtx, align: TextAlign? = null) {
    if (segs.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (s in segs) {
            when (s) {
                is Seg.T -> MdText(s.text, if (align != null) style.copy(textAlign = align) else style, ctx.onLink)
                is Seg.I -> MdImageView(s.url, s.alt, s.link, ctx)
            }
        }
    }
}

@Composable
private fun MdText(text: AnnotatedString, style: TextStyle, onLink: (String) -> Unit) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val hasLinks = remember(text) { text.getStringAnnotations("URL", 0, text.length).isNotEmpty() }
    val tap = if (hasLinks) {
        Modifier.pointerInput(text) {
            detectTapGestures { pos ->
                val l = layout ?: return@detectTapGestures
                val off = l.getOffsetForPosition(pos)
                text.getStringAnnotations("URL", off, off).firstOrNull()?.let { onLink(it.item) }
            }
        }
    } else {
        Modifier
    }
    Text(text = text, style = style, modifier = tap, onTextLayout = { layout = it })
}

@Composable
private fun MdCode(literal: String) {
    val c = G.c
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.fillMaxWidth().clip(shape).background(c.card).border(1.dp, c.outline, shape).padding(12.dp),
    ) {
        // wraps like all other text: no horizontal scrolling in the preview
        Text(
            text = literal.trimEnd('\n'),
            style = TextStyle(color = c.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp),
        )
    }
}

@Composable
private fun MdQuote(q: Node, ctx: MdCtx, depth: Int) {
    val c = G.c
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(c.accent.copy(alpha = 0.8f)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (k in kids(q)) MdBlock(k, ctx, depth + 1, true)
        }
    }
}

@Composable
private fun MdList(list: Node, ordered: Boolean, ctx: MdCtx, depth: Int) {
    val c = G.c
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        kids(list).filterIsInstance<ListItem>().forEachIndexed { i, item ->
            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = if (ordered) "${i + 1}." else if (depth % 2 == 0) "•" else "◦",
                    modifier = Modifier.width(if (ordered) 30.dp else 20.dp),
                    style = TextStyle(color = c.dim, fontSize = 15.sp, lineHeight = 23.sp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (k in kids(item)) MdBlock(k, ctx, depth + 1, false)
                }
            }
        }
    }
}

@Composable
private fun MdTable(table: Node, ctx: MdCtx) {
    val c = G.c
    val shape = RoundedCornerShape(8.dp)
    val rows = remember(table) {
        val out = ArrayList<Pair<Boolean, List<Node>>>()
        for (section in kids(table)) {
            val header = section is TableHead
            for (row in kids(section)) out.add(header to kids(row))
        }
        out
    }
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, c.outline, shape)) {
        rows.forEach { (header, cells) ->
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                    .background(if (header) c.card else Color.Transparent),
            ) {
                cells.forEach { cell ->
                    Box(Modifier.weight(1f).fillMaxHeight().border(0.5.dp, c.outline).padding(8.dp)) {
                        val align = when ((cell as? TableCell)?.alignment) {
                            TableCell.Alignment.CENTER -> TextAlign.Center
                            TableCell.Alignment.RIGHT -> TextAlign.End
                            else -> TextAlign.Start
                        }
                        MdInline(
                            cell,
                            TextStyle(
                                color = c.text, fontSize = 14.sp, lineHeight = 20.sp,
                                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                            ),
                            ctx,
                            align,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MdImageView(url: String, alt: String, link: String?, ctx: MdCtx) {
    val c = G.c
    val density = LocalDensity.current.density
    var image by remember(url) { mutableStateOf<MdImg?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url, ctx.baseDir) {
        val loaded = MdImages.load(url, ctx.baseDir)
        if (loaded == null) failed = true else image = loaded
    }
    val img = image
    val clickable = if (link != null) Modifier.clickable { ctx.onLink(link) } else Modifier
    when {
        img != null -> {
            val naturalDp = img.naturalDp ?: (img.bitmap.width / density)
            Image(
                bitmap = img.bitmap,
                contentDescription = alt,
                contentScale = ContentScale.Fit,
                modifier = Modifier.widthIn(max = naturalDp.dp).fillMaxWidth().then(clickable),
            )
        }
        failed -> if (alt.isNotBlank()) Txt("[$alt]", color = c.dim, size = 13.sp)
        else -> Spacer(Modifier.height(4.dp))
    }
}