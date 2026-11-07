package com.gitode.core

import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

// ====================================================================== smart editing

class SmartConfig(val tabWidth: Int, val useSpaces: Boolean, val lang: String?) {
    val unit: String get() = if (useSpaces) " ".repeat(tabWidth.coerceAtLeast(1)) else "\t"
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

/** Pure text logic for smart actions. [before]/[after] are the parts of the current line around the cursor. */
object SmartEdit {
    private val PAIRS = mapOf(
        '(' to ')', '[' to ']', '{' to '}', '<' to '>', '\'' to '\'', '"' to '"', '`' to '`',
    )
    private val CLOSERS = setOf(')', ']', '}', '>')
    private val QUOTES = setOf('\'', '"', '`')
    private const val PAIR_BEFORE = ")]};,.:>"
    private val COMMENT = Regex("""(^|\s)//|^\s*/\*|^\s*\*\s|(^|\s)#\s""")
    private val COLON_LANGS = setOf("python", "yaml", "gdscript")

    private fun inComment(before: String) = COMMENT.containsMatchIn(before)

    private fun canPairBefore(next: Char?) = next == null || next.isWhitespace() || PAIR_BEFORE.indexOf(next) >= 0

    fun typed(ch: Char, before: String, after: String, hasSelection: Boolean): SmartResult {
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

        if (close == null || inComment(before)) return SmartResult.None

        return when (ch) {
            in QUOTES -> {
                when {
                    ch == '\'' && prev != null && (prev.isLetterOrDigit() || prev == '_') -> SmartResult.None
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
        val indent = before.takeWhile { it == ' ' || it == '\t' }
        val last = before.trimEnd().lastOrNull()
        val nextCh = after.firstOrNull()
        val lang = cfg.lang
        val opens = last != null && (
            last in "{[(" ||
                (last == ':' && lang != null && lang in COLON_LANGS) ||
                (last == '>' && after.startsWith("</"))
            )
        val closes = last != null && nextCh != null && (
            (last == '{' && nextCh == '}') ||
                (last == '[' && nextCh == ']') ||
                (last == '(' && nextCh == ')') ||
                (last == '>' && after.startsWith("</"))
            )
        return when {
            opens && closes -> SmartResult.Edit("\n" + indent + cfg.unit + "\n" + indent, cursorBack = indent.length + 1)
            opens -> SmartResult.Edit("\n" + indent + cfg.unit)
            indent.isNotEmpty() -> SmartResult.Edit("\n" + indent)
            else -> SmartResult.None
        }
    }

    fun backspace(before: String, after: String, cfg: SmartConfig): SmartResult {
        if (before.isEmpty()) return SmartResult.None
        if (before.all { it == ' ' }) {
            val n = before.length
            val width = cfg.tabWidth.coerceAtLeast(1)
            val remove = if (n % width == 0) width else n % width
            return if (remove > 1) SmartResult.Edit("", deleteBefore = remove) else SmartResult.None
        }
        val prev = before.last()
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