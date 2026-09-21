open class BaseClient {
    public let baseURL: String

    public init(baseURL: String) {
        self.baseURL = baseURL
    }

    open func send(path: String, retries: Int) -> Bool {
        retries >= 0 && !path.isEmpty
    }
}

public final class PaymentClient: BaseClient {
    public static let defaultTimeout = 30

    public override func send(path: String, retries: Int) -> Bool {
        super.send(path: path, retries: retries)
    }

    @discardableResult
    public func charge(amount: Double, currency: String, method: PaymentMethod) -> Bool {
        guard FeatureFlags.isEnabled("payments.charge", default: true) else { return false }
        let sent = send(path: "/v1/charge", retries: 3)
        return sent && amount > 0.5 && currency == "USD"
    }
}

public enum FeatureFlags {
    public static func isEnabled(_ key: String, default value: Bool) -> Bool {
        value
    }
}

public struct Analytics {
    public init() {}

    public func track(_ event: String, count: Int, sampled: Bool) {
        _ = (event, count, sampled)
    }
}
