package com.gitode.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gitode.core.RecycleBin
import com.gitode.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Page { Main, Editor, Recycle }

@Composable
fun SettingsScreens(vm: MainViewModel, onClose: () -> Unit) {
    var page by remember { mutableStateOf(Page.Main) }
    BackHandler { if (page == Page.Main) onClose() else page = Page.Main }

    Column(Modifier.screenBase()) {
        when (page) {
            Page.Main -> MainPage(onClose, onEditor = { page = Page.Editor }, onRecycle = { page = Page.Recycle })
            Page.Editor -> EditorPage(vm.settings) { page = Page.Main }
            Page.Recycle -> RecyclePage(vm) { page = Page.Main }
        }
    }
}

// ---------------------------------------------------------------- main

@Composable
private fun MainPage(onBack: () -> Unit, onEditor: () -> Unit, onRecycle: () -> Unit) {
    ScreenBar("Settings", onBack)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingCard(Ic.Code, "Editor", onEditor, subtitle = "Font, highlighting and smart editing")
        SettingCard(Ic.Trash, "Recycle bin", onRecycle, subtitle = "Restore or permanently delete items")
        Spacer(Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------- editor settings

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = G.c
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.clip(shape).background(if (selected) c.accent else c.surface)
            .border(1.dp, if (selected) c.accent else c.outline, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, color = if (selected) c.onAccent else c.text, size = 14.sp, weight = FontWeight.Medium)
    }
}

@Composable
private fun EditorPage(s: Settings, onBack: () -> Unit) {
    val c = G.c
    val context = LocalContext.current
    var fontMenu by remember { mutableStateOf(false) }
    val fonts = remember { availableFonts(context) }

    ScreenBar("Editor Settings", onBack)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingCard(
            icon = Ic.Code,
            title = "Font size",
            subtitle = "${s.fontSize} sp",
            onClick = {},
            trailing = {
                GitodeSlider(
                    value = s.fontSize.toFloat(),
                    onChange = { s.fontSize = it.toInt() },
                    range = Settings.FONT_MIN.toFloat()..Settings.FONT_MAX.toFloat(),
                    modifier = Modifier.width(150.dp),
                    steps = Settings.FONT_MAX - Settings.FONT_MIN - 1,
                )
            },
        )
        SettingCard(
            icon = Ic.File,
            title = "Font type",
            onClick = { fontMenu = true },
            trailing = {
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt(fontName(s.fontFamily), color = c.dim, size = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(112.dp))
                        Ico(Ic.Chevron, c.dim, 18.dp, Modifier.rotate(90f))
                    }
                    DropdownMenu(
                        expanded = fontMenu,
                        onDismissRequest = { fontMenu = false },
                        modifier = Modifier.background(c.card, RoundedCornerShape(14.dp)),
                    ) {
                        for (f in fonts) {
                            DropdownMenuItem(
                                text = { Txt(f.name, color = if (f.id == s.fontFamily) c.accent else c.text) },
                                onClick = { s.fontFamily = f.id; fontMenu = false },
                            )
                        }
                    }
                }
            },
        )
        SettingCard(
            icon = Ic.Braces,
            title = "Syntax highlighting",
            subtitle = "Colour code in recognised languages",
            onClick = { s.syntaxHighlighting = !s.syntaxHighlighting },
            trailing = { GitodeSwitch(s.syntaxHighlighting) { s.syntaxHighlighting = it } },
        )
        SettingCard(
            icon = Ic.Menu,
            title = "Smart indentation",
            subtitle = "Enter keeps and adds indentation, closing brackets line up, Backspace removes a level",
            onClick = { s.smartIndent = !s.smartIndent },
            trailing = { GitodeSwitch(s.smartIndent) { s.smartIndent = it } },
        )
        SettingCard(
            icon = Ic.Star,
            title = "Smart actions",
            subtitle = "Bracket and quote pairing, deleting a pair together",
            onClick = { s.smartActions = !s.smartActions },
            trailing = { GitodeSwitch(s.smartActions) { s.smartActions = it } },
        )
        SettingCard(
            icon = Ic.Layers,
            title = "Indentation guides",
            subtitle = "Faint vertical lines at each indentation level",
            onClick = { s.indentGuides = !s.indentGuides },
            trailing = { GitodeSwitch(s.indentGuides) { s.indentGuides = it } },
        )
        SettingCard(
            icon = Ic.Layout,
            title = "Indentation width",
            subtitle = "Columns per level (also how wide a tab is drawn)",
            onClick = {},
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (w in intArrayOf(2, 4, 8)) Chip("$w", s.tabWidth == w) { s.tabWidth = w }
                }
            },
        )
        SettingCard(
            icon = Ic.Code,
            title = "Indentation type",
            subtitle = "What Tab and new indentation insert",
            onClick = {},
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Spaces", s.useSpaces) { s.useSpaces = true }
                    Chip("Tabs", !s.useSpaces) { s.useSpaces = false }
                }
            },
        )
        Spacer(Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------- recycle bin

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecyclePage(vm: MainViewModel, onBack: () -> Unit) {
    val c = G.c
    val bin: RecycleBin = vm.recycleBin
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<RecycleBin.Item>() }
    val selected = remember { mutableStateSetOf<String>() }
    var loaded by remember { mutableStateOf(false) }

    suspend fun reload() {
        val list = withContext(Dispatchers.IO) { bin.list() }
        items.clear()
        items.addAll(list)
        selected.retainAll(list.map { it.id }.toSet())
        loaded = true
    }
    LaunchedEffect(Unit) { reload() }

    val allSelected = items.isNotEmpty() && selected.size == items.size
    val selecting = selected.isNotEmpty()

    ScreenBar("Recycle bin", onBack) {
        GitodeCheckbox(allSelected, onClick = {
            if (allSelected) selected.clear() else selected.addAll(items.map { it.id })
        })
        IconBtn(Ic.TrashX, onClick = {
            val ids = selected.toList()
            scope.launch {
                withContext(Dispatchers.IO) { bin.purgeMany(ids) }
                reload()
            }
        }, tint = c.error, enabled = selecting)
    }

    if (loaded && items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Txt("Recycle bin is empty", color = c.dim, size = 16.sp)
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        items(items, key = { it.id }) { item ->
            val isSel = selected.contains(item.id)
            val shape = RoundedCornerShape(16.dp)
            Row(
                Modifier.fillMaxWidth().clip(shape)
                    .background(if (isSel) c.accent.copy(alpha = 0.20f) else c.card)
                    .border(1.dp, if (isSel) c.accent.copy(alpha = 0.6f) else c.outline, shape)
                    .combinedClickable(
                        onClick = {
                            when {
                                selecting -> if (isSel) selected.remove(item.id) else selected.add(item.id)
                                else -> scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { bin.restore(item.id) }
                                        vm.afterRecycleRestore()
                                    } catch (e: Exception) {
                                        vm.toast(e.message ?: "Couldn't restore \"${item.name}\"")
                                    }
                                    reload()
                                }
                            }
                        },
                        onLongClick = { if (isSel) selected.remove(item.id) else selected.add(item.id) },
                    )
                    .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.isDir) FolderIcon(item.name, 26.dp) else FileIcon(item.name, 26.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Txt(item.name, size = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!item.isDir) Txt(item.type, Modifier.padding(top = 2.dp), color = c.dim, size = 13.sp)
                }
                if (!selecting) {
                    IconBtn(Ic.TrashX, onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { bin.purge(item.id) } }
                            reload()
                        }
                    }, tint = c.error)
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}