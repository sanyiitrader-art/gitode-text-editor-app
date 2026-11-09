package com.gitode.ui

import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.Extension
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlBlock
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text as MdText
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.io.ByteArrayInputStream
import java.io.File

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

// ====================================================================== Markdown -> HTML

/** The rendered body of a document, and whether it contains math (which needs the maths script). */
class MdBody(val html: String, val hasMath: Boolean)

private class MathItem(val tex: String, val display: Boolean)

object MdHtml {
    // ~~strike~~ ==mark== ++ins++ ^sup^ and ~sub~ are handled by marks() below, not by an extension,
    // so that a single ~tilde~ can mean subscript.
    private val extensions: List<Extension> = listOf(
        TablesExtension.create(),
        TaskListItemsExtension.create(),
        AutolinkExtension.create(),
        FootnotesExtension.create(),
        HeadingAnchorExtension.create(),
    )
    private val parser: Parser = Parser.builder().extensions(extensions).build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder().extensions(extensions).build()

    // the last few documents are kept, so turning the preview on is instant when the text is unchanged
    private val cache = object : LinkedHashMap<String, MdBody>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MdBody>?): Boolean = size > 4
    }

    @Synchronized
    fun peek(markdown: String): MdBody? = cache[markdown]

    /** Builds (or returns the cached) HTML for a document. Safe to call from a background thread. */
    fun prepare(markdown: String): MdBody {
        peek(markdown)?.let { return it }
        val built = build(markdown)
        synchronized(this) { cache[markdown] = built }
        return built
    }

    // ---- front matter (--- ... --- at the very top) is hidden
    private val FRONT = Regex("^\\uFEFF?---[ \\t]*\\r?\\n(.*?)\\r?\\n(?:---|\\.\\.\\.)[ \\t]*(?:\\r?\\n|$)", RegexOption.DOT_MATCHES_ALL)
    private val YAML_LINE = Regex("^(\\s+.*|#.*|-.*|[^\\s:][^:]*:.*)$")

    private fun stripFrontMatter(s: String): String {
        val m = FRONT.find(s) ?: return s
        val inner = m.groupValues[1]
        // only treat it as front matter when every line looks like YAML
        if (!inner.lines().all { it.isBlank() || YAML_LINE.matches(it) }) return s
        return s.substring(m.range.last + 1)
    }

    // ---- GitHub alerts: > [!NOTE], > [!TIP], > [!IMPORTANT], > [!WARNING], > [!CAUTION]
    private val ALERT = Regex("^\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\\]$", RegexOption.IGNORE_CASE)

    private fun collectQuotes(n: Node, out: MutableList<BlockQuote>) {
        if (n is BlockQuote) out.add(n)
        var c = n.firstChild
        while (c != null) {
            collectQuotes(c, out)
            c = c.next
        }
    }

    private fun applyAlerts(doc: Node) {
        val quotes = ArrayList<BlockQuote>()
        collectQuotes(doc, quotes)
        for (bq in quotes) {
            val p = bq.firstChild as? Paragraph ?: continue
            val lead = ArrayList<Node>()
            val sb = StringBuilder()
            var n: Node? = p.firstChild
            while (n is MdText) {
                sb.append(n.literal)
                lead.add(n)
                n = n.next
            }
            val m = ALERT.matchEntire(sb.toString().trim()) ?: continue
            val kind = m.groupValues[1].lowercase()
            for (t in lead) t.unlink()
            val br = p.firstChild
            if (br != null && (br is SoftLineBreak || br is HardLineBreak)) br.unlink()
            if (p.firstChild == null) p.unlink()

            val open = HtmlBlock()
            open.literal = "<div class=\"alert alert-$kind\"><p class=\"alert-title\">" +
                kind.replaceFirstChar { it.uppercase() } + "</p>\n"
            val close = HtmlBlock()
            close.literal = "</div>\n"
            bq.insertBefore(open)
            var child = bq.firstChild
            while (child != null) {
                val next = child.next
                child.unlink()
                bq.insertBefore(child)
                child = next
            }
            bq.insertBefore(close)
            bq.unlink()
        }
    }

    // ---- math: $...$ and $$...$$ are lifted out before Markdown reads the text, so _ * \ inside a
    // formula are never treated as Markdown, and put back as elements that KaTeX draws.
    private fun extractMath(src: String, items: MutableList<MathItem>): String {
        val n = src.length
        val sb = StringBuilder(n)
        var i = 0
        var lineStart = true
        var fence: String? = null
        while (i < n) {
            if (lineStart) {
                val end = src.indexOf('\n', i).let { if (it < 0) n else it }
                val line = src.substring(i, end)
                val t = line.trimStart()
                val indentOk = line.length - t.length <= 3
                val f = fence
                if (f != null) {
                    sb.append(line)
                    if (end < n) sb.append('\n')
                    if (indentOk && t.startsWith(f) && t.trimEnd().all { it == f[0] }) fence = null
                    i = end + 1
                    continue
                }
                if (indentOk && (t.startsWith("```") || t.startsWith("~~~"))) {
                    val ch = t[0]
                    var k = 0
                    while (k < t.length && t[k] == ch) k++
                    fence = ch.toString().repeat(k)
                    sb.append(line)
                    if (end < n) sb.append('\n')
                    i = end + 1
                    continue
                }
                lineStart = false
            }
            val ch = src[i]
            when {
                ch == '\n' -> {
                    sb.append(ch)
                    i++
                    lineStart = true
                }
                ch == '\\' && i + 1 < n -> {
                    sb.append(ch).append(src[i + 1])
                    i += 2
                }
                ch == '`' -> {
                    var run = 0
                    while (i + run < n && src[i + run] == '`') run++
                    val ticks = "`".repeat(run)
                    val close = src.indexOf(ticks, i + run)
                    if (close > 0) {
                        sb.append(src, i, close + run)
                        i = close + run
                    } else {
                        sb.append(ticks)
                        i += run
                    }
                }
                ch == '$' -> {
                    if (src.startsWith("$$", i)) {
                        val end = src.indexOf("$$", i + 2)
                        val body = if (end > 0) src.substring(i + 2, end) else ""
                        if (end > 0 && body.isNotBlank() && !body.contains("\n\n")) {
                            items.add(MathItem(body.trim(), true))
                            sb.append("zzgmath").append(items.size - 1).append("zz")
                            i = end + 2
                        } else {
                            sb.append("$$")
                            i += 2
                        }
                    } else {
                        val j = i + 1
                        var found = -1
                        if (j < n && !src[j].isWhitespace()) {
                            var k = j
                            while (k < n) {
                                val c = src[k]
                                if (c == '\n' && k + 1 < n && src[k + 1] == '\n') break
                                if (c == '\\') {
                                    k += 2
                                    continue
                                }
                                if (c == '$') {
                                    // like pandoc: no space before the closing $, and not followed by a digit (prices)
                                    if (k > j && !src[k - 1].isWhitespace() && (k + 1 >= n || !src[k + 1].isDigit())) found = k
                                    break
                                }
                                k++
                            }
                        }
                        if (found > j) {
                            items.add(MathItem(src.substring(j, found), false))
                            sb.append("zzgmath").append(items.size - 1).append("zz")
                            i = found + 1
                        } else {
                            sb.append('$')
                            i++
                        }
                    }
                }
                else -> {
                    sb.append(ch)
                    i++
                }
            }
        }
        return sb.toString()
    }

    private val PLACE_P = Regex("<p[^>]*>\\s*zzgmath(\\d+)zz\\s*</p>")
    private val PLACE = Regex("zzgmath(\\d+)zz")

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun mathHtml(item: MathItem, block: Boolean): String {
        val tag = if (block) "div" else "span"
        val d = if (item.display) "\$\$" else "\$"
        // the raw formula is the fallback text, shown if the maths script can't run
        return "<$tag class=\"gm\" data-d=\"${if (item.display) 1 else 0}\" data-tex=\"${esc(item.tex)}\">${esc(d + item.tex + d)}</$tag>"
    }

    private fun restoreMath(html: String, items: List<MathItem>): String {
        if (items.isEmpty()) return html
        var h = PLACE_P.replace(html) { m ->
            val item = items.getOrNull(m.groupValues[1].toInt())
            if (item == null) m.value else mathHtml(item, true)
        }
        h = PLACE.replace(h) { m ->
            val item = items.getOrNull(m.groupValues[1].toInt())
            if (item == null) m.value else mathHtml(item, false)
        }
        return h
    }

    // ---- ~~strike~~  ==highlight==  ++insert++  ^sup^  ~sub~  (only in plain text, never in code)
    private val TOKEN = Regex("<[^>]*>|[^<]+")
    private val PROTECT_OPEN = Regex("^<(pre|code|script|style|kbd|samp|tt)(\\s|>)", RegexOption.IGNORE_CASE)
    private val PROTECT_CLOSE = Regex("^</(pre|code|script|style|kbd|samp|tt)\\s*>", RegexOption.IGNORE_CASE)
    private val STRIKE = Regex("~~(?=\\S)(.+?)(?<=\\S)~~")
    private val MARK = Regex("==(?=\\S)(.+?)(?<=\\S)==")
    private val INS = Regex("\\+\\+(?=\\S)(.+?)(?<=\\S)\\+\\+")
    private val SUB = Regex("(?<!~)~(?=[^\\s~])([^\\s~]+?)~(?!~)")
    private val SUP = Regex("\\^(?=[^\\s^])([^\\s^]+?)\\^")

    private fun transform(s: String): String {
        var r = s
        if (r.contains("~~")) r = STRIKE.replace(r, "<del>$1</del>")
        if (r.contains("==")) r = MARK.replace(r, "<mark>$1</mark>")
        if (r.contains("++")) r = INS.replace(r, "<ins>$1</ins>")
        if (r.contains('~')) r = SUB.replace(r, "<sub>$1</sub>")
        if (r.contains('^')) r = SUP.replace(r, "<sup>$1</sup>")
        return r
    }

    private fun marks(html: String): String {
        if (!html.contains('~') && !html.contains("==") && !html.contains("++") && !html.contains('^')) return html
        val out = StringBuilder(html.length + 64)
        var depth = 0
        for (m in TOKEN.findAll(html)) {
            val t = m.value
            if (t.startsWith("<")) {
                if (PROTECT_OPEN.containsMatchIn(t) && !t.endsWith("/>")) depth++
                else if (PROTECT_CLOSE.containsMatchIn(t) && depth > 0) depth--
                out.append(t)
            } else if (depth > 0) {
                out.append(t)
            } else {
                out.append(transform(t))
            }
        }
        return out.toString()
    }

    // ---- wide tables get their own sideways-scrolling box
    private val TABLE_OPEN = Regex("<table(?=[\\s>])", RegexOption.IGNORE_CASE)
    private val TABLE_CLOSE = Regex("</table>", RegexOption.IGNORE_CASE)

    private fun wrapTables(h: String): String {
        if (!h.contains("<table", ignoreCase = true)) return h
        val opened = TABLE_OPEN.replace(h) { "<div class=\"tw\"><table" }
        return TABLE_CLOSE.replace(opened, "</table></div>")
    }

    // ---- safety and direction
    private val DANGEROUS_PAIR = Regex(
        "<\\s*(script|style|iframe|object|embed|noscript|template|textarea|title|frameset)\\b[^>]*>.*?<\\s*/\\s*\\1\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val DANGEROUS_SINGLE = Regex(
        "<\\s*/?\\s*(script|style|iframe|object|embed|form|meta|link|base|frame|frameset|applet|noscript|template)\\b[^>]*>",
        RegexOption.IGNORE_CASE,
    )
    private val DIR_TAGS = Regex("<(p|li|h[1-6]|blockquote|td|th|summary|figcaption)(?=[\\s>])", RegexOption.IGNORE_CASE)

    private fun clean(html: String): String {
        var h = DANGEROUS_PAIR.replace(html, "")
        h = DANGEROUS_SINGLE.replace(h, "")
        // dir="auto": Arabic and Hebrew paragraphs, lists and headings align to the right
        return DIR_TAGS.replace(h) { "<${it.groupValues[1]} dir=\"auto\"" }
    }

    private fun build(markdown: String): MdBody {
        val items = ArrayList<MathItem>()
        val protectedSrc = extractMath(stripFrontMatter(markdown), items)
        val doc = parser.parse(protectedSrc)
        applyAlerts(doc)
        var html = clean(renderer.render(doc))
        html = marks(html)
        html = wrapTables(html)
        html = restoreMath(html, items)
        return MdBody(html, items.isNotEmpty())
    }

    /**
     * A complete page. Scripts are blocked except the two bundled ones (KaTeX and the small script that
     * draws the formulas), which are only added when the document has math.
     */
    fun page(b: MdBody, css: String): String {
        val csp = "default-src 'none'; img-src https: data: file:; style-src 'unsafe-inline' https://gitode.assets; " +
            "font-src https://gitode.assets; script-src https://gitode.assets; media-src https: file:"
        val link = if (b.hasMath) "<link rel=\"stylesheet\" href=\"https://gitode.assets/katex/katex.min.css\">" else ""
        val scripts = if (b.hasMath) {
            "<script src=\"https://gitode.assets/katex/katex.min.js\"></script>" +
                "<script src=\"https://gitode.assets/mdmath.js\"></script>"
        } else {
            ""
        }
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"$csp\">" +
            link + "<style>" + css + "</style></head><body>" + b.html + scripts + "</body></html>"
    }

    private fun hex(c: Color): String = "#" + (c.toArgb() and 0xFFFFFF).toString(16).padStart(6, '0')

    private fun rgba(c: Color, a: Float): String {
        val v = c.toArgb()
        return "rgba(${(v shr 16) and 255},${(v shr 8) and 255},${v and 255},$a)"
    }

    /** The page's style, built from the current Gitode theme. */
    fun css(c: GitodeColors): String {
        val bg = hex(c.editorBg)
        val tx = hex(c.text)
        val dim = hex(c.dim)
        val acc = hex(c.accent)
        val card = hex(c.card)
        val out = hex(c.outline)
        val codeBg = rgba(c.dim, 0.18f)
        val zebra = rgba(c.dim, 0.07f)
        val markBg = rgba(c.accent, 0.4f)
        val scheme = if (c.isDark) "dark" else "light"
        return """
:root{color-scheme:$scheme}
*{box-sizing:border-box}
html{background:$bg;-webkit-text-size-adjust:100%}
body{margin:0;padding:16px 18px 48px;background:$bg;color:$tx;font:16px/1.65 Roboto,"Noto Sans","Noto Sans Arabic","Noto Sans Hebrew",sans-serif;overflow-wrap:anywhere;overflow-x:hidden}
body>:first-child{margin-top:0}
h1,h2,h3,h4,h5,h6{margin:1.5em 0 .6em;line-height:1.3;font-weight:700}
h1{font-size:1.9em;padding-bottom:.3em;border-bottom:1px solid $out}
h2{font-size:1.5em;padding-bottom:.3em;border-bottom:1px solid $out}
h3{font-size:1.25em}
h4{font-size:1.1em}
h5{font-size:1em}
h6{font-size:.95em;color:$dim}
p,ul,ol,dl,blockquote,pre,details,figure{margin:0 0 1em}
a{color:$acc;text-decoration:underline;text-underline-offset:2px}
code,kbd,samp,tt{font-family:"Roboto Mono","Droid Sans Mono",monospace;font-size:.88em;background:$codeBg;border-radius:6px;padding:.15em .38em}
kbd{border:1px solid $out;border-bottom-width:2px}
pre{background:$card;border:1px solid $out;border-radius:10px;padding:12px 14px;overflow-x:auto;white-space:pre;overflow-wrap:normal}
pre code{background:none;padding:0;border-radius:0;font-size:.86em;white-space:pre}
blockquote{margin-left:0;padding:.1em 1em;color:$dim;border-left:3px solid $acc}
blockquote>:last-child{margin-bottom:0}
ul,ol{padding-left:1.6em}
li{margin:.25em 0}
li>p{margin:.3em 0}
li:has(>input[type=checkbox]){list-style:none;margin-left:-1.4em}
input[type=checkbox]{accent-color:$acc;margin:0 .5em 0 0;transform:scale(1.15);vertical-align:middle}
.tw{overflow-x:auto;max-width:100%;margin:0 0 1em}
.tw>table{border-collapse:collapse;width:max-content;min-width:100%;margin:0}
th,td{border:1px solid $out;padding:7px 11px;vertical-align:top;max-width:22em;overflow-wrap:break-word;word-break:normal}
th{background:$card;font-weight:700;text-align:left}
tr:nth-child(even) td{background:$zebra}
img{max-width:100%;height:auto}
hr{border:0;border-top:1px solid $out;margin:1.6em 0}
del{color:$dim}
mark{background:$markBg;color:$tx;padding:0 .2em;border-radius:3px}
ins{text-decoration:underline;text-decoration-color:$acc;text-underline-offset:3px}
sub,sup{font-size:.75em;line-height:0}
details{border:1px solid $out;border-radius:10px;padding:.6em .9em}
details>summary{cursor:pointer;font-weight:600}
details[open]>summary{margin-bottom:.5em}
.footnotes{border-top:1px solid $out;margin-top:2em;font-size:.9em;color:$dim}
.footnotes ol{padding-left:1.4em}
sup a{text-decoration:none}
.gm{overflow-wrap:normal}
div.gm{display:block;margin:1em 0;overflow-x:auto;overflow-y:hidden;text-align:center}
.katex{font-size:1.1em;text-indent:0}
.katex-display{margin:0}
.alert{margin:0 0 1em;padding:.6em 1em;border-left:4px solid $acc;border-radius:8px}
.alert>:last-child{margin-bottom:0}
.alert-title{font-weight:700;margin:0 0 .3em}
.alert-note{border-color:#4493F8;background:rgba(68,147,248,.13)}
.alert-note .alert-title{color:#4493F8}
.alert-tip{border-color:#3FB950;background:rgba(63,185,80,.13)}
.alert-tip .alert-title{color:#3FB950}
.alert-important{border-color:#AB7DF8;background:rgba(171,125,248,.13)}
.alert-important .alert-title{color:#AB7DF8}
.alert-warning{border-color:#D29922;background:rgba(210,153,34,.13)}
.alert-warning .alert-title{color:#D29922}
.alert-caution{border-color:#F85149;background:rgba(248,81,73,.13)}
.alert-caution .alert-title{color:#F85149}
"""
    }
}

