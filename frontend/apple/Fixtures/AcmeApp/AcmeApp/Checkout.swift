import Foundation

enum FeatureFlags {
    static func isEnabled(_ key: String, default value: Bool) -> Bool {
        UserDefaults.standard.object(forKey: key) as? Bool ?? value
    }
}

final class PaymentClient {
    let endpoint: String

    init(endpoint: String) {
        self.endpoint = endpoint
    }

    func send(path: String, retries: Int) -> Bool {
        retries > 0 && !path.isEmpty
    }
}

final class CheckoutService {
    static let maxAmount = 10_000.0

    private let client: PaymentClient

    init(client: PaymentClient) {
        self.client = client
    }

    @discardableResult
    func pay(amount: Double) -> Bool {
        guard FeatureFlags.isEnabled("payments.charge", default: true) else { return false }
        guard amount <= CheckoutService.maxAmount else { return false }
        return client.send(path: "/v1/charge", retries: 3)
    }
}
