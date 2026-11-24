import XCTest
import Network
@testable import DroidBridge

final class ProtocolAndCryptoTests: XCTestCase {

    private func loadVectorJSON(_ fileName: String) throws -> [String: Any] {
        // Locate protocol/test-vectors relative to this source file
        let thisFile = URL(fileURLWithPath: #filePath)
        let repoRoot = thisFile
            .deletingLastPathComponent() // DroidBridgeTests
            .deletingLastPathComponent() // macos
            .deletingLastPathComponent() // repo root
        let url = repoRoot.appendingPathComponent("protocol/test-vectors/\(fileName)")
        let data = try Data(contentsOf: url)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    func testSharedCryptoVectorsMatchByteForByte() throws {
        let root = try loadVectorJSON("crypto_vectors.json")

        // 1. Pairing HMAC-SHA256
        let pairing = try XCTUnwrap(root["pairing"] as? [String: String])
        let pairToken = try XCTUnwrap(BridgeCrypto.fromHex(pairing["pairTokenHex"]!))
        let phonePub = try XCTUnwrap(BridgeCrypto.fromHex(pairing["phoneStaticPubHex"]!))
        let expectedHmac = try XCTUnwrap(BridgeCrypto.fromHex(pairing["expectedHmacHex"]!))
        let actualHmac = BridgeCrypto.hmacSHA256(key: pairToken, data: phonePub)
        XCTAssertEqual(actualHmac, expectedHmac)
        XCTAssertEqual(BridgeCrypto.encodeBase64Url(actualHmac), pairing["expectedHmacBase64Url"])
        XCTAssertEqual(BridgeCrypto.encodeBase64Url(pairToken), pairing["pairTokenBase64Url"])

        // 2. X25519 + HKDF-SHA256 key derivation for both roles & channels
        let hs = try XCTUnwrap(root["handshake"] as? [String: Any])
        let phoneStaticPriv = try XCTUnwrap(BridgeCrypto.fromHex(hs["phoneStaticPrivHex"] as! String))
        let phoneStaticPub = try XCTUnwrap(BridgeCrypto.fromHex(hs["phoneStaticPubHex"] as! String))
        let phoneEphPriv = try XCTUnwrap(BridgeCrypto.fromHex(hs["phoneEphPrivHex"] as! String))
        let phoneEphPub = try XCTUnwrap(BridgeCrypto.fromHex(hs["phoneEphPubHex"] as! String))
        let macStaticPriv = try XCTUnwrap(BridgeCrypto.fromHex(hs["macStaticPrivHex"] as! String))
        let macStaticPub = try XCTUnwrap(BridgeCrypto.fromHex(hs["macStaticPubHex"] as! String))
        let macEphPriv = try XCTUnwrap(BridgeCrypto.fromHex(hs["macEphPrivHex"] as! String))
        let macEphPub = try XCTUnwrap(BridgeCrypto.fromHex(hs["macEphPubHex"] as! String))

        XCTAssertEqual(try BridgeCrypto.derivePublicKey(privateKey: phoneStaticPriv), phoneStaticPub)
        XCTAssertEqual(try BridgeCrypto.derivePublicKey(privateKey: phoneEphPriv), phoneEphPub)
        XCTAssertEqual(try BridgeCrypto.derivePublicKey(privateKey: macStaticPriv), macStaticPub)
        XCTAssertEqual(try BridgeCrypto.derivePublicKey(privateKey: macEphPriv), macEphPub)

        let channels = try XCTUnwrap(hs["channels"] as? [String: [String: String]])
        for channelName in ["control", "video"] {
            let expectedCh = try XCTUnwrap(channels[channelName])
            let expectedP2M = try XCTUnwrap(BridgeCrypto.fromHex(expectedCh["keyPhoneToMacHex"]!))
            let expectedM2P = try XCTUnwrap(BridgeCrypto.fromHex(expectedCh["keyMacToPhoneHex"]!))

            let phoneDerived = try BridgeCrypto.deriveSessionKeys(
                role: .phoneInitiator,
                ownStaticPriv: phoneStaticPriv, ownEphPriv: phoneEphPriv,
                peerStaticPub: macStaticPub, peerEphPub: macEphPub,
                channelName: channelName
            )
            let macDerived = try BridgeCrypto.deriveSessionKeys(
                role: .macResponder,
                ownStaticPriv: macStaticPriv, ownEphPriv: macEphPriv,
                peerStaticPub: phoneStaticPub, peerEphPub: phoneEphPub,
                channelName: channelName
            )

            XCTAssertEqual(phoneDerived.keyPhoneToMac, expectedP2M)
            XCTAssertEqual(phoneDerived.keyMacToPhone, expectedM2P)
            XCTAssertEqual(macDerived.keyPhoneToMac, expectedP2M)
            XCTAssertEqual(macDerived.keyMacToPhone, expectedM2P)
        }

        // 3. ChaCha20-Poly1305 frames + 64-bit nonce encoding
        let frames = try XCTUnwrap(root["frames"] as? [[String: Any]])
        for entry in frames {
            let key = try XCTUnwrap(BridgeCrypto.fromHex(entry["keyHex"] as! String))
            let f = try XCTUnwrap(entry["frame"] as? [String: Any])
            let counter = (f["counter"] as! NSNumber).uint64Value
            let msgType = UInt8((f["msgType"] as! NSNumber).intValue)
            let payload = try XCTUnwrap(BridgeCrypto.fromHex(f["payloadHex"] as! String))
            let expectedNonce = try XCTUnwrap(BridgeCrypto.fromHex(f["nonceHex"] as! String))
            let expectedPlain = try XCTUnwrap(BridgeCrypto.fromHex(f["plaintextHex"] as! String))
            let expectedCt = try XCTUnwrap(BridgeCrypto.fromHex(f["ciphertextAndTagHex"] as! String))
            let expectedWire = try XCTUnwrap(BridgeCrypto.fromHex(f["wireFrameHex"] as! String))

            XCTAssertEqual(BridgeCrypto.buildNonce(counter: counter), expectedNonce)

            var actualPlain = Data([msgType])
            actualPlain.append(payload)
            XCTAssertEqual(actualPlain, expectedPlain)

            let actualCt = try BridgeCrypto.sealChaCha20Poly1305(key: key, counter: counter, plaintext: actualPlain)
            XCTAssertEqual(actualCt, expectedCt)

            let dec = try BridgeCrypto.openChaCha20Poly1305(key: key, counter: counter, ciphertextAndTag: expectedCt)
            XCTAssertEqual(dec, expectedPlain)

            var wire = Data()
            wire.appendBigEndian(UInt32(actualCt.count))
            wire.append(actualCt)
            XCTAssertEqual(wire, expectedWire)
        }
    }

    func testSharedFramingVectorsMatchBinaryVideoCodecs() throws {
        let root = try loadVectorJSON("framing_vectors.json")
        let vidKey = try XCTUnwrap(BridgeCrypto.fromHex(root["videoKeyPhoneToMacHex"] as! String))

        let sender = FrameCipher(sendKey: vidKey, recvKey: vidKey, maxFrameBytes: ProtocolConstants.maxVideoFrameBytes)
        let receiver = FrameCipher(sendKey: vidKey, recvKey: vidKey, maxFrameBytes: ProtocolConstants.maxVideoFrameBytes)

        // video.config (counter 0)
        let vcObj = try XCTUnwrap(root["videoConfig"] as? [String: Any])
        let vc = VideoConfigPayload(
            width: UInt16((vcObj["width"] as! NSNumber).intValue),
            height: UInt16((vcObj["height"] as! NSNumber).intValue),
            rotation: UInt16((vcObj["rotation"] as! NSNumber).intValue),
            sps: try XCTUnwrap(BridgeCrypto.fromHex(vcObj["spsHex"] as! String)),
            pps: try XCTUnwrap(BridgeCrypto.fromHex(vcObj["ppsHex"] as! String))
        )
        let expectedVcPayload = try XCTUnwrap(BridgeCrypto.fromHex(vcObj["payloadHex"] as! String))
        XCTAssertEqual(vc.encode(), expectedVcPayload)
        XCTAssertEqual(try VideoConfigPayload.decode(expectedVcPayload), vc)

        let vcEnc = try XCTUnwrap(vcObj["encryptedFrame"] as? [String: Any])
        let expectedVcWire = try XCTUnwrap(BridgeCrypto.fromHex(vcEnc["wireFrameHex"] as! String))
        let actualVcWire = try sender.sealFrame(msgType: ProtocolConstants.msgVideoConfig, payload: vc.encode())
        XCTAssertEqual(actualVcWire, expectedVcWire)

        let (vcType, vcDec) = try receiver.openWireFrame(actualVcWire)
        XCTAssertEqual(vcType, ProtocolConstants.msgVideoConfig)
        XCTAssertEqual(try VideoConfigPayload.decode(vcDec), vc)

        // video.frame (counter 1)
        let vfObj = try XCTUnwrap(root["videoFrame"] as? [String: Any])
        let vf = VideoFramePayload(
            ptsMicros: (vfObj["ptsMicros"] as! NSNumber).uint64Value,
            flags: UInt8((vfObj["flags"] as! NSNumber).intValue),
            nalData: try XCTUnwrap(BridgeCrypto.fromHex(vfObj["nalDataHex"] as! String))
        )
        XCTAssertTrue(vf.isKeyframe)
        let expectedVfPayload = try XCTUnwrap(BridgeCrypto.fromHex(vfObj["payloadHex"] as! String))
        XCTAssertEqual(vf.encode(), expectedVfPayload)
        XCTAssertEqual(try VideoFramePayload.decode(expectedVfPayload), vf)

        let vfEnc = try XCTUnwrap(vfObj["encryptedFrame"] as? [String: Any])
        let expectedVfWire = try XCTUnwrap(BridgeCrypto.fromHex(vfEnc["wireFrameHex"] as! String))
        let actualVfWire = try sender.sealFrame(msgType: ProtocolConstants.msgVideoFrame, payload: vf.encode())
        XCTAssertEqual(actualVfWire, expectedVfWire)

        let (vfType, vfDec) = try receiver.openWireFrame(actualVfWire)
        XCTAssertEqual(vfType, ProtocolConstants.msgVideoFrame)
        XCTAssertEqual(try VideoFramePayload.decode(vfDec), vf)
    }

    func testTamperedCiphertextFailsAndPoisonsSession() throws {
        let key = Data((1...32).map { UInt8($0) })
        let sender = FrameCipher(sendKey: key, recvKey: key)
        let receiver = FrameCipher(sendKey: key, recvKey: key)

        let wire0 = try sender.sealFrame(msgType: ProtocolConstants.msgPing, payload: try JSONEncoder().encode(PingPongPayload(t: 111)))
        let wire1 = try sender.sealFrame(msgType: ProtocolConstants.msgPing, payload: try JSONEncoder().encode(PingPongPayload(t: 222)))

        // Flip 1 byte in ciphertext
        var tampered = wire0
        tampered[10] ^= 0x01

        XCTAssertThrowsError(try receiver.openWireFrame(tampered))
        XCTAssertTrue(receiver.isPoisoned)

        // Subsequent valid frame must also be rejected
        XCTAssertThrowsError(try receiver.openWireFrame(wire1))
    }

    func testPairingManagerValidatesHmacAndBurnsToken() throws {
        let suiteName = "com.droidbridge.test.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let store = KeychainStore(service: suiteName, defaults: defaults, useDiskFallback: false)
        let pm = PairingManager(store: store)
        let session = pm.startPairingSession(port: 47321)

        let phoneKP = BridgeCrypto.generateX25519KeyPair()
        let badMac = BridgeCrypto.randomBytes(count: 32)
        let badReq = PairRequestPayload(
            type: "pair.request",
            deviceId: "phone-1",
            deviceName: "Pixel 9",
            phoneStaticPub: BridgeCrypto.encodeBase64Url(phoneKP.publicKey),
            mac: BridgeCrypto.encodeBase64Url(badMac)
        )
        let badResp = pm.handlePairRequest(badReq)
        XCTAssertEqual(badResp.type, "pair.error")
        XCTAssertEqual(badResp.reason, "bad_mac")

        let goodMac = BridgeCrypto.hmacSHA256(key: session.token, data: phoneKP.publicKey)
        let goodReq = PairRequestPayload(
            type: "pair.request",
            deviceId: "phone-1",
            deviceName: "Pixel 9",
            phoneStaticPub: BridgeCrypto.encodeBase64Url(phoneKP.publicKey),
            mac: BridgeCrypto.encodeBase64Url(goodMac)
        )
        let goodResp = pm.handlePairRequest(goodReq)
        XCTAssertEqual(goodResp.type, "pair.ok")
        XCTAssertNotNil(store.findPairedPhone(publicKey: phoneKP.publicKey))

        // Replay must fail because token was burned
        let replayResp = pm.handlePairRequest(goodReq)
        XCTAssertEqual(replayResp.type, "pair.error")
        XCTAssertEqual(replayResp.reason, "not_pairing")
    }

    func testAudioBinaryRoundTrip() throws {
        let cfg = AudioConfigPayload(sampleRate: 48000, channels: 2, audioSpecificConfig: Data([0x11, 0x90]))
        XCTAssertEqual(try AudioConfigPayload.decode(cfg.encode()), cfg)

        let frame = AudioFramePayload(ptsMicros: 555666777, aacData: Data([0x21, 0x00, 0x49, 0x90]))
        XCTAssertEqual(try AudioFramePayload.decode(frame.encode()), frame)
    }

    func testLargeFragmentedKeyframeRoundTripOverLoopbackServer() throws {
        let suiteName = "com.droidbridge.streamtest.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let store = KeychainStore(service: suiteName, defaults: defaults, useDiskFallback: false)
        let pm = PairingManager(store: store)
        let server = BridgeServer(store: store, pairingManager: pm)

        // Pre-pair a synthetic phone
        let phoneStatic = BridgeCrypto.generateX25519KeyPair()
        let phoneEph = BridgeCrypto.generateX25519KeyPair()
        let macStatic = store.getOrCreateStaticKeyPair()
        store.savePairedPhone(PairedPhone(deviceId: "p-frag", deviceName: "FragTestPhone", staticPublicKey: phoneStatic.publicKey))

        let receivedBigFrame = expectation(description: "Received 320KB encrypted video frame intact")
        var receivedPayloadSize = 0

        final class CollectorDelegate: BridgeServerDelegate {
            var onVideo: ((UInt8, Data) -> Void)?
            func bridgeServer(_ server: BridgeServer, didConnectControl phone: PairedPhone, hello: HelloPayload) {}
            func bridgeServer(_ server: BridgeServer, didDisconnectControl phone: PairedPhone?) {}
            func bridgeServer(_ server: BridgeServer, didReceiveControlMessage type: UInt8, payload: Data) {}
            func bridgeServer(_ server: BridgeServer, didReceiveVideoMessage type: UInt8, payload: Data) { onVideo?(type, payload) }
            func bridgeServerDidDisconnectVideo(_ server: BridgeServer) {}
        }

        let delegate = CollectorDelegate()
        delegate.onVideo = { type, payload in
            if type == ProtocolConstants.msgVideoFrame {
                receivedPayloadSize = payload.count
                receivedBigFrame.fulfill()
            }
        }
        server.delegate = delegate
        server.start(preferredPort: 0, advertiseBonjour: false)

        // Wait briefly for NWListener to bind an ephemeral port
        let startDeadline = Date().addingTimeInterval(3)
        while server.listeningPort == ProtocolConstants.defaultPort && Date() < startDeadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.05))
        }
        let port = server.listeningPort
        defer { server.stop() }

