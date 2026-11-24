package com.droidbridge.app

import com.droidbridge.app.net.AudioConfigPayload
import com.droidbridge.app.net.AudioFramePayload
import com.droidbridge.app.net.BridgeCrypto
import com.droidbridge.app.net.CryptoException
import com.droidbridge.app.net.EncryptedChannel
import com.droidbridge.app.net.FrameCipher
import com.droidbridge.app.net.HandshakeRole
import com.droidbridge.app.net.HelloPayload
import com.droidbridge.app.net.PingPongPayload
import com.droidbridge.app.net.Protocol
import com.droidbridge.app.net.VideoConfigPayload
import com.droidbridge.app.net.VideoFramePayload
import com.droidbridge.app.pairing.PairingUri
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread

class ProtocolAndCryptoTest {

    private fun loadVectorJson(fileName: String): JSONObject {
        val candidates = listOf(
            File("../protocol/test-vectors/$fileName"),
            File("../../protocol/test-vectors/$fileName"),
            File("protocol/test-vectors/$fileName")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Could not locate test vector $fileName from cwd=${File(".").absolutePath}")
        return JSONObject(file.readText())
    }

    @Test
    fun sharedCryptoVectorsMatchByteForByte() {
        val root = loadVectorJson("crypto_vectors.json")

        // 1. Pairing HMAC-SHA256
        val pairing = root.getJSONObject("pairing")
        val pairToken = BridgeCrypto.fromHex(pairing.getString("pairTokenHex"))
        val phonePubFromPairing = BridgeCrypto.fromHex(pairing.getString("phoneStaticPubHex"))
        val expectedHmac = BridgeCrypto.fromHex(pairing.getString("expectedHmacHex"))
        val actualHmac = BridgeCrypto.hmacSha256(pairToken, phonePubFromPairing)
        assertArrayEquals("Pairing HMAC mismatch", expectedHmac, actualHmac)
        assertEquals(pairing.getString("expectedHmacBase64Url"), BridgeCrypto.encodeBase64Url(actualHmac))
        assertEquals(pairing.getString("pairTokenBase64Url"), BridgeCrypto.encodeBase64Url(pairToken))

        // 2. X25519 key derivation + HKDF-SHA256 for both initiator (Phone) and responder (Mac)
        val hs = root.getJSONObject("handshake")
        val phoneStaticPriv = BridgeCrypto.fromHex(hs.getString("phoneStaticPrivHex"))
        val phoneStaticPub = BridgeCrypto.fromHex(hs.getString("phoneStaticPubHex"))
        val phoneEphPriv = BridgeCrypto.fromHex(hs.getString("phoneEphPrivHex"))
        val phoneEphPub = BridgeCrypto.fromHex(hs.getString("phoneEphPubHex"))
        val macStaticPriv = BridgeCrypto.fromHex(hs.getString("macStaticPrivHex"))
        val macStaticPub = BridgeCrypto.fromHex(hs.getString("macStaticPubHex"))
        val macEphPriv = BridgeCrypto.fromHex(hs.getString("macEphPrivHex"))
        val macEphPub = BridgeCrypto.fromHex(hs.getString("macEphPubHex"))

        assertArrayEquals(phoneStaticPub, BridgeCrypto.derivePublicKey(phoneStaticPriv))
        assertArrayEquals(phoneEphPub, BridgeCrypto.derivePublicKey(phoneEphPriv))
        assertArrayEquals(macStaticPub, BridgeCrypto.derivePublicKey(macStaticPriv))
        assertArrayEquals(macEphPub, BridgeCrypto.derivePublicKey(macEphPriv))

        val channels = hs.getJSONObject("channels")
        for (channelName in listOf("control", "video")) {
            val expectedCh = channels.getJSONObject(channelName)
            val expectedP2M = BridgeCrypto.fromHex(expectedCh.getString("keyPhoneToMacHex"))
            val expectedM2P = BridgeCrypto.fromHex(expectedCh.getString("keyMacToPhoneHex"))

            val phoneDerived = BridgeCrypto.deriveSessionKeys(
                HandshakeRole.PHONE_INITIATOR,
                phoneStaticPriv, phoneEphPriv, macStaticPub, macEphPub, channelName
            )
            val macDerived = BridgeCrypto.deriveSessionKeys(
                HandshakeRole.MAC_RESPONDER,
                macStaticPriv, macEphPriv, phoneStaticPub, phoneEphPub, channelName
            )

            assertArrayEquals("$channelName phone P2M mismatch", expectedP2M, phoneDerived.keyPhoneToMac)
            assertArrayEquals("$channelName phone M2P mismatch", expectedM2P, phoneDerived.keyMacToPhone)
            assertArrayEquals("$channelName mac P2M mismatch", expectedP2M, macDerived.keyPhoneToMac)
            assertArrayEquals("$channelName mac M2P mismatch", expectedM2P, macDerived.keyMacToPhone)
        }

        // 3. ChaCha20-Poly1305 frames + 64-bit nonce encoding
        val frames = root.getJSONArray("frames")
        for (i in 0 until frames.length()) {
            val entry = frames.getJSONObject(i)
            val name = entry.getString("name")
            val key = BridgeCrypto.fromHex(entry.getString("keyHex"))
            val f = entry.getJSONObject("frame")
            val counter = f.getLong("counter").toULong()
            val msgType = f.getInt("msgType").toByte()
            val payload = BridgeCrypto.fromHex(f.getString("payloadHex"))
            val expectedNonce = BridgeCrypto.fromHex(f.getString("nonceHex"))
            val expectedPlain = BridgeCrypto.fromHex(f.getString("plaintextHex"))
            val expectedCt = BridgeCrypto.fromHex(f.getString("ciphertextAndTagHex"))
            val expectedWire = BridgeCrypto.fromHex(f.getString("wireFrameHex"))

            assertArrayEquals("$name nonce mismatch", expectedNonce, BridgeCrypto.buildNonce(counter))

            val actualPlain = byteArrayOf(msgType) + payload
            assertArrayEquals("$name plaintext mismatch", expectedPlain, actualPlain)

            val actualCt = BridgeCrypto.sealChaCha20Poly1305(key, counter, actualPlain)
            assertArrayEquals("$name ciphertext+tag mismatch", expectedCt, actualCt)

            val decrypted = BridgeCrypto.openChaCha20Poly1305(key, counter, expectedCt)
            assertArrayEquals("$name decrypt mismatch", expectedPlain, decrypted)

            // Verify 4-byte length prefix matches wireFrameHex
            val wireLen = ((expectedWire[0].toInt() and 0xFF) shl 24) or
                ((expectedWire[1].toInt() and 0xFF) shl 16) or
                ((expectedWire[2].toInt() and 0xFF) shl 8) or
                (expectedWire[3].toInt() and 0xFF)
            assertEquals("$name wire len", actualCt.size, wireLen)
            assertArrayEquals("$name wire ciphertext", actualCt, expectedWire.copyOfRange(4, expectedWire.size))
        }
    }

    @Test
    fun sharedFramingVectorsMatchBinaryVideoCodecs() {
        val root = loadVectorJson("framing_vectors.json")
        val vidKey = BridgeCrypto.fromHex(root.getString("videoKeyPhoneToMacHex"))

        val senderCipher = FrameCipher(sendKey = vidKey, recvKey = vidKey, maxFrameBytes = Protocol.MAX_VIDEO_FRAME_BYTES)
        val receiverCipher = FrameCipher(sendKey = vidKey, recvKey = vidKey, maxFrameBytes = Protocol.MAX_VIDEO_FRAME_BYTES)

        // video.config (counter 0)
        val vcObj = root.getJSONObject("videoConfig")
        val vc = VideoConfigPayload(
            width = vcObj.getInt("width"),
            height = vcObj.getInt("height"),
            rotation = vcObj.getInt("rotation"),
            sps = BridgeCrypto.fromHex(vcObj.getString("spsHex")),
            pps = BridgeCrypto.fromHex(vcObj.getString("ppsHex"))
        )
        val expectedVcPayload = BridgeCrypto.fromHex(vcObj.getString("payloadHex"))
        assertArrayEquals(expectedVcPayload, vc.encode())
        assertEquals(vc, VideoConfigPayload.decode(expectedVcPayload))

        val expectedVcWire = BridgeCrypto.fromHex(vcObj.getJSONObject("encryptedFrame").getString("wireFrameHex"))
        val actualVcWire = senderCipher.sealFrame(Protocol.MSG_VIDEO_CONFIG, vc.encode())
        assertArrayEquals(expectedVcWire, actualVcWire)

        val (vcType, vcDecPayload) = receiverCipher.openWireFrame(actualVcWire)
        assertEquals(Protocol.MSG_VIDEO_CONFIG, vcType)
        assertEquals(vc, VideoConfigPayload.decode(vcDecPayload))

        // video.frame (counter 1)
        val vfObj = root.getJSONObject("videoFrame")
        val vf = VideoFramePayload(
            ptsMicros = vfObj.getLong("ptsMicros"),
            flags = vfObj.getInt("flags"),
            nalData = BridgeCrypto.fromHex(vfObj.getString("nalDataHex"))
        )
        assertTrue(vf.isKeyframe)
        val expectedVfPayload = BridgeCrypto.fromHex(vfObj.getString("payloadHex"))
        assertArrayEquals(expectedVfPayload, vf.encode())
        assertEquals(vf, VideoFramePayload.decode(expectedVfPayload))

        val expectedVfWire = BridgeCrypto.fromHex(vfObj.getJSONObject("encryptedFrame").getString("wireFrameHex"))
        val actualVfWire = senderCipher.sealFrame(Protocol.MSG_VIDEO_FRAME, vf.encode())
        assertArrayEquals(expectedVfWire, actualVfWire)

        val (vfType, vfDecPayload) = receiverCipher.openWireFrame(actualVfWire)
        assertEquals(Protocol.MSG_VIDEO_FRAME, vfType)
        assertEquals(vf, VideoFramePayload.decode(vfDecPayload))
    }

    @Test
    fun tamperedCiphertextFailsAndPoisonsSession() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val sender = FrameCipher(sendKey = key, recvKey = key)
        val receiver = FrameCipher(sendKey = key, recvKey = key)

        val wire0 = sender.sealFrame(Protocol.MSG_PING, Protocol.encodeJson(PingPongPayload(12345L)))
        val wire1 = sender.sealFrame(Protocol.MSG_PING, Protocol.encodeJson(PingPongPayload(67890L)))

        // Flip 1 byte in ciphertext of wire0
        val tampered = wire0.copyOf()
        tampered[10] = (tampered[10].toInt() xor 0x01).toByte()

        try {
            receiver.openWireFrame(tampered)
            fail("Expected CryptoException on tampered frame")
        } catch (_: CryptoException) {
            // Expected
        }
        assertTrue("Cipher must be poisoned after decryption failure", receiver.isPoisoned())

        // Even a valid subsequent frame must be rejected once poisoned
        try {
            receiver.openWireFrame(wire1)
            fail("Expected CryptoException on poisoned cipher")
        } catch (_: CryptoException) {
            // Expected
        }
    }

