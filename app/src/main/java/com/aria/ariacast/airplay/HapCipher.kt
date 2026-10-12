package com.aria.ariacast.airplay

import com.aria.ariacast.airplay2.AirPlay2Crypto
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * HomeKit (HAP) session security used on the AirPlay 2 RTSP control channel and
 * the event channel once pairing has completed.
 *
 * The byte stream is cut into blocks of at most 1024 plaintext bytes; each block
 * goes out as a 2-byte little-endian length (also the AEAD's additional data),
 * the ChaCha20-Poly1305 ciphertext and its 16-byte tag. The 96-bit nonce is four
 * zero bytes followed by a 64-bit little-endian per-direction block counter.
 */
class HapCipher(private val writeKey: ByteArray, private val readKey: ByteArray) {
    private var writeCounter = 0L
    private var readCounter = 0L

    companion object {
        const val MAX_BLOCK = 0x400

        /** Keys for the RTSP control channel (we are the writer of "Control-Write"). */
        fun forControl(sharedSecret: ByteArray) = HapCipher(
            hkdf(sharedSecret, "Control-Salt", "Control-Write-Encryption-Key"),
            hkdf(sharedSecret, "Control-Salt", "Control-Read-Encryption-Key")
        )

        /**
         * Keys for the event channel. It's a reverse connection - the receiver is the
         * one issuing requests on it - so the read/write roles are swapped.
         */
        fun forEvents(sharedSecret: ByteArray) = HapCipher(
            hkdf(sharedSecret, "Events-Salt", "Events-Read-Encryption-Key"),
            hkdf(sharedSecret, "Events-Salt", "Events-Write-Encryption-Key")
        )

        /** The receiver-side counterpart of [forControl]; used by tests' fake receivers. */
        fun forControlServer(sharedSecret: ByteArray) = HapCipher(
            hkdf(sharedSecret, "Control-Salt", "Control-Read-Encryption-Key"),
            hkdf(sharedSecret, "Control-Salt", "Control-Write-Encryption-Key")
        )

        private fun hkdf(secret: ByteArray, salt: String, info: String): ByteArray =
            AirPlay2Crypto.hkdfSha512(salt.toByteArray(Charsets.UTF_8), secret, info.toByteArray(Charsets.UTF_8), 32)

        private fun nonce(counter: Long): ByteArray {
            val n = ByteArray(12)
            for (i in 0 until 8) n[4 + i] = (counter ushr (8 * i)).toByte()
            return n
        }
    }

    @Synchronized
    fun encrypt(plain: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(plain.size + (plain.size / MAX_BLOCK + 1) * 18)
        var offset = 0
        do {
            val len = minOf(MAX_BLOCK, plain.size - offset)
            val aad = byteArrayOf(len.toByte(), (len ushr 8).toByte())
            val sealed = AirPlay2Crypto.chacha20Poly1305Seal(writeKey, nonce(writeCounter++), plain, offset, len, aad)
            out.write(aad)
            out.write(sealed)
            offset += len
        } while (offset < plain.size)
        return out.toByteArray()
    }

    /** Reads and decrypts exactly one block from [raw]; returns null at a clean end of stream. */
    fun readBlock(raw: InputStream): ByteArray? {
        val lenBytes = ByteArray(2)
        val first = raw.read()
        if (first < 0) return null
        lenBytes[0] = first.toByte()
        val second = raw.read()
        if (second < 0) throw EOFException("truncated HAP block header")
        lenBytes[1] = second.toByte()
        val len = (lenBytes[0].toInt() and 0xFF) or ((lenBytes[1].toInt() and 0xFF) shl 8)
        if (len > MAX_BLOCK) throw java.io.IOException("HAP block too large: $len")
        val sealed = ByteArray(len + 16)
        readFully(raw, sealed)
        synchronized(this) {
            return AirPlay2Crypto.chacha20Poly1305Open(readKey, nonce(readCounter++), sealed, 0, sealed.size, lenBytes)
        }
    }

    /** A plaintext view over an encrypted input stream. */
    fun decryptingStream(raw: InputStream): InputStream = object : InputStream() {
        private var block = ByteArray(0)
        private var pos = 0

        private fun fill(): Boolean {
            while (pos >= block.size) {
                block = readBlock(raw) ?: return false
                pos = 0
            }
            return true
        }

        override fun read(): Int = if (!fill()) -1 else block[pos++].toInt() and 0xFF

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, block.size - pos)
            System.arraycopy(block, pos, b, off, n)
            pos += n
            return n
        }

        override fun available(): Int = block.size - pos
    }

    fun encryptingStream(raw: OutputStream): OutputStream = object : OutputStream() {
        private val pending = ByteArrayOutputStream()
        override fun write(b: Int) { pending.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { pending.write(b, off, len) }
        override fun flush() {
            if (pending.size() > 0) {
                raw.write(encrypt(pending.toByteArray()))
                pending.reset()
            }
            raw.flush()
        }
        override fun close() { flush(); raw.close() }
    }
}

internal fun readFully(input: InputStream, buf: ByteArray, off: Int = 0, len: Int = buf.size - off) {
    var read = 0
    while (read < len) {
        val n = input.read(buf, off + read, len - read)
        if (n < 0) throw EOFException("stream ended after $read of $len bytes")
        read += n
    }
}
