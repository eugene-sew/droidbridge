package com.droidbridge.app.net

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Encrypted TCP channel (either "control" or "video") implementing the DroidBridge v1
 * X25519 + HKDF-SHA256 + ChaCha20-Poly1305 handshake and low-latency framed message I/O.
 */
class EncryptedChannel private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: OutputStream,
    private val cipher: FrameCipher,
    val peerHello: HelloPayload,
    val maxFrameBytes: Int
) : Closeable {

    private val writeLock = Any()

    /**
     * Encrypts and sends a single frame. Returns write duration in milliseconds
     * and optionally records wire bytes + encrypt/write timings for telemetry.
     */
    fun send(msgType: Byte, payload: ByteArray, recordVideoMetrics: Boolean = false): Long {
        val tEnc0 = System.nanoTime()
        val wireFrame = cipher.sealFrame(msgType, payload)
        val encNanos = System.nanoTime() - tEnc0
        val t0 = System.nanoTime()
        synchronized(writeLock) {
            // Write the entire length-prefixed frame in a single syscall (tcpNoDelay=true)
            output.write(wireFrame)
        }
        val writeMicros = (System.nanoTime() - t0) / 1_000L
        if (recordVideoMetrics) {
            VideoMetricsTracker.recordFrameSent(wireFrame.size, encNanos, writeMicros)
        }
        return writeMicros / 1_000L
    }

    fun readNext(): Pair<Byte, ByteArray> {
        val cipherLen = input.readInt()
        if (cipherLen < 1 + BridgeCrypto.TAG_SIZE || cipherLen > maxFrameBytes) {
            close()
            throw IOException("Invalid encrypted frame length: $cipherLen")
        }
        val cipherAndTag = ByteArray(cipherLen)
        input.readFully(cipherAndTag)
        return try {
            cipher.openCiphertext(cipherAndTag)
        } catch (e: CryptoException) {
            close()
            throw IOException("Decryption failed: ${e.message}", e)
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    val isClosed: Boolean get() = socket.isClosed || !socket.isConnected || cipher.isPoisoned()

    companion object {
        private const val CONNECT_TIMEOUT_MS = 3_500
        private const val HANDSHAKE_TIMEOUT_MS = 5_000

        /**
         * Connects to a paired Mac as initiator (Phone), executes the E2EE handshake,
         * exchanges mandatory first `hello` frames, and returns an authenticated channel.
         */
        fun connectAsClient(
            host: String,
            port: Int,
            mode: Byte,
            channelName: String,
            phoneStaticKeyPair: X25519KeyPair,
            macStaticPub: ByteArray,
            localHello: HelloPayload,
            readTimeoutMs: Int = Protocol.HEARTBEAT_TIMEOUT_MS.toInt(),
            maxFrameBytes: Int = if (mode == Protocol.MODE_VIDEO) Protocol.MAX_VIDEO_FRAME_BYTES else Protocol.MAX_CONTROL_FRAME_BYTES
        ): EncryptedChannel {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                // IPTOS_LOWDELAY (0x10) hints Wi-Fi WMM / routers to prioritize low-latency packets
                runCatching { socket.trafficClass = 0x10 }
                if (mode == Protocol.MODE_VIDEO) {
                    runCatching { socket.sendBufferSize = 256 * 1024 }
                }

                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS

                val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
                val output = socket.getOutputStream()

                val phoneEph = BridgeCrypto.generateX25519KeyPair()

                // 1. Send 69-byte client handshake in one packet: MAGIC(4) + mode(1) + phoneStaticPub(32) + phoneEphPub(32)
                val handshakePacket = ByteArray(69)
                System.arraycopy(Protocol.MAGIC, 0, handshakePacket, 0, 4)
                handshakePacket[4] = mode
                System.arraycopy(phoneStaticKeyPair.publicKey, 0, handshakePacket, 5, 32)
                System.arraycopy(phoneEph.publicKey, 0, handshakePacket, 37, 32)
                output.write(handshakePacket)
                output.flush()

                // 2. Receive 37-byte server handshake: MAGIC(4) + status(1) + macEphPub(32)
                val serverMagic = ByteArray(4)
                input.readFully(serverMagic)
                if (!serverMagic.contentEquals(Protocol.MAGIC)) {
                    throw IOException("Invalid server magic")
                }
                val status = input.readByte()
                if (status != Protocol.HANDSHAKE_STATUS_OK) {
                    throw IOException("Server rejected handshake with status $status")
                }
                val macEphPub = ByteArray(BridgeCrypto.KEY_SIZE)
                input.readFully(macEphPub)

                // 3. Derive directional session keys
                val keys = BridgeCrypto.deriveSessionKeys(
                    role = HandshakeRole.PHONE_INITIATOR,
                    ownStaticPriv = phoneStaticKeyPair.privateKey,
                    ownEphPriv = phoneEph.privateKey,
                    peerStaticPub = macStaticPub,
                    peerEphPub = macEphPub,
                    channelName = channelName
                )
                val cipher = FrameCipher(
                    sendKey = keys.keyPhoneToMac,
                    recvKey = keys.keyMacToPhone,
                    maxFrameBytes = maxFrameBytes
                )

                // 4. Send mandatory first encrypted `hello`
                val helloWire = cipher.sealFrame(Protocol.MSG_HELLO, Protocol.encodeJson(localHello))
                output.write(helloWire)
                output.flush()

                // 5. Read peer's mandatory first encrypted `hello`
                val peerCipherLen = input.readInt()
                if (peerCipherLen < 1 + BridgeCrypto.TAG_SIZE || peerCipherLen > maxFrameBytes) {
                    throw IOException("Invalid hello frame length: $peerCipherLen")
                }
                val peerCipherBytes = ByteArray(peerCipherLen)
                input.readFully(peerCipherBytes)
                val (msgType, payload) = cipher.openCiphertext(peerCipherBytes)
                if (msgType != Protocol.MSG_HELLO) {
                    throw IOException("Expected hello (0x01) as first message, got $msgType")
                }
                val peerHello = Protocol.decodeJson<HelloPayload>(payload)
                if (peerHello.protocolVersion != Protocol.PROTOCOL_VERSION) {
                    throw IOException("Unsupported protocol version: ${peerHello.protocolVersion}")
                }

                socket.soTimeout = readTimeoutMs
                return EncryptedChannel(socket, input, output, cipher, peerHello, maxFrameBytes)
            } catch (e: Exception) {
                runCatching { socket.close() }
                if (e is IOException) throw e
                throw IOException("Handshake failed: ${e.message}", e)
            }
        }
    }
}
