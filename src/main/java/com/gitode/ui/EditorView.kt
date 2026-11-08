package com.gitode.ui

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.gitode.core.Fs
import com.gitode.core.SmartConfig
import com.gitode.core.SmartEdit
import com.gitode.core.SmartResult
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EventReceiver
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.delay
import org.eclipse.tm4e.core.registry.IThemeSource
import org.json.JSONObject

// ====================================================================== TextMate setup

object TextMate {
    private var tried = false
    private var ready = false
    private var initError: String? = null
    private val themes = HashSet<String>()
    private var scopes: Map<String, String> = emptyMap()
    private val failures = HashMap<String, String>()
    private val reported = HashSet<String>()

    @Synchronized
    fun init(context: Context) {
        if (tried) return
        tried = true
        try {
            val assets = context.applicationContext.assets
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
            val json = JSONObject(assets.open("textmate/languages.json").bufferedReader().use { it.readText() })
            val arr = json.getJSONArray("languages")
            val map = HashMap<String, String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                map[o.getString("name")] = o.getString("scopeName")
            }
            scopes = map
            ready = true
        } catch (e: Throwable) {
            ready = false
            initError = (e.message ?: e.javaClass.simpleName).take(140)
        }
    }

    /** Loads (once) and selects a syntax theme. Returns false if TextMate isn't usable. */
    @Synchronized
    fun useTheme(id: String, dark: Boolean): Boolean {
        if (!ready) return false
        return try {
            if (themes.add(id)) {
                val path = "textmate/themes/$id.json"
                val stream = FileProviderRegistry.getInstance().tryGetInputStream(path)
                if (stream == null) {
                    themes.remove(id)
                    return false
                }
                val model = ThemeModel(IThemeSource.fromInputStream(stream, path, null), id)
                model.isDark = dark
                ThemeRegistry.getInstance().loadTheme(model)
            }
            ThemeRegistry.getInstance().setTheme(id)
            true
        } catch (e: Throwable) {
            false
        }
    }

    @Synchronized
    fun languageFor(fileName: String): Language? {
        val key = Fs.languageOf(fileName) ?: return null
        if (!ready) {
            failures[key] = "the highlighting engine didn't start (${initError ?: "unknown error"})"
            return null
        }
        val scope = scopes[key]
        if (scope == null) {
            failures[key] = "its grammar wasn't bundled in this build"
            return null
        }
        return try {
            TextMateLanguage.create(scope, false)
        } catch (e: Throwable) {
            failures[key] = (e.message ?: e.javaClass.simpleName).take(140)
            null
        }
    }

    /** A one-time, user-facing reason why a language couldn't be highlighted (null if none or already shown). */
    @Synchronized
    fun problemOnce(key: String): String? {
        val why = failures[key] ?: return null
        return if (reported.add(key)) why else null
    }
}

// ====================================================================== the editor widget

private fun posAfter(line0: Int, col0: Int, s: String, upto: Int): Pair<Int, Int> {
    var l = line0
    var c = col0
    for (i in 0 until upto.coerceIn(0, s.length)) {
        if (s[i] == '\n') { l++; c = 0 } else c++
    }
    return l to c
}

private fun offsetOf(s: String, line: Int, col: Int): Int {
    var idx = 0
    var l = 0
    while (l < line) {
        val n = s.indexOf('\n', idx)
        if (n < 0) return s.length
        idx = n + 1
        l++
    }
    return (idx + col).coerceAtMost(s.length)
}

private fun CodeEditor.setProp(name: String, value: Any) {
    try {
        val props = javaClass.getMethod("getProps").invoke(this)
        props.javaClass.getField(name).set(props, value)
    } catch (e: Exception) {
        // Property not available in this editor version: keep its default.
    }
}

