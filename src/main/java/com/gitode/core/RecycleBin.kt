package com.gitode.core

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Gitode's own recycle bin. Deleted items are copied into app-private storage, together with
 * where they came from, and only then removed from the project. A deleted folder stays one item.
 */
class RecycleBin(context: Context) {
    private val base = File(context.filesDir, "recycle").apply { mkdirs() }

    class Item(
        val id: String,
        val name: String,
        val originalPath: String,
        val isDir: Boolean,
        val deletedAt: Long,
    ) {
        val type: String get() = Fs.typeLabel(name, isDir)
    }

    private fun payload(id: String) = File(File(base, id), "payload")

    /** Moves [file] (a file or a whole folder) into the bin. Throws with a readable message on failure. */
    fun trash(file: File): Item {
        val id = UUID.randomUUID().toString()
        val dir = File(base, id)
        val isDir = file.isDirectory
        try {
            dir.mkdirs()
            Fs.copyTree(file, payload(id))
            val item = Item(id, file.name, file.path, isDir, System.currentTimeMillis())
            val meta = JSONObject()
                .put("name", item.name)
                .put("originalPath", item.originalPath)
                .put("isDir", isDir)
                .put("deletedAt", item.deletedAt)
            File(dir, "meta.json").writeText(meta.toString())
            try {
                Fs.deleteTree(file)
            } catch (e: Exception) {
                // The copy is safe in the bin; the original could not be fully removed.
                throw IOException("Copied to the Recycle bin, but couldn't remove the original: ${e.message}")
            }
            return item
        } catch (e: Exception) {
            if (!File(dir, "meta.json").exists()) runCatching { Fs.deleteTree(dir) }
            throw e
        }
    }

    fun list(): List<Item> {
        val items = ArrayList<Item>()
        base.listFiles()?.forEach { d ->
            val metaFile = File(d, "meta.json")
            if (!metaFile.isFile) return@forEach
            try {
                val m = JSONObject(metaFile.readText())
                items.add(
                    Item(
                        d.name,
                        m.getString("name"),
                        m.getString("originalPath"),
                        m.getBoolean("isDir"),
                        m.optLong("deletedAt", 0L),
                    ),
                )
            } catch (e: Exception) {
                // Skip unreadable entries instead of failing the whole list.
            }
        }
        return items.sortedByDescending { it.deletedAt }
    }

    /** Restores an item to its original location (a free name is chosen if it's taken). */
    fun restore(id: String): File {
        val item = list().firstOrNull { it.id == id } ?: throw IOException("Item not found in the Recycle bin")
        val src = payload(id)
        if (!src.exists()) throw IOException("The stored copy of \"${item.name}\" is missing")
        val target = freeTarget(File(item.originalPath), item.isDir)
        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Couldn't recreate folder ${it.name}") }
        try {
            Fs.copyTree(src, target)
        } catch (e: Exception) {
            if (target.exists()) runCatching { Fs.deleteTree(target) }
            throw IOException("Couldn't restore \"${item.name}\": ${e.message}")
        }
        purge(id)
        return target
    }

    fun purge(id: String) {
        val dir = File(base, id)
        if (dir.exists()) Fs.deleteTree(dir)
    }

    /** Permanently deletes several items; returns how many were removed. */
    fun purgeMany(ids: Collection<String>): Int {
        var removed = 0
        for (id in ids) {
            try {
                purge(id)
                removed++
            } catch (e: Exception) {
                // Keep going; the remaining items are still deleted.
            }
        }
        return removed
    }

    private fun freeTarget(target: File, isDir: Boolean): File {
        if (!target.exists()) return target
        val name = target.name
        val dot = name.lastIndexOf('.')
        val stem = if (!isDir && dot > 0) name.substring(0, dot) else name
        val ext = if (!isDir && dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = File(target.parentFile, "$stem ($n)$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }
}