import Foundation
import CryptoKit

public enum BridgeCryptoError: Error, LocalizedError {
    case invalidKeyLength
    case invalidCiphertextLength
    case authenticationFailed
    case sessionPoisoned
    case counterExhausted
    case lowOrderPublicKey
    case invalidWireFrame

    public var errorDescription: String? {
        switch self {
        case .invalidKeyLength: return "Invalid cryptographic key length"
        case .invalidCiphertextLength: return "Ciphertext frame length out of bounds"
        case .authenticationFailed: return "ChaCha20-Poly1305 authentication failed"
        case .sessionPoisoned: return "Encrypted session terminated due to prior error"
        case .counterExhausted: return "Frame nonce counter exhausted"
        case .lowOrderPublicKey: return "X25519 shared secret is invalid (low-order point)"
        case .invalidWireFrame: return "Malformed encrypted wire frame"
        }
    }
}

public struct X25519KeyPair: Equatable {
    public let privateKey: Data
    public let publicKey: Data

    public init(privateKey: Data, publicKey: Data) {
        self.privateKey = privateKey
        self.publicKey = publicKey
    }
}

public struct SessionKeys: Equatable {
    public let keyPhoneToMac: Data
    public let keyMacToPhone: Data
}

public enum HandshakeRole {
    case phoneInitiator
    case macResponder
}

public enum BridgeCrypto {
    public static let keySize = 32
    public static let nonceSize = 12
    public static let tagSize = 16
    public static let pairTokenSize = 16
    public static let salt = Data("droidbridge-v1".utf8)

    public static func generateX25519KeyPair() -> X25519KeyPair {
        let priv = Curve25519.KeyAgreement.PrivateKey()
        return X25519KeyPair(
            privateKey: priv.rawRepresentation,
            publicKey: priv.publicKey.rawRepresentation
        )
    }

    public static func derivePublicKey(privateKey: Data) throws -> Data {
        guard privateKey.count == keySize else { throw BridgeCryptoError.invalidKeyLength }
        let priv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateKey)
        return priv.publicKey.rawRepresentation
    }

    public static func randomBytes(count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        let status = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        if status != errSecSuccess {
            for i in 0..<count {
                bytes[i] = UInt8.random(in: 0...255)
            }
        }
        return Data(bytes)
    }

    public static func x25519SharedSecret(privateKey: Data, peerPublicKey: Data) throws -> Data {
        guard privateKey.count == keySize, peerPublicKey.count == keySize else {
            throw BridgeCryptoError.invalidKeyLength
        }
        let priv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateKey)
        let pub = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerPublicKey)
        let shared = try priv.sharedSecretFromKeyAgreement(with: pub)
        let data = shared.withUnsafeBytes { Data($0) }
        // Reject all-zero output (RFC 7748 §6.1)
        var orAll: UInt8 = 0
        for b in data { orAll |= b }
        if orAll == 0 {
            throw BridgeCryptoError.lowOrderPublicKey
        }
        return data
    }

    public static func hmacSHA256(key: Data, data: Data) -> Data {
        let code = HMAC<SHA256>.authenticationCode(for: data, using: SymmetricKey(data: key))
        return Data(code)
    }

    public static func constantTimeEquals(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<a.count {
            diff |= a[a.index(a.startIndex, offsetBy: i)] ^ b[b.index(b.startIndex, offsetBy: i)]
        }
        return diff == 0
    }

    /// Canonical initiator-first key derivation:
    /// dh1 = DH(I_eph,    R_eph)
    /// dh2 = DH(I_static, R_eph)
    /// dh3 = DH(I_eph,    R_static)
    /// okm = HKDF-SHA256(dh1 || dh2 || dh3, salt="droidbridge-v1", info=channelName, L=64)
    public static func deriveSessionKeys(
        role: HandshakeRole,
        ownStaticPriv: Data,
        ownEphPriv: Data,
        peerStaticPub: Data,
        peerEphPub: Data,
        channelName: String
    ) throws -> SessionKeys {
        let dh1: Data
        let dh2: Data
        let dh3: Data
        switch role {
        case .phoneInitiator:
            dh1 = try x25519SharedSecret(privateKey: ownEphPriv, peerPublicKey: peerEphPub)
            dh2 = try x25519SharedSecret(privateKey: ownStaticPriv, peerPublicKey: peerEphPub)
            dh3 = try x25519SharedSecret(privateKey: ownEphPriv, peerPublicKey: peerStaticPub)
        case .macResponder:
            dh1 = try x25519SharedSecret(privateKey: ownEphPriv, peerPublicKey: peerEphPub)
            dh2 = try x25519SharedSecret(privateKey: ownEphPriv, peerPublicKey: peerStaticPub)
            dh3 = try x25519SharedSecret(privateKey: ownStaticPriv, peerPublicKey: peerEphPub)
        }
        var ikm = Data(capacity: keySize * 3)
        ikm.append(dh1)
        ikm.append(dh2)
        ikm.append(dh3)

        let derived = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: ikm),
            salt: salt,
            info: Data(channelName.utf8),
            outputByteCount: 64
        )
        let okm = derived.withUnsafeBytes { Data($0) }
        return SessionKeys(
            keyPhoneToMac: Data(okm[0..<32]),
            keyMacToPhone: Data(okm[32..<64])
        )
    }

    public static func buildNonce(counter: UInt64) -> Data {
        var data = Data([0, 0, 0, 0])
        var be = counter.bigEndian
        withUnsafeBytes(of: &be) { data.append(contentsOf: $0) }
        return data
    }

    public static func sealChaCha20Poly1305(key: Data, counter: UInt64, plaintext: Data) throws -> Data {
        guard key.count == keySize else { throw BridgeCryptoError.invalidKeyLength }
        let nonce = try ChaChaPoly.Nonce(data: buildNonce(counter: counter))
        let sealed = try ChaChaPoly.seal(plaintext, using: SymmetricKey(data: key), nonce: nonce)
        var out = Data(capacity: sealed.ciphertext.count + sealed.tag.count)
        out.append(sealed.ciphertext)
        out.append(sealed.tag)
        return out
    }

    public static func openChaCha20Poly1305(key: Data, counter: UInt64, ciphertextAndTag: Data) throws -> Data {
        guard key.count == keySize else { throw BridgeCryptoError.invalidKeyLength }
        guard ciphertextAndTag.count >= tagSize else {
            throw BridgeCryptoError.invalidCiphertextLength
        }
        let splitIdx = ciphertextAndTag.count - tagSize
        let ct = ciphertextAndTag.prefix(splitIdx)
        let tag = ciphertextAndTag.suffix(tagSize)
        let nonce = try ChaChaPoly.Nonce(data: buildNonce(counter: counter))
        let box = try ChaChaPoly.SealedBox(nonce: nonce, ciphertext: ct, tag: tag)
        do {
            return try ChaChaPoly.open(box, using: SymmetricKey(data: key))
        } catch {
            throw BridgeCryptoError.authenticationFailed
        }
    }

    public static func encodeBase64Url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    public static func decodeBase64Url(_ string: String) -> Data? {
        var s = string
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let rem = s.count % 4
        if rem > 0 {
            s.append(String(repeating: "=", count: 4 - rem))
        }
        return Data(base64Encoded: s)
    }

    public static func toHex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    public static func fromHex(_ hex: String) -> Data? {
        guard hex.count % 2 == 0 else { return nil }
        var out = Data(capacity: hex.count / 2)
        var idx = hex.startIndex
        while idx < hex.endIndex {
            let next = hex.index(idx, offsetBy: 2)
            guard let b = UInt8(hex[idx..<next], radix: 16) else { return nil }
            out.append(b)
            idx = next
        }
        return out
    }
}