// ====================================================================== the preview view

private class PreviewState(
    var page: String? = null,
    var base: String = "",
    var onLink: (String) -> Unit = {},
)

/** Draws every formula element on the page with KaTeX; a formula that fails keeps its raw text. */
private const val MATH_JS = """
(function () {
  function run() {
    if (typeof katex === 'undefined') return;
    var els = document.querySelectorAll('.gm');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      try {
        katex.render(el.getAttribute('data-tex') || '', el, {
          displayMode: el.getAttribute('data-d') === '1',
          throwOnError: false,
          output: 'html'
        });
      } catch (e) {}
    }
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', run); else run();
})();
"""

private fun assetResponse(mime: String, data: ByteArray, status: Int = 200, reason: String = "OK"): WebResourceResponse {
    val headers = HashMap<String, String>()
    headers["Access-Control-Allow-Origin"] = "*" // the font loader needs this for files from another origin
    headers["Cache-Control"] = "max-age=3600"
    val text = mime.startsWith("text/") || mime.contains("javascript")
    return WebResourceResponse(mime, if (text) "UTF-8" else null, status, reason, headers, ByteArrayInputStream(data))
}

private fun serveAsset(context: Context, path: String): WebResourceResponse {
    if (path == "/mdmath.js") return assetResponse("application/javascript", MATH_JS.toByteArray(Charsets.UTF_8))
    if (path.startsWith("/katex/") && !path.contains("..")) {
        val rel = path.removePrefix("/katex/")
        try {
            val bytes = context.assets.open("katex/$rel").use { it.readBytes() }
            val mime = when {
                rel.endsWith(".js") -> "application/javascript"
                rel.endsWith(".css") -> "text/css"
                rel.endsWith(".woff2") -> "font/woff2"
                else -> "application/octet-stream"
            }
            return assetResponse(mime, bytes)
        } catch (e: Exception) {
            // not bundled: fall through to "not found"
        }
    }
    return assetResponse("text/plain", ByteArray(0), 404, "Not Found")
}

