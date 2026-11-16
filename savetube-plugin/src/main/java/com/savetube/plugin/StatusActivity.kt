package com.savetube.plugin

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.yausername.youtubedl_android.YoutubeDL
import kotlin.concurrent.thread

/** A small screen so the plugin is not a mystery icon: shows the yt-dlp version and can update it. */
class StatusActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER }
        val button = Button(this).apply { text = "Update yt-dlp" }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(TextView(context).apply { textSize = 22f; text = "Savetube yt-dlp plugin"; gravity = Gravity.CENTER })
            addView(text)
            addView(button)
        })

        fun refresh(note: String = "") = thread {
            runCatching { PluginService.prepare(this) }
            val version = runCatching { YoutubeDL.getInstance().version(this) }.getOrNull() ?: "starting…"
            runOnUiThread {
                text.text = "yt-dlp $version\n\nThis plugin works for Savetube. Open Savetube to use it.$note"
                button.isEnabled = true
            }
        }
        button.setOnClickListener {
            button.isEnabled = false
            text.text = "Updating…"
            thread {
                val status = PluginService.update(this, force = true)
                runOnUiThread { refresh(if (status != null) "\n\nUpdate: $status" else "\n\nUpdate failed.") }
            }
        }
        text.text = "Loading…"
        refresh()
    }
}
