import Foundation
import Security

public struct PairedPhone: Codable, Identifiable, Equatable {
    public var id: String { deviceId }
    public let deviceId: String
    public let deviceName: String
    public let staticPubBase64Url: String
    public let pairedAt: Date

    public var staticPublicKey: Data? {
        BridgeCrypto.decodeBase64Url(staticPubBase64Url)
    }

    public init(deviceId: String, deviceName: String, staticPublicKey: Data, pairedAt: Date = Date()) {
        self.deviceId = deviceId
        self.deviceName = deviceName
        self.staticPubBase64Url = BridgeCrypto.encodeBase64Url(staticPublicKey)
        self.pairedAt = pairedAt
    }
}

/// Stores the Mac's long-term static X25519 private key in macOS Keychain (with a 0600 file
/// fallback in Application Support so ad-hoc signed local builds NEVER lose pairing across
/// app restarts, rebuilds, or Wi-Fi network changes) and manages trusted paired Android phones.
public final class KeychainStore {
    public static let shared = KeychainStore()

    private let service: String
    private let defaults: UserDefaults
    private let lock = NSLock()
    private let fallbackIdentityURL: URL?
    private var cachedKeyPair: X25519KeyPair?

    private static let keyAccountStaticPriv = "mac_static_x25519_priv"
    private static let defaultsKeyDeviceId = "droidbridge.mac.deviceId"
    private static let defaultsKeyPairedPhones = "droidbridge.pairedPhones"

    private struct StoredIdentity: Codable {
        let privateKeyBase64: String
    }

    public init(
        service: String = "com.droidbridge.macos.keys",
        defaults: UserDefaults = .standard,
        useDiskFallback: Bool = true
    ) {
        self.service = service
        self.defaults = defaults
        if useDiskFallback,
           let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first {
            let dir = appSupport.appendingPathComponent("DroidBridge", isDirectory: true)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let safeName = service.replacingOccurrences(of: "/", with: "_")
            self.fallbackIdentityURL = dir.appendingPathComponent("\(safeName).identity.json")
        } else {
            self.fallbackIdentityURL = nil
        }
    }

    public func getOrCreateDeviceId() -> String {
        lock.lock()
        defer { lock.unlock() }
        if let existing = defaults.string(forKey: Self.defaultsKeyDeviceId), !existing.isEmpty {
            return existing
        }
        let id = UUID().uuidString
        defaults.set(id, forKey: Self.defaultsKeyDeviceId)
        return id
    }

    public func getOrCreateStaticKeyPair() -> X25519KeyPair {
        lock.lock()
        defer { lock.unlock() }

        if let cached = cachedKeyPair {
            return cached
        }

        // 1. Try macOS Keychain first
        if let priv = readKeychain(account: Self.keyAccountStaticPriv),
           let pub = try? BridgeCrypto.derivePublicKey(privateKey: priv) {
            let kp = X25519KeyPair(privateKey: priv, publicKey: pub)
            cachedKeyPair = kp
            // Keep disk fallback in sync so ad-hoc rebuilds preserve the exact same keypair
            writeDiskFallback(privateKey: priv)
            return kp
        }

        // 2. Try 0600 Application Support fallback (crucial for ad-hoc signed local builds)
        if let priv = readDiskFallback(),
           let pub = try? BridgeCrypto.derivePublicKey(privateKey: priv) {
            let kp = X25519KeyPair(privateKey: priv, publicKey: pub)
            cachedKeyPair = kp
            _ = writeKeychain(account: Self.keyAccountStaticPriv, data: priv)
            return kp
        }

        // 3. First run ever: generate new static keypair and persist to both Keychain & 0600 file
        let kp = BridgeCrypto.generateX25519KeyPair()
        _ = writeKeychain(account: Self.keyAccountStaticPriv, data: kp.privateKey)
        writeDiskFallback(privateKey: kp.privateKey)
        cachedKeyPair = kp
        return kp
    }

    public func pairedPhones() -> [PairedPhone] {
        lock.lock()
        defer { lock.unlock() }
        guard let data = defaults.data(forKey: Self.defaultsKeyPairedPhones),
              let phones = try? JSONDecoder().decode([PairedPhone].self, from: data) else {
            return []
        }
        return phones
    }

    public func findPairedPhone(publicKey: Data) -> PairedPhone? {
        let encoded = BridgeCrypto.encodeBase64Url(publicKey)
        return pairedPhones().first { $0.staticPubBase64Url == encoded }
    }

    public func savePairedPhone(_ phone: PairedPhone) {
        lock.lock()
        defer { lock.unlock() }
        var list = (defaults.data(forKey: Self.defaultsKeyPairedPhones)
            .flatMap { try? JSONDecoder().decode([PairedPhone].self, from: $0) }) ?? []
        list.removeAll { $0.deviceId == phone.deviceId || $0.staticPubBase64Url == phone.staticPubBase64Url }
        list.append(phone)
        if let data = try? JSONEncoder().encode(list) {
            defaults.set(data, forKey: Self.defaultsKeyPairedPhones)
        }
    }

    public func removePairedPhone(deviceId: String) {
        lock.lock()
        defer { lock.unlock() }
        var list = (defaults.data(forKey: Self.defaultsKeyPairedPhones)
            .flatMap { try? JSONDecoder().decode([PairedPhone].self, from: $0) }) ?? []
        list.removeAll { $0.deviceId == deviceId }
        if let data = try? JSONEncoder().encode(list) {
            defaults.set(data, forKey: Self.defaultsKeyPairedPhones)
        }
    }

    // MARK: - Keychain & 0600 Disk Fallback Helpers

    private func readKeychain(account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return data
    }

    @discardableResult
    private func writeKeychain(account: String, data: Data) -> Bool {
        let deleteQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(deleteQuery as CFDictionary)

        let addQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        return SecItemAdd(addQuery as CFDictionary, nil) == errSecSuccess
    }

    private func readDiskFallback() -> Data? {
        guard let url = fallbackIdentityURL,
              let data = try? Data(contentsOf: url),
              let stored = try? JSONDecoder().decode(StoredIdentity.self, from: data) else {
            return nil
        }
        return Data(base64Encoded: stored.privateKeyBase64)
    }

    private func writeDiskFallback(privateKey: Data) {
        guard let url = fallbackIdentityURL else { return }
        let stored = StoredIdentity(privateKeyBase64: privateKey.base64EncodedString())
        if let data = try? JSONEncoder().encode(stored) {
            try? data.write(to: url, options: .atomic)
            try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        }
    }
}
