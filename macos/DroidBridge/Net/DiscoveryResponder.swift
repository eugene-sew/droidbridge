import Foundation
import Darwin
import os

/// Lightweight UDP LAN discovery responder listening on port 47322.
/// Responds to paired Android broadcast probes `DBP1|<macDeviceId>` with `DBR1|<macDeviceId>|<tcpPort>`
/// so the phone immediately rediscovers this Mac on any new Wi-Fi network even if mDNS is blocked.
public final class DiscoveryResponder {
    private let logger = Logger(subsystem: "com.droidbridge.macos", category: "Discovery")
    private let macDeviceId: String
    private var tcpPort: UInt16
    private let lock = NSLock()
    private var fd: Int32 = -1
    private var readSource: DispatchSourceRead?
    private let queue = DispatchQueue(label: "com.droidbridge.udp.discovery", qos: .utility)

    public init(macDeviceId: String, tcpPort: UInt16) {
        self.macDeviceId = macDeviceId
        self.tcpPort = tcpPort
    }

    public func updateTcpPort(_ port: UInt16) {
        lock.lock()
        self.tcpPort = port
        lock.unlock()
    }

    public func start() {
        queue.async { [weak self] in
            self?.startSocket()
        }
    }

    public func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            self.readSource?.cancel()
            self.readSource = nil
            if self.fd >= 0 {
                close(self.fd)
                self.fd = -1
            }
        }
    }

    private func startSocket() {
        if fd >= 0 { return }
        let sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard sock >= 0 else {
            logger.error("Failed to create UDP discovery socket")
            return
        }

        var yes: Int32 = 1
        setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(sock, SOL_SOCKET, SO_REUSEPORT, &yes, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(sock, SOL_SOCKET, SO_BROADCAST, &yes, socklen_t(MemoryLayout<Int32>.size))
        _ = fcntl(sock, F_SETFL, O_NONBLOCK)

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = ProtocolConstants.udpDiscoveryPort.bigEndian
        addr.sin_addr = in_addr(s_addr: INADDR_ANY)

        let bindResult = withUnsafePointer(to: &addr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                bind(sock, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bindResult == 0 else {
            logger.warning("Failed to bind UDP discovery port \(ProtocolConstants.udpDiscoveryPort): errno=\(errno)")
            close(sock)
            return
        }

        self.fd = sock
        let source = DispatchSource.makeReadSource(fileDescriptor: sock, queue: queue)
        source.setEventHandler { [weak self] in
            self?.handleReadable(sock: sock)
        }
        source.resume()
        self.readSource = source
        logger.info("UDP LAN discovery listening on port \(ProtocolConstants.udpDiscoveryPort)")
    }

    private func handleReadable(sock: Int32) {
        var buffer = [UInt8](repeating: 0, count: 256)
        var senderAddr = sockaddr_in()
        var addrLen = socklen_t(MemoryLayout<sockaddr_in>.size)

        let received = withUnsafeMutablePointer(to: &senderAddr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                recvfrom(sock, &buffer, buffer.count, 0, sa, &addrLen)
            }
        }
        guard received > 0,
              let text = String(bytes: buffer[0..<received], encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) else {
            return
        }

        // Expect "DBP1|<macDeviceId>"
        let parts = text.split(separator: "|")
        guard parts.count == 2, parts[0] == "DBP1", String(parts[1]) == macDeviceId else {
            return
        }

        lock.lock()
        let port = self.tcpPort
        lock.unlock()

        let reply = "DBR1|\(macDeviceId)|\(port)"
        let replyBytes = Array(reply.utf8)
        _ = withUnsafePointer(to: &senderAddr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                sendto(sock, replyBytes, replyBytes.count, 0, sa, addrLen)
            }
        }
    }
}
