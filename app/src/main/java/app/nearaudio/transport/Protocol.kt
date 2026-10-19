package app.nearaudio.transport

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

class ProtocolException(message: String) : IOException(message)

object Protocol {
    const val VERSION = 1
    const val MAX_PAYLOAD = 16 * 1024
    const val SESSION_ID_BYTES = 16
    const val COMMITMENT_BYTES = 32
    const val ACCEPT_TAG_BYTES = 16
    const val GCM_TAG_BYTES = 16
    const val MAX_ALIAS_BYTES = 32
    const val MAX_KEY_BYTES = 128
    const val MAX_ALIAS_CHARS = 24
}

enum class RejectReason(val code: Int) {
    DECLINED(1), BUSY(2), UNSUPPORTED_VERSION(3), UNKNOWN(255);

    companion object {
        fun fromCode(code: Int): RejectReason = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * Handshake: Hello(commitment to sender key) -> HelloReply(receiver key) -> KeyReveal(sender key)
 * -> receiver user approves -> Accept(tag). Committing before the receiver's key is revealed
 * stops a man-in-the-middle from grinding keys until the short verification codes match.
 * After Accept the sender streams Audio (AES-GCM) plus Ping keepalives, and ends with Stop.
 */
sealed interface Message {
    class Hello(val version: Int, val sessionId: ByteArray, val alias: String, val commitment: ByteArray) : Message
    class HelloReply(val version: Int, val alias: String, val publicKey: ByteArray) : Message
    class KeyReveal(val publicKey: ByteArray) : Message
    class Accept(val tag: ByteArray) : Message
    class Reject(val reason: RejectReason) : Message
    class Audio(val counter: Long, val ciphertext: ByteArray) : Message
    data object Stop : Message
    data object Ping : Message
}

/** Wire format: [type:1][payloadLength:4 big-endian][payload]. */
object Codec {
    private const val T_HELLO = 1
    private const val T_REPLY = 2
    private const val T_REVEAL = 3
    private const val T_ACCEPT = 4
    private const val T_REJECT = 5
    private const val T_AUDIO = 6
    private const val T_STOP = 7
    private const val T_PING = 8

    fun encode(msg: Message): ByteArray {
        val body = ByteArrayOutputStream()
        val d = DataOutputStream(body)
        val type: Int = when (msg) {
            is Message.Hello -> {
                requireSize(msg.sessionId, Protocol.SESSION_ID_BYTES, "sessionId")
                requireSize(msg.commitment, Protocol.COMMITMENT_BYTES, "commitment")
                d.writeByte(msg.version)
                d.write(msg.sessionId)
                writeAlias(d, msg.alias)
                d.write(msg.commitment)
                T_HELLO
            }
            is Message.HelloReply -> {
                d.writeByte(msg.version)
                writeAlias(d, msg.alias)
                writeKey(d, msg.publicKey)
                T_REPLY
            }
            is Message.KeyReveal -> {
                writeKey(d, msg.publicKey)
                T_REVEAL
            }
            is Message.Accept -> {
                requireSize(msg.tag, Protocol.ACCEPT_TAG_BYTES, "tag")
                d.write(msg.tag)
                T_ACCEPT
            }
            is Message.Reject -> {
                d.writeByte(msg.reason.code)
                T_REJECT
            }
            is Message.Audio -> {
                if (msg.ciphertext.size < Protocol.GCM_TAG_BYTES) throw ProtocolException("ciphertext too short")
                d.writeLong(msg.counter)
                d.write(msg.ciphertext)
                T_AUDIO
            }
            is Message.Stop -> T_STOP
            is Message.Ping -> T_PING
        }
        val payload = body.toByteArray()
        if (payload.size > Protocol.MAX_PAYLOAD) throw ProtocolException("payload too large")
        return ByteBuffer.allocate(5 + payload.size).put(type.toByte()).putInt(payload.size).put(payload).array()
    }

    fun write(out: OutputStream, msg: Message) {
        val bytes = encode(msg)
        synchronized(out) {
            out.write(bytes)
            out.flush()
        }
    }

    /** Clean end of stream before a message starts throws [EOFException]; anything else malformed throws [ProtocolException]. */
    fun read(input: InputStream): Message {
        val type = input.read()
        if (type < 0) throw EOFException("end of stream")
        val header = ByteArray(4)
        readFully(input, header)
        val len = ByteBuffer.wrap(header).int
        if (len < 0 || len > Protocol.MAX_PAYLOAD) throw ProtocolException("invalid length $len")
        val payload = ByteArray(len)
        readFully(input, payload)
        return decode(type, payload)
    }

    fun decode(type: Int, payload: ByteArray): Message {
        val d = DataInputStream(ByteArrayInputStream(payload))
        try {
            val msg: Message = when (type) {
                T_HELLO -> {
                    val version = d.readUnsignedByte()
                    val sid = readN(d, Protocol.SESSION_ID_BYTES)
                    val alias = readAlias(d)
                    val commitment = readN(d, Protocol.COMMITMENT_BYTES)
                    Message.Hello(version, sid, alias, commitment)
                }
                T_REPLY -> Message.HelloReply(d.readUnsignedByte(), readAlias(d), readKey(d))
                T_REVEAL -> Message.KeyReveal(readKey(d))
                T_ACCEPT -> Message.Accept(readN(d, Protocol.ACCEPT_TAG_BYTES))
                T_REJECT -> Message.Reject(RejectReason.fromCode(d.readUnsignedByte()))
                T_AUDIO -> {
                    if (payload.size < 8 + Protocol.GCM_TAG_BYTES) throw ProtocolException("audio payload too short")
                    val counter = d.readLong()
                    return Message.Audio(counter, payload.copyOfRange(8, payload.size))
                }
                T_STOP -> Message.Stop
                T_PING -> Message.Ping
                else -> throw ProtocolException("unknown message type $type")
            }
            if (d.available() > 0) throw ProtocolException("trailing bytes")
            return msg
        } catch (e: EOFException) {
            throw ProtocolException("truncated message")
        }
    }

    private fun requireSize(b: ByteArray, n: Int, name: String) {
        if (b.size != n) throw ProtocolException("$name must be $n bytes")
    }

    private fun writeAlias(d: DataOutputStream, alias: String) {
        val b = alias.toByteArray(Charsets.UTF_8)
        if (b.size > Protocol.MAX_ALIAS_BYTES) throw ProtocolException("alias too long")
        d.writeByte(b.size)
        d.write(b)
    }

    private fun writeKey(d: DataOutputStream, key: ByteArray) {
        if (key.isEmpty() || key.size > Protocol.MAX_KEY_BYTES) throw ProtocolException("invalid key size")
        d.writeByte(key.size)
        d.write(key)
    }

    private fun readN(d: DataInputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        d.readFully(b)
        return b
    }

    private fun readAlias(d: DataInputStream): String {
        val n = d.readUnsignedByte()
        if (n > Protocol.MAX_ALIAS_BYTES) throw ProtocolException("alias too long")
        return String(readN(d, n), Charsets.UTF_8)
    }

    private fun readKey(d: DataInputStream): ByteArray {
        val n = d.readUnsignedByte()
        if (n == 0 || n > Protocol.MAX_KEY_BYTES) throw ProtocolException("invalid key size")
        return readN(d, n)
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw ProtocolException("truncated message")
            off += n
        }
    }
}

/** Peer-supplied names are untrusted display text only: strip control characters, cap length. */
fun sanitizeAlias(raw: String?): String {
    val cleaned = (raw ?: "").filterNot { Character.isISOControl(it) }.trim().take(Protocol.MAX_ALIAS_CHARS)
    return cleaned.ifBlank { "Unknown" }
}
