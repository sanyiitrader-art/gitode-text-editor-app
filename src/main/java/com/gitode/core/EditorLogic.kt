package com.gitode.core

import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

// ====================================================================== smart editing

/**
 * What the editor should do for the current file. [indent] switches the indentation features
 * (Enter, closing-delimiter alignment, Backspace by a level); [pairs] switches bracket and quote pairing.
 */
class SmartConfig(
    val tabWidth: Int,
    val useSpaces: Boolean,
    val lang: String?,
    val indent: Boolean = true,
    val pairs: Boolean = true,
) {
    /** One level of indentation. Makefile recipes need a real tab, and Go code is tab-indented (gofmt). */
    val unit: String
        get() = when {
            lang == "makefile" || lang == "go" -> "\t"
            useSpaces -> " ".repeat(tabWidth.coerceAtLeast(1))
            else -> "\t"
        }
}

/** What the editor should do instead of its default behaviour. */
sealed class SmartResult {
    /** Let the editor handle the input normally. */
    object None : SmartResult()

    /** Move the cursor over an existing closing character instead of typing a duplicate. */
    object Skip : SmartResult()

    /** Wrap the current selection in [open] / [close]. */
    class Wrap(val open: String, val close: String) : SmartResult()

    /** Delete [deleteBefore]/[deleteAfter] characters around the cursor, insert [insert], then move back [cursorBack]. */
    class Edit(
        val insert: String,
        val deleteBefore: Int = 0,
        val deleteAfter: Int = 0,
        val cursorBack: Int = 0,
    ) : SmartResult()
}

/** How one language wants its code edited. */
class LangRules(
    /** Line-comment markers; pairing and indentation ignore what follows one. */
    val lineComments: List<String>,
    /** C-style block comments, for example in the middle of a Javadoc. */
    val blockComments: Boolean = false,
    /** XML-style comments (the marker before the dash dash). */
    val xmlComments: Boolean = false,
    /** Bracket and quote pairing at all. */
    val pairs: Boolean = true,
    /** "<" pairs with ">" (generics, tags). */
    val angle: Boolean = false,
    /** A trailing ":" starts an indented block (Python, YAML, GDScript). */
    val colonOpens: Boolean = false,
    /** A trailing backslash continues the line, so the next line is indented. */
    val continuation: Boolean = false,
    /** "- item" lines continue as "- " on Enter. */
    val listDash: Boolean = false,
    /** Lines (trimmed) matching this open an indented block, for example Ruby's def/if/do. */
    val opener: Regex? = null,
    /** ' is also used for lifetimes, so it is not paired after < & or a comma. */
    val rustQuotes: Boolean = false,
    /** ' is also the transpose operator (MATLAB, Julia), so it is not paired after ) ] } . or '. */
    val transposeQuote: Boolean = false,
    /** Markdown lists: Enter continues "- ", "* ", "1. " and "- [ ] " items; Enter on an empty item leaves the list. */
    val markdownLists: Boolean = false,
    /** False where ' is not a string quote (Haskell identifiers, Lisp quote), so it is never auto-paired. */
    val singleQuotePairs: Boolean = true,
    /** Markup: Enter after an opening tag indents, and typing "</" at the start of a line goes back one level. */
    val markup: Boolean = false,
    /** Indent after { [ ( and align a typed closing delimiter with its opener. Off for prose-like formats. */
    val bracketIndent: Boolean = true,
)

object Langs {
    private val C_LIKE = LangRules(listOf("//"), blockComments = true)
    private val C_LIKE_ANGLE = LangRules(listOf("//"), blockComments = true, angle = true)
    private val MARKUP = LangRules(emptyList(), xmlComments = true, angle = true, markup = true)

    /** Unknown extensions: pairing like code, but indentation only ever preserves what is there. */
    private val UNKNOWN = LangRules(listOf("//", "#"), blockComments = true, bracketIndent = false)

    private val RUBY_OPENER = Regex(
        """^(?!.*\bend\s*$)(?:(?:def|class|module|if|unless|while|until|for|case|begin|else|elsif|ensure|rescue|when)\b.*|.*\bdo(?:\s*\|[^|]*\|)?)$""",
    )

