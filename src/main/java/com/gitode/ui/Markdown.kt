package com.gitode.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.Extension
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
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

object MdHtml {
    private val extensions: List<Extension> = listOf(
        TablesExtension.create(),
        StrikethroughExtension.create(),
        TaskListItemsExtension.create(),
        AutolinkExtension.create(),
        FootnotesExtension.create(),
        HeadingAnchorExtension.create(),
    )
    private val parser: Parser = Parser.builder().extensions(extensions).build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder().extensions(extensions).build()

    // ---- front matter (--- ... --- at the very top) is hidden, like on GitHub's raw view of a page
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

    /** The HTML for the document body. */
    fun body(markdown: String): String {
        val doc = parser.parse(stripFrontMatter(markdown))
        applyAlerts(doc)
        return clean(renderer.render(doc))
    }

    /** A complete page: scripts are blocked by the policy, images may come from https, data: or files. */
    fun page(body: String, css: String): String =
        "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; " +
            "img-src https: data: file:; style-src 'unsafe-inline'; media-src https: file:\">" +
            "<style>" + css + "</style></head><body>" + body + "</body></html>"

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
p,ul,ol,dl,blockquote,pre,table,details,figure{margin:0 0 1em}
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
table{border-collapse:collapse;display:block;width:max-content;max-width:100%;overflow-x:auto}
th,td{border:1px solid $out;padding:7px 11px;vertical-align:top}
th{background:$card;font-weight:700}
tr:nth-child(even) td{background:$zebra}
img{max-width:100%;height:auto}
hr{border:0;border-top:1px solid $out;margin:1.6em 0}
del{color:$dim}
mark{background:$markBg;color:$tx;padding:0 .2em;border-radius:3px}
sub,sup{font-size:.75em}
details{border:1px solid $out;border-radius:10px;padding:.6em .9em}
details>summary{cursor:pointer;font-weight:600}
details[open]>summary{margin-bottom:.5em}
.footnotes{border-top:1px solid $out;margin-top:2em;font-size:.9em;color:$dim}
.footnotes ol{padding-left:1.4em}
sup a{text-decoration:none}
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

private class PreviewState(var page: String? = null, var base: String = "")

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

@Suppress("DEPRECATION")
private fun forceDarkOff(s: WebSettings) {
    if (Build.VERSION.SDK_INT >= 29) s.forceDark = WebSettings.FORCE_DARK_OFF
}

private fun createPreview(context: Context, bg: Color, onLink: (String) -> Unit): View {
    return try {
        val web = WebView(context)
        web.tag = PreviewState()
        web.setBackgroundColor(bg.toArgb())
        web.isHorizontalScrollBarEnabled = false
        web.overScrollMode = View.OVER_SCROLL_NEVER
        val s = web.settings
        s.javaScriptEnabled = false // nothing in a document may ever run
        s.allowFileAccess = true // images next to the document
        s.allowContentAccess = false
        s.domStorageEnabled = false
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.textZoom = 100
        s.useWideViewPort = true
        s.loadWithOverviewMode = false
        forceDarkOff(s)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme?.lowercase()
                val base = (view.tag as? PreviewState)?.base ?: ""
                return when {
                    scheme == "http" || scheme == "https" || scheme == "mailto" || scheme == "tel" -> {
                        onLink(url.toString())
                        true
                    }
                    // links inside the document (#section) scroll normally
                    base.isNotEmpty() && url.toString().startsWith(base) && url.fragment != null -> false
                    else -> true
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean = true
        }
        web
    } catch (e: Throwable) {
        TextView(context).apply {
            text = "The Markdown preview needs Android System WebView, which isn't available on this device."
            setTextColor(android.graphics.Color.WHITE)
            setPadding(48, 48, 48, 48)
        }
    }
}

/**
 * The rendered Markdown. The page scrolls vertically only. Long code lines and wide tables scroll
 * sideways inside their own box, never the page. There are no line numbers and nothing can be edited.
 */
@Composable
fun MarkdownPreview(text: String, baseDir: File?, modifier: Modifier = Modifier) {
    val c = G.c
    val context = androidx.compose.ui.platform.LocalContext.current
    var body by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(text) { body = withContext(Dispatchers.Default) { MdHtml.body(text) } }
    val css = remember(c) { MdHtml.css(c) }
    val base = remember(baseDir) { baseUrlFor(baseDir) }
    val b = body
    val page = remember(b, css) { if (b == null) null else MdHtml.page(b, css) }

    if (page == null) {
        Box(modifier.fillMaxSize().background(c.editorBg))
        return
    }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx -> createPreview(ctx, c.editorBg, onLink = { u -> openLink(context, u) }) },
        update = { v ->
            val wv = v as? WebView
            val st = v.tag as? PreviewState
            if (wv != null && st != null) {
                st.base = base
                if (st.page !== page) {
                    st.page = page
                    wv.setBackgroundColor(c.editorBg.toArgb())
                    wv.loadDataWithBaseURL(base, page, "text/html", "UTF-8", null)
                }
            }
        },
        onRelease = { v -> (v as? WebView)?.destroy() },
    )
}