    @Test
    fun pairingUriParsesAndValidates() {
        val pub = ByteArray(32) { (it * 3).toByte() }
        val token = ByteArray(16) { (it * 5).toByte() }
        val uri = "droidbridge://pair?h=192.168.1.42&p=47321&k=${BridgeCrypto.encodeBase64Url(pub)}&t=${BridgeCrypto.encodeBase64Url(token)}&n=MacBook%20Pro"

        val parsed = PairingUri.parse(uri)
        assertEquals("192.168.1.42", parsed.host)
        assertEquals(47321, parsed.port)
        assertArrayEquals(pub, parsed.macStaticPub)
        assertArrayEquals(token, parsed.pairToken)
        assertEquals("MacBook Pro", parsed.macName)
    }

    @Test
    fun audioBinaryRoundTrip() {
        val cfg = AudioConfigPayload(48000, 2, byteArrayOf(0x11, 0x90.toByte()))
        val decCfg = AudioConfigPayload.decode(cfg.encode())
        assertEquals(48000, decCfg.sampleRate)
        assertEquals(2, decCfg.channels)
        assertArrayEquals(cfg.audioSpecificConfig, decCfg.audioSpecificConfig)

        val frame = AudioFramePayload(987654321L, byteArrayOf(0x21, 0x00, 0x49, 0x90.toByte()))
        val decFrame = AudioFramePayload.decode(frame.encode())
        assertEquals(987654321L, decFrame.ptsMicros)
        assertArrayEquals(frame.aacData, decFrame.aacData)
    }

