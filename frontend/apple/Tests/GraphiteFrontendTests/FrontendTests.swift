import Foundation
import XCTest
@testable import GraphiteFrontend
@testable import GraphiteIR

/// A decoded IR stream, for assertions.
struct DecodedIR {
    var header = GraphiteIRHeader()
    var strings: [String] = []
    var nodes: [UInt32: GraphiteIRNode] = [:]
    var edges: [GraphiteIREdge] = []
    var methods: [GraphiteIRMethodRef] = []
    var relations: [GraphiteIRTypeRelationEntry] = []
    var origins: [GraphiteIRClassOrigin] = []
    var enumValues: [GraphiteIREnumValueEntry] = []
    var trailer = GraphiteIRTrailer()

    init(url: URL) throws {
        for chunk in try IRStream.chunks(of: try Data(contentsOf: url)) {
            switch chunk.chunk! {
            case .header(let h): header = h
            case .strings(let s): strings += s.values
            case .nodes(let n): for node in n.nodes { nodes[node.id] = node }
            case .edges(let e): edges += e.edges
            case .methods(let m): methods += m.methods
            case .typeRelations(let t): relations += t.relations
            case .classOrigins(let c): origins += c.origins
            case .enumValues(let e): enumValues += e.entries
            case .artifactDependencies: break
            case .trailer(let t): trailer = t
            }
        }
    }

    func str(_ id: UInt32) -> String { strings[Int(id)] }
    func type(_ ref: GraphiteIRTypeRef) -> String { str(ref.name) }
    func signature(_ m: GraphiteIRMethodRef) -> String {
        "\(type(m.declaringClass)).\(str(m.name))(\(m.parameterTypes.map(type).joined(separator: ", "))) -> \(type(m.returnType))"
    }
    var callSites: [GraphiteIRCallSite] { nodes.values.filter { $0.callSite != GraphiteIRCallSite() }.map(\.callSite) }
    func literal(_ id: UInt32) -> String {
        let node = nodes[id]!
        switch node.kind! {
        case .stringConstant(let c): return "\"\(str(c.value))\""
        case .intConstant(let c): return "\(c.value)"
        case .longConstant(let c): return "\(c.value)L"
        case .doubleConstant(let c): return "\(c.value)"
        case .booleanConstant(let c): return "\(c.value)"
        case .nullConstant: return "nil"
        case .localVariable(let v): return "<\(str(v.name))>"
        default: return "?"
        }
    }
}

