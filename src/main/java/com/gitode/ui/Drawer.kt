package com.gitode.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplorerDrawer(
    vm: MainViewModel,
    onClose: () -> Unit,
    onActions: () -> Unit,
    onSettings: () -> Unit,
) {
    val c = G.c
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val root = vm.root
    val edit = vm.inlineEdit

    var field by remember(edit) { mutableStateOf(startValue(edit)) }
    var error by remember(edit) { mutableStateOf<String?>(null) }
    var trigger by remember(edit) { mutableIntStateOf(0) }
    var createMenu by remember { mutableStateOf(false) }
    var itemMenu by remember { mutableStateOf<File?>(null) }
    var confirm by remember { mutableStateOf<File?>(null) }
    var confirmBulk by remember { mutableStateOf(false) }

    fun submit() {
        scope.launch {
            val err = vm.commitInline(field.text)
            if (err != null) {
                error = err
                trigger++
            } else {
                keyboard?.hide()
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

    Column(
        Modifier.fillMaxSize().background(c.surface).statusBarsPadding().imePadding(),
    ) {
        // ---- drawer top bar
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(
                if (root != null) vm.rootName else "Explorer", Modifier.weight(1f),
                size = 18.sp, weight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            when {
                edit != null -> {
                    TextAction("Cancel", { vm.cancelInline() })
                    TextAction("Save", { submit() }, color = c.accent, bold = true)
                }
                vm.selectionMode -> TextAction(
                    "Delete ${vm.selected.size}", { confirmBulk = true },
                    color = c.error, enabled = vm.selected.isNotEmpty(), bold = true,
                )
                else -> TextAction("Create", { createMenu = true }, color = c.accent, enabled = root != null, bold = true)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))

        // ---- explorer listing
        if (root == null) {
            Box(Modifier.weight(1f).fillMaxWidth().noRippleClick { vm.tapBlank() }, contentAlignment = Alignment.Center) {
                Txt("No folder open", color = c.dim)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().noRippleClick { vm.tapBlank() },
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
                                    onClick = {
                                        if (f.isDirectory) {
                                            vm.tapFolder(f)
                                        } else {
                                            vm.tapFile(f)
                                            if (!vm.selectionMode) onClose()
                                        }
                                    },
                                    onLongClick = { if (!vm.selectionMode && edit == null) itemMenu = f },
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- fixed bottom bar: Actions and Settings
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(onClick = onActions)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ico(Ic.Hammer, c.accent, 24.dp)
                Spacer(Modifier.width(14.dp))
                Txt("Actions", size = 16.sp, weight = FontWeight.SemiBold)
            }
            IconBtn(Ic.Settings, onSettings)
        }
    }

    // ---- menus and dialogs
    if (createMenu) {
        ActionMenuDialog(
            options = listOf("File" to { vm.startCreate(false) }, "Folder" to { vm.startCreate(true) }),
            onDismiss = { createMenu = false },
        )
    }
    itemMenu?.let { f ->
        val options = ArrayList<Pair<String, () -> Unit>>()
        options.add("Rename" to { vm.startRename(f) })
        if (!f.isDirectory) options.add("Select" to { vm.enterSelection(f) })
        options.add("Delete" to { confirm = f })
        ActionMenuDialog(options = options, onDismiss = { itemMenu = null })
    }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(
    file: File,
    depth: Int,
    isExpanded: Boolean,
    lit: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = G.c
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (lit) c.accent.copy(alpha = 0.20f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = (4 + depth * 18).dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (file.isDirectory) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Ico(Ic.Chevron, c.dim, 18.dp, Modifier.rotate(if (isExpanded) 90f else 0f))
            }
            Spacer(Modifier.width(8.dp))
            FolderIcon(file.name)
        } else {
            Spacer(Modifier.width(32.dp))
            FileIcon(file.name)
        }
        Spacer(Modifier.width(12.dp))
        Txt(file.name, Modifier.weight(1f), size = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Modifier.fillMaxWidth().padding(start = (10 + depth * 18).dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(if (isDir) 4.dp else 0.dp))
        Ico(if (isDir) Ic.Folder else Ic.File, c.dim, 22.dp)
        Spacer(Modifier.width(10.dp))
        GitodeInput(
            value = field, onValueChange = onField, modifier = Modifier.weight(1f),
            error = error, errorTrigger = trigger, autoFocus = true, onDone = onDone, minHeight = 48.dp,
        )
    }
}

@Composable
private fun ActionMenuDialog(options: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    val c = G.c
    DialogShell(onDismiss) {
        for ((label, action) in options) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable { onDismiss(); action() }.padding(horizontal = 12.dp, vertical = 15.dp),
            ) {
                Txt(label, color = if (label == "Delete") c.error else c.text, size = 16.sp)
            }
        }
    }
}