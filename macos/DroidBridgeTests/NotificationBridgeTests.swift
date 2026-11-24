import XCTest
@testable import DroidBridge

final class NotificationBridgeTests: XCTestCase {

    private func makeManager() -> (NotificationBridgeManager, String) {
        let suite = "com.droidbridge.notiftest.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        return (NotificationBridgeManager(defaults: defaults), suite)
    }

    private func samplePayload(
        key: String = "0|com.google.android.apps.messaging|1",
        pkg: String = "com.google.android.apps.messaging",
        appName: String = "Messages",
        title: String? = "Kofi",
        text: String? = "Are we still on for 6?",
        conversationTitle: String? = nil,
        isGroup: Bool = false,
        messages: [NotifMessage] = [],
        actions: [NotifAction] = [
            NotifAction(index: 0, title: "Reply", hasRemoteInput: true)
        ]
    ) -> NotifPostedPayload {
        NotifPostedPayload(
            key: key,
            pkg: pkg,
            appName: appName,
            title: title,
            text: text,
            subText: nil,
            conversationTitle: conversationTitle,
            messages: messages,
            postTime: 1732300000000,
            category: "msg",
            isGroupConversation: isGroup,
            actions: actions,
            largeIconPngBase64: nil
        )
    }

    func testFormatsGroupConversationAndReplyableCategory() {
        let (mgr, suite) = makeManager()
        defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }

        let payload = samplePayload(
            conversationTitle: "Weekend Crew",
            isGroup: true,
            messages: [
                NotifMessage(sender: "Ama", text: "See you at 6!", t: 1732300000000)
            ]
        )

        let formatted = mgr.formatNotification(payload)
        XCTAssertNotNil(formatted)
        XCTAssertEqual(formatted?.title, "Weekend Crew")
        XCTAssertEqual(formatted?.subtitle, "Messages")
        XCTAssertEqual(formatted?.body, "Ama: See you at 6!")
        XCTAssertEqual(formatted?.threadIdentifier, "com.google.android.apps.messaging:Weekend Crew")
        XCTAssertEqual(formatted?.categoryIdentifier, NotificationBridgeManager.categoryReplyable)
    }

    func testRespectsMuteListPrivacyToggleAndPause() {
        let (mgr, suite) = makeManager()
        defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }

        let payload = samplePayload()

        // Privacy toggle hides message body
        mgr.showMessageContent = false
        XCTAssertEqual(mgr.formatNotification(payload)?.body, "New notification")
        mgr.showMessageContent = true

        // Muted package is skipped
        mgr.setMuted(true, pkg: payload.pkg)
        XCTAssertNil(mgr.formatNotification(payload))
        mgr.setMuted(false, pkg: payload.pkg)
        XCTAssertNotNil(mgr.formatNotification(payload))

        // Pause skips all
        mgr.notificationsPaused = true
        XCTAssertNil(mgr.formatNotification(payload))
    }
}
