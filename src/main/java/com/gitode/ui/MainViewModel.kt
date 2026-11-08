package com.gitode.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gitode.GitodeApp
import com.gitode.core.DuplicateIndex
import com.gitode.core.Fs
import com.gitode.core.IndentStyle
import com.gitode.core.SmartConfig
import com.gitode.core.detectIndent
import com.gitode.core.normalizeRepoName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One open text file. [text] is a snapshot; for the active file the editor is the source of truth. */
class Buffer(file: File, text: String, val crlf: Boolean, dirty: Boolean = false) {
    var file by mutableStateOf(file)
    var text: String = text
    var dirty by mutableStateOf(dirty)
    var version = 0
    var cursorLine = 0
    var cursorColumn = 0
    val indent: IndentStyle? = detectIndent(text)
}

/** One search match. [path] is set for project-wide results (which file it is in). */
class SearchHit(
    val line: Int, // zero-based line number
    val start: Int,
    val end: Int,
    val preview: String,
    val previewStart: Int,
    val previewEnd: Int,
    val path: String? = null,
)

/** A file found by name in the drawer's search box. [dir] is shown under the name, e.g. "Game/core". */
class FileHit(val file: File, val dir: String)

/** Ask the editor to scroll to [line] (zero-based) and select columns [start]..[end]. */
class Jump(val line: Int, val start: Int, val end: Int)

sealed interface InlineEdit {
    class Create(val parent: File, val isDir: Boolean) : InlineEdit
    class Rename(val file: File) : InlineEdit
}

/** Outcome of saving an inline create/rename: an [error] to show, or the new [openFile] to open. */
class InlineResult(val error: String? = null, val openFile: File? = null)

/** Hooks the live editor registers so the view model can reach it. */
class EditorBridge {
    var getText: (() -> String)? = null
    /** Replaces every match (case-insensitive) in one undoable step and returns how many were replaced. */
    var replaceAll: ((find: String, with: String) -> Int)? = null
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val gitode = app as GitodeApp
    val settings = gitode.settings
    private val projects = gitode.projects
    val recycleBin = gitode.recycleBin
    private val duplicates = DuplicateIndex()

    // ---- project and files
    var root by mutableStateOf<File?>(null)
        private set
    var repoName by mutableStateOf<String?>(null)
        private set
    val buffers = mutableStateMapOf<String, Buffer>()
    var active by mutableStateOf<Buffer?>(null)
        private set
    var imageFile by mutableStateOf<File?>(null)
        private set
    val bridge = EditorBridge()

    /** Recently opened files, most recent first (the panel under the top bar). */
    val tabs = mutableStateListOf<String>()

    // ---- messages and requests
    var notice by mutableStateOf<String?>(null)
    var duplicateNotice by mutableStateOf<String?>(null)
        private set
    /** Set when a file should be opened with another app; MainActivity consumes and clears it. */
    var openWith by mutableStateOf<File?>(null)
    var pendingJump by mutableStateOf<Jump?>(null)

    // ---- explorer
    val expanded = mutableStateSetOf<String>()
    var highlighted by mutableStateOf<String?>(null)
    var selectionMode by mutableStateOf(false)
        private set
    val selected = mutableStateSetOf<String>()
    var inlineEdit by mutableStateOf<InlineEdit?>(null)
        private set
    var treeVersion by mutableIntStateOf(0)
        private set
    private val dirCache = HashMap<String, List<File>>()

    // ---- search in the open file
    var searchQuery by mutableStateOf("")
        private set
    var searchHits by mutableStateOf<List<SearchHit>>(emptyList())
        private set
    var searchRan by mutableStateOf(false)
        private set

    // ---- search in the whole project
    var projQuery by mutableStateOf("")
        private set
    var projHits by mutableStateOf<List<SearchHit>>(emptyList())
        private set
    var projRan by mutableStateOf(false)
        private set
    var projRunning by mutableStateOf(false)
        private set
    private var projJob: Job? = null

    // ---- the drawer's file-name search box
    var drawerQuery by mutableStateOf("")
        private set
    var drawerResults by mutableStateOf<List<FileHit>>(emptyList())
        private set
    var drawerBusy by mutableStateOf(false)
        private set
    private var drawerJob: Job? = null

