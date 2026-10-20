package app.nearaudio.transport

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Offline (Wi-Fi Direct) mode: the sharing phone is group owner at a known address, so a joining
 * listener tells it where to connect: [magic:4][id:4][port:2][aliasLen:1][alias]. This only makes the
 * listener *visible*; the normal handshake, verification code and approval still apply.
 */
object Announce {
    const val PORT = 47321
    private val MAGIC = byteArrayOf('N'.code.toByte(), 'A'.code.toByte(), 'U'.code.toByte(), 1)
    private val ID_REGEX = Regex("[0-9a-f]{8}")

    class Message(val id: String, val port: Int, val alias: String)

    fun encode(id: String, port: Int, alias: String): ByteArray {
        if (!ID_REGEX.matches(id)) throw ProtocolException("bad id")
        if (port !in 1..65535) throw ProtocolException("bad port")
        val a = alias.toByteArray(Charsets.UTF_8)
        if (a.size > Protocol.MAX_ALIAS_BYTES) throw ProtocolException("alias too long")
        val idBytes = ByteArray(4) { id.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return ByteBuffer.allocate(11 + a.size).put(MAGIC).put(idBytes).putShort(port.toShort()).put(a.size.toByte()).put(a).array()
    }

    fun read(input: InputStream): Message {
        val d = DataInputStream(input)
        try {
            val magic = ByteArray(4).also { d.readFully(it) }
            if (!magic.contentEquals(MAGIC)) throw ProtocolException("bad magic")
            val idBytes = ByteArray(4).also { d.readFully(it) }
            val port = d.readUnsignedShort()
            if (port == 0) throw ProtocolException("bad port")
            val n = d.readUnsignedByte()
            if (n > Protocol.MAX_ALIAS_BYTES) throw ProtocolException("alias too long")
            val alias = String(ByteArray(n).also { d.readFully(it) }, Charsets.UTF_8)
            return Message(idBytes.joinToString("") { "%02x".format(it) }, port, alias)
        } catch (e: EOFException) {
            throw ProtocolException("truncated announce")
        }
    }
}
