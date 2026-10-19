package app.nearaudio.discovery

/** A nearby app instance. [id] is a random per-launch value, not a hardware identifier. */
data class Peer(val id: String, val alias: String, val host: String, val port: Int)
