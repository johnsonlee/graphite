import XCTest
@testable import GraphiteFrontend

final class SwiftSignatureTests: XCTestCase {
    func testInstanceMethod() {
        let s = SwiftSignature.parse("AcmeShop.CartService.checkout(order: AcmeShop.Order, method: AcmeShop.PaymentMethod) -> Swift.Bool", name: "checkout")
        XCTAssertEqual(s, SwiftSignature(declaringType: "AcmeShop.CartService", parameterTypes: ["AcmeShop.Order", "AcmeShop.PaymentMethod"], returnType: "Swift.Bool"))
    }

    func testStaticMethodWithUnlabeledParameter() {
        let s = SwiftSignature.parse("static AcmeShop.FeatureFlags.isEnabled(_: Swift.String, default: Swift.Bool) -> Swift.Bool", name: "isEnabled")
        XCTAssertEqual(s, SwiftSignature(declaringType: "AcmeShop.FeatureFlags", parameterTypes: ["Swift.String", "Swift.Bool"], returnType: "Swift.Bool", isStatic: true))
    }

    func testInitializerAndVoidReturn() {
        XCTAssertEqual(
            SwiftSignature.parse("AcmeShop.CartService.init(client: AcmeShop.PaymentClient) -> AcmeShop.CartService", name: "init"),
            SwiftSignature(declaringType: "AcmeShop.CartService", parameterTypes: ["AcmeShop.PaymentClient"], returnType: "AcmeShop.CartService")
        )
        XCTAssertEqual(
            SwiftSignature.parse("AcmeShop.Analytics.track(_: Swift.String, count: Swift.Int, sampled: Swift.Bool) -> ()", name: "track"),
            SwiftSignature(declaringType: "AcmeShop.Analytics", parameterTypes: ["Swift.String", "Swift.Int", "Swift.Bool"], returnType: "Swift.Void")
        )
    }

    func testOperatorsAndExtensionsAndGenerics() {
        XCTAssertEqual(
            SwiftSignature.parse("static Swift.Int.<= infix(Swift.Int, Swift.Int) -> Swift.Bool", name: "<="),
            SwiftSignature(declaringType: "Swift.Int", parameterTypes: ["Swift.Int", "Swift.Int"], returnType: "Swift.Bool", isStatic: true)
        )
        XCTAssertEqual(
            SwiftSignature.parse("(extension in Ext):Swift.String.shout(times: Swift.Int) -> Swift.String", name: "shout"),
            SwiftSignature(declaringType: "Swift.String", parameterTypes: ["Swift.Int"], returnType: "Swift.String")
        )
        XCTAssertEqual(
            SwiftSignature.parse("(extension in Swift):Swift.Collection.map<A, B where B1: Swift.Error>((A.Element) throws(B1) -> A1) throws(B1) -> [A1]", name: "map"),
            SwiftSignature(declaringType: "Swift.Collection", parameterTypes: ["(A.Element) throws(B1) -> A1"], returnType: "[A1]")
        )
        XCTAssertEqual(
            SwiftSignature.parse("Ext.Outer.Inner.run(_: [Swift.String], map: [Swift.String : Swift.Int]) -> Swift.String?", name: "run"),
            SwiftSignature(declaringType: "Ext.Outer.Inner", parameterTypes: ["[Swift.String]", "[Swift.String : Swift.Int]"], returnType: "Swift.String?")
        )
        XCTAssertEqual(
            SwiftSignature.parse("Ext.free(Swift.Int...) -> ()", name: "free"),
            SwiftSignature(declaringType: "Ext", parameterTypes: ["Swift.Int..."], returnType: "Swift.Void")
        )
    }