    private val LUA_OPENER = Regex(
        """^(?!.*\bend\s*[,;)]*$)(?:.*\b(?:then|do)|repeat|else|elseif\b.*|(?:local\s+)?function\b.*|.*\bfunction\s*\(.*)$""",
    )

    private val JULIA_OPENER = Regex(
        """^(?!.*\bend\s*$)(?:(?:function|macro|if|elseif|else|for|while|begin|let|struct|mutable\s+struct|abstract\s+type|module|baremodule|try|catch|finally|quote)\b.*|.*\bdo(?:\s+[\w,\s()]*)?)$""",
    )

    private val MATLAB_OPENER = Regex(
        """^(?!.*\bend\s*;?\s*$)(?:function|if|elseif|else|for|parfor|while|switch|case|otherwise|try|catch|classdef|methods|properties|events|enumeration)\b.*$""",
    )

    private val SHELL_OPENER = Regex(
        """^(?:.*[;\s](?:then|do)|then|do|else|elif\b.*[;\s]then)$""",
    )

    /** "target: dependencies" lines start a recipe, which is indented with a tab. */
    private val MAKE_OPENER = Regex("""^[^\s#][^#=]*:(?!=).*$""")

    private val HASKELL_OPENER = Regex("""^.*(?:\bwhere|\bdo|\bof|\blet|\bthen|\belse|=|->)$""")
    private val ELIXIR_OPENER = Regex("""^.*(?:\bdo|->)$""")
    private val ERLANG_OPENER = Regex("""^.*(?:->|\bof|\bbegin|\bafter|\breceive|\btry)$""")
    private val NIX_OPENER = Regex("""^.*\blet$""")

    private val VUE_LIKE = LangRules(
        listOf("//"), blockComments = true, xmlComments = true, angle = true, markup = true,
    )

    private val table: Map<String, LangRules> = mapOf(
        // original languages
        "python" to LangRules(listOf("#"), colonOpens = true),
        "gdscript" to LangRules(listOf("#"), colonOpens = true),
        "yaml" to LangRules(listOf("#"), colonOpens = true, listDash = true),
        "toml" to LangRules(listOf("#")),
        "ruby" to LangRules(listOf("#"), opener = RUBY_OPENER),
        "kotlin" to C_LIKE_ANGLE,
        "java" to C_LIKE_ANGLE,
        "typescript" to C_LIKE_ANGLE,
        "tsx" to LangRules(listOf("//"), blockComments = true, angle = true, markup = true),
        "javascript" to LangRules(listOf("//"), blockComments = true, markup = true),
        "cpp" to C_LIKE_ANGLE,
        "c" to C_LIKE,
        "swift" to C_LIKE_ANGLE,
        "rust" to LangRules(listOf("//"), blockComments = true, angle = true, rustQuotes = true),
        "php" to LangRules(listOf("//", "#"), blockComments = true, markup = true),
        "xml" to MARKUP,
        "qmake" to LangRules(listOf("#"), continuation = true),
        "gitignore" to LangRules(listOf("#"), pairs = false, bracketIndent = false),
        "csharp" to C_LIKE_ANGLE,
        "go" to C_LIKE,
        "dart" to C_LIKE_ANGLE,
        "lua" to LangRules(listOf("--"), opener = LUA_OPENER),
        "sql" to LangRules(listOf("--"), blockComments = true),
        "html" to MARKUP,
        "css" to LangRules(emptyList(), blockComments = true),
        "json" to LangRules(emptyList()),
        "jsonc" to C_LIKE,
        "shellscript" to LangRules(listOf("#"), continuation = true, opener = SHELL_OPENER),
        "powershell" to LangRules(listOf("#")),
        "dockerfile" to LangRules(listOf("#"), continuation = true),
        "makefile" to LangRules(listOf("#"), continuation = true, opener = MAKE_OPENER),
        "r" to LangRules(listOf("#")),
        "julia" to LangRules(listOf("#"), opener = JULIA_OPENER, transposeQuote = true),
        "matlab" to LangRules(listOf("%"), opener = MATLAB_OPENER, transposeQuote = true),
        "scala" to C_LIKE,
        "groovy" to C_LIKE,
        // newest additions
        "markdown" to LangRules(emptyList(), xmlComments = true, markdownLists = true, bracketIndent = false),
        // plain text: nothing is paired and nothing is added, indentation is only preserved
        "text" to LangRules(emptyList(), pairs = false, bracketIndent = false),
        "graphql" to LangRules(listOf("#")),
        "haskell" to LangRules(listOf("--"), opener = HASKELL_OPENER, singleQuotePairs = false),
        "elixir" to LangRules(listOf("#"), opener = ELIXIR_OPENER),
        "erlang" to LangRules(listOf("%"), opener = ERLANG_OPENER),
        "perl" to LangRules(listOf("#")),
        "asm" to LangRules(listOf(";", "#", "//"), blockComments = true),
        "zig" to C_LIKE,
        "nix" to LangRules(listOf("#"), blockComments = true, opener = NIX_OPENER),
        "terraform" to LangRules(listOf("#", "//"), blockComments = true),
        "vue" to VUE_LIKE,
        "svelte" to VUE_LIKE,
        "clojure" to LangRules(listOf(";"), singleQuotePairs = false),
    )

