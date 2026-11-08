package com.gitode.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import java.io.File

private sealed interface TreeRow {
    class Item(val file: File, val depth: Int) : TreeRow
    class Create(val parent: File, val isDir: Boolean, val depth: Int) : TreeRow
}

private fun buildRows(vm: MainViewModel, dir: File, depth: Int, out: MutableList<TreeRow>) {
    val edit = vm.inlineEdit
    if (edit is InlineEdit.Create && edit.parent.path == dir.path) {
        out.add(TreeRow.Create(edit.parent, edit.isDir, depth))
    }
    for (f in vm.children(dir)) {
        out.add(TreeRow.Item(f, depth))
        if (f.isDirectory && vm.expanded.contains(f.path)) buildRows(vm, f, depth + 1, out)
    }
}

private fun collectFiles(vm: MainViewModel, dir: File, out: MutableList<File>) {
    for (f in vm.children(dir)) {
        if (f.isDirectory) {
            if (vm.expanded.contains(f.path)) collectFiles(vm, f, out)
        } else {
            out.add(f)
        }
    }
}

/** A folder lights up in selection mode only when it has files and every one of them is selected. */
private fun folderFullySelected(vm: MainViewModel, dir: File): Boolean {
    if (!vm.expanded.contains(dir.path)) return false
    val files = ArrayList<File>()
    collectFiles(vm, dir, files)
    return files.isNotEmpty() && files.all { vm.selected.contains(it.path) }
}

private fun startValue(edit: InlineEdit?): TextFieldValue = when (edit) {
    is InlineEdit.Rename -> TextFieldValue(edit.file.name, TextRange(0, edit.file.name.length))
    else -> TextFieldValue("")
}