    @Test
    fun loopbackHandshakeAndPingPongAndUnknownKeyRejection() {
        val phoneStatic = BridgeCrypto.generateX25519KeyPair()
        val macStatic = BridgeCrypto.generateX25519KeyPair()
        val intruderStatic = BridgeCrypto.generateX25519KeyPair()

        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        // Mock Mac server thread handling 2 connections: 1 valid paired phone, 1 unknown intruder
        val serverThread = thread {
            serverSocket.use { srv ->
                // Connection 1: valid paired phone
                srv.accept().use { s ->
                    val input = DataInputStream(s.getInputStream())
                    val output = DataOutputStream(s.getOutputStream())
                    val magic = ByteArray(4); input.readFully(magic)
                    val mode = input.readByte()
                    val peerStaticPub = ByteArray(32); input.readFully(peerStaticPub)
                    val peerEphPub = ByteArray(32); input.readFully(peerEphPub)
                    assertArrayEquals(Protocol.MAGIC, magic)
                    assertEquals(Protocol.MODE_CONTROL, mode)
                    assertArrayEquals(phoneStatic.publicKey, peerStaticPub)

                    val macEph = BridgeCrypto.generateX25519KeyPair()
                    output.write(Protocol.MAGIC)
                    output.writeByte(Protocol.HANDSHAKE_STATUS_OK.toInt())
                    output.write(macEph.publicKey)
                    output.flush()

                    val keys = BridgeCrypto.deriveSessionKeys(
                        HandshakeRole.MAC_RESPONDER,
                        macStatic.privateKey, macEph.privateKey,
                        peerStaticPub, peerEphPub, Protocol.CHANNEL_CONTROL
                    )
                    val cipher = FrameCipher(sendKey = keys.keyMacToPhone, recvKey = keys.keyPhoneToMac)

                    // Read phone hello
                    val hLen = input.readInt()
                    val hBytes = ByteArray(hLen); input.readFully(hBytes)
                    val (hType, _) = cipher.openCiphertext(hBytes)
                    assertEquals(Protocol.MSG_HELLO, hType)

                    // Send mac hello
                    val macHello = HelloPayload("mac-1", "Test Mac", "1.0.0", 1, listOf("notifications"))
                    output.write(cipher.sealFrame(Protocol.MSG_HELLO, Protocol.encodeJson(macHello)))
                    output.flush()

                    // Read ping -> send pong
                    val pLen = input.readInt()
                    val pBytes = ByteArray(pLen); input.readFully(pBytes)
                    val (pType, pPayload) = cipher.openCiphertext(pBytes)
                    assertEquals(Protocol.MSG_PING, pType)
                    val ping = Protocol.decodeJson<PingPongPayload>(pPayload)
                    output.write(cipher.sealFrame(Protocol.MSG_PONG, Protocol.encodeJson(ping)))
                    output.flush()
                }

                // Connection 2: unknown intruder -> server immediately closes
                srv.accept().use { s ->
                    val input = DataInputStream(s.getInputStream())
                    val header = ByteArray(4 + 1 + 32 + 32)
                    input.readFully(header)
                    val peerStaticPub = header.copyOfRange(5, 37)
                    if (!peerStaticPub.contentEquals(phoneStatic.publicKey)) {
                        s.close()
                    }
                }
            }
        }

        // Valid phone connects and exchanges ping/pong
        val phoneHello = HelloPayload("phone-1", "Test Phone", "1.0.0", 1, listOf("notifications"))
        EncryptedChannel.connectAsClient(
            host = "127.0.0.1",
            port = port,
            mode = Protocol.MODE_CONTROL,
            channelName = Protocol.CHANNEL_CONTROL,
            phoneStaticKeyPair = phoneStatic,
            macStaticPub = macStatic.publicKey,
            localHello = phoneHello
        ).use { ch ->
            assertEquals("Test Mac", ch.peerHello.deviceName)
            ch.send(Protocol.MSG_PING, Protocol.encodeJson(PingPongPayload(424242L)))
            val (type, payload) = ch.readNext()
            assertEquals(Protocol.MSG_PONG, type)
            assertEquals(424242L, Protocol.decodeJson<PingPongPayload>(payload).t)
        }

        // Intruder with unknown static key is rejected
        try {
            EncryptedChannel.connectAsClient(
                host = "127.0.0.1",
                port = port,
                mode = Protocol.MODE_CONTROL,
                channelName = Protocol.CHANNEL_CONTROL,
                phoneStaticKeyPair = intruderStatic,
                macStaticPub = macStatic.publicKey,
                localHello = phoneHello
            )
            fail("Expected unknown key connection to be rejected")
        } catch (_: Exception) {
            // Expected
        }

        serverThread.join(5_000)
    }
}
