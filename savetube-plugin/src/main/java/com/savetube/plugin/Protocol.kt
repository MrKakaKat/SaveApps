package com.savetube.plugin

/** Messages between Savetube and this plugin. Keep identical to Savetube's copy of this file. */
object Protocol {
    const val VERSION = 1

    // Requests (Savetube -> plugin); every request carries replyTo.
    const val INFO = 1
    const val DOWNLOAD = 2
    const val CANCEL = 3

    // Replies (plugin -> Savetube)
    const val OK = 101
    const val ERROR = 102
    const val PROGRESS = 103

    // Bundle keys
    const val URL = "url"
    const val ID = "id"
    const val FORMAT = "format"
    const val KIND = "kind" // "audio" or "video"
    const val START = "start"
    const val END = "end"
    const val URI = "uri" // content uri the finished file is written to
    const val JSON = "json"
    const val MESSAGE = "message"
    const val FRACTION = "fraction"
}
