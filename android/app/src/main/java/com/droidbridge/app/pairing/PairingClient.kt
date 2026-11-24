package com.droidbridge.app.pairing

import android.os.Build
import com.droidbridge.app.net.BridgeCrypto
import com.droidbridge.app.net.BridgeLog
import com.droidbridge.app.net.PairRequestPayload
import com.droidbridge.app.net.PairResponsePayload
import com.droidbridge.app.net.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class PairingException(message: String, cause: Throwable? = null) : Exception(message, cause)

object PairingClient {
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 10_000

    suspend fun pair(
        pairingUri: PairingUri,
        keyStoreManager: KeyStoreManager
    ): PairedMacInfo = withContext(Dispatchers.IO) {
        val deviceId = keyStoreManager.getOrCreateDeviceId()
        val staticKp = keyStoreManager.getOrCreateStaticKeyPair()
        val deviceName = listOfNotNull(Build.MANUFACTURER, Build.MODEL)
            .joinToString(" ")
            .trim()
            .ifBlank { "Android Device" }

        val hmac = BridgeCrypto.hmacSha256(pairingUri.pairToken, staticKp.publicKey)
        val req = PairRequestPayload(
            deviceId = deviceId,
            deviceName = deviceName,
            phoneStaticPub = BridgeCrypto.encodeBase64Url(staticKp.publicKey),
            mac = BridgeCrypto.encodeBase64Url(hmac)
        )
        val reqBytes = Protocol.encodeJson(req)

        BridgeLog.i("Pairing", "Connecting to ${pairingUri.host}:${pairingUri.port}")
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(pairingUri.host, pairingUri.port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            socket.tcpNoDelay = true

            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())

            // Preamble: "DBR1" + 0x01 (pairing)
            out.write(Protocol.MAGIC)
            out.writeByte(Protocol.MODE_PAIRING.toInt())
            out.writeInt(reqBytes.size)
            out.write(reqBytes)
            out.flush()

            val respLen = input.readInt()
            if (respLen <= 0 || respLen > Protocol.MAX_PAIRING_FRAME_BYTES) {
                throw PairingException("Invalid pairing response length: $respLen")
            }
            val respBytes = ByteArray(respLen)
            input.readFully(respBytes)
            val resp = Protocol.decodeJson<PairResponsePayload>(respBytes)

            if (resp.type != "pair.ok") {
                throw PairingException("Pairing rejected by Mac: ${resp.reason ?: resp.type}")
            }

            val macId = resp.macDeviceId ?: throw PairingException("Missing macDeviceId in pair.ok")
            val macName = resp.macName?.takeIf { it.isNotBlank() } ?: pairingUri.macName
            val info = PairedMacInfo(
                macDeviceId = macId,
                macName = macName,
                macStaticPub = pairingUri.macStaticPub,
                lastHost = pairingUri.host,
                lastPort = pairingUri.port
            )
            keyStoreManager.savePairedMac(info)
            BridgeLog.i("Pairing", "Paired successfully with $macName ($macId)")
            info
        } catch (e: PairingException) {
            throw e
        } catch (e: IOException) {
            throw PairingException("Could not reach Mac at ${pairingUri.host}:${pairingUri.port}: ${e.message}", e)
        } finally {
            runCatching { socket.close() }
        }
    }
}
