package com.savetok.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

object Downloader {
    /** Saves into Movies/<app> or Pictures/<app> so files show up in the gallery. */
    fun enqueue(context: Context, post: Post, index: Int, item: MediaItem, variant: MediaVariant) {
        val isPhoto = item.type == MediaType.PHOTO
        val ext = if (isPhoto) "jpg" else "mp4"
        val dir = if (isPhoto) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES
        val name = "savetok_${post.authorHandle}_${post.id}_${index + 1}_${variant.height}.$ext"
        val request = DownloadManager.Request(Uri.parse(variant.url))
            .setTitle(name)
            .setDescription(APP_FOLDER)
            .setMimeType(if (isPhoto) "image/jpeg" else "video/mp4")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(dir, "$APP_FOLDER/$name")
        context.getSystemService(DownloadManager::class.java).enqueue(request)
    }
}