private class PreviewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        val scheme = url.scheme?.lowercase()
        val st = view.tag as? PreviewState
        val base = st?.base ?: ""
        return when {
            scheme == "http" || scheme == "https" || scheme == "mailto" || scheme == "tel" -> {
                st?.onLink?.invoke(url.toString())
                true
            }
            // links inside the document (#section) scroll normally
            base.isNotEmpty() && url.toString().startsWith(base) && url.fragment != null -> false
            else -> true
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val u = request.url
        return if (u.host == "gitode.assets") serveAsset(view.context, u.path ?: "") else null
    }

    override fun onPageFinished(view: WebView, url: String?) {
        view.visibility = View.VISIBLE
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        MdWebPool.discard(view)
        return true
    }
}

/**
 * Keeps one ready WebView so turning the Markdown preview on does not have to build a browser first.
 * It is created in advance (when a Markdown file opens) and reused.
 */
object MdWebPool {
    private var view: WebView? = null
    private var wrapper: MutableContextWrapper? = null
    private var appContext: Context? = null

    @Suppress("DEPRECATION")
    private fun configure(v: WebView) {
        v.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        v.isHorizontalScrollBarEnabled = false
        v.overScrollMode = View.OVER_SCROLL_NEVER
        val s = v.settings
        s.javaScriptEnabled = false // switched on per page, only for documents that contain math
        s.allowFileAccess = true // images next to the document
        s.allowContentAccess = false
        s.domStorageEnabled = false
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.textZoom = 100
        s.useWideViewPort = true
        s.loadWithOverviewMode = false
        if (Build.VERSION.SDK_INT >= 29) s.forceDark = WebSettings.FORCE_DARK_OFF
        v.webViewClient = PreviewClient()
    }