/// Exercise SwiftPM itself: its release indexing defaults and header discovery differ
/// from the directory-based fixtures used by the other frontend tests.
final class SwiftPMFrontendRegressionTests: XCTestCase {
    private func package(_ files: [String: String], run: (URL) throws -> Void) throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("graphite-swiftpm-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        for (path, text) in files {
            let file = root.appendingPathComponent(path)
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try text.write(to: file, atomically: true, encoding: .utf8)
        }
        try run(root)
    }

    func testReleaseBuildProducesAnIndexedGraph() throws {
        try package([
            "Package.swift": """
            // swift-tools-version:5.10
            import PackageDescription
            let package = Package(name: "ReleaseProbe", targets: [.target(name: "ReleaseProbe")])
            """,
            "Sources/ReleaseProbe/Probe.swift": """
            public struct Probe {
                public static func read(_ key: String) -> Bool { true }
                public static func run() -> Bool { read("release.flag") }
            }
            """,
        ]) { root in
            var options = BuildOptions()
            options.package = root.path
            options.configuration = "release"
            let output = root.appendingPathComponent("release.graphite-ir")
            let result = try Frontend(options: options).build(to: output)
            XCTAssertTrue(FileManager.default.fileExists(atPath: result.indexStore))
            let graph = try DecodedIR(url: output)
            XCTAssertEqual(graph.origins.map(\.className), ["ReleaseProbe.Probe"])
            let call = try XCTUnwrap(graph.callSites.first { graph.str($0.callee.name) == "read(_:)" })
            XCTAssertEqual(graph.signature(call.caller), "ReleaseProbe.Probe.run()() -> Swift.Bool")
            XCTAssertEqual(graph.signature(call.callee), "ReleaseProbe.Probe.read(_:)(Swift.String) -> Swift.Bool")
            XCTAssertEqual(call.arguments.map(graph.literal), ["\"release.flag\""])
        }
    }

    func testSwiftPMIncludesObjectiveCHeaderDeclarationsAndNullability() throws {
        try package([
            "Package.swift": """
            // swift-tools-version:5.10
            import PackageDescription
            let package = Package(name: "Legacy", targets: [
                .target(name: "Legacy", path: "Custom/Legacy", exclude: ["Excluded"], publicHeadersPath: "Public")
            ])
            """,
            "Custom/Legacy/Public/Legacy.h": """
            #pragma clang assume_nonnull begin
            @protocol Reading
            @property (readonly) int enabled;
            - (const char *)readName;
            @end
            __attribute__((objc_root_class))
            @interface Legacy
            @property (readonly) int count;
            - (const char *)lookup:(const char *)key;
            @end
            #pragma clang assume_nonnull end
            """,
            "Custom/Legacy/Legacy.m": """
            #import "Legacy.h"
            @implementation Legacy
            - (int)count { return 1; }
            - (const char *)lookup:(const char *)key { return key; }
            @end
            """,
            "Custom/Legacy/Excluded/Ignore.h": "@interface Ignored\n@end\n",
        ]) { root in
            var options = BuildOptions()
            options.package = root.path
            let output = root.appendingPathComponent("legacy.graphite-ir")
            let result = try Frontend(options: options).build(to: output)
            XCTAssertEqual(result.files, 2, "the implementation and custom public header, excluding ignored headers")
            let graph = try DecodedIR(url: output)
            XCTAssertTrue(graph.origins.contains { $0.className == "Reading" || $0.className == "Legacy.Reading" })
            let fields = graph.nodes.values.compactMap { node -> GraphiteIRField? in
                if case .field(let field)? = node.kind { return field }
                return nil
            }
            XCTAssertEqual(Set(fields.map { "\(graph.str($0.field.name)): \(graph.type($0.field.type))" }), ["enabled: int", "count: int"])
            let lookup = try XCTUnwrap(graph.methods.first { graph.str($0.name) == "lookup:" })
            XCTAssertEqual(lookup.parameterTypes.map(graph.type), ["const char * _Nonnull"])
            XCTAssertEqual(graph.type(lookup.returnType), "const char * _Nonnull")
            let readName = try XCTUnwrap(graph.methods.first { graph.str($0.name) == "readName" })
            XCTAssertEqual(readName.parameterTypes, [])
            XCTAssertEqual(graph.type(readName.returnType), "const char * _Nonnull")
            XCTAssertFalse(graph.origins.contains { $0.className.contains("Ignored") })
        }
    }
}

final class FrontendTests: XCTestCase {
    static let fixture = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("Fixtures/AcmeShop").path

    static var ir: DecodedIR!
    static var result: BuildResult!
    static var progress: [String] = []

    override class func setUp() {
        super.setUp()
        var options = BuildOptions()
        options.package = fixture
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("acme-\(UUID().uuidString).graphite-ir")
        do {
            result = try Frontend(options: options) { progress.append($0.phase) }.build(to: out)
            ir = try DecodedIR(url: out)
        } catch {
            XCTFail("building the fixture failed: \(error)")
        }
        try? FileManager.default.removeItem(at: out)
    }

    var ir: DecodedIR { Self.ir }

    func testHeaderTrailerAndSummary() throws {
        XCTAssertEqual(ir.header.schemaVersion, 1)
        XCTAssertEqual(ir.header.language, "swift")
        XCTAssertEqual(ir.header.frontend.name, frontendName)
        XCTAssertEqual(ir.header.frontend.version, frontendVersion)
        XCTAssertEqual(ir.header.source.kind, "swiftpm")
        XCTAssertEqual(ir.header.source.path, Self.fixture)
        XCTAssertEqual(ir.header.options["files"], "4")
        XCTAssertEqual(ir.trailer.nodeCount, UInt64(ir.nodes.count))
        XCTAssertEqual(ir.trailer.edgeCount, UInt64(ir.edges.count))
        XCTAssertEqual(ir.trailer.stringCount, UInt64(ir.strings.count))
        XCTAssertEqual(Self.result.files, 4)
        XCTAssertEqual(Self.result.summary.nodes, ir.trailer.nodeCount)
        XCTAssertEqual(Self.result.summary.types, 11)
        XCTAssertEqual(Set(Self.progress), ["swift build", "swift package describe", "index", "syntax", "demangle", "emit"])
    }