/** Calls an optional editor method by name; silently does nothing if this version doesn't have it. */
private fun CodeEditor.tryCall(name: String, vararg args: Any) {
    try {
        val types = Array<Class<*>>(args.size) { i ->
            when (val a = args[i]) {
                is Boolean -> java.lang.Boolean.TYPE
                is Float -> java.lang.Float.TYPE
                is Int -> java.lang.Integer.TYPE
                else -> a.javaClass
            }
        }
        javaClass.getMethod(name, *types).invoke(this, *args)
    } catch (e: Throwable) {
        // Not available in this editor version.
    }
}

/** Sets one of the editor's named colours; skipped if this editor version doesn't have that colour. */
private fun EditorColorScheme.setByName(name: String, color: androidx.compose.ui.graphics.Color) {
    try {
        val key = EditorColorScheme::class.java.getField(name).getInt(null)
        setColor(key, color.toArgb())
    } catch (e: Throwable) {
        // Unknown colour name in this version.
    }
}

class ToolKey(val label: String, val text: String, val close: String? = null, val tab: Boolean = false)

val TOOL_KEYS: List<ToolKey> = listOf(
    ToolKey("Tab", "\t", tab = true),
    ToolKey("{ }", "{", "}"), ToolKey("( )", "(", ")"), ToolKey("[ ]", "[", "]"), ToolKey("< >", "<", ">"),
    ToolKey("=", "="), ToolKey(";", ";"), ToolKey(":", ":"),
    ToolKey("\" \"", "\"", "\""), ToolKey("' '", "'", "'"), ToolKey("` `", "`", "`"),
    ToolKey("/", "/"), ToolKey("\\", "\\"), ToolKey("|", "|"), ToolKey("&", "&"), ToolKey("!", "!"),
    ToolKey("?", "?"), ToolKey("+", "+"), ToolKey("-", "-"), ToolKey("*", "*"), ToolKey("_", "_"),
    ToolKey("$", "$"), ToolKey("#", "#"), ToolKey("@", "@"), ToolKey("~", "~"),
)

/** Sora editor with Gitode's smart editing hooked into typing, Enter, Backspace and Tab. */
class GitodeEditor(context: Context) : CodeEditor(context) {
    /** Smart-editing configuration, or null while Smart actions are off. */
    var smartProvider: () -> SmartConfig? = { null }

    /** Indentation settings for the Tab key (always available). */
    var indentProvider: () -> SmartConfig = { SmartConfig(4, true, null) }

    private class Ctx(val line: Int, val col: Int, val before: String, val after: String)

    /** Undo / redo for the toolbar buttons. */
    fun undoAction() { tryCall("undo") }

    fun redoAction() { tryCall("redo") }