    // every file in the open folder, cached briefly so typing in a search box stays fast
    @Volatile
    private var fileIndex: List<File>? = null
    private var indexTime = 0L

    private val loose = File("/gitode-loose")
    private val persistRoot: File get() = root ?: loose
    private var restored = false
    private var workJob: Job? = null
    private var autosaveJob: Job? = null
    private var searchJob: Job? = null
    private var lastDupKey: String? = null

    val rootName: String get() = root?.name ?: ""
    val hasUnsaved: Boolean get() = buffers.values.any { it.dirty }

    fun toast(message: String) { notice = message }

    // ================================================================ project lifecycle

    /** Restores the last project (or loose files) once storage access is available. */
    fun restoreSession() {
        if (restored) return
        restored = true
        viewModelScope.launch {
            val last = withContext(Dispatchers.IO) { projects.last() }
            switchTo(if (last != null && last.isDirectory) last else null)
        }
    }

    fun openProject(dir: File) {
        viewModelScope.launch { switchTo(dir) }
    }

    fun handleFolderUri(uri: Uri) {
        val dir = Fs.pathFromUri(uri)
        if (dir == null || !dir.isDirectory) {
            toast("Gitode can't read that folder's location. Pick a folder from Internal storage or an SD card.")
            return
        }
        openProject(dir)
    }

    fun handleFileUri(uri: Uri) {
        val f = Fs.pathFromUri(uri)
        if (f == null || !f.isFile) {
            toast("Gitode can't read that file's location. Pick it from Internal storage or an SD card.")
            return
        }
        openFile(f)
    }

    private suspend fun switchTo(dir: File?) {
        persistAll()
        resetState()
        root = dir
        val key = dir ?: loose
        val stored = withContext(Dispatchers.IO) { projects.buffers(key) }
        val storedTabs = withContext(Dispatchers.IO) { projects.tabs(key) }
        repoName = withContext(Dispatchers.IO) { projects.repoName(key) }
        val act = withContext(Dispatchers.IO) { projects.active(key) }
        if (dir != null) withContext(Dispatchers.IO) { projects.setLast(dir) }
        for (b in stored) buffers[b.path] = Buffer(File(b.path), b.text, b.crlf, dirty = true)
        tabs.addAll(storedTabs.filter { File(it).exists() || buffers.containsKey(it) })
        // a file with unsaved edits always has a tab, so it can't be lost from view
        for (b in stored) if (!tabs.contains(b.path)) tabs.add(b.path)
        if (act != null && (File(act).exists() || buffers.containsKey(act))) openFileInternal(File(act))
        if (dir != null) {
            viewModelScope.launch(Dispatchers.Default) { duplicates.rebuild(dir) }
        } else {
            duplicates.clear()
        }
    }

    private fun resetState() {
        workJob?.cancel()
        autosaveJob?.cancel()
        searchJob?.cancel()
        projJob?.cancel()
        drawerJob?.cancel()
        buffers.clear()
        tabs.clear()
        active = null
        imageFile = null
        expanded.clear()
        selected.clear()
        selectionMode = false
        highlighted = null
        inlineEdit = null
        dirCache.clear()
        fileIndex = null
        treeVersion++
        duplicateNotice = null
        lastDupKey = null
        searchQuery = ""
        searchHits = emptyList()
        searchRan = false
        projQuery = ""
        projHits = emptyList()
        projRan = false
        projRunning = false
        drawerQuery = ""
        drawerResults = emptyList()
        drawerBusy = false
        pendingJump = null
    }

    // ================================================================ buffers

    /** Pulls the live text from the editor into the active buffer. */
    fun flushActive() {
        val text = bridge.getText?.invoke() ?: return
        active?.text = text
    }

    private suspend fun persistAll() {
        flushActive()
        val key = persistRoot
        val dirty = buffers.values.filter { it.dirty }.map { Triple(it.file.path, it.text, it.crlf) }
        val act = active?.file?.path ?: imageFile?.path
        withContext(Dispatchers.IO) {
            dirty.forEach { projects.saveBuffer(key, it.first, it.second, it.third) }
            projects.setActive(key, act)
        }
    }

