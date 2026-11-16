package com.savetube.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/** Downloads the yt-dlp plugin APK from the project's latest release and hands it to the system installer. */
object PluginInstaller {
    private const val URL = "https://github.com/MrKakaKat/SaveApps/releases/download/latest/SavetubePlugin.apk"

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Android asks the user once to allow installs from this app. */
    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    suspend fun download(context: Context, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "plugin").apply { mkdirs() }
        val apk = File(dir, "SavetubePlugin.apk")
        val req = Request.Builder().url(URL).header("User-Agent", NpDownloader.UA).build()
        NpDownloader.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("HTTP ${resp.code}")
            val total = body.contentLength()
            var done = 0L
            val buf = ByteArray(64 * 1024)
            body.byteStream().use { input ->
                apk.outputStream().use { out ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
        apk
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