    /** Hides the keyboard and drops the editor's focus so it stays hidden until the editor is tapped again. */
    fun dismissKeyboard() {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(windowToken, 0)
        } catch (e: Throwable) {
            // Nothing to hide.
        }
        try {
            clearFocus()
        } catch (e: Throwable) {
            // Ignore.
        }
    }

    private fun ctx(): Ctx {
        val ln = cursor.leftLine
        val s = text.getLineString(ln)
        val col = cursor.leftColumn.coerceIn(0, s.length)
        return Ctx(ln, col, s.substring(0, col), s.substring(col))
    }

    // ---- editing helpers (all go through the editor's undoable text model)

    private fun apply(r: SmartResult.Edit, c: Ctx) {
        text.beginBatchEdit()
        try {
            if (r.deleteAfter > 0) text.delete(c.line, c.col, c.line, c.col + r.deleteAfter)
            var col = c.col
            if (r.deleteBefore > 0) {
                text.delete(c.line, col - r.deleteBefore, c.line, col)
                col -= r.deleteBefore
            }
            if (r.insert.isNotEmpty()) text.insert(c.line, col, r.insert)
            val (l, cc) = posAfter(c.line, col, r.insert, r.insert.length - r.cursorBack)
            setSelection(l, cc)
        } finally {
            text.endBatchEdit()
        }
    }

    private fun wrapSelection(open: String, close: String) {
        val l1 = cursor.leftLine
        val c1 = cursor.leftColumn
        val l2 = cursor.rightLine
        val c2 = cursor.rightColumn
        val full = text.toString()
        val selected = full.substring(offsetOf(full, l1, c1), offsetOf(full, l2, c2))
        text.beginBatchEdit()
        try {
            text.replace(l1, c1, l2, c2, open + selected + close)
        } finally {
            text.endBatchEdit()
        }
        val endCol = if (l1 == l2) c2 + open.length else c2
        setSelectionRegion(l1, c1 + open.length, l2, endCol)
    }

    private fun insertAtCursor(s: String, cursorBack: Int = 0) {
        val l1 = cursor.leftLine
        val c1 = cursor.leftColumn
        text.beginBatchEdit()
        try {
            if (cursor.isSelected) text.replace(l1, c1, cursor.rightLine, cursor.rightColumn, s)
            else text.insert(l1, c1, s)
        } finally {
            text.endBatchEdit()
        }
        val (l, c) = posAfter(l1, c1, s, s.length - cursorBack)
        setSelection(l, c)
    }

    private fun insertIndent() {
        val unit = indentProvider().unit
        if (cursor.isSelected && cursor.leftLine != cursor.rightLine) {
            val l1 = cursor.leftLine
            val c1 = cursor.leftColumn
            val l2 = cursor.rightLine
            val c2 = cursor.rightColumn
            text.beginBatchEdit()
            try {
                for (ln in l1..l2) text.insert(ln, 0, unit)
            } finally {
                text.endBatchEdit()
            }
            setSelectionRegion(l1, c1 + unit.length, l2, c2 + unit.length)
        } else {
            insertAtCursor(unit)
        }
    }

    // ---- smart reactions (return true when the input was handled)

    private fun handleTyped(ch: Char, hasSelection: Boolean): Boolean {
        val cfg = smartProvider() ?: return false
        val c = ctx()
        val r = SmartEdit.typed(ch, c.before, c.after, hasSelection, cfg.lang)
        return when {
            r is SmartResult.Wrap -> { wrapSelection(r.open, r.close); true }
            r === SmartResult.Skip -> { setSelection(c.line, c.col + 1); true }
            r is SmartResult.Edit -> { apply(r, c); true }
            else -> false
        }
    }

    private fun handleEnter(): Boolean {
        val cfg = smartProvider() ?: return false
        if (cursor.isSelected) return false
        val c = ctx()
        val r = SmartEdit.enter(c.before, c.after, cfg)
        if (r is SmartResult.Edit) { apply(r, c); return true }
        return false
    }

    private fun handleBackspace(): Boolean {
        val cfg = smartProvider() ?: return false
        if (cursor.isSelected) return false
        val c = ctx()
        val r = SmartEdit.backspace(c.before, c.after, cfg)
        if (r is SmartResult.Edit) { apply(r, c); return true }
        return false
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(base, true) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                if (smartProvider() != null && newCursorPosition == 1) {
                    if (text.length == 1 && text[0] == '\n') {
                        finishComposingText()
                        if (handleEnter()) return true
                    } else if (text.length == 1) {
                        finishComposingText()
                        if (handleTyped(text[0], cursor.isSelected)) return true
                    }
                }
                return super.commitText(text, newCursorPosition)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && smartProvider() != null) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_ENTER -> if (handleEnter()) return true
                        KeyEvent.KEYCODE_DEL -> if (handleBackspace()) return true
                    }
                }
                return super.sendKeyEvent(event)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength == 1 && afterLength == 0 && handleBackspace()) return true
                return super.deleteSurroundingText(beforeLength, afterLength)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_TAB && !event.isShiftPressed) {
            insertIndent()
            return true
        }
        if (smartProvider() != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_ENTER -> if (handleEnter()) return true
                KeyEvent.KEYCODE_DEL -> if (handleBackspace()) return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** A key from the smart-key toolbar. */
    fun pressKey(key: ToolKey) {
        when {
            key.tab -> insertIndent()
            key.close != null ->
                if (cursor.isSelected) wrapSelection(key.text, key.close)
                else insertAtCursor(key.text + key.close, cursorBack = key.close.length)
            else -> insertAtCursor(key.text)
        }
    }
}

