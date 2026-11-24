package com.droidbridge.app.pairing

import com.droidbridge.app.net.BridgeCrypto
import java.net.URI
import java.net.URLDecoder

data class PairingUri(
    val host: String,
    val port: Int,
    val macStaticPub: ByteArray,
    val pairToken: ByteArray,
    val macName: String
) {
    companion object {
        fun parse(raw: String): PairingUri {
            val uri = URI(raw.trim())
            require(uri.scheme == "droidbridge" && uri.host == "pair") {
                "Not a DroidBridge pairing URI"
            }
            val params = mutableMapOf<String, String>()
            val query = uri.rawQuery ?: throw IllegalArgumentException("Missing query parameters")
            for (part in query.split("&")) {
                if (part.isBlank()) continue
                val idx = part.indexOf('=')
                if (idx <= 0) continue
                val key = URLDecoder.decode(part.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                params[key] = value
            }

            val host = params["h"]?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Missing host (h)")
            val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 }
                ?: throw IllegalArgumentException("Invalid port (p)")
            val pubB64 = params["k"]
                ?: throw IllegalArgumentException("Missing Mac public key (k)")
            val tokenB64 = params["t"]
                ?: throw IllegalArgumentException("Missing pair token (t)")
            val macName = params["n"]?.takeIf { it.isNotBlank() } ?: "Mac"

            val macStaticPub = BridgeCrypto.decodeBase64Url(pubB64)
            require(macStaticPub.size == BridgeCrypto.KEY_SIZE) {
                "Invalid Mac public key length: ${macStaticPub.size}"
            }
            val pairToken = BridgeCrypto.decodeBase64Url(tokenB64)
            require(pairToken.size == BridgeCrypto.PAIR_TOKEN_SIZE) {
                "Invalid pair token length: ${pairToken.size}"
            }

            return PairingUri(host, port, macStaticPub, pairToken, macName)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingUri) return false
        return host == other.host && port == other.port &&
            macStaticPub.contentEquals(other.macStaticPub) &&
            pairToken.contentEquals(other.pairToken) &&
            macName == other.macName
    }

    override fun hashCode(): Int {
        var r = host.hashCode()
        r = 31 * r + port
        r = 31 * r + macStaticPub.contentHashCode()
        r = 31 * r + pairToken.contentHashCode()
        r = 31 * r + macName.hashCode()
        return r
    }
}
