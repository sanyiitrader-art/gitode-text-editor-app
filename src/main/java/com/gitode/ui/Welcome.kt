package com.gitode.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gitode.core.normalizeAccountUrl

@Composable
private fun PermissionCard(
    title: String,
    body: String,
    granted: Boolean,
    actionText: String,
    onAction: () -> Unit,
) {
    val c = G.c
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.card).border(1.dp, c.outline, shape).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(title, Modifier.weight(1f), size = 17.sp, weight = FontWeight.SemiBold)
            if (granted) Ico(Ic.Check, c.accent, 22.dp)
        }
        Txt(body, Modifier.padding(top = 8.dp), color = c.dim, size = 14.sp, lineHeight = 21.sp)
        if (granted) {
            Txt("Granted", Modifier.padding(top = 12.dp), color = c.accent, size = 13.sp)
        } else {
            Spacer(Modifier.height(16.dp))
            GlowButton(actionText, onAction, Modifier.fillMaxWidth())
        }
    }
}

/** First-launch permissions: all-files access (required) and notifications (optional). */
@Composable
fun PermissionsScreen(
    storageGranted: Boolean,
    notificationsApply: Boolean,
    notificationsGranted: Boolean,
    legacyStorage: Boolean,
    onStorage: () -> Unit,
    onNotifications: () -> Unit,
    onContinue: () -> Unit,
) {
    val c = G.c
    Column(Modifier.screenBase().padding(horizontal = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(40.dp))
            Txt("Before we start", size = 28.sp, weight = FontWeight.Bold)
            Txt(
                "Gitode needs a couple of permissions to work properly.",
                Modifier.padding(top = 8.dp, bottom = 28.dp), color = c.dim, lineHeight = 22.sp,
            )
            PermissionCard(
                title = "Access to your files",
                body = if (legacyStorage) {
                    "Gitode edits real files and folders on your phone and keeps Git repositories inside them, so it needs storage access."
                } else {
                    "Gitode edits real files and folders on your phone and keeps Git repositories inside them, so it needs all-files access. Android opens a settings screen where you switch it on for Gitode, then come back here."
                },
                granted = storageGranted,
                actionText = if (legacyStorage) "Allow" else "Open settings",
                onAction = onStorage,
            )
            if (notificationsApply) {
                Spacer(Modifier.height(16.dp))
                PermissionCard(
                    title = "Notifications",
                    body = "Used to tell you when a build finishes or fails and when a download completes. This one is optional.",
                    granted = notificationsGranted,
                    actionText = "Allow",
                    onAction = onNotifications,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
            GlowButton("Continue", onContinue, Modifier.fillMaxWidth(), enabled = storageGranted)
        }
    }
}

/** Shown once after the permissions. The X opens the editor in local/offline mode. */
@Composable
fun WelcomeScreen(onConnect: () -> Unit, onDismiss: () -> Unit) {
    val c = G.c
    val context = LocalContext.current
    val logo = remember {
        try {
            context.assets.open("icon.png").use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        } catch (e: Exception) {
            null
        }
    }
    Box(Modifier.screenBase()) {
        IconBtn(Ic.Close, onDismiss, Modifier.align(Alignment.TopEnd).padding(8.dp))
        Column(
            Modifier.fillMaxSize().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (logo != null) {
                Image(bitmap = logo, contentDescription = null, modifier = Modifier.size(120.dp))
            } else {
                Ico(Ic.Code, c.accent, 72.dp)
            }
            Spacer(Modifier.height(28.dp))
            Txt("Gitode", size = 34.sp, weight = FontWeight.Bold)
            Txt(
                "A calm code editor with GitHub built in. Edit files on your phone, push your work and follow your builds.",
                Modifier.padding(top = 12.dp), color = c.dim, textAlign = TextAlign.Center, lineHeight = 23.sp,
            )
            Spacer(Modifier.height(44.dp))
            GlowButton("Connect to GitHub", onConnect, icon = Ic.GitBranch)
        }
    }
}

/** Asks for the GitHub account URL (used after connecting and in Build settings). */
@Composable
fun AccountUrlDialog(
    initial: String,
    onCancel: () -> Unit,
    onSubmit: (String) -> Unit,
    title: String = "GitHub account",
    actionText: String = "Continue",
) {
    val base = initial.ifEmpty { "https://github.com/" }
    var value by remember { mutableStateOf(TextFieldValue(base, TextRange(base.length))) }
    var error by remember { mutableStateOf<String?>(null) }
    var trigger by remember { mutableIntStateOf(0) }

    fun submit() {
        val normalized = normalizeAccountUrl(value.text)
        if (normalized == null) {
            error = "Enter your account URL like https://github.com/username (no repository name)"
            trigger++
        } else {
            onSubmit(normalized)
        }
    }

    FormDialog(title, actionText, onCancel, onAction = { submit() }) {
        GitodeInput(
            value = value,
            onValueChange = { value = it; error = null },
            label = "Account URL",
            error = error,
            errorTrigger = trigger,
            autoFocus = true,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
            onDone = { submit() },
        )
    }
}