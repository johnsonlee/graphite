import Foundation

/// A Swift class Objective-C calls through the generated `AcmeApp-Swift.h`.
@objcMembers
final class SwiftAuditLog: NSObject {
    private static var events: [String] = []

    @discardableResult
    static func record(_ event: String) -> Bool {
        events.append(event)
        return FeatureFlags.isEnabled("audit.enabled", default: true)
    }
}
