package com.savetube.app

import android.app.Application
import org.jaudiotagger.tag.TagOptionSingleton
import org.schabi.newpipe.extractor.NewPipe

class SavetubeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NewPipe.init(NpDownloader)
        TagOptionSingleton.getInstance().isAndroid = true
    }
}
