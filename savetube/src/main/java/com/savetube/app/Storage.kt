package com.savetube.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Where finished files go: Music/Savetube and Movies/Savetube by default, or a folder the user
 * picked with the system folder picker.
 */
class Storage(private val context: Context) {
    private val prefs = context.getSharedPreferences("storage", Context.MODE_PRIVATE)

    var folder: Uri?
        get() = prefs.getString("tree", null)?.let(Uri::parse)
        set(value) {
            folder?.let { old ->
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
            }
            value?.let {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            prefs.edit().putString("tree", value?.toString()).apply()
        }

    fun folderLabel(): String = folder?.let { DocumentFile.fromTreeUri(context, it)?.name } ?: tr("Музыка/Savetube и Movies/Savetube", "Music/Savetube and Movies/Savetube")

    fun save(file: File, name: String, kind: Kind): Uri {
        val mime = if (kind == Kind.AUDIO) "audio/mp4" else "video/mp4"
        val tree = folder
        val uri = if (tree != null) {
            val dir = DocumentFile.fromTreeUri(context, tree) ?: throw IllegalStateException(tr("Папка недоступна", "Folder is unavailable"))
            dir.createFile(mime, name)?.uri ?: throw IllegalStateException(tr("Не удалось создать файл", "Could not create the file"))
        } else {
            val collection = if (kind == Kind.AUDIO) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (kind == Kind.AUDIO) "Music/Savetube" else "Movies/Savetube")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            context.contentResolver.insert(collection, values) ?: throw IllegalStateException(tr("Не удалось сохранить файл", "Could not save the file"))
        }
        write(uri, file)
        if (tree == null) {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        return uri
    }

    /** Overwrites [uri] with [file] (used after editing tags). */
    fun write(uri: Uri, file: File) {
        context.contentResolver.openOutputStream(uri, "wt")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    fun copyToCache(uri: Uri, name: String): File {
        val f = File(context.cacheDir, name)
        context.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    /** Renames the file to match new tags; returns the (possibly new) uri. */
    fun rename(uri: Uri, name: String): Uri = runCatching {
        if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.renameDocument(context.contentResolver, uri, name) ?: uri
        } else {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }, null, null)
            uri
        }
    }.getOrDefault(uri)

    fun delete(uri: Uri) {
        runCatching {
            if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(context.contentResolver, uri)
            else context.contentResolver.delete(uri, null, null)
        }
    }

    companion object {
        fun fileName(base: String, ext: String): String =
            base.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").trim().take(120).ifEmpty { "savetube" } + "." + ext
    }
}
