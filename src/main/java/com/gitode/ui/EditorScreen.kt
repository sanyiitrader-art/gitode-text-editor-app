package com.gitode.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@Composable
fun EditorScreen(
    vm: MainViewModel,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onActions: () -> Unit,
    onPickFolder: () -> Unit,
    onPickFile: () -> Unit,
    onConnect: () -> Unit,
    onPush: () -> Unit,
) {
    val c = G.c
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current

    var p by remember { mutableFloatStateOf(0f) }
    var anim by remember { mutableStateOf<Job?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showUnsaved by remember { mutableStateOf(false) }
    var showReplace by remember { mutableStateOf(false) }
    var showGoto by remember { mutableStateOf(false) }
    var showRepo by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<GitodeEditor?>(null) }

    fun closeKeyboard() {
        editor?.dismissKeyboard()
        hideKeyboard(view)
    }

    // Tapping anywhere that has no function (empty top-bar space, the name sheet, empty screen) hides the keyboard.
    val dead: () -> Unit = { closeKeyboard() }

    fun settle(target: Float) {
        anim?.cancel()
        if (target == 0f) vm.cancelInline()
        anim = scope.launch { animate(p, target, animationSpec = tween(220)) { v, _ -> p = v } }
    }

    // The keyboard goes away as soon as the drawer starts to open and stays away until it is needed again.
    LaunchedEffect(p > 0f) { if (p > 0f) closeKeyboard() }
    BackHandler(enabled = p > 0f) { settle(0f) }

    val active = vm.active
    val image = vm.imageFile
    val connected = vm.settings.githubConnected

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.screenBase().imePadding()) {
            // ---- fixed top bar
            Row(
                Modifier.fillMaxWidth().background(c.surface).height(64.dp).noRippleClick(dead)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBtn(Ic.Menu, { settle(1f) })
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    CenterButton(if (connected) "Push" else "Connect to GitHub", if (connected) onPush else onConnect)
                }
                Box {
                    IconBtn(Ic.More, { menuOpen = true })
                    EditorMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        hasFile = active != null,
                        autosave = vm.settings.autosave,
                        onAutosave = { vm.settings.autosave = it },
                        onSave = { scope.launch { if (vm.saveCurrent()) vm.toast("Saved") } },
                        onSaveAll = { scope.launch { if (vm.saveAllNow()) vm.toast("All files saved") } },
                        onOpenFile = onPickFile,
                        onOpenFolder = { if (vm.hasUnsaved) showUnsaved = true else onPickFolder() },
                        onSearch = onSearch,
                        onReplace = { showReplace = true },
                        onGoto = { showGoto = true },
                        onRepo = { if (vm.root == null) vm.toast("Open a folder first") else showRepo = true },
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))

            // ---- thin file-name sheet: just the name (and the unsaved dot), no box around it
            val sheetName = active?.file?.name ?: image?.name
            if (sheetName != null) {
                Row(
                    Modifier.fillMaxWidth().background(c.card).noRippleClick(dead)
                        .padding(horizontal = 16.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt(
                        sheetName, Modifier.weight(1f, fill = false),
                        size = 12.sp, weight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (active?.dirty == true) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(7.dp).clip(CircleShape).background(c.accent))
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
            }

            // ---- main surface
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    active != null -> CodeEditorView(
                        vm = vm, buffer = active, onEditor = { editor = it }, modifier = Modifier.fillMaxSize(),
                    )
                    image != null -> ImageViewer(image)
                    vm.root == null -> EmptyState(onPickFolder, onPickFile, dead)
                    else -> FolderContents(vm, onOpenDrawer = { settle(1f) }, onBlank = dead)
                }
            }

            if (active != null) SmartKeysBar(editor)
        }

        // ---- duplicate-content reminder: a layer at the very top of the screen (never blocks anything)
        vm.duplicateNotice?.let { msg ->
            LaunchedEffect(msg) {
                delay(7000)
                if (vm.duplicateNotice == msg) vm.dismissDuplicate()
            }
            Box(
                Modifier.align(Alignment.TopCenter).statusBarsPadding()
                    .padding(top = 8.dp, start = 12.dp, end = 12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card)
                        .border(1.dp, c.accent.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                        .padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt(msg, Modifier.weight(1f), size = 13.sp, lineHeight = 18.sp)
                    IconBtn(Ic.Close, { vm.dismissDuplicate() }, size = 36.dp)
                }
            }
        }

        // ---- drawer
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val panelWidth = maxWidth * 0.95f
            val widthPx = with(density) { panelWidth.toPx() }
            val exclusionLen = with(density) { 200.dp.roundToPx() }
            val dragState = rememberDraggableState { d ->
                anim?.cancel()
                p = (p + d / widthPx).coerceIn(0f, 1f)
            }
            val onStop: suspend CoroutineScope.(Float) -> Unit = { v ->
                settle(if (v > 700f) 1f else if (v < -700f) 0f else if (p > 0.5f) 1f else 0f)
            }

            // Left-edge strip. It is ALWAYS composed, so a drag that starts on it keeps going while
            // the drawer appears (the old version removed it mid-drag, which cancelled the gesture).
            // It also asks Android not to treat swipes here as the system Back gesture.
            Box(
                Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 100.dp)
                    .height(320.dp).width(28.dp)
                    .onGloballyPositioned { co ->
                        if (Build.VERSION.SDK_INT >= 29) {
                            val pos = co.positionInWindow()
                            val h = co.size.height
                            val len = minOf(h, exclusionLen)
                            val top = (pos.y + (h - len) / 2f).toInt()
                            view.systemGestureExclusionRects =
                                listOf(android.graphics.Rect(0, top, co.size.width, top + len))
                        }
                    }
                    .draggable(dragState, Orientation.Horizontal, onDragStopped = onStop),
            )

            if (p > 0f) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f * p))
                        .noRippleClick { settle(0f) },
                )
                Box(
                    Modifier.fillMaxHeight().width(panelWidth)
                        .offset { IntOffset((-(1f - p) * widthPx).roundToInt(), 0) }
                        .draggable(dragState, Orientation.Horizontal, onDragStopped = onStop),
                ) {
                    ExplorerDrawer(
                        vm = vm,
                        onClose = { settle(0f) },
                        onActions = { settle(0f); onActions() },
                        onSettings = { settle(0f); onSettings() },
                    )
                }
            }
        }
    }

    if (showUnsaved) {
        ThreeButtonDialog(
            title = "There are unsaved changes in this session",
            message = "OK opens the other folder. Your unsaved edits stay stored with this folder and come back when you reopen it. Save all writes them to your files first.",
            onCancel = { showUnsaved = false },
            middleText = "OK",
            onMiddle = { showUnsaved = false; onPickFolder() },
            rightText = "Save all",
            onRight = {
                showUnsaved = false
                scope.launch { if (vm.saveAllNow()) onPickFolder() }
            },
        )
    }
    if (showReplace) {
        ReplaceDialog(
            initialFind = vm.searchQuery,
            onCancel = { showReplace = false },
            onReplace = { find, repl -> showReplace = false; vm.replaceAll(find, repl) },
        )
    }
    if (showGoto) {
        GotoDialog(
            onCancel = { showGoto = false },
            lines = { vm.lineCount() },
            onGo = { line -> if (vm.gotoLine(line)) { showGoto = false; true } else false },
        )
    }
    if (showRepo) {
        RepoDialog(
            initial = vm.repoName ?: "",
            onCancel = { showRepo = false },
            onSave = { name -> val err = vm.setRepo(name); if (err == null) showRepo = false; err },
        )
    }
}

