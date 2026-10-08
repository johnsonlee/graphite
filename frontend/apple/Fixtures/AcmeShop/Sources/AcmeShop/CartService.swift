public final class CartService: Repository, Auditable {
    public static let maxItems = 50

    public let client: PaymentClient
    public var orders: [String: Order] = [:]
    public let auditTag = "cart"

    private let analytics = Analytics()

    public init(client: PaymentClient) {
        self.client = client
    }

    public func find(id: String) -> Order? {
        orders[id]
    }

    @available(*, deprecated, message: "use checkout(order:method:)")
    public func checkout(order: Order) -> Bool {
        checkout(order: order, method: .card)
    }

    public func checkout(order: Order, method: PaymentMethod) -> Bool {
        guard order.items.count <= CartService.maxItems else {
            analytics.track("checkout.rejected", count: 1, sampled: false)
            return false
        }
        analytics.track("checkout", count: order.items.count, sampled: true)
        let paid = client.charge(amount: order.total, currency: "USD", method: method)
        if paid {
            orders[order.id] = nil
        }
        return paid
    }
}
