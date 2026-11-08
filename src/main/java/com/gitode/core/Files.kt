package com.gitode.core

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.StandardCopyOption
import java.nio.file.Files as NioFiles

/** File-system helpers: paths, name validation, text IO, copy/delete, file-type detection. */
object Fs {
    const val MAX_TEXT_BYTES = 10L * 1024 * 1024

    enum class Kind { TEXT, IMAGE, OTHER }

    /** Text as the editor sees it: always "\n" line endings. [crlf] remembers the original style. */
    class TextData(val text: String, val crlf: Boolean)

    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif")

    private val BINARY_EXT = setOf(
        "pdf", "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "apk", "aab", "jar", "class", "dex",
        "so", "o", "a", "exe", "dll", "bin", "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus",
        "mp4", "mkv", "avi", "mov", "webm", "3gp", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "odt", "ods", "odp", "ttf", "otf", "woff", "woff2", "psd", "db", "sqlite", "ico",
    )

    private val ILLEGAL_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    /**
     * File extension -> language key (also the key of the TextMate grammar).
     * For ".gitignore" the extension is "gitignore", because the name starts with the dot.
     * Files without an extension (Dockerfile, Makefile) are recognised by name in [languageOf].
     */
    private val LANG_BY_EXT = mapOf(
        // original languages
        "py" to "python", "pyw" to "python",
        "kt" to "kotlin", "kts" to "kotlin",
        "ts" to "typescript", "mts" to "typescript", "cts" to "typescript",
        "tsx" to "tsx",
        "js" to "javascript", "mjs" to "javascript", "cjs" to "javascript", "jsx" to "javascript",
        "toml" to "toml",
        "gd" to "gdscript",
        "cpp" to "cpp", "cc" to "cpp", "cxx" to "cpp", "hpp" to "cpp", "hh" to "cpp", "hxx" to "cpp", "h" to "cpp",
        "swift" to "swift",
        "java" to "java",
        "rb" to "ruby",
        "php" to "php",
        "rs" to "rust",
        "yml" to "yaml", "yaml" to "yaml",
        "xml" to "xml", "xsd" to "xml", "xsl" to "xml", "svg" to "xml", "plist" to "xml",
        "pro" to "qmake", "pri" to "qmake", "prf" to "qmake",
        "gitignore" to "gitignore", "dockerignore" to "gitignore", "npmignore" to "gitignore",
        "cs" to "csharp", "csx" to "csharp",
        "go" to "go",
        "dart" to "dart",
        "lua" to "lua",
        "sql" to "sql",
        "html" to "html", "htm" to "html", "xhtml" to "html",
        "css" to "css",
        "json" to "json", "jsonc" to "jsonc",
        "sh" to "shellscript", "bash" to "shellscript", "zsh" to "shellscript",
        "bashrc" to "shellscript", "zshrc" to "shellscript",
        "ps1" to "powershell", "psm1" to "powershell", "psd1" to "powershell",
        "mk" to "makefile", "mak" to "makefile",
        "r" to "r",
        "jl" to "julia",
        "m" to "matlab",
        "scala" to "scala", "sc" to "scala", "sbt" to "scala",
        // newest additions
        "md" to "markdown", "markdown" to "markdown",
        "txt" to "text",
        "log" to "text", // log files are plain text (own logo, see iconKeyOf)
        "c" to "c",
        "graphql" to "graphql", "gql" to "graphql", "graphqls" to "graphql",
        "hs" to "haskell", "lhs" to "haskell",
        "ex" to "elixir", "exs" to "elixir",
        "erl" to "erlang", "hrl" to "erlang",
        "pl" to "perl", "pm" to "perl",
        "asm" to "asm", "s" to "asm", "nasm" to "asm",
        "zig" to "zig", "zon" to "zig",
        "nix" to "nix",
        "tf" to "terraform", "tfvars" to "terraform", "hcl" to "terraform",
        "vue" to "vue",
        "svelte" to "svelte",
        "clj" to "clojure", "cljs" to "clojure", "cljc" to "clojure", "edn" to "clojure",
        // extras
        "gradle" to "groovy", "groovy" to "groovy",
        "ini" to "ini", "cfg" to "ini", "properties" to "ini",
    )

    private val LANG_NAMES = mapOf(
        "python" to "Python", "kotlin" to "Kotlin", "typescript" to "TypeScript", "tsx" to "TSX",
        "javascript" to "JavaScript", "toml" to "TOML", "gdscript" to "GDScript", "cpp" to "C++",
        "c" to "C", "swift" to "Swift", "java" to "Java", "ruby" to "Ruby", "php" to "PHP",
        "rust" to "Rust", "yaml" to "YAML", "xml" to "XML", "qmake" to "Qt project",
        "gitignore" to "Git ignore", "json" to "JSON", "jsonc" to "JSON", "markdown" to "Markdown",
        "html" to "HTML", "css" to "CSS", "shellscript" to "Bash", "groovy" to "Groovy",
        "ini" to "Config", "sql" to "SQL", "csharp" to "C#", "go" to "Go", "dart" to "Dart",
        "lua" to "Lua", "powershell" to "PowerShell", "dockerfile" to "Dockerfile",
        "makefile" to "Makefile", "r" to "R", "julia" to "Julia", "matlab" to "MATLAB",
        "scala" to "Scala", "text" to "Text", "graphql" to "GraphQL", "haskell" to "Haskell",
        "elixir" to "Elixir", "erlang" to "Erlang", "perl" to "Perl", "asm" to "Assembly",
        "zig" to "Zig", "nix" to "Nix", "terraform" to "Terraform", "vue" to "Vue",
        "svelte" to "Svelte", "clojure" to "Clojure",
    )