// ---------------------------------------------------------------- top bar pieces

@Composable
private fun CenterButton(text: String, onClick: () -> Unit) {
    val c = G.c
    Box(
        Modifier.clip(RoundedCornerShape(20.dp)).background(c.accent)
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, color = c.onAccent, size = 14.sp, weight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun MenuHeader(text: String) {
    Txt(text, Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp), color = G.c.accent, size = 12.sp, weight = FontWeight.SemiBold)
}

@Composable
private fun MenuRow(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = G.c
    DropdownMenuItem(
        text = { Txt(label, color = if (enabled) c.text else c.dim.copy(alpha = 0.6f)) },
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
private fun EditorMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    hasFile: Boolean,
    autosave: Boolean,
    onAutosave: (Boolean) -> Unit,
    onSave: () -> Unit,
    onSaveAll: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenFolder: () -> Unit,
    onSearch: () -> Unit,
    onReplace: () -> Unit,
    onGoto: () -> Unit,
    onRepo: () -> Unit,
) {
    val c = G.c
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(264.dp).background(c.card, RoundedCornerShape(16.dp)),
    ) {
        MenuHeader("File")
        MenuRow("Save", hasFile) { onDismiss(); onSave() }
        MenuRow("Save all") { onDismiss(); onSaveAll() }
        DropdownMenuItem(
            text = { Txt("Autosave") },
            onClick = { onAutosave(!autosave) },
            trailingIcon = { GitodeSwitch(autosave) { onAutosave(it) } },
        )
        MenuRow("Open file") { onDismiss(); onOpenFile() }
        MenuRow("Open folder") { onDismiss(); onOpenFolder() }
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(c.outline))
        MenuHeader("Editor")
        MenuRow("Search text in file", hasFile) { onDismiss(); onSearch() }
        MenuRow("Replace", hasFile) { onDismiss(); onReplace() }
        MenuRow("Go to line", hasFile) { onDismiss(); onGoto() }
        MenuRow("Repo name") { onDismiss(); onRepo() }
    }
}

// ---------------------------------------------------------------- main surface states