@Composable
fun ExplorerDrawer(
    vm: MainViewModel,
    onClose: () -> Unit,
    onActions: () -> Unit,
    onSettings: () -> Unit,
) {
    val c = G.c
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val root = vm.root
    val edit = vm.inlineEdit

    var field by remember(edit) { mutableStateOf(startValue(edit)) }
    var error by remember(edit) { mutableStateOf<String?>(null) }
    var trigger by remember(edit) { mutableIntStateOf(0) }
    var createMenu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<File?>(null) }
    var confirmBulk by remember { mutableStateOf(false) }

    // search box
    var searchFocused by remember { mutableStateOf(false) }
    var barVisible by remember { mutableStateOf(true) }
    val focusedNow by rememberUpdatedState(searchFocused)

    val dismissSearch: () -> Unit = {
        focusManager.clearFocus()
        hideKeyboard(view)
    }

    // Any tap on empty space: clear the highlight, cancel create/rename, leave selection mode,
    // close the search and hide the keyboard.
    val onBlank: () -> Unit = {
        vm.tapBlank()
        dismissSearch()
    }

    // Scrolling the file list up hides the search box, scrolling down brings it back
    // (only while the box isn't being used).
    val nested = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!focusedNow) {
                    if (available.y < -8f) barVisible = false
                    else if (available.y > 8f) barVisible = true
                }
                return Offset.Zero
            }
        }
    }

    fun submit() {
        scope.launch {
            val result = vm.commitInline(field.text)
            val err = result.error
            if (err != null) {
                error = err
                trigger++
            } else {
                hideKeyboard(view)
                val created = result.openFile
                if (created != null) {
                    // A new file opens straight away and the drawer closes.
                    vm.openFile(created)
                    onClose()
                }
            }
        }
    }

    val rows = ArrayList<TreeRow>()
    if (root != null) buildRows(vm, root, 0, rows)
    val listState = rememberLazyListState()
    LaunchedEffect(edit) {
        if (edit is InlineEdit.Create) {
            val i = rows.indexOfFirst { it is TreeRow.Create }
            if (i >= 0) listState.animateScrollToItem(i)
        }
    }

    // How wide the list needs to be so every row keeps its normal look: the widest row decides.
    val measurer = rememberTextMeasurer()
    val widthCache = remember { HashMap<String, Int>() }
    var maxRowDp = 0f
    for (r in rows) {
        if (r is TreeRow.Item) {
            val name = r.file.name
            val textPx = widthCache.getOrPut(name) {
                measurer.measure(name, style = TextStyle(fontSize = 15.sp), softWrap = false, maxLines = 1).size.width
            }
            val textDp = with(density) { textPx.toDp().value }
            val fixed = 8f + r.depth * 14f + 8f + 20f + (if (r.file.isDirectory) 56f else 50f) + 12f
            maxRowDp = maxOf(maxRowDp, fixed + textDp * 1.08f + 6f)
        }
    }

    Column(
        Modifier.fillMaxSize().background(c.surface).statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        // ---- drawer top bar (empty space in it counts as a blank tap)
        Row(
            Modifier.fillMaxWidth().height(64.dp).noRippleClick(onBlank).padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(
                if (root != null) vm.rootName else "Explorer", Modifier.weight(1f),
                size = 18.sp, weight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            when {
                edit != null -> {
                    TextAction("Cancel", { vm.cancelInline(); hideKeyboard(view) })
                    TextAction("Save", { submit() }, color = c.accent, bold = true)
                }
                vm.selectionMode -> TextAction(
                    "Delete ${vm.selected.size}", { confirmBulk = true },
                    color = c.error, enabled = vm.selected.isNotEmpty(), bold = true,
                )
                else -> Box {
                    TextAction("Create", { createMenu = true }, color = c.accent, enabled = root != null, bold = true)
                    DropdownMenu(
                        expanded = createMenu,
                        onDismissRequest = { createMenu = false },
                        modifier = Modifier.background(c.card, RoundedCornerShape(12.dp)),
                    ) {
                        DropdownMenuItem(
                            text = { Txt("File") },
                            onClick = { createMenu = false; vm.startCreate(false) },
                        )
                        DropdownMenuItem(
                            text = { Txt("Folder") },
                            onClick = { createMenu = false; vm.startCreate(true) },
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))

        // ---- search box (hides while the file list is scrolled up)
        if (root != null) {
            AnimatedVisibility(
                visible = barVisible || searchFocused,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                DrawerSearchBar(
                    value = vm.drawerQuery,
                                     onValue = { vm.updateDrawerQuery(it) },
                    onFocusChange = { searchFocused = it },
                )
            }
        }

        // ---- explorer listing (scrolls sideways when nested folders make rows wider than the drawer)
        if (root == null) {
            Box(
                Modifier.weight(1f).fillMaxWidth().noRippleClick(onBlank),
                contentAlignment = Alignment.Center,
            ) { Txt("No folder open", color = c.dim) }
        } else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().nestedScroll(nested)) {
                val viewportW = maxWidth
                val contentW = maxOf(viewportW, maxRowDp.dp)
                val hState = rememberScrollState()

                Box(Modifier.fillMaxSize().horizontalScroll(hState, enabled = contentW > viewportW)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.width(contentW).fillMaxHeight().noRippleClick(onBlank),
                    ) {
                        items(
                            rows,
                            key = { r ->
                                when (r) {
                                    is TreeRow.Item -> r.file.path
                                    is TreeRow.Create -> "__create__"
                                }
                            },
                        ) { r ->
                            when (r) {
                                is TreeRow.Create -> InlineRow(r.depth, r.isDir, field, { field = it; error = null }, error, trigger) { submit() }
                                is TreeRow.Item -> {
                                    val f = r.file
                                    if (edit is InlineEdit.Rename && edit.file.path == f.path) {
                                        InlineRow(r.depth, f.isDirectory, field, { field = it; error = null }, error, trigger) { submit() }
                                    } else {
                                        val lit = if (vm.selectionMode) {
                                            if (f.isDirectory) folderFullySelected(vm, f) else vm.selected.contains(f.path)
                                        } else {
                                            vm.highlighted == f.path
                                        }
                                        ItemRow(
                                            file = f,
                                            depth = r.depth,
                                            isExpanded = vm.expanded.contains(f.path),
                                            lit = lit,
                                            menuEnabled = !vm.selectionMode && edit == null,
                                            onClick = {
                                                dismissSearch()
                                                if (f.isDirectory) {
                                                    vm.tapFolder(f)
                                                } else {
                                                    vm.tapFile(f)
                                                    if (!vm.selectionMode) onClose()
                                                }
                                            },
                                            onRename = { vm.startRename(f) },
                                            onSelect = if (f.isDirectory) null else ({ vm.enterSelection(f) }),
                                            onDelete = { confirm = f },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // file-name search results: a layer under the search box, up to about half the screen
                if (searchFocused && vm.drawerQuery.isNotBlank()) {
                    val maxH = minOf(maxHeight, (screenHeightDp * 0.5f).dp)
                    SearchResultsPanel(
                        results = vm.drawerResults,
                        busy = vm.drawerBusy,
                        onPick = { hit ->
                            dismissSearch()
                            vm.highlighted = hit.file.path
                            vm.openFile(hit.file)
                            onClose()
                        },
                        modifier = Modifier.align(Alignment.TopCenter).heightIn(max = maxH),
                    )
                }
            }
        }

        // ---- fixed bottom area: Actions and Settings, stacked, above the system navigation bar.
        // Its background differs from the list's, so the two buttons read as their own group.
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
        Column(
            Modifier.fillMaxWidth().background(c.bg).noRippleClick(onBlank)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DrawerButton(Ic.Hammer, "Actions", onActions)
            DrawerButton(Ic.Settings, "Settings", onSettings)
        }
    }

    // ---- dialogs
    confirm?.let { f ->
        ConfirmDialog(
            title = "Are you sure?",
            message = if (f.isDirectory) "The folder \"${f.name}\" and its contents will be moved into the Recycle bin."
            else "\"${f.name}\" will be moved to the Recycle bin.",
            actionText = "Delete",
            destructive = true,
            onCancel = { confirm = null },
            onAction = {
                confirm = null
                scope.launch { vm.trash(f)?.let { vm.toast(it) } }
            },
        )
    }
    if (confirmBulk) {
        val n = vm.selected.size
        ConfirmDialog(
            title = "Are you sure?",
            message = "$n selected file${if (n == 1) "" else "s"} will be moved to the Recycle bin.",
            actionText = "Delete",
            destructive = true,
            onCancel = { confirmBulk = false },
            onAction = {
                confirmBulk = false
                scope.launch { vm.trashSelected()?.let { vm.toast(it) } }
            },
        )
    }
}

// ---------------------------------------------------------------- search box and results

@Composable
private fun DrawerSearchBar(value: String, onValue: (String) -> Unit, onFocusChange: (Boolean) -> Unit) {
    val c = G.c
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    // Tapping the field always brings the keyboard back, even if it was dismissed while the field kept focus.
    LaunchedEffect(interaction) {
        interaction.interactions.collect { if (it is PressInteraction.Release) keyboard?.show() }
    }
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).height(36.dp)
            .clip(shape).background(c.card).border(1.dp, c.outline, shape)
            .noRippleClick { focus.requestFocus(); keyboard?.show() }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(Ic.Search, c.dim, 18.dp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Txt("Search files", color = c.dim, size = 14.sp)
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                interactionSource = interaction,
                textStyle = TextStyle(color = c.text, fontSize = 14.sp),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { onFocusChange(it.isFocused) },
            )
        }
        if (value.isNotEmpty()) IconBtn(Ic.Close, { onValue("") }, tint = c.dim, size = 28.dp)
    }
}

@Composable
private fun SearchResultsPanel(
    results: List<FileHit>,
    busy: Boolean,
    onPick: (FileHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = G.c
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(shape).background(c.surface).border(1.dp, c.outline, shape).noRippleClick { },
    ) {
        if (results.isEmpty()) {
            Txt(
                if (busy) "Searching…" else "No files found",
                Modifier.padding(16.dp), color = c.dim, size = 14.sp,
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(results, key = { it.file.path }) { hit -> ResultRow(hit, onPick) }
            }
        }
    }
}

@Composable
private fun ResultRow(hit: FileHit, onPick: (FileHit) -> Unit) {
    val c = G.c
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp)
            .clip(shape).background(c.card).border(1.dp, c.outline, shape)
            .clickable { onPick(hit) }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileIcon(hit.file.name, 24.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(hit.file.name, size = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Txt(hit.dir, color = c.dim, size = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- rows and menus

@Composable
private fun DrawerButton(ic: Ic, label: String, onClick: () -> Unit) {
    val c = G.c
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(shape).background(c.card)
            .border(1.dp, c.outline, shape).clickable(onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(ic, c.accent, 26.dp)
        Spacer(Modifier.width(16.dp))
        Txt(label, size = 17.sp, weight = FontWeight.SemiBold)
    }
}

/** Places the long-press menu just above where the finger pressed (below the row if there's no room above). */
private class AboveProvider(private val pressX: Int, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - 8).coerceAtLeast(8)
        val x = (anchorBounds.left + pressX - popupContentSize.width / 2).coerceIn(8, maxX)
        var y = anchorBounds.top - popupContentSize.height - gap
        if (y < 8) y = anchorBounds.bottom + gap
        return IntOffset(x, y)
    }
}

@Composable
private fun ContextMenu(items: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    val c = G.c
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.clip(shape).background(c.card).border(1.dp, c.outline, shape).padding(vertical = 4.dp)) {
        for ((label, action) in items) {
            Box(
                Modifier.widthIn(min = 124.dp).clickable { onDismiss(); action() }
                    .padding(horizontal = 18.dp, vertical = 11.dp),
            ) {
                Txt(label, color = if (label == "Delete") c.error else c.text, size = 14.sp)
            }
        }
    }
}

@Composable
private fun ItemRow(
    file: File,
    depth: Int,
    isExpanded: Boolean,
    lit: Boolean,
    menuEnabled: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onSelect: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val c = G.c
    val shape = RoundedCornerShape(12.dp)
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    var pressX by remember(file.path) { mutableStateOf<Float?>(null) }
    val clickNow by rememberUpdatedState(onClick)
    val menuOk by rememberUpdatedState(menuEnabled)

    Box(
        Modifier.fillMaxWidth().padding(start = (8 + depth * 14).dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(46.dp).clip(shape)
                .background(if (lit) c.accent.copy(alpha = 0.20f) else Color.Transparent)
                .border(1.dp, if (lit) c.accent.copy(alpha = 0.75f) else c.outline, shape)
                .pointerInput(file.path) {
                    detectTapGestures(
                        onTap = { clickNow() },
                        onLongPress = { offset -> if (menuOk) pressX = offset.x },
                    )
                }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (file.isDirectory) {
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    Ico(Ic.Chevron, c.dim, 18.dp, Modifier.rotate(if (isExpanded) 90f else 0f))
                }
                Spacer(Modifier.width(6.dp))
                FolderIcon(file.name)
            } else {
                Spacer(Modifier.width(28.dp))
                FileIcon(file.name)
            }
            Spacer(Modifier.width(12.dp))
            Txt(file.name, Modifier.weight(1f), size = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        val px = pressX
        if (px != null) {
            val items = ArrayList<Pair<String, () -> Unit>>()
            items.add("Rename" to onRename)
            if (onSelect != null) items.add("Select" to onSelect)
            items.add("Delete" to onDelete)
            Popup(
                popupPositionProvider = remember(px) { AboveProvider(px.toInt(), gap) },
                onDismissRequest = { pressX = null },
                properties = PopupProperties(focusable = true),
            ) {
                ContextMenu(items) { pressX = null }
            }
        }
    }
}

@Composable
private fun InlineRow(
    depth: Int,
    isDir: Boolean,
    field: TextFieldValue,
    onField: (TextFieldValue) -> Unit,
    error: String?,
    trigger: Int,
    onDone: () -> Unit,
) {
    val c = G.c
    Row(
        // the empty click keeps taps in and around the input from counting as blank taps
        Modifier.fillMaxWidth().noRippleClick { }
            .padding(start = (14 + depth * 14).dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(if (isDir) Ic.Folder else Ic.File, c.dim, 22.dp)
        Spacer(Modifier.width(10.dp))
        GitodeInput(
            value = field, onValueChange = onField, modifier = Modifier.weight(1f),
            error = error, errorTrigger = trigger, autoFocus = true, onDone = onDone, minHeight = 46.dp,
        )
    }
}