    /** Called when the app goes to the background. */
    fun onBackgrounded() {
        viewModelScope.launch {
            if (settings.autosave) saveAllNow() else persistAll()
        }
    }

    fun openFile(file: File) {
        viewModelScope.launch { openFileInternal(file) }
    }

    private suspend fun openFileInternal(file: File) {
        flushActive()
        val existing = buffers[file.path]
        if (existing != null) {
            activate(existing)
            return
        }
        if (!file.exists()) {
            toast("${file.name} no longer exists")
            if (tabs.remove(file.path)) persistTabs()
            return
        }
        when (withContext(Dispatchers.IO) { Fs.kindOf(file) }) {
            Fs.Kind.TEXT -> {
                try {
                    val data = withContext(Dispatchers.IO) { Fs.readText(file) }
                    val b = Buffer(file, data.text, data.crlf)
                    buffers[file.path] = b
                    activate(b)
                } catch (e: Exception) {
                    toast("Couldn't open ${file.name}: ${e.message}")
                }
            }
            Fs.Kind.IMAGE -> {
                active = null
                imageFile = file
                duplicateNotice = null
                touchTab(file.path)
                val key = persistRoot
                viewModelScope.launch(Dispatchers.IO) { projects.setActive(key, file.path) }
            }
            Fs.Kind.OTHER -> openWith = file
        }
    }

    private fun activate(b: Buffer) {
        active = b
        imageFile = null
        duplicateNotice = null
        lastDupKey = null
        touchTab(b.file.path)
        val key = persistRoot
        viewModelScope.launch(Dispatchers.IO) { projects.setActive(key, b.file.path) }
        if (searchQuery.isNotEmpty()) search(searchQuery)
    }

    // ================================================================ recent files (tabs)

    private fun persistTabs() {
        val key = persistRoot
        val list = tabs.toList()
        viewModelScope.launch(Dispatchers.IO) { projects.setTabs(key, list) }
    }

    /** Moves a file to the front of the recent list (never dropping files with unsaved edits). */
    private fun touchTab(path: String) {
        tabs.remove(path)
        tabs.add(0, path)
        var i = tabs.size - 1
        while (tabs.size > MAX_TABS && i > 0) {
            val p = tabs[i]
            if (buffers[p]?.dirty != true) tabs.removeAt(i)
            i--
        }
        persistTabs()
    }

    /**
     * Closes a tab. With unsaved edits, [saveFirst] writes them to the file first; otherwise they are discarded.
     * If it was the file on screen, the next most recent file opens.
     */
    fun closeTab(path: String, saveFirst: Boolean) {
        viewModelScope.launch { closeTabInternal(path, saveFirst) }
    }

    private suspend fun closeTabInternal(path: String, saveFirst: Boolean) {
        flushActive()
        val b = buffers[path]
        val key = persistRoot
        if (b != null && b.dirty) {
            if (saveFirst) {
                if (!save(b)) return
            } else {
                withContext(Dispatchers.IO) { projects.dropBuffer(key, path) }
                val disk = withContext(Dispatchers.IO) {
                    try { Fs.readText(b.file).text } catch (e: Exception) { null }
                }
                withContext(Dispatchers.Default) { duplicates.update(path, disk) }
            }
        }
        val wasShown = active?.file?.path == path || imageFile?.path == path
        buffers.remove(path)
        tabs.remove(path)
        persistTabs()
        if (wasShown) {
            active = null
            imageFile = null
            duplicateNotice = null
            lastDupKey = null
            val next = tabs.firstOrNull()
            if (next != null) openFileInternal(File(next))
            else withContext(Dispatchers.IO) { projects.setActive(key, null) }
        }
    }

    // ================================================================ editing and saving

    /** The editor calls this on every user edit (cheap: it only schedules work). */
    fun onTextChanged() {
        val b = active ?: return
        b.dirty = true
        b.version++
        workJob?.cancel()
        workJob = viewModelScope.launch {
            delay(600)
            flushActive()
            val key = persistRoot
            val text = b.text
            withContext(Dispatchers.IO) { projects.saveBuffer(key, b.file.path, text, b.crlf) }
            checkDuplicates(b)
        }
        if (settings.autosave) {
            autosaveJob?.cancel()
            autosaveJob = viewModelScope.launch {
                delay(settings.autosaveDelaySec.coerceAtLeast(1) * 1000L)
                save(b)
            }
        }
    }