/// Stateful per-connection ChaCha20-Poly1305 frame sealer/opener.
/// Any decryption error or counter exhaustion poisons the session permanently.
public final class FrameCipher {
    private let sendKey: Data
    private let recvKey: Data
    private let maxFrameBytes: Int
    private let sendLock = NSLock()
    private let recvLock = NSLock()
    private var sendCounter: UInt64 = 0
    private var recvCounter: UInt64 = 0
    private var poisoned = false

    public init(sendKey: Data, recvKey: Data, maxFrameBytes: Int = ProtocolConstants.maxControlFrameBytes) {
        self.sendKey = sendKey
        self.recvKey = recvKey
        self.maxFrameBytes = maxFrameBytes
    }

    public var isPoisoned: Bool {
        recvLock.lock()
        defer { recvLock.unlock() }
        return poisoned
    }

    public func sealFrame(msgType: UInt8, payload: Data) throws -> Data {
        sendLock.lock()
        defer { sendLock.unlock() }
        if poisoned { throw BridgeCryptoError.sessionPoisoned }
        if sendCounter == UInt64.max {
            poisoned = true
            throw BridgeCryptoError.counterExhausted
        }
        var plaintext = Data(capacity: 1 + payload.count)
        plaintext.append(msgType)
        plaintext.append(payload)

        let ctAndTag = try BridgeCrypto.sealChaCha20Poly1305(key: sendKey, counter: sendCounter, plaintext: plaintext)
        sendCounter += 1

        var wire = Data(capacity: 4 + ctAndTag.count)
        var lenBE = UInt32(ctAndTag.count).bigEndian
        withUnsafeBytes(of: &lenBE) { wire.append(contentsOf: $0) }
        wire.append(ctAndTag)
        return wire
    }

    public func openCiphertext(_ ciphertextAndTag: Data) throws -> (UInt8, Data) {
        recvLock.lock()
        defer { recvLock.unlock() }
        if poisoned { throw BridgeCryptoError.sessionPoisoned }
        if recvCounter == UInt64.max {
            poisoned = true
            throw BridgeCryptoError.counterExhausted
        }
        guard ciphertextAndTag.count >= 1 + BridgeCrypto.tagSize,
              ciphertextAndTag.count <= maxFrameBytes else {
            poisoned = true
            throw BridgeCryptoError.invalidCiphertextLength
        }
        do {
            let plaintext = try BridgeCrypto.openChaCha20Poly1305(
                key: recvKey,
                counter: recvCounter,
                ciphertextAndTag: ciphertextAndTag
            )
            recvCounter += 1
            let msgType = plaintext[plaintext.startIndex]
            let payload = Data(plaintext.dropFirst())
            return (msgType, payload)
        } catch {
            poisoned = true
            throw error
        }
    }

    public func openWireFrame(_ wireFrame: Data) throws -> (UInt8, Data) {
        guard wireFrame.count >= 4 else {
            recvLock.lock()
            poisoned = true
            recvLock.unlock()
            throw BridgeCryptoError.invalidWireFrame
        }
        let len = wireFrame.withUnsafeBytes { ptr -> UInt32 in
            UInt32(bigEndian: ptr.loadUnaligned(as: UInt32.self))
        }
        guard Int(len) == wireFrame.count - 4 else {
            recvLock.lock()
            poisoned = true
            recvLock.unlock()
            throw BridgeCryptoError.invalidWireFrame
        }
        return try openCiphertext(Data(wireFrame.dropFirst(4)))
    }
}
