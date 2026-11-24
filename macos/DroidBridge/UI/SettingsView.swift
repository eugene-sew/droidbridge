import SwiftUI

struct SettingsView: View {
    @ObservedObject var coordinator: AppCoordinator
    @ObservedObject var notificationManager: NotificationBridgeManager
    @ObservedObject var server: BridgeServer

    var body: some View {
        TabView {
            generalTab
                .tabItem { Label("General", systemImage: "gear") }

            notificationsTab
                .tabItem { Label("Notifications", systemImage: "bell") }

            securityTab
                .tabItem { Label("Security & Limits", systemImage: "lock.shield") }
        }
        .padding(16)
        .frame(minWidth: 460, minHeight: 460)
    }

    private var generalTab: some View {
        Form {
            Section("Startup & Sync") {
                Toggle("Launch DroidBridge at login", isOn: Binding(
                    get: { coordinator.launchAtLoginEnabled },
                    set: { coordinator.setLaunchAtLogin($0) }
                ))

                Toggle("Sync Mac clipboard to phone", isOn: $coordinator.clipboardSyncEnabled)
            }

            Section("Local Network Server") {
                LabeledContent("Bonjour Service") {
                    Text(ProtocolConstants.bonjourType).font(.body.monospaced())
                }
                LabeledContent("TCP Port") {
                    Text(String(server.listeningPort)).font(.body.monospaced())
                }
                LabeledContent("LAN IP Address") {
                    Text(PairingManager.localWiFiIPv4() ?? "Unavailable").font(.body.monospaced())
                }
                LabeledContent("Status") {
                    switch server.state {
                    case let .connected(name):
                        Text("Connected to \(name)").foregroundColor(.green)
                    case let .listening(port):
                        Text("Listening on port \(port)").foregroundColor(.secondary)
                    case .stopped:
                        Text("Stopped").foregroundColor(.red)
                    }
                }
            }
        }
        .formStyle(.grouped)
    }

    private var notificationsTab: some View {
        VStack(alignment: .leading, spacing: 12) {
            Form {
                Section("Behavior") {
                    Toggle("Pause all notifications", isOn: $notificationManager.notificationsPaused)
                    Toggle("Show message content in banners", isOn: $notificationManager.showMessageContent)
                }
            }
            .formStyle(.grouped)
            .frame(height: 120)

            Text("Per-App Allow / Mute List")
                .font(.headline)
                .padding(.horizontal, 4)

            let sortedApps = notificationManager.seenApps.sorted { $0.value.localizedCaseInsensitiveCompare($1.value) == .orderedAscending }
            if sortedApps.isEmpty {
                Text("Apps will appear here automatically as notifications arrive from your Android phone.")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .padding(.horizontal, 4)
                Spacer()
            } else {
                List {
                    ForEach(sortedApps, id: \.key) { pkg, appName in
                        Toggle(isOn: Binding(
                            get: { !notificationManager.isMuted(pkg: pkg) },
                            set: { allowed in notificationManager.setMuted(!allowed, pkg: pkg) }
                        )) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(appName).fontWeight(.medium)
                                Text(pkg).font(.caption).foregroundColor(.secondary)
                            }
                        }
                    }
                }
            }
        }
    }

    private var securityTab: some View {
        Form {
            Section("End-to-End Encryption") {
                Text("All control, notification, input, and video traffic is encrypted with X25519 + HKDF-SHA256 + ChaCha20-Poly1305 using per-connection ephemeral keys and static keys pinned during QR pairing.")
                    .font(.callout)
            }
            Section("Platform Security Boundaries") {
                Text("• Android 14+ requires approving screen sharing on the phone for every mirroring session.\n• Banking and DRM apps marked FLAG_SECURE render black while mirrored.\n• Android 15+ may redact one-time passcodes in notifications.\n• Phone → Mac clipboard sync works while the DroidBridge app or keyboard is in the foreground.")
                    .font(.callout)
            }
        }
        .formStyle(.grouped)
    }
}