    suspend fun save(b: Buffer): Boolean {
        if (b === active) flushActive()
        val text = b.text
        val versionAtStart = b.version
        return try {
            val key = persistRoot
            withContext(Dispatchers.IO) {
                Fs.writeText(b.file, text, b.crlf)
                projects.dropBuffer(key, b.file.path)
            }
            if (b.version == versionAtStart) b.dirty = false
            withContext(Dispatchers.Default) { duplicates.update(b.file.path, text) }
            true
        } catch (e: Exception) {
            toast("Couldn't save ${b.file.name}: ${e.message}")
            false
        }
    }

    suspend fun saveCurrent(): Boolean {
        val b = active ?: return true
        return save(b)
    }

    suspend fun saveAllNow(): Boolean {
        flushActive()
        var ok = true
        for (b in buffers.values.filter { it.dirty }.toList()) {
            if (!save(b)) ok = false
        }
        return ok
    }

    // ================================================================ duplicates

    private suspend fun checkDuplicates(b: Buffer) {
        val text = b.text
        val path = b.file.path
        val others = withContext(Dispatchers.Default) {
            duplicates.update(path, text)
            duplicates.duplicatesOf(path)
        }
        if (b !== active) return
        if (others.isEmpty()) {
            lastDupKey = null
            duplicateNotice = null
            return
        }
        val names = others.map { File(it).name }.distinct().sorted()
        val key = "${text.length}:${text.hashCode()}:${names.joinToString()}"
        if (key == lastDupKey) return // already reminded about this exact content
        lastDupKey = key
        val shown = names.take(3).joinToString(", ") + if (names.size > 3) " and ${names.size - 3} more" else ""
        duplicateNotice = "Duplicate content detected: This content already exists in $shown."
    }

    fun dismissDuplicate() { duplicateNotice = null }

    // ================================================================ smart editing config

    fun smartConfig(b: Buffer?): SmartConfig {
        val ind = b?.indent
        val lang = b?.let { Fs.languageOf(it.file.name) }
        return SmartConfig(ind?.width ?: settings.tabWidth, ind?.useSpaces ?: settings.useSpaces, lang)
    }

    // ================================================================ repository name

    /** Returns an error message, or null on success. */
    fun setRepo(input: String): String? {
        val dir = root ?: return "Open a folder first"
        val name = normalizeRepoName(input)
            ?: return "Use letters, numbers, dots, dashes or underscores (up to 100 characters)"
        repoName = name
        viewModelScope.launch(Dispatchers.IO) { projects.setRepoName(dir, name) }
        return null
    }

    // ================================================================ files in the open folder

