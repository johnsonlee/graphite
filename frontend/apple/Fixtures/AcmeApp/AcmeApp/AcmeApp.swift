import SwiftUI

@main
struct AcmeApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    private let checkout = CheckoutService(client: PaymentClient(endpoint: "https://api.acme.example"))

    var body: some Scene {
        WindowGroup {
            Text(checkout.pay(amount: 42.0) ? "Paid" : "Declined")
        }
    }
}
