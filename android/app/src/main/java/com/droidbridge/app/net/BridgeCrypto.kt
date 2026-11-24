package com.droidbridge.app.net

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class X25519KeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is X25519KeyPair) return false
        return privateKey.contentEquals(other.privateKey) && publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int = 31 * privateKey.contentHashCode() + publicKey.contentHashCode()
}

data class SessionKeys(
    val keyPhoneToMac: ByteArray,
    val keyMacToPhone: ByteArray
)

enum class HandshakeRole {
    PHONE_INITIATOR,
    MAC_RESPONDER
}

object BridgeCrypto {
    const val KEY_SIZE = 32
    const val NONCE_SIZE = 12
    const val TAG_SIZE = 16
    const val PAIR_TOKEN_SIZE = 16
    private val SALT = "droidbridge-v1".toByteArray(Charsets.UTF_8)
    private val secureRandom = SecureRandom()

    // Native BoringSSL/Conscrypt (Android 10+) / OpenJDK 17 ChaCha20-Poly1305 is 20x-50x faster
    // than pure-Java BouncyCastle for 60fps video frames. Verified byte-for-byte against shared vectors.
    internal val nativeChaChaAvailable: Boolean by lazy {
        runCatching {
            val c = Cipher.getInstance("ChaCha20-Poly1305")
            val k = SecretKeySpec(ByteArray(32), "ChaCha20")
            c.init(Cipher.ENCRYPT_MODE, k, IvParameterSpec(ByteArray(12)))
            c.doFinal(byteArrayOf(1)).size == 17
        }.getOrDefault(false)
    }

    private val threadLocalCipher: ThreadLocal<Cipher?> = ThreadLocal.withInitial {
        if (nativeChaChaAvailable) runCatching { Cipher.getInstance("ChaCha20-Poly1305") }.getOrNull() else null
    }

    fun generateX25519KeyPair(): X25519KeyPair {
        val priv = ByteArray(KEY_SIZE)
        X25519.generatePrivateKey(secureRandom, priv)
        val pub = derivePublicKey(priv)
        return X25519KeyPair(priv, pub)
    }

    fun derivePublicKey(privateKey: ByteArray): ByteArray {
        if (privateKey.size != KEY_SIZE) {
            throw CryptoException("Invalid X25519 private key length: ${privateKey.size}")
        }
        val pub = ByteArray(KEY_SIZE)
        X25519.scalarMultBase(privateKey, 0, pub, 0)
        return pub
    }