    private fun scanFiles(dir: File): List<File> {
        val out = ArrayList<File>()
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty() && out.size < MAX_INDEX) {
            val d = stack.removeLast()
            val kids = d.listFiles() ?: continue
            for (f in kids) {
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue
                if (f.isDirectory) {
                    if (f.name !in SKIP_DIRS) stack.add(f)
                } else if (!f.name.endsWith(".gitode-tmp")) {
                    out.add(f)
                }
            }
        }
        out.sortBy { it.path.lowercase() }
        return out
    }

    @Synchronized
    private fun filesOf(dir: File): List<File> {
        val now = System.currentTimeMillis()
        val cached = fileIndex
        if (cached != null && now - indexTime < 8000) return cached
        val fresh = scanFiles(dir)
        fileIndex = fresh
        indexTime = now
        return fresh
    }

    // ================================================================ the drawer's search box (file names)

    fun setDrawerQuery(q: String) {
        drawerQuery = q
        drawerJob?.cancel()
        val dir = root
        if (q.isBlank() || dir == null) {
            drawerResults = emptyList()
            drawerBusy = false
            return
        }
        drawerBusy = true
        drawerJob = viewModelScope.launch(Dispatchers.Default) {
            delay(120)
            val needle = q.trim().lowercase()
            val byPath = needle.contains('/')
            val matches = ArrayList<FileHit>()
            for (f in filesOf(dir)) {
                if (!isActive) return@launch
                val rel = relDir(dir, f)
                val hay = if (byPath) (rel + "/" + f.name).lowercase() else f.name.lowercase()
                if (hay.contains(needle)) {
                    matches.add(FileHit(f, rel))
                    if (matches.size >= MAX_NAME_HITS) break
                }
            }
            matches.sortWith(
                compareBy<FileHit>({ !it.file.name.lowercase().startsWith(needle) }, { it.file.name.lowercase() }),
            )
            withContext(Dispatchers.Main) {
                if (drawerQuery == q) {
                    drawerResults = matches
                    drawerBusy = false
                }
            }
        }
    }

    /** The folder a file is in, starting at the open folder's own name: "Game/core". */
    private fun relDir(dir: File, f: File): String {
        val parent = f.parentFile ?: return dir.name
        val rel = parent.path.removePrefix(dir.path).trim('/')
        return if (rel.isEmpty()) dir.name else dir.name + "/" + rel
    }

    // ================================================================ search, go to line, replace

    fun search(query: String) {
        searchQuery = query
        searchJob?.cancel()
        if (query.isEmpty()) {
            searchHits = emptyList()
            searchRan = false
            return
        }
        flushActive()
        val text = active?.text
        if (text == null) {
            searchHits = emptyList()
            searchRan = true
            return
        }
        searchJob = viewModelScope.launch(Dispatchers.Default) {
            val hits = findHits(text, query, null, MAX_HITS)
            withContext(Dispatchers.Main) {
                searchHits = hits
                searchRan = true
            }
        }
    }

    private fun findHits(text: String, query: String, path: String?, max: Int): List<SearchHit> {
        val hits = ArrayList<SearchHit>()
        var lineNo = 0
        for (line in text.split('\n')) {
            var i = line.indexOf(query, 0, ignoreCase = true)
            while (i >= 0 && hits.size < max) {
                val from = (i - 30).coerceAtLeast(0)
                val to = (i + query.length + 50).coerceAtMost(line.length)
                val prefix = if (from > 0) "…" else ""
                val suffix = if (to < line.length) "…" else ""
                val raw = line.substring(from, to)
                val trimmed = raw.trimStart()
                val lead = raw.length - trimmed.length
                val ms = (prefix.length + (i - from) - lead).coerceAtLeast(0)
                hits.add(SearchHit(lineNo, i, i + query.length, prefix + trimmed + suffix, ms, ms + query.length, path))
                i = line.indexOf(query, i + query.length, ignoreCase = true)
            }
            if (hits.size >= max) break
            lineNo++
        }
        return hits
    }

    /** Searches every text file in the open folder (unsaved edits of open files included). */
    fun searchProject(query: String) {
        projQuery = query
        projJob?.cancel()
        val dir = root
        if (query.isEmpty() || dir == null) {
            projHits = emptyList()
            projRan = false
            projRunning = false
            return
        }
        flushActive()
        val open = HashMap<String, String>()
        for ((p, b) in buffers) open[p] = b.text
        projRunning = true
        projRan = false
        projJob = viewModelScope.launch(Dispatchers.Default) {
            delay(250)
            val hits = ArrayList<SearchHit>()
            for (f in filesOf(dir)) {
                if (!isActive) return@launch
                if (hits.size >= MAX_PROJECT_HITS) break
                val live = open[f.path]
                val text: String = if (live != null) {
                    live
                } else {
                    if (f.length() !in 1L..MAX_SEARCH_BYTES) continue
                    if (Fs.kindOf(f) != Fs.Kind.TEXT) continue
                    try { f.readText() } catch (e: Exception) { continue }
                }
                if (!text.contains(query, ignoreCase = true)) continue
                hits.addAll(findHits(text.replace("\r\n", "\n"), query, f.path, PER_FILE_HITS))
            }
            withContext(Dispatchers.Main) {
                if (projQuery == query) {
                    projHits = hits
                    projRan = true
                    projRunning = false
                }
            }
        }
    }

    /** Opens the match: for a project result the file opens first, then the editor scrolls to the text and selects it. */
    fun jumpToHit(h: SearchHit) {
        val p = h.path
        if (p == null) {
            pendingJump = Jump(h.line, h.start, h.end)
            return
        }
        viewModelScope.launch {
            openFileInternal(File(p))
            pendingJump = Jump(h.line, h.start, h.end)
        }
    }

    fun lineCount(): Int {
        flushActive()
        val t = active?.text ?: return 0
        return t.count { it == '\n' } + 1
    }

    /** Returns false (and does nothing) if [line] (one-based) is out of range. */
    fun gotoLine(line: Int): Boolean {
        val total = lineCount()
        if (line !in 1..total) return false
        pendingJump = Jump(line - 1, 0, 0)
        return true
    }

    /** Replaces every match in the current file. Returns the number of replacements. */
    fun replaceAll(find: String, with: String): Int {
        val n = bridge.replaceAll?.invoke(find, with) ?: 0
        toast(if (n == 0) "No matches found" else "Replaced $n occurrence${if (n == 1) "" else "s"}")
        if (n > 0 && searchQuery.isNotEmpty()) search(searchQuery)
        return n
    }

    // ================================================================ explorer

    fun children(dir: File): List<File> {
        treeVersion // read so composables listing a folder refresh after changes
        return dirCache.getOrPut(dir.path) { Fs.list(dir) }
    }

    fun invalidate() {
        dirCache.clear()
        fileIndex = null
        treeVersion++
    }

    fun afterRecycleRestore() {
        invalidate()
        root?.let { dir -> viewModelScope.launch(Dispatchers.Default) { duplicates.rebuild(dir) } }
    }

    fun tapFile(file: File) {
        cancelInline()
        highlighted = file.path
        if (selectionMode) toggleSelect(file) else openFile(file)
    }

    fun tapFolder(dir: File) {
        cancelInline()
        highlighted = dir.path
        if (expanded.contains(dir.path)) collapse(dir) else expanded.add(dir.path)
    }

    private fun collapse(dir: File) {
        expanded.remove(dir.path)
        val under = selected.filter { it.startsWith(dir.path + "/") }
        selected.removeAll(under.toSet())
    }

    /** A tap on empty space clears the highlight, cancels create/rename and leaves selection mode. */
    fun tapBlank() {
        cancelInline()
        highlighted = null
        if (selectionMode) exitSelection()
    }

    fun enterSelection(file: File) {
        cancelInline()
        selectionMode = true
        selected.clear()
        selected.add(file.path)
        highlighted = null
    }

    fun toggleSelect(file: File) {
        if (!selected.remove(file.path)) selected.add(file.path)
    }

    fun exitSelection() {
        selectionMode = false
        selected.clear()
    }

    // ---- inline create / rename

    private fun createTarget(): File? {
        val r = root ?: return null
        val h = highlighted?.let { File(it) } ?: return r
        return when {
            h.isDirectory -> h
            h.exists() -> h.parentFile ?: r
            else -> r
        }
    }

    fun startCreate(isDir: Boolean) {
        val target = createTarget() ?: return
        val r = root
        if (target != r) expanded.add(target.path) // a collapsed highlighted folder opens first
        inlineEdit = InlineEdit.Create(target, isDir)
    }

    fun startRename(file: File) {
        cancelInline()
        inlineEdit = InlineEdit.Rename(file)
    }

    fun cancelInline() { inlineEdit = null }

    /**
     * Saves the inline edit. On success a new FILE is returned in [InlineResult.openFile] so the
     * caller can open it and close the drawer; on failure [InlineResult.error] is shown on the field.
     */
    suspend fun commitInline(raw: String): InlineResult {
        val edit = inlineEdit ?: return InlineResult()
        when (edit) {
            is InlineEdit.Create -> {
                Fs.validateName(raw, edit.parent, edit.isDir)?.let { return InlineResult(it) }
                return try {
                    val created = withContext(Dispatchers.IO) {
                        if (edit.isDir) Fs.createDir(edit.parent, raw) else Fs.createFile(edit.parent, raw)
                    }
                    expanded.add(edit.parent.path)
                    highlighted = created.path
                    inlineEdit = null
                    invalidate()
                    InlineResult(openFile = if (edit.isDir) null else created)
                } catch (e: Exception) {
                    InlineResult("Couldn't create it: ${e.message}")
                }
            }
            is InlineEdit.Rename -> {
                val old = edit.file
                val newName = raw.trimStart()
                if (newName == old.name) {
                    inlineEdit = null
                    return InlineResult()
                }
                val parent = old.parentFile ?: return InlineResult("Can't rename this item")
                Fs.validateName(raw, parent, old.isDirectory, ignore = old)?.let { return InlineResult(it) }
                return try {
                    val renamed = withContext(Dispatchers.IO) { Fs.rename(old, raw) }
                    remapPaths(old.path, renamed.path)
                    inlineEdit = null
                    highlighted = renamed.path
                    invalidate()
                    InlineResult()
                } catch (e: Exception) {
                    InlineResult("Couldn't rename it: ${e.message}")
                }
            }
        }
    }

    private fun mapPath(p: String, old: String, new: String): String? = when {
        p == old -> new
        p.startsWith("$old/") -> new + p.substring(old.length)
        else -> null
    }

    private suspend fun remapPaths(old: String, new: String) {
        flushActive()
        val key = persistRoot
        for (e in buffers.entries.toList()) {
            val mapped = mapPath(e.key, old, new) ?: continue
            val b = e.value
            buffers.remove(e.key)
            b.file = File(mapped)
            buffers[mapped] = b
        }
        imageFile?.let { f -> mapPath(f.path, old, new)?.let { imageFile = File(it) } }
        for (p in expanded.toList()) {
            mapPath(p, old, new)?.let { expanded.remove(p); expanded.add(it) }
        }
        for (i in tabs.indices) {
            mapPath(tabs[i], old, new)?.let { tabs[i] = it }
        }
        persistTabs()
        withContext(Dispatchers.IO) { projects.remapPaths(key, old, new) }
        root?.let { dir -> viewModelScope.launch(Dispatchers.Default) { duplicates.rebuild(dir) } }
    }

    // ---- delete (always through the Recycle bin)

    private fun inside(path: String, parent: String) = path == parent || path.startsWith("$parent/")

    /** Moves [file] (or a whole folder) to the Recycle bin. Returns an error message or null. */
    suspend fun trash(file: File): String? {
        flushActive()
        val key = persistRoot
        val affected = buffers.values.filter { inside(it.file.path, file.path) }.toList()
        // Save pending edits first so the recycled copy contains the latest work.
        for (b in affected.filter { it.dirty }) {
            try {
                withContext(Dispatchers.IO) { Fs.writeText(b.file, b.text, b.crlf) }
            } catch (e: Exception) {
                return "Couldn't save ${b.file.name} before deleting: ${e.message}"
            }
        }
        try {
            withContext(Dispatchers.IO) { recycleBin.trash(file) }
        } catch (e: Exception) {
            invalidate()
            return e.message ?: "Couldn't move it to the Recycle bin"
        }
        for (b in affected) {
            buffers.remove(b.file.path)
            withContext(Dispatchers.IO) { projects.dropBuffer(key, b.file.path) }
            duplicates.update(b.file.path, null)
        }
        if (active != null && affected.any { it === active }) {
            active = null
            withContext(Dispatchers.IO) { projects.setActive(key, null) }
        }
        imageFile?.let { if (inside(it.path, file.path)) imageFile = null }
        tabs.removeAll { inside(it, file.path) }
        persistTabs()
        selected.removeAll(selected.filter { inside(it, file.path) }.toSet())
        expanded.removeAll(expanded.filter { inside(it, file.path) }.toSet())
        highlighted?.let { if (inside(it, file.path)) highlighted = null }
        invalidate()
        return null
    }

    /** Deletes every selected file (moving each to the Recycle bin). Returns an error message or null. */
    suspend fun trashSelected(): String? {
        val files = selected.map { File(it) }
        var error: String? = null
        for (f in files) {
            val e = trash(f)
            if (e != null && error == null) error = e
        }
        exitSelection()
        return error
    }

    companion object {
        private const val MAX_HITS = 1000
        private const val MAX_PROJECT_HITS = 600
        private const val PER_FILE_HITS = 40
        private const val MAX_SEARCH_BYTES = 1024L * 1024
        private const val MAX_NAME_HITS = 200
        private const val MAX_INDEX = 50000
        private const val MAX_TABS = 40
        private val SKIP_DIRS = setOf(".git", "node_modules", ".gradle", ".idea", "__pycache__")
    }
}