@Composable
private fun EmptyState(onPickFolder: () -> Unit, onPickFile: () -> Unit, onBlank: () -> Unit) {
    Column(
        Modifier.fillMaxSize().noRippleClick(onBlank).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GlowButton("Select Folder", onPickFolder, Modifier.fillMaxWidth(0.8f), icon = Ic.Folder)
        Spacer(Modifier.height(28.dp))
        GlowButton("Open File", onPickFile, Modifier.fillMaxWidth(0.8f), icon = Ic.File)
    }
}

@Composable
private fun FolderContents(vm: MainViewModel, onOpenDrawer: () -> Unit, onBlank: () -> Unit) {
    val c = G.c
    val root = vm.root ?: return
    val items = vm.children(root)
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().noRippleClick(onBlank), contentAlignment = Alignment.Center) {
            Txt("This folder is empty", color = c.dim, size = 16.sp)
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().noRippleClick(onBlank).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        items(items, key = { it.path }) { f ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card)
                    .border(1.dp, c.outline, RoundedCornerShape(14.dp))
                    .clickable { if (f.isDirectory) { vm.tapFolder(f); onOpenDrawer() } else vm.openFile(f) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (f.isDirectory) FolderIcon(f.name) else FileIcon(f.name)
                Spacer(Modifier.width(14.dp))
                Txt(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private fun decodeSampled(file: File, max: Int): ImageBitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > max || bounds.outHeight / sample > max) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp: Bitmap? = BitmapFactory.decodeFile(file.path, opts)
    bmp?.asImageBitmap()
} catch (e: Throwable) {
    null
}

@Composable
private fun ImageViewer(file: File) {
    var bmp by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(file) { bmp = withContext(Dispatchers.IO) { decodeSampled(file, 2048) } }

    fun clamp(o: Offset, s: Float): Offset {
        val mx = box.width * (s - 1f) / 2f
        val my = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        Modifier.fillMaxSize().clipToBounds().onSizeChanged { box = it }
            .pointerInput(file) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val s = (scale * zoom).coerceIn(1f, 8f)
                    scale = s
                    offset = if (s == 1f) Offset.Zero else clamp(offset + pan, s)
                }
            }
            .pointerInput(file) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let {
            Image(
                bitmap = it, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
            )
        }
    }
}

// ---------------------------------------------------------------- dialogs

@Composable
private fun ReplaceDialog(initialFind: String, onCancel: () -> Unit, onReplace: (String, String) -> Unit) {
    var find by remember { mutableStateOf(TextFieldValue(initialFind, TextRange(initialFind.length))) }
    var repl by remember { mutableStateOf(TextFieldValue("")) }
    var error by remember { mutableStateOf<String?>(null) }
    var trigger by remember { mutableIntStateOf(0) }

    fun submit() {
        if (find.text.isEmpty()) {
            error = "Enter the text to find"
            trigger++
        } else {
            onReplace(find.text, repl.text)
        }
    }

    FormDialog("Replace", "Replace", onCancel, onAction = { submit() }) {
        GitodeInput(find, { find = it; error = null }, label = "Find", error = error, errorTrigger = trigger, autoFocus = true, imeAction = ImeAction.Next)
        Spacer(Modifier.height(14.dp))
        GitodeInput(repl, { repl = it }, label = "Replace with", imeAction = ImeAction.Done, onDone = { submit() })
        Txt("Replaces every match in this file, ignoring upper/lower case. You can undo it.", Modifier.padding(top = 12.dp), color = G.c.dim, size = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun GotoDialog(onCancel: () -> Unit, lines: () -> Int, onGo: (Int) -> Boolean) {
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var error by remember { mutableStateOf<String?>(null) }
    var trigger by remember { mutableIntStateOf(0) }

    fun submit() {
        val n = value.text.trim().toIntOrNull()
        if (n == null || !onGo(n)) {
            error = "Enter a line number between 1 and ${lines()}"
            trigger++
        }
    }

    FormDialog("Go to line", "Go", onCancel, onAction = { submit() }) {
        GitodeInput(
            value, { value = it; error = null }, label = "Go to line", error = error, errorTrigger = trigger,
            autoFocus = true, keyboardType = KeyboardType.Number, imeAction = ImeAction.Go, onDone = { submit() },
        )
    }
}

@Composable
private fun RepoDialog(initial: String, onCancel: () -> Unit, onSave: (String) -> String?) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    var error by remember { mutableStateOf<String?>(null) }
    var trigger by remember { mutableIntStateOf(0) }

    fun submit() {
        val err = onSave(value.text)
        if (err != null) {
            error = err
            trigger++
        }
    }

    FormDialog("Repository name", "Save", onCancel, onAction = { submit() }) {
        GitodeInput(
            value, { value = it; error = null }, label = "Repository name", placeholder = "my-repository",
            error = error, errorTrigger = trigger, autoFocus = true, imeAction = ImeAction.Done, onDone = { submit() },
        )
        Txt("Only the name, not the whole URL. Each project folder keeps its own repository name.", Modifier.padding(top = 12.dp), color = G.c.dim, size = 12.sp, lineHeight = 17.sp)
    }
}