    fun of(lang: String?): LangRules = lang?.let { table[it] } ?: UNKNOWN
}

// ---------------------------------------------------------------- reading one line of code

private fun startsLineComment(line: String, i: Int, rules: LangRules): Boolean {
    for (m in rules.lineComments) {
        if (!line.startsWith(m, i)) continue
        if (m == "#" && i + 1 < line.length && line[i + 1] == '{') continue // interpolation, not a comment
        if (m == "#" && i > 0 && line[i - 1] == '$') continue // shell $#
        if (m == "//" && i > 0 && line[i - 1] == ':') continue // a URL outside quotes
        return true
    }
    return false
}

private fun isStringApostrophe(line: String, i: Int, rules: LangRules): Boolean {
    val prev = if (i > 0) line[i - 1] else ' '
    if (prev.isLetterOrDigit() || prev == '_') return false
    if (rules.transposeQuote && (prev == ')' || prev == ']' || prev == '}' || prev == '.' || prev == '\'')) return false
    if (rules.rustQuotes && (prev == '<' || prev == '&' || prev == ',')) return false
    return line.indexOf('\'', i + 1) > i
}

/**
 * The line with string contents blanked out and any trailing comment removed, so brackets and colons
 * inside strings or comments never count. The result has the same column positions as the line.
 */
private fun codeOf(line: String, rules: LangRules): String {
    val t = line.trimStart()
    if (rules.blockComments && (t.startsWith("/*") || t.startsWith("*/") || t.startsWith("* ") || t == "*")) return ""
    val n = line.length
    val sb = StringBuilder(n)
    var quote = '\u0000'
    var i = 0
    while (i < n) {
        val ch = line[i]
        if (quote != '\u0000') {
            if (ch == '\\' && i + 1 < n) {
                sb.append("  ")
                i += 2
                continue
            }
            if (ch == quote) quote = '\u0000'
            sb.append(' ')
            i++
            continue
        }
        if (startsLineComment(line, i, rules)) break
        val opensString = when (ch) {
            '"', '`' -> true
            '\'' -> rules.singleQuotePairs && isStringApostrophe(line, i, rules)
            else -> false
        }
        if (opensString) {
            quote = ch
            sb.append(' ')
            i++
            continue
        }
        sb.append(ch)
        i++
    }
    return sb.toString()
}

/** Pure text logic for smart actions. [before]/[after] are the parts of the current line around the cursor. */
object SmartEdit {
    private val PAIRS = mapOf(
        '(' to ')', '[' to ']', '{' to '}', '<' to '>', '\'' to '\'', '"' to '"', '`' to '`',
    )
    private val CLOSERS = setOf(')', ']', '}', '>')
    private val QUOTES = setOf('\'', '"', '`')
    private const val PAIR_BEFORE = ")]};,.:>"

    /** A Markdown list line: indent, marker ("-", "*", "+", "1." or "1)"), optional task box, text. */
    private val MD_LIST = Regex("""^(\s*)([-*+]|\d{1,9}[.)])[ \t]+(\[[ xX]\][ \t]+)?(.*)$""")

