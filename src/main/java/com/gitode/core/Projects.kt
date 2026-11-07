package com.gitode.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Per-project-folder persistence: repository name, active file and unsaved editor buffers.
 * Every folder gets its own directory, so one project's state can never leak into another.
 */
class Projects(context: Context) {
    private val base = File(context.filesDir, "projects").apply { mkdirs() }
    private val lastFile = File(context.filesDir, "last_project.txt")
    private val lock = Any()

    class StoredBuffer(val path: String, val text: String, val crlf: Boolean)
    class Info(val root: File, val repo: String?)

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun keyOf(root: File): String =
        try { root.canonicalPath } catch (e: Exception) { root.absolutePath }

    private fun dirOf(root: File) = File(base, sha1(keyOf(root)).take(16))
    private fun bufFile(dir: File, path: String) = File(dir, "buf-" + sha1(path).take(16) + ".txt")

    private fun atomicWrite(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    private fun readMeta(root: File): JSONObject {
        val f = File(dirOf(root), "project.json")
        return try { JSONObject(f.readText()) } catch (e: Exception) { JSONObject() }
    }

    private fun writeMeta(root: File, meta: JSONObject) {
        meta.put("path", root.path)
        atomicWrite(File(dirOf(root), "project.json"), meta.toString())
    }

    // ---------------------------------------------------------------- repository name

    fun repoName(root: File): String? = synchronized(lock) {
        readMeta(root).optString("repo", "").ifBlank { null }
    }

    fun setRepoName(root: File, name: String) {
        synchronized(lock) {
            val meta = readMeta(root)
            meta.put("repo", name)
            writeMeta(root, meta)
        }
    }

    // ---------------------------------------------------------------- active file

    fun active(root: File): String? = synchronized(lock) {
        readMeta(root).optString("active", "").ifBlank { null }
    }

    fun setActive(root: File, path: String?) {
        synchronized(lock) {
            val meta = readMeta(root)
            if (path == null) meta.remove("active") else meta.put("active", path)
            writeMeta(root, meta)
        }
    }

    // ---------------------------------------------------------------- unsaved buffers

    fun buffers(root: File): List<StoredBuffer> = synchronized(lock) {
        val result = ArrayList<StoredBuffer>()
        val arr = readMeta(root).optJSONArray("buffers")
        if (arr != null) {
            val dir = dirOf(root)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val path = o.optString("path", "")
                if (path.isEmpty()) continue
                val f = bufFile(dir, path)
                if (!f.isFile) continue
                val text = try { f.readText() } catch (e: Exception) { continue }
                result.add(StoredBuffer(path, text, o.optBoolean("crlf", false)))
            }
        }
        result
    }

    fun saveBuffer(root: File, path: String, text: String, crlf: Boolean) {
        synchronized(lock) {
            val dir = dirOf(root)
            atomicWrite(bufFile(dir, path), text)
            val meta = readMeta(root)
            val old = meta.optJSONArray("buffers") ?: JSONArray()
            val arr = JSONArray()
            var found = false
            for (i in 0 until old.length()) {
                val o = old.optJSONObject(i) ?: continue
                if (o.optString("path") == path) {
                    found = true
                    arr.put(JSONObject().put("path", path).put("crlf", crlf))
                } else {
                    arr.put(o)
                }
            }
            if (!found) arr.put(JSONObject().put("path", path).put("crlf", crlf))
            meta.put("buffers", arr)
            writeMeta(root, meta)
        }
    }

    fun dropBuffer(root: File, path: String) {
        synchronized(lock) {
            val dir = dirOf(root)
            bufFile(dir, path).delete()
            val meta = readMeta(root)
            val old = meta.optJSONArray("buffers") ?: return
            val arr = JSONArray()
            for (i in 0 until old.length()) {
                val o = old.optJSONObject(i) ?: continue
                if (o.optString("path") != path) arr.put(o)
            }
            meta.put("buffers", arr)
            writeMeta(root, meta)
        }
    }

    /** After a rename: moves stored buffers and the active file from [oldPath] to [newPath]. */
    fun remapPaths(root: File, oldPath: String, newPath: String) {
        val stored = buffers(root)
        for (b in stored) {
            val mapped = when {
                b.path == oldPath -> newPath
                b.path.startsWith("$oldPath/") -> newPath + b.path.substring(oldPath.length)
                else -> null
            } ?: continue
            dropBuffer(root, b.path)
            saveBuffer(root, mapped, b.text, b.crlf)
        }
        val act = active(root)
        if (act != null) {
            val mapped = when {
                act == oldPath -> newPath
                act.startsWith("$oldPath/") -> newPath + act.substring(oldPath.length)
                else -> null
            }
            if (mapped != null) setActive(root, mapped)
        }
    }

    // ---------------------------------------------------------------- registry

    fun last(): File? = synchronized(lock) {
        try {
            val s = lastFile.readText().trim()
            if (s.isEmpty()) null else File(s)
        } catch (e: Exception) {
            null
        }
    }

    fun setLast(root: File?) {
        synchronized(lock) {
            if (root == null) lastFile.delete() else lastFile.writeText(root.path)
        }
    }

    /** Every known project folder that still exists (used for app-wide GitHub history). */
    fun all(): List<Info> = synchronized(lock) {
        val result = ArrayList<Info>()
        val seen = HashSet<String>()
        base.listFiles()?.forEach { d ->
            val meta = try { JSONObject(File(d, "project.json").readText()) } catch (e: Exception) { null }
            val path = meta?.optString("path", "").orEmpty()
            if (path.isNotEmpty() && File(path).isDirectory && seen.add(path)) {
                result.add(Info(File(path), meta?.optString("repo", "")?.ifBlank { null }))
            }
        }
        result
    }
}