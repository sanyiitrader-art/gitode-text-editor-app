package com.gitode.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SearchScreen(vm: MainViewModel, onClose: () -> Unit, onPick: (SearchHit) -> Unit) {
    val c = G.c
    val view = LocalView.current
    var value by remember { mutableStateOf(TextFieldValue(vm.searchQuery, TextRange(vm.searchQuery.length))) }
    BackHandler { onClose() }

    // A tap on empty space hides the keyboard.
    Column(Modifier.screenBase().imePadding().noRippleClick { hideKeyboard(view) }) {
        Row(
            // the empty click keeps taps next to the input from counting as blank taps
            Modifier.fillMaxWidth().noRippleClick { }.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(Ic.Close, onClose)
            GitodeInput(
                value = value,
                onValueChange = { value = it; vm.search(it.text) },
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                placeholder = "Search in file",
                autoFocus = vm.searchQuery.isEmpty(),
                imeAction = ImeAction.Search,
                onDone = { hideKeyboard(view) },
            )
        }
        when {
            vm.searchQuery.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Txt("Type to search", color = c.dim, size = 16.sp)
            }
            vm.searchRan && vm.searchHits.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Txt("Nothing match", color = c.dim, size = 16.sp)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                itemsIndexed(vm.searchHits) { _, hit -> ResultCard(hit) { onPick(hit) } }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun ResultCard(hit: SearchHit, onClick: () -> Unit) {
    val c = G.c
    val shape = RoundedCornerShape(16.dp)
    val styled = remember(hit) {
        buildAnnotatedString {
            append(hit.preview)
            val s = hit.previewStart.coerceIn(0, hit.preview.length)
            val e = hit.previewEnd.coerceIn(s, hit.preview.length)
            addStyle(
                SpanStyle(background = c.matchHighlight, color = c.text, fontWeight = FontWeight.SemiBold),
                s, e,
            )
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.card).border(1.dp, c.outline, shape).clickable(onClick = onClick),
    ) {
        // thin header: only as tall as its text needs
        Box(
            Modifier.fillMaxWidth().background(c.accent.copy(alpha = 0.16f))
                .padding(horizontal = 16.dp, vertical = 3.dp),
        ) {
            Txt("Line ${hit.line + 1}", color = c.accent, size = 12.sp, weight = FontWeight.SemiBold)
        }
        Text(
            text = styled,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            color = c.text,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 21.sp,
        )
    }
}