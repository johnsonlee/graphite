import AcmeShop

/// The storefront's command line: checks out one order and reports whether it went through.
@main
struct Tool {
    static func main() {
        let service = CartService(client: PaymentClient(baseURL: "https://pay.acme.test"))
        let order = Order(id: "order-1", items: [Item(sku: "sku-1", price: 9.5)])
        let paid = service.checkout(order: order, method: .applePay)
        print(paid ? "paid" : "declined")
    }
}
