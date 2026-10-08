public struct Item {
    public let sku: String
    public let price: Double

    public init(sku: String, price: Double) {
        self.sku = sku
        self.price = price
    }
}

public struct Order {
    public let id: String
    public var items: [Item]

    public var total: Double {
        items.reduce(0) { $0 + $1.price }
    }

    public init(id: String, items: [Item]) {
        self.id = id
        self.items = items
    }
}

public enum PaymentMethod {
    case card
    case applePay
}

public protocol Repository {
    func find(id: String) -> Order?
}

public protocol Auditable: AnyObject {
    var auditTag: String { get }
}