    fun x25519SharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        if (privateKey.size != KEY_SIZE || peerPublicKey.size != KEY_SIZE) {
            throw CryptoException("Invalid X25519 key length")
        }
        val shared = ByteArray(KEY_SIZE)
        X25519.scalarMult(privateKey, 0, peerPublicKey, 0, shared, 0)
        // Reject low-order points (all-zero output) per RFC 7748 §6.1
        var orAll = 0
        for (b in shared) {
            orAll = orAll or (b.toInt() and 0xFF)
        }
        if (orAll == 0) {
            throw CryptoException("X25519 shared secret is all zeros (low-order public key)")
        }
        return shared
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val hmac = HMac(SHA256Digest())
        hmac.init(KeyParameter(key))
        hmac.update(data, 0, data.size)
        val out = ByteArray(hmac.macSize)
        hmac.doFinal(out, 0)
        return out
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        return MessageDigest.isEqual(a, b)
    }

    /**
     * Canonical initiator-first key derivation:
     * dh1 = DH(I_eph,    R_eph)
     * dh2 = DH(I_static, R_eph)
     * dh3 = DH(I_eph,    R_static)
     * ikm = dh1 || dh2 || dh3 (96 bytes)
     * okm = HKDF-SHA256(ikm, salt="droidbridge-v1", info=channelName, L=64)
     */
    fun deriveSessionKeys(
        role: HandshakeRole,
        ownStaticPriv: ByteArray,
        ownEphPriv: ByteArray,
        peerStaticPub: ByteArray,
        peerEphPub: ByteArray,
        channelName: String
    ): SessionKeys {
        val dh1: ByteArray
        val dh2: ByteArray
        val dh3: ByteArray
        when (role) {
            HandshakeRole.PHONE_INITIATOR -> {
                dh1 = x25519SharedSecret(ownEphPriv, peerEphPub)
                dh2 = x25519SharedSecret(ownStaticPriv, peerEphPub)
                dh3 = x25519SharedSecret(ownEphPriv, peerStaticPub)
            }
            HandshakeRole.MAC_RESPONDER -> {
                dh1 = x25519SharedSecret(ownEphPriv, peerEphPub)
                dh2 = x25519SharedSecret(ownEphPriv, peerStaticPub)
                dh3 = x25519SharedSecret(ownStaticPriv, peerEphPub)
            }
        }
        val ikm = ByteArray(KEY_SIZE * 3)
        System.arraycopy(dh1, 0, ikm, 0, KEY_SIZE)
        System.arraycopy(dh2, 0, ikm, KEY_SIZE, KEY_SIZE)
        System.arraycopy(dh3, 0, ikm, KEY_SIZE * 2, KEY_SIZE)

        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(ikm, SALT, channelName.toByteArray(Charsets.UTF_8)))
        val okm = ByteArray(64)
        hkdf.generateBytes(okm, 0, 64)

        return SessionKeys(
            keyPhoneToMac = okm.copyOfRange(0, 32),
            keyMacToPhone = okm.copyOfRange(32, 64)
        )
    }

    fun buildNonce(counter: ULong): ByteArray {
        val out = ByteArray(NONCE_SIZE)
        val v = counter.toLong()
        out[4] = (v ushr 56).toByte()
        out[5] = (v ushr 48).toByte()
        out[6] = (v ushr 40).toByte()
        out[7] = (v ushr 32).toByte()
        out[8] = (v ushr 24).toByte()
        out[9] = (v ushr 16).toByte()
        out[10] = (v ushr 8).toByte()
        out[11] = v.toByte()
        return out
    }

    fun sealChaCha20Poly1305(key: ByteArray, counter: ULong, plaintext: ByteArray): ByteArray {
        if (key.size != KEY_SIZE) throw CryptoException("Invalid key length")
        val nonce = buildNonce(counter)
        if (nativeChaChaAvailable) {
            return try {
                val native = Cipher.getInstance("ChaCha20-Poly1305")
                native.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
                native.doFinal(plaintext)
            } catch (e: Exception) {
                throw CryptoException("Native ChaCha20-Poly1305 seal failed", e)
            }
        }
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), TAG_SIZE * 8, nonce, ByteArray(0)))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, len)
        return out
    }

    fun openChaCha20Poly1305(key: ByteArray, counter: ULong, ciphertextAndTag: ByteArray): ByteArray {
        if (key.size != KEY_SIZE) throw CryptoException("Invalid key length")
        if (ciphertextAndTag.size < TAG_SIZE) {
            throw CryptoException("Ciphertext too short: ${ciphertextAndTag.size}")
        }
        val nonce = buildNonce(counter)
        if (nativeChaChaAvailable) {
            return try {
                val native = Cipher.getInstance("ChaCha20-Poly1305")
                native.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
                native.doFinal(ciphertextAndTag)
            } catch (e: Exception) {
                throw CryptoException("ChaCha20-Poly1305 authentication failed", e)
            }
        }
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), TAG_SIZE * 8, nonce, ByteArray(0)))
        val out = ByteArray(cipher.getOutputSize(ciphertextAndTag.size))
        return try {
            val len = cipher.processBytes(ciphertextAndTag, 0, ciphertextAndTag.size, out, 0)
            cipher.doFinal(out, len)
            out
        } catch (e: Exception) {
            throw CryptoException("ChaCha20-Poly1305 authentication failed", e)
        }
    }

    fun encodeBase64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decodeBase64Url(str: String): ByteArray =
        Base64.getUrlDecoder().decode(str)

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Odd hex string length" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}

/**
 * Stateful per-connection ChaCha20-Poly1305 frame sealer/opener.
 * Uses native BoringSSL/Conscrypt ChaCha20-Poly1305 when available and pre-allocates
 * SecretKeySpec so 60fps video encryption runs with minimal allocations.
 * Any decryption error or counter exhaustion poisons the cipher permanently.
 */