    /** Languages that have a logo bundled by tools/prepare.py (the key is also the logo's file name). */
    private val LOGO_KEYS = setOf(
        "python", "kotlin", "typescript", "javascript", "cpp", "swift", "java", "ruby", "php",
        "rust", "toml", "gdscript", "tsx", "yaml", "xml", "qmake", "gitignore",
        "csharp", "go", "dart", "lua", "sql", "html", "css", "json", "shellscript", "powershell",
        "dockerfile", "makefile", "r", "julia", "matlab", "scala",
        "markdown", "txt", "log", "c", "graphql", "haskell", "elixir", "erlang", "perl", "asm", "zig",
        "nix", "terraform", "vue", "svelte", "clojure",
    )

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()

    /**
     * Language key for a file name, or null for unknown files. ".txt" and ".log" give "text", a
     * language with no highlighting and no smart pairing (plain text).
     */
    fun languageOf(name: String): String? {
        val lower = name.lowercase()
        // files recognised by their name rather than an extension
        if (lower == "dockerfile" || lower.startsWith("dockerfile.") || lower.endsWith(".dockerfile")) return "dockerfile"
        if (lower == "makefile" || lower == "gnumakefile" || lower == "bsdmakefile") return "makefile"
        return LANG_BY_EXT[ext(name)]
    }

    /** True for Markdown files (the editor offers a preview switch for them). */
    fun isMarkdown(name: String): Boolean = languageOf(name) == "markdown"

    /** Key of the bundled logo for a file name, or null if it has none. */
    fun iconKeyOf(name: String): String? {
        if (ext(name) == "log") return "log"
        val lang = languageOf(name) ?: return null
        // .dockerignore and .npmignore share the ignore-file highlighting but not Git's logo.
        if (lang == "gitignore" && ext(name) != "gitignore") return null
        val key = when (lang) {
            "jsonc" -> "json"
            "text" -> "txt"
            else -> lang
        }
        return if (key in LOGO_KEYS) key else null
    }

    fun isImageName(name: String): Boolean = ext(name) in IMAGE_EXT

    /** Short human description of a file's type, e.g. "Kotlin" or "PNG image". */
    fun typeLabel(name: String, isDir: Boolean): String {
        if (isDir) return "Folder"
        if (ext(name) == "log") return "Log"
        val lang = languageOf(name)
        if (lang != null) return LANG_NAMES[lang] ?: lang
        val e = ext(name)
        return when {
            e in IMAGE_EXT -> e.uppercase() + " image"
            e.isNotEmpty() -> e.uppercase() + " file"
            else -> "File"
        }
    }

    // ---------------------------------------------------------------- file kind

    fun kindOf(file: File): Kind {
        val e = ext(file.name)
        if (e in IMAGE_EXT) return if (isDecodableImage(file)) Kind.IMAGE else Kind.OTHER
        if (e in BINARY_EXT) return Kind.OTHER
        if (!file.isFile) return Kind.OTHER
        if (file.length() > MAX_TEXT_BYTES) return Kind.OTHER
        return if (looksLikeText(file)) Kind.TEXT else Kind.OTHER
    }