        // Connect as a raw TCP client and send a 320 KB encrypted video frame split into 4KB chunks
        let conn = NWConnection(
            host: "127.0.0.1",
            port: NWEndpoint.Port(rawValue: port)!,
            using: .tcp
        )
        let clientQ = DispatchQueue(label: "test.client")
        let connectedExp = expectation(description: "Client connected")
        conn.stateUpdateHandler = { state in
            if case .ready = state { connectedExp.fulfill() }
        }
        conn.start(queue: clientQ)
        wait(for: [connectedExp], timeout: 3.0)
        defer { conn.cancel() }

        // 1. Send 69-byte video channel handshake
        var hs = Data()
        hs.append(ProtocolConstants.magic)
        hs.append(ProtocolConstants.modeVideo)
        hs.append(phoneStatic.publicKey)
        hs.append(phoneEph.publicKey)
        conn.send(content: hs, completion: .idempotent)

        // 2. Read 37-byte server handshake
        let hsRespExp = expectation(description: "Server handshake received")
        var macEphPub = Data()
        conn.receive(minimumIncompleteLength: 37, maximumLength: 37) { data, _, _, _ in
            if let data, data.count == 37 {
                macEphPub = Data(data.suffix(32))
                hsRespExp.fulfill()
            }
        }
        wait(for: [hsRespExp], timeout: 3.0)
        XCTAssertEqual(macEphPub.count, 32)

