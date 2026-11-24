import Foundation
import CoreImage
import AppKit
import Darwin

public struct ActivePairingSession {
    public let token: Data
    public let expiresAt: Date
    public let qrUri: String
    public let host: String
    public let port: UInt16
}

public final class PairingManager: ObservableObject {
    public static let tokenValiditySeconds: TimeInterval = 120

    @Published public private(set) var activeSession: ActivePairingSession?
    @Published public private(set) var pairedPhones: [PairedPhone] = []
    @Published public private(set) var lastPairingStatus: String?
    @Published public private(set) var lastPairingError: String?

    private let store: KeychainStore
    private let lock = NSLock()
    private var currentToken: Data?
    private var tokenExpiresAt: Date?

    public init(store: KeychainStore = .shared) {
        self.store = store
        self.pairedPhones = store.pairedPhones()
    }

    public var macName: String {
        Host.current().localizedName ?? ProcessInfo.processInfo.hostName
    }

    public func reloadPairedPhones() {
        let list = store.pairedPhones()
        DispatchQueue.main.async {
            self.pairedPhones = list
        }
    }

    public func forgetPhone(deviceId: String) {
        store.removePairedPhone(deviceId: deviceId)
        reloadPairedPhones()
    }

    @discardableResult
    public func startPairingSession(port: UInt16) -> ActivePairingSession {
        let token = BridgeCrypto.randomBytes(count: BridgeCrypto.pairTokenSize)
        let expires = Date().addingTimeInterval(Self.tokenValiditySeconds)
        let host = Self.localWiFiIPv4() ?? "127.0.0.1"
        let staticPub = store.getOrCreateStaticKeyPair().publicKey

        let uri = Self.buildPairingUri(
            host: host,
            port: port,
            macStaticPub: staticPub,
            pairToken: token,
            macName: macName
        )

        lock.lock()
        self.currentToken = token
        self.tokenExpiresAt = expires
        lock.unlock()

        let session = ActivePairingSession(token: token, expiresAt: expires, qrUri: uri, host: host, port: port)
        DispatchQueue.main.async {
            self.activeSession = session
            self.lastPairingStatus = nil
            self.lastPairingError = nil
        }
        return session
    }

    public func cancelPairingSession() {
        lock.lock()
        currentToken = nil
        tokenExpiresAt = nil
        lock.unlock()
        DispatchQueue.main.async {
            self.activeSession = nil
        }
    }

    /// Validates an incoming `pair.request` payload, burns the one-time token on success,
    /// stores the phone's static public key as trusted, and returns the `PairResponsePayload`.
    public func handlePairRequest(_ req: PairRequestPayload) -> PairResponsePayload {
        func fail(_ reason: String, human: String) -> PairResponsePayload {
            DispatchQueue.main.async { self.lastPairingError = human }
            return PairResponsePayload(type: "pair.error", reason: reason)
        }

        lock.lock()
        guard let token = currentToken, let expires = tokenExpiresAt else {
            lock.unlock()
            return fail("not_pairing", human: "Pairing attempt rejected: no active QR session")
        }
        if Date() > expires {
            currentToken = nil
            tokenExpiresAt = nil
            lock.unlock()
            DispatchQueue.main.async { self.activeSession = nil }
            return fail("expired", human: "QR code expired — generate a fresh QR and scan again")
        }

        guard req.type == "pair.request",
              !req.deviceId.isEmpty,
              let phonePub = BridgeCrypto.decodeBase64Url(req.phoneStaticPub),
              phonePub.count == BridgeCrypto.keySize,
              let receivedMac = BridgeCrypto.decodeBase64Url(req.mac),
              receivedMac.count == 32 else {
            lock.unlock()
            return fail("invalid_request", human: "Malformed pairing request received")
        }

        let expectedMac = BridgeCrypto.hmacSHA256(key: token, data: phonePub)
        guard BridgeCrypto.constantTimeEquals(expectedMac, receivedMac) else {
            lock.unlock()
            return fail("bad_mac", human: "Pairing token mismatch — scan the latest QR code")
        }

        // Burn token immediately on valid pairing
        currentToken = nil
        tokenExpiresAt = nil
        lock.unlock()

        let phone = PairedPhone(
            deviceId: req.deviceId,
            deviceName: req.deviceName.isEmpty ? "Android Phone" : req.deviceName,
            staticPublicKey: phonePub
        )
        store.savePairedPhone(phone)

        DispatchQueue.main.async {
            self.activeSession = nil
            self.pairedPhones = self.store.pairedPhones()
            self.lastPairingError = nil
            self.lastPairingStatus = "Paired with \(phone.deviceName)"
        }

        return PairResponsePayload(
            type: "pair.ok",
            macDeviceId: store.getOrCreateDeviceId(),
            macName: macName
        )
    }

    public static func buildPairingUri(
        host: String,
        port: UInt16,
        macStaticPub: Data,
        pairToken: Data,
        macName: String
    ) -> String {
        var comps = URLComponents()
        comps.scheme = "droidbridge"
        comps.host = "pair"
        comps.queryItems = [
            URLQueryItem(name: "h", value: host),
            URLQueryItem(name: "p", value: String(port)),
            URLQueryItem(name: "k", value: BridgeCrypto.encodeBase64Url(macStaticPub)),
            URLQueryItem(name: "t", value: BridgeCrypto.encodeBase64Url(pairToken)),
            URLQueryItem(name: "n", value: macName)
        ]
        return comps.string ?? ""
    }

    public static func generateQRCodeImage(from string: String, size: CGFloat = 240) -> NSImage? {
        guard let data = string.data(using: .utf8),
              let filter = CIFilter(name: "CIQRCodeGenerator") else {
            return nil
        }
        filter.setValue(data, forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage else { return nil }
        let scale = size / max(output.extent.width, 1)
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let rep = NSCIImageRep(ciImage: scaled)
        let nsImage = NSImage(size: rep.size)
        nsImage.addRepresentation(rep)
        return nsImage
    }

    /// Returns the primary LAN IPv4 address (preferring en0 Wi-Fi).
    public static func localWiFiIPv4() -> String? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }

        var fallback: String?
        var ptr: UnsafeMutablePointer<ifaddrs>? = firstAddr
        while let current = ptr {
            defer { ptr = current.pointee.ifa_next }
            let flags = Int32(current.pointee.ifa_flags)
            guard (flags & (IFF_UP | IFF_RUNNING)) == (IFF_UP | IFF_RUNNING),
                  (flags & IFF_LOOPBACK) == 0,
                  let addr = current.pointee.ifa_addr,
                  addr.pointee.sa_family == UInt8(AF_INET) else {
                continue
            }
            var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let result = getnameinfo(
                addr,
                socklen_t(addr.pointee.sa_len),
                &hostname,
                socklen_t(hostname.count),
                nil,
                0,
                NI_NUMERICHOST
            )
            if result == 0 {
                let ip = String(cString: hostname)
                let ifName = String(cString: current.pointee.ifa_name)
                if ifName == "en0" {
                    return ip
                }
                if fallback == nil && !ip.hasPrefix("169.254.") {
                    fallback = ip
                }
            }
        }
        return fallback
    }
}