class FrameCipher(
    private val sendKey: ByteArray,
    private val recvKey: ByteArray,
    private val maxFrameBytes: Int = Protocol.MAX_CONTROL_FRAME_BYTES
) {
    private val sendLock = Any()
    private val recvLock = Any()
    private var sendCounter: ULong = 0uL
    private var recvCounter: ULong = 0uL
    @Volatile
    private var poisoned = false

    private val sendKeySpec = SecretKeySpec(sendKey, "ChaCha20")
    private val recvKeySpec = SecretKeySpec(recvKey, "ChaCha20")
    private val sendNativeCipher: Cipher? =
        if (BridgeCrypto.nativeChaChaAvailable) runCatching { Cipher.getInstance("ChaCha20-Poly1305") }.getOrNull() else null
    private val recvNativeCipher: Cipher? =
        if (BridgeCrypto.nativeChaChaAvailable) runCatching { Cipher.getInstance("ChaCha20-Poly1305") }.getOrNull() else null

    fun sealFrame(msgType: Byte, payload: ByteArray): ByteArray = synchronized(sendLock) {
        if (poisoned) throw CryptoException("Cipher session is terminated")
        if (sendCounter == ULong.MAX_VALUE) {
            poisoned = true
            throw CryptoException("Send counter exhausted")
        }
        val counter = sendCounter
        sendCounter += 1uL

        val cipherLen = 1 + payload.size + BridgeCrypto.TAG_SIZE
        val native = sendNativeCipher
        if (native != null) {
            val nonce = BridgeCrypto.buildNonce(counter)
            val wire = ByteArray(4 + cipherLen)
            wire[0] = (cipherLen ushr 24).toByte()
            wire[1] = (cipherLen ushr 16).toByte()
            wire[2] = (cipherLen ushr 8).toByte()
            wire[3] = cipherLen.toByte()
            try {
                native.init(Cipher.ENCRYPT_MODE, sendKeySpec, IvParameterSpec(nonce))
                val n1 = native.update(byteArrayOf(msgType), 0, 1, wire, 4)
                native.doFinal(payload, 0, payload.size, wire, 4 + n1)
                return wire
            } catch (e: Exception) {
                poisoned = true
                throw CryptoException("Native ChaCha20-Poly1305 seal failed", e)
            }
        }

        val plaintext = ByteArray(1 + payload.size)
        plaintext[0] = msgType
        System.arraycopy(payload, 0, plaintext, 1, payload.size)
        val ctAndTag = BridgeCrypto.sealChaCha20Poly1305(sendKey, counter, plaintext)

        val wire = ByteBuffer.allocate(4 + ctAndTag.size).order(ByteOrder.BIG_ENDIAN)
        wire.putInt(ctAndTag.size)
        wire.put(ctAndTag)
        wire.array()
    }

    fun openCiphertext(ciphertextAndTag: ByteArray): Pair<Byte, ByteArray> = synchronized(recvLock) {
        if (poisoned) throw CryptoException("Cipher session is terminated")
        if (recvCounter == ULong.MAX_VALUE) {
            poisoned = true
            throw CryptoException("Receive counter exhausted")
        }
        if (ciphertextAndTag.size < 1 + BridgeCrypto.TAG_SIZE || ciphertextAndTag.size > maxFrameBytes) {
            poisoned = true
            throw CryptoException("Invalid encrypted frame length: ${ciphertextAndTag.size}")
        }
        val plaintext = try {
            val native = recvNativeCipher
            if (native != null) {
                native.init(Cipher.DECRYPT_MODE, recvKeySpec, IvParameterSpec(BridgeCrypto.buildNonce(recvCounter)))
                native.doFinal(ciphertextAndTag)
            } else {
                BridgeCrypto.openChaCha20Poly1305(recvKey, recvCounter, ciphertextAndTag)
            }
        } catch (e: Exception) {
            poisoned = true
            throw if (e is CryptoException) e else CryptoException("ChaCha20-Poly1305 authentication failed", e)
        }
        recvCounter += 1uL
        val msgType = plaintext[0]
        val payload = plaintext.copyOfRange(1, plaintext.size)
        msgType to payload
    }

    fun openWireFrame(wireFrame: ByteArray): Pair<Byte, ByteArray> {
        if (wireFrame.size < 4) {
            poisoned = true
            throw CryptoException("Wire frame shorter than 4-byte length prefix")
        }
        val len = ByteBuffer.wrap(wireFrame, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        if (len != wireFrame.size - 4) {
            poisoned = true
            throw CryptoException("Wire frame length prefix ($len) does not match actual bytes (${wireFrame.size - 4})")
        }
        return openCiphertext(wireFrame.copyOfRange(4, wireFrame.size))
    }

    fun isPoisoned(): Boolean = poisoned
}