    private val VOID_TAGS = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr",
    )
    private val OPEN_TAG = Regex("""^<([A-Za-z][\w:.-]*)(?:\s[^<>]*)?>$""")

    private fun inComment(before: String, r: LangRules): Boolean {
        for (marker in r.lineComments) {
            var i = before.indexOf(marker)
            while (i >= 0) {
                val startsOk = i == 0 || before[i - 1].isWhitespace()
                val interpolation = marker == "#" && before.getOrNull(i + 1) == '{'
                if (startsOk && !interpolation) return true
                i = before.indexOf(marker, i + 1)
            }
        }
        if (r.blockComments) {
            val t = before.trimStart()
            if (t.startsWith("/*") || t.startsWith("* ") || t == "*") return true
        }
        if (r.xmlComments && before.lastIndexOf("<!--") > before.lastIndexOf("-->")) return true
        return false
    }

    private fun canPairBefore(next: Char?) = next == null || next.isWhitespace() || PAIR_BEFORE.indexOf(next) >= 0

    /** True when the line ends with an opening (non-void, non-self-closing) tag, so the next line goes one level in. */
    private fun markupOpen(before: String): Boolean {
        val t = before.trim()
        if (!t.startsWith("<")) return false
        val tag = t.substring(t.lastIndexOf('<'))
        if (tag == "<>") return true // JSX fragment
        if (tag.endsWith("/>")) return false
        val m = OPEN_TAG.matchEntire(tag) ?: return false
        return m.groupValues[1].lowercase() !in VOID_TAGS
    }

    fun typed(ch: Char, before: String, after: String, hasSelection: Boolean, cfg: SmartConfig): SmartResult {
        val rules = Langs.of(cfg.lang)

        // Markup: typing "</" at the start of a line steps back one level (the tag is never closed for you).
        if (cfg.indent && rules.markup && ch == '/' && !hasSelection && before.endsWith("<")) {
            val ws = before.dropLast(1)
            val u = cfg.unit
            if (ws.isNotEmpty() && ws.all { it == ' ' || it == '\t' } && ws.endsWith(u)) {
                return SmartResult.Edit("</", deleteBefore = u.length + 1)
            }
        }

        if (!cfg.pairs || !rules.pairs) return SmartResult.None
        if ((ch == '<' || ch == '>') && !rules.angle) return SmartResult.None

        val close = PAIRS[ch]
        if (hasSelection) {
            return if (close != null) SmartResult.Wrap(ch.toString(), close.toString()) else SmartResult.None
        }
        // Where ' is not a string quote it is never paired.
        if (ch == '\'' && !rules.singleQuotePairs) return SmartResult.None

        val prev = before.lastOrNull()
        val next = after.firstOrNull()

        // Typing a closer right before the matching closer steps over it.
        if (next == ch && (ch in CLOSERS || ch in QUOTES)) {
            val skip = when {
                ch in QUOTES -> before.count { it == ch } % 2 == 1
                ch == '>' -> before.count { it == '<' } > before.count { it == '>' }
                else -> true
            }
            if (skip) return SmartResult.Skip
        }

        if (close == null || inComment(before, rules)) return SmartResult.None

        return when (ch) {
            in QUOTES -> {
                when {
                    ch == '\'' && prev != null && (prev.isLetterOrDigit() || prev == '_') -> SmartResult.None
                    ch == '\'' && rules.rustQuotes && prev != null && (prev == '<' || prev == '&' || prev == ',') -> SmartResult.None
                    ch == '\'' && rules.transposeQuote && prev != null &&
                        (prev == ')' || prev == ']' || prev == '}' || prev == '.' || prev == '\'') -> SmartResult.None
                    before.count { it == ch } % 2 == 1 -> SmartResult.None
                    !canPairBefore(next) -> SmartResult.None
                    else -> SmartResult.Edit(ch.toString() + ch, cursorBack = 1)
                }
            }
            '<' -> {
                val genericLike = prev != null && (prev.isLetterOrDigit() || prev == '_')
                val tagLike = before.isBlank() && after.isBlank()
                if ((genericLike || tagLike) && canPairBefore(next)) {
                    SmartResult.Edit("<>", cursorBack = 1)
                } else {
                    SmartResult.None
                }
            }
            else -> if (canPairBefore(next)) SmartResult.Edit(ch.toString() + close, cursorBack = 1) else SmartResult.None
        }
    }

    /**
     * The indentation of the line holding the opening delimiter that matches a closing [closer] typed on
     * [fromLine], or null if none is found nearby. Brackets inside strings and comments are ignored.
     */
    fun openerIndent(lineAt: (Int) -> String, fromLine: Int, closer: Char, cfg: SmartConfig, maxLines: Int = 4000): String? {
        val opener = when (closer) {
            ')' -> '('
            ']' -> '['
            '}' -> '{'
            else -> return null
        }
        val rules = Langs.of(cfg.lang)
        var depth = 1
        var ln = fromLine - 1
        var scanned = 0
        while (ln >= 0 && scanned < maxLines) {
            val raw = lineAt(ln)
            val code = codeOf(raw, rules)
            var i = code.length - 1
            while (i >= 0) {
                val c = code[i]
                if (c == closer) {
                    depth++
                } else if (c == opener) {
                    depth--
                    if (depth == 0) return raw.takeWhile { it == ' ' || it == '\t' }
                }
                i--
            }
            ln--
            scanned++
        }
        return null
    }

    fun enter(before: String, after: String, cfg: SmartConfig): SmartResult {
        if (!cfg.indent) return SmartResult.None
        val rules = Langs.of(cfg.lang)

        // Markdown: continue the list item you are in (an empty item ends the list).
        if (rules.markdownLists) {
            val m = MD_LIST.matchEntire(before)
            if (m != null) {
                val listIndent = m.groupValues[1]
                val marker = m.groupValues[2]
                val task = m.groupValues[3]
                val content = m.groupValues[4]
                if (content.isBlank()) return SmartResult.Edit("", deleteBefore = before.length)
                val next = if (marker.first().isDigit()) {
                    val n = marker.dropLast(1).toIntOrNull() ?: 1
                    "${n + 1}${marker.last()}"
                } else {
                    marker
                }
                val box = if (task.isNotEmpty()) "[ ] " else ""
                return SmartResult.Edit("\n$listIndent$next $box")
            }
        }

        val indent = before.takeWhile { it == ' ' || it == '\t' }
        // brackets, colons and keywords only count in real code, not inside strings or comments
        val code = codeOf(before, rules).trimEnd()
        val last = code.lastOrNull()
        val rawTrim = before.trimEnd()
        val rawLast = rawTrim.lastOrNull()
        val nextCh = after.firstOrNull()

        val tagBetween = rules.markup && rawLast == '>' && after.startsWith("</")
        val bracketOpens = rules.bracketIndent && last != null && last in "{[("
        val opens = bracketOpens ||
            (last == ':' && rules.colonOpens) ||
            tagBetween ||
            (rules.markup && markupOpen(before)) ||
            (rules.opener?.matches(code.trim()) == true)
        val bracketCloses = rules.bracketIndent && rawLast != null && nextCh != null && (
            (rawLast == '{' && nextCh == '}') ||
                (rawLast == '[' && nextCh == ']') ||
                (rawLast == '(' && nextCh == ')')
            )
        val closes = bracketCloses || tagBetween

        if (opens && closes) {
            return SmartResult.Edit("\n" + indent + cfg.unit + "\n" + indent, cursorBack = indent.length + 1)
        }
        if (opens) return SmartResult.Edit("\n" + indent + cfg.unit)

        if (rules.continuation && rawTrim.endsWith("\\")) {
            // The first continued line is indented; later ones keep that indentation.
            return SmartResult.Edit("\n" + indent + if (indent.isEmpty()) cfg.unit else "")
        }
        if (rules.listDash) {
            val t = before.trimStart()
            if (t.startsWith("- ") && t.trim().length > 1) return SmartResult.Edit("\n$indent- ")
        }
        return if (indent.isNotEmpty()) SmartResult.Edit("\n" + indent) else SmartResult.None
    }

    fun backspace(before: String, after: String, cfg: SmartConfig): SmartResult {
        if (before.isEmpty()) return SmartResult.None
        // Inside the leading spaces of a line, Backspace removes one whole indentation level.
        if (cfg.indent && before.all { it == ' ' }) {
            val n = before.length
            val width = cfg.tabWidth.coerceAtLeast(1)
            val remove = if (n % width == 0) width else n % width
            return if (remove > 1) SmartResult.Edit("", deleteBefore = remove) else SmartResult.None
        }
        if (!cfg.pairs) return SmartResult.None
        val rules = Langs.of(cfg.lang)
        if (!rules.pairs) return SmartResult.None
        val prev = before.last()
        if (prev == '<' && !rules.angle) return SmartResult.None
        val close = PAIRS[prev]
        if (close != null && after.firstOrNull() == close) {
            return SmartResult.Edit("", deleteBefore = 1, deleteAfter = 1)
        }
        return SmartResult.None
    }
}

