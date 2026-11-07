package com.gitode.core

import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

// ====================================================================== smart editing

class SmartConfig(val tabWidth: Int, val useSpaces: Boolean, val lang: String?) {
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
    /** Line-comment markers; pairing is switched off after one. */
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
)

object Langs {
    private val C_LIKE = LangRules(listOf("//"), blockComments = true)
    private val C_LIKE_ANGLE = LangRules(listOf("//"), blockComments = true, angle = true)
    private val MARKUP = LangRules(emptyList(), xmlComments = true, angle = true)
    private val DEFAULT = LangRules(listOf("//", "#"), blockComments = true)

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
        "tsx" to C_LIKE_ANGLE,
        "javascript" to C_LIKE,
        "cpp" to C_LIKE_ANGLE,
        "c" to C_LIKE,
        "swift" to C_LIKE_ANGLE,
        "rust" to LangRules(listOf("//"), blockComments = true, angle = true, rustQuotes = true),
        "php" to LangRules(listOf("//", "#"), blockComments = true),
        "xml" to MARKUP,
        "qmake" to LangRules(listOf("#"), continuation = true),
        "gitignore" to LangRules(listOf("#"), pairs = false),
        // more languages
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
        // extras
        "groovy" to C_LIKE,
    )

    fun of(lang: String?): LangRules = lang?.let { table[it] } ?: DEFAULT
}

/** Pure text logic for smart actions. [before]/[after] are the parts of the current line around the cursor. */
object SmartEdit {
    private val PAIRS = mapOf(
        '(' to ')', '[' to ']', '{' to '}', '<' to '>', '\'' to '\'', '"' to '"', '`' to '`',
    )
    private val CLOSERS = setOf(')', ']', '}', '>')
    private val QUOTES = setOf('\'', '"', '`')
    private const val PAIR_BEFORE = ")]};,.:>"

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

    fun typed(ch: Char, before: String, after: String, hasSelection: Boolean, lang: String? = null): SmartResult {
        val rules = Langs.of(lang)
        if (!rules.pairs) return SmartResult.None
        if ((ch == '<' || ch == '>') && !rules.angle) return SmartResult.None

        val close = PAIRS[ch]
        if (hasSelection) {
            return if (close != null) SmartResult.Wrap(ch.toString(), close.toString()) else SmartResult.None
        }
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

    fun enter(before: String, after: String, cfg: SmartConfig): SmartResult {
        val rules = Langs.of(cfg.lang)
        val indent = before.takeWhile { it == ' ' || it == '\t' }
        val trimmed = before.trimEnd()
        val last = trimmed.lastOrNull()
        val nextCh = after.firstOrNull()

        val tagBetween = last == '>' && after.startsWith("</")
        val opens = last != null && (
            last in "{[(" ||
                (last == ':' && rules.colonOpens) ||
                tagBetween
            ) || (rules.opener?.matches(before.trim()) == true)
        val closes = last != null && nextCh != null && (
            (last == '{' && nextCh == '}') ||
                (last == '[' && nextCh == ']') ||
                (last == '(' && nextCh == ')') ||
                tagBetween
            )
        if (opens && closes) {
            return SmartResult.Edit("\n" + indent + cfg.unit + "\n" + indent, cursorBack = indent.length + 1)
        }
        if (opens) return SmartResult.Edit("\n" + indent + cfg.unit)

        if (rules.continuation && trimmed.endsWith("\\")) {
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
        if (before.all { it == ' ' }) {
            val n = before.length
            val width = cfg.tabWidth.coerceAtLeast(1)
            val remove = if (n % width == 0) width else n % width
            return if (remove > 1) SmartResult.Edit("", deleteBefore = remove) else SmartResult.None
        }
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