// ====================================================================== Compose host

private class EditorHolder {
    var editor: GitodeEditor? = null
    var loaded: Buffer? = null
    var loading = false
    var fontSize = -1
    var fontFamily = ""
    var colors: GitodeColors? = null
    var syntaxOn: Boolean? = null
    var syntaxTheme = ""
    var languageBuffer: Buffer? = null
    var languageSyntaxOn: Boolean? = null
}

private fun applyChrome(scheme: EditorColorScheme, c: GitodeColors, overrideText: Boolean) {
    fun set(key: Int, color: androidx.compose.ui.graphics.Color) = scheme.setColor(key, color.toArgb())
    set(EditorColorScheme.WHOLE_BACKGROUND, c.editorBg)
    set(EditorColorScheme.LINE_NUMBER_BACKGROUND, c.gutterBg)
    set(EditorColorScheme.LINE_NUMBER, c.gutterText)
    set(EditorColorScheme.LINE_NUMBER_CURRENT, c.text)
    set(EditorColorScheme.LINE_DIVIDER, c.dim.copy(alpha = 0.55f))
    set(EditorColorScheme.CURRENT_LINE, c.currentLine)
    set(EditorColorScheme.SELECTION_INSERT, c.accent)
    set(EditorColorScheme.SELECTION_HANDLE, c.accent)
    set(EditorColorScheme.SELECTED_TEXT_BACKGROUND, c.selection)
    set(EditorColorScheme.MATCHED_TEXT_BACKGROUND, c.matchHighlight)
    if (overrideText) set(EditorColorScheme.TEXT_NORMAL, c.text)

    // The matching-bracket highlight follows the theme (it used to be drawn in black).
    scheme.setByName("HIGHLIGHTED_DELIMITERS_BACKGROUND", c.accent.copy(alpha = 0.35f))
    scheme.setByName("HIGHLIGHTED_DELIMITERS_FOREGROUND", c.text)
    scheme.setByName("HIGHLIGHTED_DELIMITERS_UNDERLINE", c.accent)
}

private fun createEditor(context: Context, vm: MainViewModel, holder: EditorHolder): GitodeEditor {
    TextMate.init(context)
    val ed = GitodeEditor(context)
    holder.editor = ed
    // Gitode's own smart editing replaces the editor's built-in auto-indent and auto-pairing.
    ed.setProp("symbolPairAutoCompletion", false)
    ed.setProp("autoIndent", false)
    try { ed.getComponent(EditorAutoCompletion::class.java).isEnabled = false } catch (e: Throwable) { /* keep default */ }

    // Line numbers: stay fixed while the code scrolls sideways, sit a little away from the screen edge,
    // and are separated from the text by a visible line.
    val dp = context.resources.displayMetrics.density
    ed.tryCall("setPinLineNumber", true)
    ed.tryCall("setLineNumberMarginLeft", 10f * dp)
    ed.tryCall("setDividerWidth", 1.5f * dp)
    ed.tryCall("setDividerMargin", 8f * dp, 8f * dp)

    ed.setInputType(
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
    )
    ed.smartProvider = { if (vm.settings.smartActions) vm.smartConfig(holder.loaded) else null }
    ed.indentProvider = { vm.smartConfig(holder.loaded) }

    ed.subscribeEvent(
        ContentChangeEvent::class.java,
        EventReceiver<ContentChangeEvent> { _, _ -> if (!holder.loading) vm.onTextChanged() },
    )
    ed.subscribeEvent(
        SelectionChangeEvent::class.java,
        EventReceiver<SelectionChangeEvent> { _, _ ->
            if (!holder.loading) {
                holder.loaded?.let {
                    it.cursorLine = ed.cursor.leftLine
                    it.cursorColumn = ed.cursor.leftColumn
                }
            }
        },
    )

    vm.bridge.getText = {
        if (holder.loaded === vm.active) ed.text.toString() else (vm.active?.text ?: "")
    }
    vm.bridge.replaceAll = { find, repl -> replaceAllIn(ed, find, repl) }
    return ed
}

