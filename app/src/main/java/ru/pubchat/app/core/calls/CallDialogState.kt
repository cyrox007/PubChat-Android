package ru.pubchat.app.core.calls

/** Signaling availability alone is not a call and must not open a modal. */
internal fun shouldShowCallDialog(phase: String, peerUid: String?): Boolean =
    !peerUid.isNullOrBlank() && phase in setOf(
        "incoming", "outgoing", "connecting", "reconnecting", "active",
        "video_track", "error", "disconnected",
    )
