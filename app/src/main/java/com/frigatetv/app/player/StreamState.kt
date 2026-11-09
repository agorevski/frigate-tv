package com.frigatetv.app.player

/** What a camera tile or the fullscreen view shows about its stream. */
enum class StreamState(val label: String) {
    Connecting("CONNECTING"),
    Live("LIVE"),
    Buffering("BUFFERING"),
    Offline("OFFLINE"),
}
