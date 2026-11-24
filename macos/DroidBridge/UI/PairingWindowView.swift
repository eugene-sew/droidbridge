import SwiftUI
import AppKit

struct PairingWindowView: View {
    @ObservedObject var pairingManager: PairingManager
    @ObservedObject var server: BridgeServer
    let onForgetPhone: (String) -> Void

    @State private var remainingSeconds: Int = 0
    @State private var copiedFeedback: Bool = false

    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 16) {
            Text("Pair Android Phone")
                .font(.title2.bold())

            if let session = pairingManager.activeSession, remainingSeconds > 0 {
                if let qrImage = PairingManager.generateQRCodeImage(from: session.qrUri, size: 220) {
                    Image(nsImage: qrImage)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: 220, height: 220)
                        .padding(12)
                        .background(Color.white)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .shadow(radius: 3)
                }

                Text(String(format: "QR expires in %d:%02d", remainingSeconds / 60, remainingSeconds % 60))
                    .font(.subheadline.monospacedDigit())
                    .foregroundColor(remainingSeconds < 20 ? .orange : .secondary)

                if let err = pairingManager.lastPairingError {
                    Text(err)
                        .font(.caption)
                        .foregroundColor(.orange)
                        .multilineTextAlignment(.center)
                }

                VStack(spacing: 4) {
                    Text("Manual fallback: \(session.host):\(session.port)")
                        .font(.caption.monospaced())
                        .foregroundColor(.secondary)
                    Button(copiedFeedback ? "Copied Pairing URI ✓" : "Copy Pairing URI") {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(session.qrUri, forType: .string)
                        copiedFeedback = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                            copiedFeedback = false
                        }
                    }
                    .buttonStyle(.link)
                    .font(.caption)
                }
            } else {
                VStack(spacing: 10) {
                    if let status = pairingManager.lastPairingStatus {
                        Text(status)
                            .font(.headline)
                            .foregroundColor(.green)
                    } else {
                        Text("Pairing code expired or inactive")
                            .foregroundColor(.secondary)
                    }
                    Button("Generate New Pairing QR") {
                        pairingManager.startPairingSession(port: server.listeningPort)
                        refreshCountdown()
                    }
                    .buttonStyle(.borderedProminent)
                }
                .frame(height: 240)
            }

            Divider()

            VStack(alignment: .leading, spacing: 8) {
                Text("Paired Phones")
                    .font(.headline)

                if pairingManager.pairedPhones.isEmpty {
                    Text("No paired phones yet.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    List {
                        ForEach(pairingManager.pairedPhones) { phone in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(phone.deviceName)
                                        .fontWeight(.medium)
                                    Text("Paired \(phone.pairedAt.formatted(date: .abbreviated, time: .shortened))")
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                                Spacer()
                                Button("Forget", role: .destructive) {
                                    onForgetPhone(phone.deviceId)
                                }
                                .buttonStyle(.bordered)
                                .controlSize(.small)
                            }
                        }
                    }
                    .frame(height: 130)
                }
            }
        }
        .padding(20)
        .frame(width: 420)
        .onAppear { refreshCountdown() }
        .onChange(of: pairingManager.activeSession?.expiresAt) { _, _ in refreshCountdown() }
        .onReceive(timer) { _ in refreshCountdown() }
    }

    private func refreshCountdown() {
        if let exp = pairingManager.activeSession?.expiresAt {
            remainingSeconds = max(0, Int(exp.timeIntervalSinceNow.rounded(.up)))
        } else {
            remainingSeconds = 0
        }
    }
}