private fun replaceAllIn(ed: GitodeEditor, find: String, repl: String): Int {
    if (find.isEmpty()) return 0
    val full = ed.text.toString()
    var count = 0
    var idx = full.indexOf(find, 0, ignoreCase = true)
    while (idx >= 0) {
        count++
        idx = full.indexOf(find, idx + find.length, ignoreCase = true)
    }
    if (count == 0) return 0
    val sb = StringBuilder()
    var from = 0
    idx = full.indexOf(find, 0, ignoreCase = true)
    while (idx >= 0) {
        sb.append(full, from, idx).append(repl)
        from = idx + find.length
        idx = full.indexOf(find, from, ignoreCase = true)
    }
    sb.append(full, from, full.length)
    val line = ed.cursor.leftLine
    val col = ed.cursor.leftColumn
    val t = ed.text
    val lastLine = t.lineCount - 1
    t.replace(0, 0, lastLine, t.getColumnCount(lastLine), sb.toString())
    val ln = line.coerceIn(0, ed.text.lineCount - 1)
    ed.setSelection(ln, col.coerceIn(0, ed.text.getColumnCount(ln)))
    return count
}

private fun sync(
    context: Context,
    vm: MainViewModel,
    holder: EditorHolder,
    ed: GitodeEditor,
    buffer: Buffer,
    colors: GitodeColors,
    fontSize: Int,
    fontFamily: String,
    syntaxOn: Boolean,
    syntaxTheme: String,
) {
    if (holder.fontSize != fontSize) {
        ed.setTextSize(fontSize.toFloat())
        holder.fontSize = fontSize
    }
    if (holder.fontFamily != fontFamily) {
        val tf = loadTypeface(context, fontFamily)
        ed.typefaceText = tf
        ed.typefaceLineNumber = tf
        holder.fontFamily = fontFamily
    }

    if (holder.colors !== colors || holder.syntaxOn != syntaxOn || holder.syntaxTheme != syntaxTheme) {
        val dark = SYNTAX_THEMES.firstOrNull { it.id == syntaxTheme }?.dark ?: true
        val textMateOk = TextMate.useTheme(syntaxTheme, dark)
        val scheme: EditorColorScheme = try {
            if (textMateOk) TextMateColorScheme.create(ThemeRegistry.getInstance()) else ed.colorScheme
        } catch (e: Throwable) {
            ed.colorScheme
        }
        applyChrome(scheme, colors, overrideText = !syntaxOn || !textMateOk)
        ed.colorScheme = scheme
        holder.colors = colors
        holder.syntaxOn = syntaxOn
        holder.syntaxTheme = syntaxTheme
    }

    if (holder.loaded !== buffer) {
        holder.loaded?.let {
            it.cursorLine = ed.cursor.leftLine
            it.cursorColumn = ed.cursor.leftColumn
        }
        holder.loading = true
        try {
            ed.setText(buffer.text)
        } finally {
            holder.loading = false
        }
        holder.loaded = buffer
        val lines = ed.text.lineCount
        val ln = buffer.cursorLine.coerceIn(0, lines - 1)
        ed.setSelection(ln, buffer.cursorColumn.coerceIn(0, ed.text.getColumnCount(ln)))
    }

    if (holder.languageBuffer !== buffer || holder.languageSyntaxOn != syntaxOn) {
        val key = Fs.languageOf(buffer.file.name)
        val created: Language? = if (syntaxOn) TextMate.languageFor(buffer.file.name) else null
        if (syntaxOn && key != null && created == null) {
            // Tell the user (once per language) why this file isn't coloured, instead of failing silently.
            TextMate.problemOnce(key)?.let { why ->
                val label = Fs.typeLabel(buffer.file.name, false)
                ed.post { vm.toast("Syntax highlighting for $label isn't available: $why") }
            }
        }
        ed.setEditorLanguage(created ?: EmptyLanguage())
        holder.languageBuffer = buffer
        holder.languageSyntaxOn = syntaxOn
    }
}

