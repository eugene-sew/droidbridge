package com.droidbridge.app.pairing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.droidbridge.app.net.BridgeCrypto
import com.droidbridge.app.net.X25519KeyPair
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class PairedMacInfo(
    val macDeviceId: String,
    val macName: String,
    val macStaticPub: ByteArray,
    val lastHost: String,
    val lastPort: Int
)

/**
 * Persists the phone's static X25519 private key encrypted with an Android Keystore AES-256-GCM key,
 * along with the paired Mac's static public key and last-known LAN address.
 */
class KeyStoreManager(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getOrCreateDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    fun getOrCreateStaticKeyPair(): X25519KeyPair {
        val encPriv = prefs.getString(KEY_ENC_PRIV, null)
        val ivStr = prefs.getString(KEY_ENC_IV, null)
        if (encPriv != null && ivStr != null) {
            val priv = decryptWithKeystore(
                Base64.decode(encPriv, Base64.NO_WRAP),
                Base64.decode(ivStr, Base64.NO_WRAP)
            )
            val pub = BridgeCrypto.derivePublicKey(priv)
            return X25519KeyPair(priv, pub)
        }
        val kp = BridgeCrypto.generateX25519KeyPair()
        val (ciphertext, iv) = encryptWithKeystore(kp.privateKey)
        prefs.edit()
            .putString(KEY_ENC_PRIV, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(KEY_ENC_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()
        return kp
    }

    fun savePairedMac(info: PairedMacInfo) {
        prefs.edit()
            .putString(KEY_MAC_ID, info.macDeviceId)
            .putString(KEY_MAC_NAME, info.macName)
            .putString(KEY_MAC_PUB, BridgeCrypto.encodeBase64Url(info.macStaticPub))
            .putString(KEY_MAC_HOST, info.lastHost)
            .putInt(KEY_MAC_PORT, info.lastPort)
            .apply()
    }

    fun updateLastEndpoint(host: String, port: Int) {
        prefs.edit()
            .putString(KEY_MAC_HOST, host)
            .putInt(KEY_MAC_PORT, port)
            .apply()
    }

    fun getPairedMac(): PairedMacInfo? {
        val id = prefs.getString(KEY_MAC_ID, null) ?: return null
        val name = prefs.getString(KEY_MAC_NAME, null) ?: return null
        val pubStr = prefs.getString(KEY_MAC_PUB, null) ?: return null
        val host = prefs.getString(KEY_MAC_HOST, null) ?: return null
        val port = prefs.getInt(KEY_MAC_PORT, -1)
        if (port <= 0) return null
        val pub = runCatching { BridgeCrypto.decodeBase64Url(pubStr) }.getOrNull() ?: return null
        if (pub.size != BridgeCrypto.KEY_SIZE) return null
        return PairedMacInfo(id, name, pub, host, port)
    }

    fun isPaired(): Boolean = getPairedMac() != null

    fun clearPairing() {
        prefs.edit()
            .remove(KEY_MAC_ID)
            .remove(KEY_MAC_NAME)
            .remove(KEY_MAC_PUB)
            .remove(KEY_MAC_HOST)
            .remove(KEY_MAC_PORT)
            .apply()
    }

    private fun getOrCreateAesKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    private fun encryptWithKeystore(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAesKey())
        val ct = cipher.doFinal(plaintext)
        return ct to cipher.iv
    }

    private fun decryptWithKeystore(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateAesKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private const val PREFS_NAME = "droidbridge_keys"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "droidbridge_static_x25519_wrap"

        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ENC_PRIV = "enc_static_priv"
        private const val KEY_ENC_IV = "enc_static_iv"
        private const val KEY_MAC_ID = "mac_device_id"
        private const val KEY_MAC_NAME = "mac_name"
        private const val KEY_MAC_PUB = "mac_static_pub"
        private const val KEY_MAC_HOST = "mac_host"
        private const val KEY_MAC_PORT = "mac_port"
    }
}