    private fun isDecodableImage(file: File): Boolean = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        opts.outWidth > 0 && opts.outHeight > 0
    } catch (e: Exception) {
        false
    }

    private fun looksLikeText(file: File): Boolean = try {
        val buf = ByteArray(8192)
        val n = file.inputStream().use { it.read(buf) }
        if (n <= 0) {
            true
        } else {
            var hasNul = false
            for (i in 0 until n) if (buf[i] == 0.toByte()) { hasNul = true; break }
            if (hasNul) {
                false
            } else {
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                // endOfInput = false: a multi-byte character cut at the end of the sample is fine
                val result = decoder.decode(ByteBuffer.wrap(buf, 0, n), CharBuffer.allocate(n + 1), false)
                !result.isError
            }
        }
    } catch (e: Exception) {
        false
    }

    // ---------------------------------------------------------------- text IO

    fun readText(file: File): TextData {
        val raw = file.readText(Charsets.UTF_8)
        val crlf = raw.contains("\r\n")
        return TextData(if (crlf) raw.replace("\r\n", "\n") else raw, crlf)
    }

    /** Writes through a temporary file so a failure never leaves a half-written file behind. */
    fun writeText(file: File, text: String, crlf: Boolean) {
        val out = if (crlf) text.replace("\n", "\r\n") else text
        val parent = file.parentFile ?: throw IOException("Invalid path")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Couldn't create folder ${parent.name}")
        val tmp = File(parent, ".${file.name}.gitode-tmp")
        try {
            tmp.writeText(out, Charsets.UTF_8)
            NioFiles.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    // ---------------------------------------------------------------- listing

    /** Folders first, then files; each group sorted alphabetically. Hides the .git folder. */
    fun list(dir: File): List<File> {
        val all = dir.listFiles() ?: return emptyList()
        return all
            .filter { !(it.isDirectory && it.name == ".git") && !it.name.endsWith(".gitode-tmp") }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    // ---------------------------------------------------------------- names

    /**
     * Returns an error message, or null if [rawName] may be used in [parent].
     * [ignore] is the item being renamed (it may keep or re-case its own name).
     */
    fun validateName(rawName: String, parent: File, isDir: Boolean, ignore: File? = null): String? {
        val name = rawName.trimStart()
        if (name.isBlank()) return "Name can't be empty"
        if (name.length > 255) return "Name is too long"
        if (name == "." || name == "..") return "That name isn't allowed"
        if (name.any { it in ILLEGAL_CHARS || it.code < 32 }) {
            return "Names can't contain / \\ : * ? \" < > |"
        }
        if (name.endsWith(" ") || name.endsWith(".")) return "Names can't end with a space or a dot"
        val siblings = parent.listFiles() ?: return null
        for (s in siblings) {
            if (ignore != null && s.path == ignore.path) continue
            if (!s.name.equals(name, ignoreCase = true)) continue
            return when {
                s.isDirectory == isDir ->
                    if (isDir) "A folder named \"${s.name}\" already exists"
                    else "A file named \"${s.name}\" already exists"
                // Android's file system cannot hold a file and a folder with the same name.
                s.isDirectory -> "A folder named \"${s.name}\" already uses this name (a file and a folder can't share a name on Android)"
                else -> "A file named \"${s.name}\" already uses this name (a file and a folder can't share a name on Android)"
            }
        }
        return null
    }

    /** [name] inside [parent], or null if it would escape [parent]. */
    fun safeChild(parent: File, name: String): File? {
        return try {
            val p = parent.canonicalFile
            val c = File(p, name).canonicalFile
            if (c.path.startsWith(p.path + File.separator)) c else null
        } catch (e: IOException) {
            null
        }
    }

    // ---------------------------------------------------------------- operations

    fun createFile(parent: File, rawName: String): File {
        val f = File(parent, rawName.trimStart())
        if (!f.createNewFile()) throw IOException("Couldn't create \"${f.name}\"")
        return f
    }

    fun createDir(parent: File, rawName: String): File {
        val d = File(parent, rawName.trimStart())
        if (!d.mkdir()) throw IOException("Couldn't create folder \"${d.name}\"")
        return d
    }

    fun rename(file: File, rawName: String): File {
        val dest = File(file.parentFile, rawName.trimStart())
        if (!file.renameTo(dest)) throw IOException("Couldn't rename \"${file.name}\"")
        return dest
    }

    /** Recursive copy. Symbolic links are skipped. */
    fun copyTree(src: File, dst: File) {
        if (NioFiles.isSymbolicLink(src.toPath())) return
        if (src.isDirectory) {
            if (!dst.exists() && !dst.mkdirs()) throw IOException("Couldn't create folder ${dst.name}")
            src.listFiles()?.forEach { copyTree(it, File(dst, it.name)) }
        } else {
            dst.parentFile?.mkdirs()
            NioFiles.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Recursive delete. A symbolic link is removed without following it. */
    fun deleteTree(f: File) {
        if (!NioFiles.isSymbolicLink(f.toPath()) && f.isDirectory) {
            f.listFiles()?.forEach { deleteTree(it) }
        }
        if (!f.delete() && f.exists()) throw IOException("Couldn't delete \"${f.name}\"")
    }

    // ---------------------------------------------------------------- SAF uri -> path

    /**
     * Converts a picker result (folder tree or document) to a real path.
     * Returns null for providers that don't map to a plain path.
     */
    fun pathFromUri(uri: Uri): File? {
        val authority = uri.authority ?: return null
        val id = try {
            if (DocumentsContract.isTreeUri(uri)) DocumentsContract.getTreeDocumentId(uri)
            else DocumentsContract.getDocumentId(uri)
        } catch (e: Exception) {
            return null
        }
        return when (authority) {
            "com.android.externalstorage.documents" -> {
                val volume = id.substringBefore(':')
                val rel = id.substringAfter(':', "")
                val base = when {
                    volume == "primary" -> Environment.getExternalStorageDirectory()
                    volume == "home" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}").matches(volume) -> File("/storage/$volume")
                    else -> return null
                }
                if (rel.isEmpty()) base else File(base, rel)
            }
            "com.android.providers.downloads.documents" ->
                if (id.startsWith("raw:")) File(id.removePrefix("raw:")) else null
            else -> null
        }
    }
}