// ====================================================================== indentation detection

class IndentStyle(val useSpaces: Boolean, val width: Int)

/** Guesses a file's indentation convention from its first lines, or null if unclear. */
fun detectIndent(text: String): IndentStyle? {
    var tabs = 0
    var spaceLines = 0
    var previous = 0
    var seen = 0
    val diffs = IntArray(9)
    for (line in text.lineSequence()) {
        if (++seen > 1000) break
        if (line.isBlank()) continue
        if (line[0] == '\t') {
            tabs++
            continue
        }
        val n = line.takeWhile { it == ' ' }.length
        if (n > 0) {
            spaceLines++
            val d = abs(n - previous)
            if (d in 1..8) diffs[d]++
        }
        previous = n
    }
    if (tabs >= 3 && tabs > spaceLines) return IndentStyle(false, 4)
    if (spaceLines < 3) return null
    var best = 0
    var bestCount = 0
    for (w in intArrayOf(2, 4, 3, 8)) {
        if (diffs[w] > bestCount) {
            best = w
            bestCount = diffs[w]
        }
    }
    return if (best == 0) null else IndentStyle(true, best)
}

// ====================================================================== duplicate detection

/**
 * Remembers a hash of every text file in the open project so identical content can be reported.
 * Empty and very short files are ignored. Reports never block typing, pasting or saving.
 */
