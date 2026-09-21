import XCTest
@testable import GraphiteFrontend

final class SyntaxPassTests: XCTestCase {
    func testLiteralArgumentsAreKeyedByTheCalledName() {
        let source = """
        let flags = FeatureFlags.isEnabled("payments.charge", default: true)
        analytics.track("checkout", count: order.items.count, sampled: false)
        let client = PaymentClient(baseURL: "https://example.test", timeout: 30, ratio: -1.5, retries: -2, tag: nil)
        values.map { $0 * 2 }.first
        Foo<Int>(0x10, 0b11, 0o7, 1_000)
        api?.send("x")!
        interpolated("a\\(b)c")
        """
        let facts = SyntaxFacts.parse(source: source, path: "/src/Main.swift")
        func call(_ line: Int, _ column: Int) -> SyntaxCall? {
            facts.calls[SourcePosition(path: "/src/Main.swift", line: line, column: column)]
        }
        let enabled = try! XCTUnwrap(call(1, 26))
        XCTAssertEqual(enabled.arguments, ["\"payments.charge\"", "true"])
        XCTAssertEqual(enabled.literals, [
            LiteralArgument(index: 0, label: nil, value: .string("payments.charge")),
            LiteralArgument(index: 1, label: "default", value: .bool(true)),
        ])
        let track = try! XCTUnwrap(call(2, 11))
        XCTAssertEqual(track.arguments.count, 3)
        XCTAssertEqual(track.literals.map(\.index), [0, 2])
        XCTAssertEqual(track.literals.map(\.value), [.string("checkout"), .bool(false)])
        let client = try! XCTUnwrap(call(3, 14))
        XCTAssertEqual(client.literals.map(\.value), [.string("https://example.test"), .int(30), .double(-1.5), .int(-2), .null])
        XCTAssertEqual(call(4, 8)?.literals, [])
        XCTAssertEqual(call(4, 8)?.arguments, ["{ $0 * 2 }"])
        XCTAssertEqual(call(5, 1)?.literals.map(\.value), [.int(16), .int(3), .int(7), .int(1000)])
        XCTAssertEqual(call(6, 6)?.literals.map(\.value), [.string("x")])
        XCTAssertEqual(call(7, 1)?.literals, [], "interpolated strings are not constants")
        XCTAssertEqual(call(7, 1)?.arguments.count, 1)
    }

    func testStringLiteralsCarryTheirRuntimeValue() throws {
        let source = ###"""
        f("a\nb", "\u{41}\t\"q\"\\\0\r", "caf\u{E9} \u{1F600}")
        g(#"raw\n\#tX"#, ##"two\##u{41} \#n"##)
        h("""
            line1
              line2 \
            cont
            """)
        i("bad \q", "\u{110000}", "\u{}")
        """###
        let facts = SyntaxFacts.parse(source: source, path: "/src/Main.swift")
        func call(_ line: Int, _ column: Int) -> SyntaxCall? {
            facts.calls[SourcePosition(path: "/src/Main.swift", line: line, column: column)]
        }
        let f = try XCTUnwrap(call(1, 1))
        XCTAssertEqual(f.literals.map(\.value), [.string("a\nb"), .string("A\t\"q\"\\\0\r"), .string("café 😀")])
        let g = try XCTUnwrap(call(2, 1))
        XCTAssertEqual(g.literals.map(\.value), [.string("raw\\n\tX"), .string("twoA \\#n")])
        let h = try XCTUnwrap(call(3, 1))
        XCTAssertEqual(h.literals.map(\.value), [.string("line1\n  line2 cont")])
        let i = try XCTUnwrap(call(8, 1))
        XCTAssertEqual(i.literals, [], "undefined or invalid escapes are not constants")
        XCTAssertEqual(i.arguments.count, 3)
    }

    func testAttributesAreKeyedByTheDeclaredName() {
        let source = """
        @MainActor
        public final class CartService: Repository {
            @available(*, deprecated, message: "use checkout(order:method:)")
            public func checkout(order: Order) -> Bool { true }
            @Published var count = 0
            @objc init() {}
        }
        @frozen enum PaymentMethod { @available(iOS 17, *) case card }
        @propertyWrapper struct Box {}
        protocol Repository {}
        actor Worker {}
        """
        let facts = SyntaxFacts.parse(source: source, path: "/src/Main.swift")
        let byName = Dictionary(grouping: facts.attributes, by: \.name)
        XCTAssertEqual(byName["MainActor"]?.map(\.position), [SourcePosition(path: "/src/Main.swift", line: 2, column: 20)])
        XCTAssertNil(byName["MainActor"]?.first?.arguments)
        XCTAssertEqual(byName["available"]?.count, 2)
        XCTAssertEqual(byName["available"]?.first?.position.line, 4)
        XCTAssertEqual(byName["available"]?.first?.arguments, "*, deprecated, message: \"use checkout(order:method:)\"")
        XCTAssertEqual(byName["Published"]?.first?.position, SourcePosition(path: "/src/Main.swift", line: 5, column: 20))
        XCTAssertEqual(byName["objc"]?.first?.position.column, 11)
        XCTAssertEqual(byName["frozen"]?.first?.position.column, 14)
        XCTAssertEqual(byName["propertyWrapper"]?.first?.position.line, 9)
        XCTAssertEqual(facts.attributes.count, 7)
    }

    func testParseFile() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("syntax-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let file = dir.appendingPathComponent("A.swift")
        try "f(1)\n".write(to: file, atomically: true, encoding: .utf8)
        let facts = try SyntaxFacts.parse(path: file.path)
        XCTAssertEqual(facts.calls.count, 1)
        XCTAssertThrowsError(try SyntaxFacts.parse(path: dir.appendingPathComponent("missing.swift").path))
    }

    func testDeclaredSignaturesAreKeyedByTheDeclaredName() throws {
        let source = """
        final class AppDelegate: NSObject {
            var window: UIWindow?
            static let shared = AppDelegate()
            func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool { true }
            init(name: String, values: Int..., done: @escaping (Bool) -> Void, count: inout Int) {}
            class func make() -> AppDelegate { AppDelegate() }
            func plain() {}
        }
        """
        let facts = SyntaxFacts.parse(source: source, path: "/src/AppDelegate.swift")
        func declaration(_ line: Int, _ column: Int) throws -> SyntaxDeclaration {
            try XCTUnwrap(facts.declarations[SourcePosition(path: "/src/AppDelegate.swift", line: line, column: column)])
        }
        let window = try declaration(2, 9)
        XCTAssertEqual(window.returnType, "UIWindow?")
        XCTAssertFalse(window.isStatic)
        let shared = try declaration(3, 16)
        XCTAssertNil(shared.returnType, "no type written")
        XCTAssertTrue(shared.isStatic)
        let launch = try declaration(4, 10)
        XCTAssertEqual(launch.parameterTypes, ["UIApplication", "[UIApplication.LaunchOptionsKey: Any]?"])
        XCTAssertEqual(launch.returnType, "Bool")
        let initializer = try declaration(5, 5)
        XCTAssertEqual(initializer.parameterTypes, ["String", "Int...", "(Bool) -> Void", "inout Int"])
        XCTAssertNil(initializer.returnType)
        let make = try declaration(6, 16)
        XCTAssertTrue(make.isStatic)
        XCTAssertEqual(make.returnType, "AppDelegate")
        let plain = try declaration(7, 10)
        XCTAssertEqual(plain.parameterTypes, [])
        XCTAssertNil(plain.returnType)
        XCTAssertEqual(facts.declarations.count, 6)
    }
}