    /** Builds the WebView ahead of time (call it when idle, e.g. as soon as a Markdown file opens). */
    fun warm(context: Context) {
        if (view != null) return
        try {
            val app = context.applicationContext
            appContext = app
            val w = MutableContextWrapper(app)
            val v = WebView(w)
            configure(v)
            v.loadDataWithBaseURL(null, "<html><body></body></html>", "text/html", "UTF-8", null)
            wrapper = w
            view = v
        } catch (e: Throwable) {
            view = null
        }
    }

    private fun fallback(context: Context): View = TextView(context).apply {
        text = "The Markdown preview needs Android System WebView, which isn't available on this device."
        setTextColor(android.graphics.Color.WHITE)
        setPadding(48, 48, 48, 48)
    }

    fun take(context: Context): View {
        return try {
            warm(context)
            val v = view ?: return fallback(context)
            view = null
            (v.parent as? ViewGroup)?.removeView(v)
            wrapper?.baseContext = context
            v.tag = PreviewState()
            v.visibility = View.INVISIBLE
            v
        } catch (e: Throwable) {
            fallback(context)
        }
    }

    fun give(v: View) {
        val w = v as? WebView ?: return
        try {
            (w.parent as? ViewGroup)?.removeView(w)
            w.loadUrl("about:blank")
            w.settings.javaScriptEnabled = false
            (w.tag as? PreviewState)?.page = null
            appContext?.let { wrapper?.baseContext = it }
            view = w
        } catch (e: Throwable) {
            discard(w)
        }
    }