        let keys = try BridgeCrypto.deriveSessionKeys(
            role: .phoneInitiator,
            ownStaticPriv: phoneStatic.privateKey,
            ownEphPriv: phoneEph.privateKey,
            peerStaticPub: macStatic.publicKey,
            peerEphPub: macEphPub,
            channelName: ProtocolConstants.channelVideo
        )
        let cipher = FrameCipher(sendKey: keys.keyPhoneToMac, recvKey: keys.keyMacToPhone, maxFrameBytes: ProtocolConstants.maxVideoFrameBytes)

        // 3. Send hello
        let helloData = try JSONEncoder().encode(HelloPayload(deviceId: "p-frag", deviceName: "FragTestPhone", appVersion: "1.0", protocolVersion: 1, capabilities: ["mirror"]))
        let helloWire = try cipher.sealFrame(msgType: ProtocolConstants.msgHello, payload: helloData)
        conn.send(content: helloWire, completion: .idempotent)

        // 4. Send a 320 KB video frame intentionally fragmented into small 4 KB TCP writes
        let bigNal = Data(repeating: 0xAB, count: 320 * 1024)
        let bigFrame = VideoFramePayload(ptsMicros: 999999, flags: VideoFramePayload.flagKeyframe, nalData: bigNal).encode()
        let bigWire = try cipher.sealFrame(msgType: ProtocolConstants.msgVideoFrame, payload: bigFrame)

        var offset = 0
        while offset < bigWire.count {
            let end = min(offset + 4096, bigWire.count)
            conn.send(content: bigWire.subdata(in: offset..<end), completion: .idempotent)
            offset = end
        }

        wait(for: [receivedBigFrame], timeout: 5.0)
        XCTAssertEqual(receivedPayloadSize, bigFrame.count)
    }
}