    func testFunctionTypedParametersKeepTheirNeighbours() {
        XCTAssertEqual(
            SwiftSignature.parse("Demo.Test.run(callback: () -> Swift.Void, count: Swift.Int) -> Swift.Bool", name: "run"),
            SwiftSignature(declaringType: "Demo.Test", parameterTypes: ["() -> Swift.Void", "Swift.Int"], returnType: "Swift.Bool")
        )
        XCTAssertEqual(
            SwiftSignature.parse("Demo.Test.map<A>(_: (A) -> Swift.Int, then: Swift.Array<A>) -> [Swift.Int]", name: "map"),
            SwiftSignature(declaringType: "Demo.Test", parameterTypes: ["(A) -> Swift.Int", "Swift.Array<A>"], returnType: "[Swift.Int]")
        )
        XCTAssertEqual(
            SwiftSignature.splitTopLevel("a: (Swift.Int) -> Swift.Bool, b: Swift.Dictionary<Swift.String, () -> ()>, c: Swift.Int"[...], separator: ","),
            ["a: (Swift.Int) -> Swift.Bool", "b: Swift.Dictionary<Swift.String, () -> ()>", "c: Swift.Int"]
        )
    }

    func testProperties() {
        XCTAssertEqual(
            SwiftSignature.parse("AcmeShop.CartService.orders : [Swift.String : AcmeShop.Order]", name: "orders"),
            SwiftSignature(declaringType: "AcmeShop.CartService", returnType: "[Swift.String : AcmeShop.Order]")
        )
        XCTAssertEqual(
            SwiftSignature.parse("static AcmeShop.CartService.maxItems : Swift.Int", name: "maxItems"),
            SwiftSignature(declaringType: "AcmeShop.CartService", returnType: "Swift.Int", isStatic: true)
        )
        XCTAssertEqual(
            SwiftSignature.parse("AcmeShop.CartService.(analytics in _D6064BEB0F43E13BEFB41E8D8C9242BD) : AcmeShop.Analytics", name: "analytics"),
            SwiftSignature(declaringType: "AcmeShop.CartService", returnType: "AcmeShop.Analytics")
        )
    }

    func testPrivateFunctions() {
        // Private members demangle with a file discriminator: `Qualified.(name in _HASH)(...)`.
        XCTAssertEqual(
            SwiftSignature.parse(
                "FoodTruckKit.AccountStore.(handleAuthorizationResult in _AE19B3C2D4E5F60718293A4B5C6D7E8F)(_: _AuthenticationServices_SwiftUI.ASAuthorizationResult, username: Swift.String?) async throws -> ()",
                name: "handleAuthorizationResult"
            ),
            SwiftSignature(declaringType: "FoodTruckKit.AccountStore", parameterTypes: ["_AuthenticationServices_SwiftUI.ASAuthorizationResult", "Swift.String?"], returnType: "Swift.Void")
        )
        XCTAssertEqual(
            SwiftSignature.parse(
                "FoodTruckKit.AccountStore.(passkeyRegistrationRequest in _AE19B3C2D4E5F60718293A4B5C6D7E8F)(username: Swift.String) -> AuthenticationServices.ASAuthorizationRequest",
                name: "passkeyRegistrationRequest"
            ),
            SwiftSignature(declaringType: "FoodTruckKit.AccountStore", parameterTypes: ["Swift.String"], returnType: "AuthenticationServices.ASAuthorizationRequest")
        )
        XCTAssertEqual(
            SwiftSignature.parse("static AcmeShop.PaymentClient.(validate in _0F1E2D3C)(amount: Swift.Double) throws -> Swift.Bool", name: "validate"),
            SwiftSignature(declaringType: "AcmeShop.PaymentClient", parameterTypes: ["Swift.Double"], returnType: "Swift.Bool", isStatic: true)
        )
        XCTAssertEqual(
            SwiftSignature.parse("AcmeShop.CartService.(refresh in _0F1E2D3C)() async -> ()", name: "refresh"),
            SwiftSignature(declaringType: "AcmeShop.CartService", parameterTypes: [], returnType: "Swift.Void")
        )
        XCTAssertEqual(
            SwiftSignature.parse("AcmeShop.Store.(first in _0F1E2D3C)<A where A: Swift.Equatable>(of: [A]) -> A?", name: "first"),
            SwiftSignature(declaringType: "AcmeShop.Store", parameterTypes: ["[A]"], returnType: "A?")
        )
        // A private property whose type is a function still reads as a property.
        XCTAssertEqual(
            SwiftSignature.parse("AcmeShop.CartService.(onChange in _0F1E2D3C) : (Swift.Int) -> ()", name: "onChange"),
            SwiftSignature(declaringType: "AcmeShop.CartService", returnType: "(Swift.Int) -> ()")
        )
        // The hash must close before anything else is read.
        XCTAssertNil(SwiftSignature.parse("AcmeShop.CartService.(broken in _0F1E2D3C", name: "broken"))
    }

