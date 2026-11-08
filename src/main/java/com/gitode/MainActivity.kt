package com.gitode

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.provider.Settings as AndroidSettings
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.gitode.core.Fs
import com.gitode.core.Projects
import com.gitode.core.RecycleBin
import com.gitode.core.Settings
import com.gitode.ui.EditorScreen
import com.gitode.ui.G
import com.gitode.ui.GitodeTheme
import com.gitode.ui.MainViewModel
import com.gitode.ui.PermissionsScreen
import com.gitode.ui.SearchScreen
import com.gitode.ui.SettingsScreens
import com.gitode.ui.Txt
import com.gitode.ui.WelcomeScreen
import com.gitode.ui.noRippleClick
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileNotFoundException

class GitodeApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var projects: Projects
        private set
    lateinit var recycleBin: RecycleBin
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        projects = Projects(this)
        recycleBin = RecycleBin(this)
    }
}

private enum class Screen { Editor, Search, Project, Settings }

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private var storageGranted by mutableStateOf(false)
    private var notifGranted by mutableStateOf(true)
    private var notifAsked by mutableStateOf(false)
    private var permsDone by mutableStateOf(false)

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.handleFolderUri(uri)
    }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.handleFileUri(uri)
    }
    private val legacyStoragePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPermissions()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissions()
        setContent {
            val s = vm.settings
            GitodeTheme(s.theme, s.uiTemperature) {
                val colors = G.c
                SideEffect { applySystemBars(colors.isDark) }
                AppContent()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    override fun onStop() {
        super.onStop()
        vm.onBackgrounded()
    }

    private fun applySystemBars(dark: Boolean) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    // ================================================================ permissions

    private fun refreshPermissions() {
        storageGranted = if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
        notifGranted = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
                )
            } catch (e: Exception) {
                try {
                    startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e2: Exception) {
                    vm.toast("Couldn't open the settings screen. Open Settings > Apps > Gitode > Permissions.")
                }
            }
        } else {
            legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (notifAsked && !notifGranted) {
            // Android won't show the prompt again; send the user to the app's notification settings.
            try {
                startActivity(
                    Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName),
                )
            } catch (e: Exception) {
                vm.toast("Couldn't open notification settings.")
            }
        } else {
            notifAsked = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ================================================================ open with

    private fun openWithChooser(file: File) {
        val uri = Uri.Builder().scheme("content").authority(FileShareProvider.AUTHORITY).path(file.absolutePath).build()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(Fs.ext(file.name)) ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        view.clipData = ClipData.newRawUri("", uri)
        try {
            startActivity(Intent.createChooser(view, "Open with"))
        } catch (e: Exception) {
            vm.toast("No app can open ${file.name}")
        }
    }

    // ================================================================ UI

    @Composable
    private fun AppContent() {
        val s = vm.settings
        val showPermissions = !storageGranted || (!s.onboardingDone && !permsDone)
        Box(Modifier.fillMaxSize().background(G.c.bg)) {
            when {
                showPermissions -> PermissionsScreen(
                    storageGranted = storageGranted,
                    notificationsApply = Build.VERSION.SDK_INT >= 33,
                    notificationsGranted = notifGranted,
                    legacyStorage = Build.VERSION.SDK_INT < 30,
                    onStorage = { requestStorage() },
                    onNotifications = { requestNotifications() },
                    onContinue = { permsDone = true },
                )
                !s.onboardingDone -> WelcomeScreen(
                    onConnect = { vm.toast("Connecting to GitHub arrives in the next update. Tap X to use Gitode locally.") },
                    onDismiss = { s.onboardingDone = true },
                )
                else -> MainContent()
            }
            NoticeHost()
        }
    }

    @Composable
    private fun MainContent() {
        var screen by remember { mutableStateOf(Screen.Editor) }
        val keyboard = LocalSoftwareKeyboardController.current

        LaunchedEffect(Unit) { vm.restoreSession() }
        val toOpen = vm.openWith
        LaunchedEffect(toOpen) {
            if (toOpen != null) {
                openWithChooser(toOpen)
                vm.openWith = null
            }
        }

        Box(Modifier.fillMaxSize()) {
            EditorScreen(
                vm = vm,
                onSearch = { vm.flushActive(); screen = Screen.Search },
                onFindInProject = { vm.flushActive(); screen = Screen.Project },
                onSettings = { keyboard?.hide(); screen = Screen.Settings },
                onActions = { vm.toast("Actions arrive in the next update") },
                onPickFolder = { folderPicker.launch(null) },
                onPickFile = { filePicker.launch(arrayOf("*/*")) },
                onConnect = { vm.toast("Connecting to GitHub arrives in the next update") },
                onPush = { vm.toast("Push arrives in the next update") },
            )
            if (screen == Screen.Search) {
                Overlay {
                    SearchScreen(
                        vm = vm,
                        project = false,
                        onClose = { screen = Screen.Editor },
                        onPick = { hit -> vm.jumpToHit(hit); screen = Screen.Editor },
                    )
                }
            }
            if (screen == Screen.Project) {
                Overlay {
                    SearchScreen(
                        vm = vm,
                        project = true,
                        onClose = { screen = Screen.Editor },
                        onPick = { hit -> vm.jumpToHit(hit); screen = Screen.Editor },
                    )
                }
            }
            if (screen == Screen.Settings) {
                Overlay { SettingsScreens(vm) { screen = Screen.Editor } }
            }
        }
    }

    /**
     * Full-screen layer that covers the editor underneath. The empty click handler only stops
     * touches reaching the screen below. It never consumes touch movement, which is what made
     * Settings ignore most taps before (a parent that consumes every pointer event cancels the
     * taps of the buttons inside it).
     */
    @Composable
    private fun Overlay(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(G.c.bg).noRippleClick { }) { content() }
    }

    @Composable
    private fun NoticeHost() {
        val msg = vm.notice
        LaunchedEffect(msg) {
            if (msg != null) {
                delay(3600)
                if (vm.notice == msg) vm.notice = null
            }
        }
        if (msg != null) {
            val c = G.c
            Box(
                Modifier.fillMaxSize().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 84.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(c.card)
                        .border(1.dp, c.outline, RoundedCornerShape(16.dp))
                        .noRippleClick { vm.notice = null }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    Txt(msg, size = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
                }
            }
        }
    }
}

/**
 * Serves one file to another app for "Open with". It is not exported: only apps that
 * receive an explicit read grant through Gitode's chooser intent can open a file.
 */
class FileShareProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.gitode.files"
    }

    private fun fileFor(uri: Uri): File? {
        val path = uri.path ?: return null
        val f = try { File(path).canonicalFile } catch (e: Exception) { return null }
        val external = try { Environment.getExternalStorageDirectory().canonicalPath } catch (e: Exception) { "/storage/emulated/0" }
        val allowed = f.path.startsWith(external) || f.path.startsWith("/storage/")
        return if (allowed && f.isFile) f else null
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? {
        val f = fileFor(uri) ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(Fs.ext(f.name)) ?: "application/octet-stream"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val f = fileFor(uri) ?: return null
        val cols = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).map { it }.toTypedArray()
        val cursor = MatrixCursor(cols)
        cursor.addRow(
            cols.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> f.name
                    OpenableColumns.SIZE -> f.length()
                    else -> null
                }
            },
        )
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileFor(uri) ?: throw FileNotFoundException("Not available")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}