@Composable
fun CodeEditorView(
    vm: MainViewModel,
    buffer: Buffer,
    onEditor: (GitodeEditor?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val s = vm.settings
    val colors = G.c
    val fontSize = s.fontSize
    val fontFamily = s.fontFamily
    val syntaxOn = s.syntaxHighlighting
    val syntaxTheme = s.syntaxTheme
    val holder = remember { EditorHolder() }

    DisposableEffect(Unit) {
        onDispose {
            vm.bridge.getText = null
            vm.bridge.replaceAll = null
            onEditor(null)
            try { holder.editor?.release() } catch (e: Throwable) { /* already released */ }
            holder.editor = null
            holder.loaded = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx -> createEditor(ctx, vm, holder) },
        update = { ed ->
            sync(context, vm, holder, ed, buffer, colors, fontSize, fontFamily, syntaxOn, syntaxTheme)
        },
    )
    SideEffect { onEditor(holder.editor) }

    // Jump to a line (go to line, search results, "Find in project"). When a file was just opened the
    // editor needs a moment to load it, so wait for that first.
    val jump = vm.pendingJump
    LaunchedEffect(jump) {
        if (jump != null) {
            var tries = 0
            while (tries < 40 && !(holder.editor != null && holder.loaded === buffer)) {
                delay(25)
                tries++
            }
            val ed = holder.editor
            if (ed != null && holder.loaded === buffer) {
                val ln = jump.line.coerceIn(0, ed.text.lineCount - 1)
                val len = ed.text.getColumnCount(ln)
                val a = jump.start.coerceIn(0, len)
                val b = jump.end.coerceIn(a, len)
                if (b > a) ed.setSelectionRegion(ln, a, ln, b) else ed.setSelection(ln, 0)
            }
            vm.pendingJump = null
        }
    }
}

// ====================================================================== smart-key toolbar

@Composable
private fun IconKey(ic: Ic, onClick: () -> Unit) {
    val c = G.c
    Box(
        Modifier.padding(horizontal = 2.dp).height(40.dp).widthIn(min = 42.dp)
            .clip(RoundedCornerShape(10.dp)).background(c.card)
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Ico(ic, c.text, 20.dp)
    }
}

/** One horizontally scrolling row of keys that phone keyboards lack. Undo and Redo come first, then Tab. */
@Composable
fun SmartKeysBar(editor: GitodeEditor?, modifier: Modifier = Modifier) {
    val c = G.c
    // The empty click stops taps between the keys from counting as "blank" taps (which hide the keyboard).
    Box(modifier.fillMaxWidth().background(c.surface).noRippleClick { }) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
        Row(
            // extra space on the left keeps the first key clear of the drawer's edge-swipe strip
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = 30.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconKey(Ic.Undo) { editor?.undoAction() }
            IconKey(Ic.Redo) { editor?.redoAction() }
            for (k in TOOL_KEYS) {
                Box(
                    Modifier.padding(horizontal = 2.dp).height(40.dp).widthIn(min = 42.dp)
                        .clip(RoundedCornerShape(10.dp)).background(c.card)
                        .clickable { editor?.pressKey(k) }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Txt(
                        k.label, size = 15.sp, weight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace, maxLines = 1,
                    )
                }
            }
        }
    }
}