    fun discard(v: View) {
        try {
            (v as? WebView)?.destroy()
        } catch (e: Throwable) {
            // already gone
        }
        if (view === v) view = null
    }
}

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

/** Images next to the document load through this base address. */
private fun baseUrlFor(dir: File?): String {
    if (dir == null) return "file:///"
    return Uri.fromFile(dir).toString().trimEnd('/') + "/"
}

/**
 * The rendered Markdown. The page scrolls vertically only. Code and wide tables scroll sideways inside
 * their own box, never the page. There are no line numbers and nothing can be edited.
 */
@Composable
fun MarkdownPreview(text: String, baseDir: File?, modifier: Modifier = Modifier) {
    val c = G.c
    val context = LocalContext.current
    var body by remember(text) { mutableStateOf(MdHtml.peek(text)) }
    LaunchedEffect(text) {
        if (body == null) body = withContext(Dispatchers.Default) { MdHtml.prepare(text) }
    }
    val css = remember(c) { MdHtml.css(c) }
    val base = remember(baseDir) { baseUrlFor(baseDir) }
    val b = body
    val page = remember(b, css) { if (b == null) null else MdHtml.page(b, css) }

    Box(modifier.fillMaxSize().background(c.editorBg)) {
        if (b != null && page != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> MdWebPool.take(ctx) },
                update = { v ->
                    val wv = v as? WebView
                    val st = v.tag as? PreviewState
                    if (wv != null && st != null) {
                        st.base = base
                        st.onLink = { u -> openLink(context, u) }
                        if (st.page !== page) {
                            st.page = page
                            wv.settings.javaScriptEnabled = b.hasMath
                            wv.visibility = View.INVISIBLE
                            wv.loadDataWithBaseURL(base, page, "text/html", "UTF-8", null)
                            // never leave it hidden if the page-finished signal doesn't arrive
                            wv.postDelayed({ wv.visibility = View.VISIBLE }, 1500)
                        }
                    }
                },
                onRelease = { v -> MdWebPool.give(v) },
            )
        }
    }
}