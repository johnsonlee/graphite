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