    func testPropertiesInPrivateDeclaringTypes() {
        let owner = "Signal.(AddContactShareToContactsFlow in _855D7A027B4E81189F13437A2D9414CE)"
        XCTAssertEqual(
            SwiftSignature.parse("\(owner).contactShare : SignalUI.ContactShareViewModel", name: "contactShare"),
            SwiftSignature(declaringType: owner, returnType: "SignalUI.ContactShareViewModel")
        )
        XCTAssertEqual(
            SwiftSignature.parse("static \(owner).Nested.completion : (() -> ())?", name: "completion"),
            SwiftSignature(declaringType: owner + ".Nested", returnType: "(() -> ())?", isStatic: true)
        )
        // Preserve the separate private-member form when its declaring type is
        // private too; recognizing a property separator must not stop that fallback.
        XCTAssertEqual(
            SwiftSignature.parse("\(owner).(operation in _855D7A027B4E81189F13437A2D9414CE) : Swift.Int", name: "operation"),
            SwiftSignature(declaringType: owner, returnType: "Swift.Int")
        )
        XCTAssertNil(SwiftSignature.parse("\(owner).contactShare : SignalUI.ContactShareViewModel", name: "other"))
    }

    func testDeinitializersHaveVoidReturnWithoutAParameterClause() throws {
        // This USR is emitted for Signal's source declaration `deinit { ... }`.
        let usr = "s:16SignalServiceKit28AccountAttributesUpdaterImplCfd"
        let demangler = try XCTUnwrap(Demangler.locate())
        let demangled = try demangler.demangle([usr])
        let text = try XCTUnwrap(demangled[usr])
        XCTAssertEqual(text, "SignalServiceKit.AccountAttributesUpdaterImpl.deinit")
        XCTAssertEqual(
            SwiftSignature.parse(text, name: "deinit"),
            SwiftSignature(declaringType: "SignalServiceKit.AccountAttributesUpdaterImpl", returnType: "Swift.Void")
        )
        XCTAssertEqual(
            SwiftSignature.parse("Signal.(Owner in _01234567).__deallocating_deinit", name: "deinit"),
            SwiftSignature(declaringType: "Signal.(Owner in _01234567)", returnType: "Swift.Void")
        )
        XCTAssertNil(SwiftSignature.parse(text, name: "other"))
        XCTAssertNil(SwiftSignature.parse("Signal.Owner.other", name: "deinit"))
    }

    func testUnparsableForms() {
        XCTAssertNil(SwiftSignature.parse("closure #1 (AcmeShop.Item) -> Swift.Double in AcmeShop.Order.total.getter : Swift.Double", name: "total"))
        XCTAssertNil(SwiftSignature.parse("x #1 : [Swift.Int] in Ext.free(Swift.Int...) -> ()", name: "x"))
        XCTAssertNil(SwiftSignature.parse("AcmeShop.CartService", name: "checkout"))
        XCTAssertNil(SwiftSignature.parse("AcmeShop.CartService.other(x: Swift.Int) -> ()", name: "checkout"))
    }

    func testHelpers() {
        XCTAssertEqual(SwiftSignature.splitTopLevel("a: (Swift.Int, Swift.Int), b: [Swift.String : Swift.Int], c: Swift.Array<Swift.Int>"[...], separator: ","), ["a: (Swift.Int, Swift.Int)", "b: [Swift.String : Swift.Int]", "c: Swift.Array<Swift.Int>"])
        XCTAssertEqual(SwiftSignature.splitTopLevel(""[...], separator: ","), [])
        XCTAssertEqual(SwiftSignature.stripLabel("[Swift.String : Swift.Int]"), "[Swift.String : Swift.Int]")
        XCTAssertEqual(SwiftSignature.stripLabel("_: Swift.Int"), "Swift.Int")
        XCTAssertEqual(SwiftSignature.normalize(" () "), "Swift.Void")
    }
}
