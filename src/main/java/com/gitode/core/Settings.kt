package com.gitode.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** A preference that is both persisted and observable by Compose. */
private class StatePref<T>(
    private val sp: SharedPreferences,
    private val key: String,
    def: T,
    read: (SharedPreferences, String, T) -> T,
    private val write: (SharedPreferences.Editor, String, T) -> Unit,
) : ReadWriteProperty<Any?, T> {
    private val state = mutableStateOf(read(sp, key, def))

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        state.value = value
        val editor = sp.edit()
        write(editor, key, value)
        editor.apply()
    }
}

/**
 * All user-visible settings. Reading a property inside a composable
 * re-composes it when the value changes; writing persists it immediately.
 * Secrets (tokens, API keys) are NOT stored here, see [SecureStore].
 */
class Settings(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("gitode_settings", Context.MODE_PRIVATE)

    val secure = SecureStore(context)

    private fun bool(key: String, def: Boolean) =
        StatePref(sp, key, def, { p, k, d -> p.getBoolean(k, d) }, { e, k, v -> e.putBoolean(k, v) })

    private fun int(key: String, def: Int) =
        StatePref(sp, key, def, { p, k, d -> p.getInt(k, d) }, { e, k, v -> e.putInt(k, v) })

    private fun float(key: String, def: Float) =
        StatePref(sp, key, def, { p, k, d -> p.getFloat(k, d) }, { e, k, v -> e.putFloat(k, v) })

    private fun str(key: String, def: String) =
        StatePref(sp, key, def, { p, k, d -> p.getString(k, d) ?: d }, { e, k, v -> e.putString(k, v) })

    // ---- App state
    /** True once the first-launch permission and welcome flow has been completed or dismissed. */
    var onboardingDone by bool("onboarding_done", false)

    // ---- Editor settings
    var fontSize by int("font_size", 14)
    var fontFamily by str("font_family", "jetbrains-mono")
    var syntaxHighlighting by bool("syntax_highlighting", true)
    var smartActions by bool("smart_actions", true)
    var tabWidth by int("tab_width", 4)
    var useSpaces by bool("use_spaces", true)
    var autosave by bool("autosave", false)
    var autosaveDelaySec by int("autosave_delay_sec", 2)
    /** Subtle vertical lines that show indentation levels (a visual decoration only). */
    var indentGuides by bool("indent_guides", true)

    // ---- Appearance settings
    var theme by str("theme", "obsidian")
    /** -1f = coolest (bluish), 0f = neutral, 1f = warmest (reddish/orange). */
    var uiTemperature by float("ui_temperature", 0f)
    var syntaxTheme by str("syntax_theme", "dark-plus")

    // ---- Build / GitHub settings
    /** Normalised account URL, for example https://github.com/username. Empty when not set. */
    var accountUrl by str("account_url", "")
    /** True while a validated GitHub token is stored in [secure]. */
    var githubConnected by bool("github_connected", false)
    var defaultBranch by str("default_branch", "main")
    var notifyBuilds by bool("notify_builds", true)
    /** Custom download folder. Blank means Download/Gitode. */
    var downloadDir by str("download_dir", "")

    fun resolvedDownloadDir(): File {
        val custom = downloadDir.trim()
        return if (custom.isNotEmpty()) File(custom)
        else File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Gitode")
    }

    companion object {
        const val FONT_MIN = 10
        const val FONT_MAX = 28
    }
}

/**
 * Small encrypted key/value store for secrets. Values are encrypted with an AES-256-GCM
 * key that lives in the Android Keystore and never leaves it. Nothing here is ever logged.
 */
class SecureStore(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("gitode_secure", Context.MODE_PRIVATE)
    private val lock = Any()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** Stores [value] under [name]; a null value removes the entry. */
    fun put(name: String, value: String?) {
        synchronized(lock) {
            if (value == null) {
                sp.edit().remove(name).commit()
                return
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val blob = cipher.iv + encrypted
            sp.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
        }
    }

    /** Returns the secret, or null if it is missing or can no longer be decrypted. */
    fun get(name: String): String? {
        synchronized(lock) {
            val stored = sp.getString(name, null) ?: return null
            return try {
                val blob = Base64.decode(stored, Base64.NO_WRAP)
                val iv = blob.copyOfRange(0, IV_BYTES)
                val body = blob.copyOfRange(IV_BYTES, blob.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                String(cipher.doFinal(body), Charsets.UTF_8)
            } catch (e: Exception) {
                // Key was invalidated or the entry is corrupt: drop it so the user can reconnect.
                sp.edit().remove(name).commit()
                null
            }
        }
    }

    fun has(name: String): Boolean = synchronized(lock) { sp.contains(name) }

    /** Moves a secret from one slot to another (used for transactional account switching). */
    fun promote(from: String, to: String): Boolean {
        val value = get(from) ?: return false
        put(to, value)
        put(from, null)
        return true
    }

    companion object {
        private const val ALIAS = "gitode_secrets"
        private const val IV_BYTES = 12

        const val GITHUB_TOKEN = "github_token"
        const val GITHUB_TOKEN_PENDING = "github_token_pending"
        const val GEMINI_KEY = "gemini_key"
    }
}

private val USER_RE = Regex("^[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*$")
private val REPO_RE = Regex("^[A-Za-z0-9._-]+$")

/**
 * Validates a GitHub account URL and returns it in the form https://github.com/username,
 * or null if it is not a plain account URL (a repository URL is rejected on purpose).
 */
fun normalizeAccountUrl(input: String): String? {
    var s = input.trim().trimEnd('/')
    s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
    val prefix = "github.com/"
    if (!s.startsWith(prefix, ignoreCase = true)) return null
    val user = s.substring(prefix.length)
    if (user.isEmpty() || user.length > 39 || user.contains('/')) return null
    if (!USER_RE.matches(user)) return null
    return "https://github.com/$user"
}

/** The username part of a normalised account URL. */
fun accountUser(accountUrl: String): String = accountUrl.trimEnd('/').substringAfterLast('/')

/** Validates a repository name (a trailing ".git" is removed); null if invalid. */
fun normalizeRepoName(input: String): String? {
    val s = input.trim().removeSuffix(".git")
    if (s.isEmpty() || s.length > 100 || s == "." || s == "..") return null
    if (!REPO_RE.matches(s)) return null
    return s
}

/** https://github.com/username/repository.git */
fun repoUrl(accountUrl: String, repoName: String): String =
    "${accountUrl.trimEnd('/')}/$repoName.git"