class DuplicateIndex {
    private val lock = Any()
    private val hashOf = HashMap<String, String>()
    private val byHash = HashMap<String, MutableSet<String>>()
    private var scanId = 0

    private fun hash(text: String): String? {
        val t = text.replace("\r\n", "\n")
        if (t.trim().length < MIN_CHARS) return null
        val digest = MessageDigest.getInstance("SHA-1").digest(t.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun clear() {
        synchronized(lock) {
            scanId++
            hashOf.clear()
            byHash.clear()
        }
    }

    /** Scans [root] (call from a background thread). A newer rebuild or clear aborts this one. */
    fun rebuild(root: File) {
        val id = synchronized(lock) {
            scanId++
            hashOf.clear()
            byHash.clear()
            scanId
        }
        var count = 0
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty() && count < MAX_FILES) {
            if (synchronized(lock) { id != scanId }) return
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (f in children) {
                if (f.isDirectory) {
                    val skip = f.name == ".git" || f.name == "node_modules" ||
                        java.nio.file.Files.isSymbolicLink(f.toPath())
                    if (!skip) stack.add(f)
                } else if (f.length() in 1L..MAX_BYTES && Fs.kindOf(f) == Fs.Kind.TEXT) {
                    val text = try { f.readText() } catch (e: Exception) { continue }
                    put(id, f.path, text)
                    count++
                }
            }
        }
    }

    private fun put(id: Int, path: String, text: String) {
        val h = hash(text) ?: return
        synchronized(lock) {
            if (id != scanId) return
            hashOf[path] = h
            byHash.getOrPut(h) { HashSet() }.add(path)
        }
    }

    /** Records the latest known content of [path]; null removes it. */
    fun update(path: String, text: String?) {
        synchronized(lock) {
            hashOf.remove(path)?.let { old -> byHash[old]?.remove(path) }
            if (text != null) {
                val h = hash(text) ?: return
                hashOf[path] = h
                byHash.getOrPut(h) { HashSet() }.add(path)
            }
        }
    }

    /** Other files whose content is identical to [path]'s. */
    fun duplicatesOf(path: String): List<String> = synchronized(lock) {
        val h = hashOf[path]
        if (h == null) emptyList() else byHash[h].orEmpty().filter { it != path }
    }

    companion object {
        private const val MIN_CHARS = 5
        private const val MAX_BYTES = 1024L * 1024
        private const val MAX_FILES = 20000
    }
}