    /// The manifest, not `Sources/`, says where a target's files are: the fixture's
    /// command-line tool lives under `CommandLineTool/` and is graphed all the same.
    func testTargetsOutsideSourcesAreDiscoveredFromTheManifest() {
        let origins = Dictionary(uniqueKeysWithValues: ir.origins.map { ($0.className, $0.source) })
        XCTAssertEqual(origins["AcmeShopTool.Tool"], "AcmeShopTool")
        let calls = ir.callSites.filter { ir.type($0.caller.declaringClass) == "AcmeShopTool.Tool" }
        XCTAssertEqual(
            Set(calls.map { ir.str($0.callee.name) }),
            ["init(baseURL:)", "init(client:)", "init(id:items:)", "init(sku:price:)", "checkout(order:method:)", "print(_:separator:terminator:)"]
        )
        let checkout = try? XCTUnwrap(calls.first { ir.str($0.callee.name) == "checkout(order:method:)" })
        XCTAssertEqual(checkout.map { ir.type($0.callee.declaringClass) }, "AcmeShop.CartService")
        XCTAssertTrue(ir.strings.contains("order-1"), "the tool's literals are in the string table")
    }

    /// The index store records the real path of every file, so a package reached through
    /// a symlink must still join its occurrences to the syntax facts of the same files.
    func testSymlinkedRootJoinsSyntaxFacts() throws {
        let link = FileManager.default.temporaryDirectory.appendingPathComponent("acme-link-\(UUID().uuidString)")
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: URL(fileURLWithPath: Self.fixture))
        defer { try? FileManager.default.removeItem(at: link) }
        XCTAssertEqual(
            try Frontend.swiftFiles(under: [link.path + "/Sources"]),
            try Frontend.swiftFiles(under: [Self.fixture + "/Sources"])
        )
        var options = BuildOptions()
        options.indexStore = link.path + "/.build/debug/index/store"
        options.sources = [link.path + "/Sources", link.path + "/CommandLineTool"]
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("acme-\(UUID().uuidString).graphite-ir")
        defer { try? FileManager.default.removeItem(at: out) }
        let result = try Frontend(options: options).build(to: out)
        XCTAssertEqual(result.files, 4)
        let linked = try DecodedIR(url: out)
        let constants = { (decoded: DecodedIR) in
            decoded.nodes.values.filter { if case .stringConstant? = $0.kind { return true } else { return false } }.count
        }
        XCTAssertGreaterThan(constants(linked), 0)
        XCTAssertEqual(constants(linked), constants(ir))
        XCTAssertEqual(linked.edges.count, ir.edges.count)
        XCTAssertEqual(linked.nodes.count, ir.nodes.count)
        let annotations = { (decoded: DecodedIR) in
            Set(decoded.nodes.values.compactMap { node -> String? in
                if case .annotation(let a)? = node.kind { return a.name } else { return nil }
            })
        }
        XCTAssertEqual(annotations(linked), ["available", "discardableResult", "main"])
        XCTAssertEqual(annotations(linked), annotations(ir))
        XCTAssertFalse(linked.strings.contains { $0.hasPrefix(link.path) }, "positions carry the canonical spelling")
    }

    func testTypesOriginsAndRelations() {
        let origins = Dictionary(uniqueKeysWithValues: ir.origins.map { ($0.className, $0.source) })
        XCTAssertEqual(origins["AcmeShop.CartService"], "AcmeShop")
        XCTAssertEqual(origins["AcmeShop.PaymentMethod"], "AcmeShop")
        XCTAssertEqual(origins.count, 11)
        let relations = Set(ir.relations.map { "\(ir.type($0.subtype)) \($0.relation) \(ir.type($0.supertype))" })
        XCTAssertEqual(relations, [
            "AcmeShop.CartService implements AcmeShop.Repository",
            "AcmeShop.CartService implements AcmeShop.Auditable",
            "AcmeShop.PaymentClient extends AcmeShop.BaseClient",
        ])
        XCTAssertEqual(Set(ir.enumValues.map { "\($0.enumClass).\($0.enumName)" }), ["AcmeShop.PaymentMethod.card", "AcmeShop.PaymentMethod.applePay"])
    }

    func testMethodsAndFields() {
        let methods = Set(ir.methods.map(ir.signature))
        XCTAssertTrue(methods.contains("AcmeShop.CartService.checkout(order:method:)(AcmeShop.Order, AcmeShop.PaymentMethod) -> Swift.Bool"), "\(methods)")
        XCTAssertTrue(methods.contains("AcmeShop.CartService.init(client:)(AcmeShop.PaymentClient) -> AcmeShop.CartService"))
        XCTAssertTrue(methods.contains("AcmeShop.FeatureFlags.isEnabled(_:default:)(Swift.String, Swift.Bool) -> Swift.Bool"))
        XCTAssertTrue(methods.contains("AcmeShop.Repository.find(id:)(Swift.String) -> AcmeShop.Order?"))
        XCTAssertTrue(methods.contains("AcmeShop.Analytics.init()() -> AcmeShop.Analytics"))
        XCTAssertFalse(methods.contains { $0.contains("getter:") }, "an explicit getter is not a method of its own: \(methods)")
        XCTAssertEqual(methods.count, 15)

        let fields = ir.nodes.values.compactMap { node -> String? in
            guard case .field(let f) = node.kind else { return nil }
            return "\(ir.type(f.field.declaringClass)).\(ir.str(f.field.name)): \(ir.type(f.field.type))\(f.isStatic ? " static" : "")"
        }
        XCTAssertTrue(fields.contains("AcmeShop.CartService.orders: [Swift.String : AcmeShop.Order]"), "\(fields)")
        XCTAssertTrue(fields.contains("AcmeShop.CartService.maxItems: Swift.Int static"))
        XCTAssertTrue(fields.contains("AcmeShop.CartService.analytics: AcmeShop.Analytics"), "private members keep their name")
        XCTAssertTrue(fields.contains("AcmeShop.Order.total: Swift.Double"), "computed properties are fields too")
        XCTAssertEqual(fields.count, 13)
    }

    func testCallSitesCarryLiteralArgumentsInPosition() throws {
        let sites = ir.callSites
        func site(_ callee: String, line: Int32) -> GraphiteIRCallSite? {
            sites.first { ir.str($0.callee.name) == callee && $0.line == line }
        }
        let enabled = try XCTUnwrap(site("isEnabled(_:default:)", line: 22))
        XCTAssertEqual(ir.signature(enabled.caller), "AcmeShop.PaymentClient.charge(amount:currency:method:)(Swift.Double, Swift.String, AcmeShop.PaymentMethod) -> Swift.Bool")
        XCTAssertEqual(ir.type(enabled.callee.declaringClass), "AcmeShop.FeatureFlags")
        XCTAssertEqual(enabled.arguments.map(ir.literal), ["\"payments.charge\"", "true"])

        let track = try XCTUnwrap(site("track(_:count:sampled:)", line: 28))
        XCTAssertEqual(track.arguments.map(ir.literal), ["\"checkout\"", "<order.items.count>", "true"])
        XCTAssertEqual(ir.str(track.caller.name), "checkout(order:method:)")

        let send = try XCTUnwrap(site("send(path:retries:)", line: 23))
        XCTAssertEqual(send.arguments.map(ir.literal), ["\"/v1/charge\"", "3"])

        let charge = try XCTUnwrap(site("charge(amount:currency:method:)", line: 29))
        XCTAssertEqual(charge.arguments.map(ir.literal), ["<order.total>", "\"USD\"", "<method>"])

        // A call in a property initializer is attributed to the property.
        let analytics = try XCTUnwrap(site("init()", line: 8))
        XCTAssertEqual(ir.str(analytics.caller.name), "analytics")
        XCTAssertEqual(ir.type(analytics.caller.declaringClass), "AcmeShop.CartService")

        // An operator is a call to a static method of the operand type.
        let compare = try XCTUnwrap(site("<=(_:_:)", line: 24))
        XCTAssertEqual(ir.type(compare.callee.declaringClass), "Swift.Int")
        XCTAssertEqual(compare.arguments, [])

        // Every argument flows into its call site with a PARAMETER_PASS edge.
        let flows = Set(ir.edges.filter { $0.dataFlow.kind == .parameterPass }.map { "\($0.from)->\($0.to)" })
        for site in sites {
            let id = ir.nodes.first { $0.value.callSite == site }!.key
            for argument in site.arguments { XCTAssertTrue(flows.contains("\(argument)->\(id)")) }
        }
        XCTAssertEqual(flows.count, ir.edges.count)
        XCTAssertEqual(sites.count, 24)
    }

    func testAnnotations() {
        let annotations = ir.nodes.values.compactMap { node -> String? in
            guard case .annotation(let a) = node.kind else { return nil }
            return "\(a.className)#\(a.memberName) @\(a.name) \(a.values.map { "\($0.name)=\($0.value.stringValue)" })"
        }
        XCTAssertEqual(Set(annotations), [
            "AcmeShop.CartService#checkout(order:) @available [\"arguments=*, deprecated, message: \\\"use checkout(order:method:)\\\"\"]",
            "AcmeShop.PaymentClient#charge(amount:currency:method:) @discardableResult []",
            "AcmeShopTool.Tool#<class> @main []",
        ])
    }

    func testBuildFromAnExistingIndexStore() throws {
        var options = BuildOptions()
        options.indexStore = Self.fixture + "/.build/debug/index/store"
        options.sources = [Self.fixture + "/Sources/AcmeShop/CartService.swift"]
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("acme-\(UUID().uuidString).graphite-ir")
        defer { try? FileManager.default.removeItem(at: out) }
        let result = try Frontend(options: options).build(to: out)
        XCTAssertEqual(result.files, 1)
        let decoded = try DecodedIR(url: out)
        XCTAssertEqual(decoded.header.source.kind, "index-store")
        XCTAssertEqual(decoded.origins.map(\.className), ["AcmeShop.CartService"])
    }

    func testInputErrors() {
        XCTAssertThrowsError(try Frontend(options: BuildOptions()).build(to: URL(fileURLWithPath: "/tmp/x"))) {
            XCTAssertEqual("\($0)", FrontendError.noInput.description)
        }
        var empty = BuildOptions()
        empty.indexStore = Self.fixture + "/.build/debug/index/store"
        empty.sources = [Self.fixture + "/Package.swift.missing"]
        XCTAssertThrowsError(try Frontend(options: empty).build(to: URL(fileURLWithPath: "/tmp/x"))) {
            XCTAssertTrue("\($0)".hasPrefix("no Swift or Objective-C sources under"), "\($0)")
        }
        var missingStore = BuildOptions()
        missingStore.indexStore = "/nonexistent/store"
        missingStore.sources = [Self.fixture + "/Sources"]
        XCTAssertThrowsError(try Frontend(options: missingStore).build(to: URL(fileURLWithPath: "/tmp/x"))) {
            XCTAssertTrue("\($0)".hasPrefix("no index store at"), "\($0)")
        }
        var noBuild = BuildOptions()
        noBuild.package = Self.fixture
        noBuild.environment = ["PATH": "/nonexistent"]
        XCTAssertThrowsError(try Frontend(options: noBuild).build(to: URL(fileURLWithPath: "/tmp/x"))) {
            XCTAssertEqual("\($0)", FrontendError.noSwift.description)
        }
        let broken = FileManager.default.temporaryDirectory.appendingPathComponent("broken-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: broken, withIntermediateDirectories: true)
        try? "// swift-tools-version:5.10\nimport PackageDescription\nlet package = Package(name: \"Broken\", targets: [.target(name: \"Broken\")])\n".write(to: broken.appendingPathComponent("Package.swift"), atomically: true, encoding: .utf8)
        try? FileManager.default.createDirectory(at: broken.appendingPathComponent("Sources/Broken"), withIntermediateDirectories: true)
        try? "let x: Int = \"no\"\n".write(to: broken.appendingPathComponent("Sources/Broken/A.swift"), atomically: true, encoding: .utf8)
        defer { try? FileManager.default.removeItem(at: broken) }
        var failing = BuildOptions()
        failing.package = broken.path
        XCTAssertThrowsError(try Frontend(options: failing).build(to: URL(fileURLWithPath: "/tmp/x"))) {
            XCTAssertTrue("\($0)".hasPrefix("swift build failed"), "\($0)")
        }
        XCTAssertEqual(FrontendError.noDemangler.description, "no swift-demangle found; set GRAPHITE_SWIFT_DEMANGLE or put the toolchain on PATH")
    }

    func testToolLocation() throws {
        XCTAssertNil(Frontend.locateSwift(environment: [:]))
        #if !os(macOS)
        // On macOS xcrun finds the toolchain whatever PATH says; elsewhere PATH is all there is.
        XCTAssertNil(Demangler.locate(environment: ["PATH": "/nonexistent"]))
        XCTAssertNil(IndexReader.locateLibrary(environment: ["PATH": "/nonexistent"]))
        #endif
        let swift = try XCTUnwrap(Frontend.locateSwift(environment: ProcessInfo.processInfo.environment))
        XCTAssertEqual(Frontend.locateSwift(environment: ["GRAPHITE_SWIFT": swift]), swift)
        let toolchain = URL(fileURLWithPath: swift).resolvingSymlinksInPath().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().path
        XCTAssertNotNil(Frontend.locateSwift(environment: ["GRAPHITE_SWIFT_TOOLCHAIN": toolchain]))
        XCTAssertNotNil(Demangler.locate(environment: ["GRAPHITE_SWIFT_TOOLCHAIN": toolchain]))
        XCTAssertNotNil(IndexReader.locateLibrary(environment: ["GRAPHITE_SWIFT_TOOLCHAIN": toolchain]))
        let library = try XCTUnwrap(IndexReader.locateLibrary(environment: ProcessInfo.processInfo.environment))
        XCTAssertEqual(IndexReader.locateLibrary(environment: ["GRAPHITE_INDEXSTORE_LIBRARY": library]), library)
        let demangler = try XCTUnwrap(Demangler.locate(environment: ProcessInfo.processInfo.environment))
        XCTAssertEqual(Demangler.locate(environment: ["GRAPHITE_SWIFT_DEMANGLE": demangler.executable.path])?.executable, demangler.executable)
        let demangled = try demangler.demangle(["s:8AcmeShop11CartServiceC", "$s8AcmeShop5OrderV", "c:objc(cs)NSObject", "s:not_a_symbol"])
        XCTAssertEqual(demangled["s:8AcmeShop11CartServiceC"], "AcmeShop.CartService")
        XCTAssertEqual(demangled["$s8AcmeShop5OrderV"], "AcmeShop.Order")
        XCTAssertNil(demangled["c:objc(cs)NSObject"])
        XCTAssertNil(demangled["s:not_a_symbol"])
        XCTAssertEqual(try demangler.demangle([]), [:])
        XCTAssertThrowsError(try IndexReader(storePath: Self.fixture, libraryPath: "/nonexistent/libIndexStore.so"))
        XCTAssertEqual(try Frontend.swiftFiles(under: ["/nonexistent"]), [])
        XCTAssertEqual(
            try Frontend.swiftFiles(under: [Self.fixture + "/Sources/AcmeShop/Models.swift", Self.fixture + "/Package.swift", Self.fixture + "/.build/build.db"]),
            [SourcePosition.canonical(Self.fixture + "/Package.swift"), SourcePosition.canonical(Self.fixture + "/Sources/AcmeShop/Models.swift")]
        )
    }
}
