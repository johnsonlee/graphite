import Foundation
import XCTest
@testable import GraphiteFrontend
@testable import GraphiteIR

final class EmitterObjcExtensionTests: XCTestCase {
    /// An imported Objective-C owner has no declaration in the indexed source
    /// files. Read the compiler's real extension/member USRs, not a model with a
    /// fabricated local NSObject definition that masks the owner fallback.
    func testExternalObjectiveCClassExtensionThroughTheCompilerIndex() throws {
        #if !os(macOS)
        throw XCTSkip("Swift Objective-C interoperability requires the macOS toolchain")
        #else
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("graphite-external-extension-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let sources = root.appendingPathComponent("Sources/ExtensionProbe")
        try FileManager.default.createDirectory(at: sources, withIntermediateDirectories: true)
        try """
        // swift-tools-version:5.10
        import PackageDescription
        let package = Package(name: "ExtensionProbe", targets: [.target(name: "ExtensionProbe")])
        """.write(to: root.appendingPathComponent("Package.swift"), atomically: true, encoding: .utf8)
        let source = sources.appendingPathComponent("Extension.swift")
        try """
        import Foundation
        extension NSObject {
            @objc public func graphiteProbe(_ value: Int) -> Int { value }
            @objc public var graphiteEnabled: Bool { true }
        }
        public func useProbe(_ object: NSObject) -> Int { object.graphiteProbe(3) }
        extension NSObject {
            @objc public func graphiteObjcMixed(_ value: Int) -> Int { value }
            public func graphiteSwiftMixed(_ value: Int) -> Int { value }
        }
        public func useMixedProbe(_ object: NSObject) -> Int { object.graphiteObjcMixed(4) }
        """.write(to: source, atomically: true, encoding: .utf8)
        var options = BuildOptions()
        options.package = root.path
        let output = root.appendingPathComponent("extension.graphite-ir")
        let result = try Frontend(options: options).build(to: output)
        let library = try XCTUnwrap(IndexReader.locateLibrary())
        let reader = try IndexReader(storePath: result.indexStore, libraryPath: library)
        let model = reader.read(files: [SourcePosition.canonical(source.path)])
        XCTAssertFalse(model.types.contains { $0.symbol.usr == "c:objc(cs)NSObject" })
        let declaration = try XCTUnwrap(model.members.first { $0.symbol.name == "graphiteProbe(_:)" })
        XCTAssertEqual(declaration.symbol.usr, "c:@CM@ExtensionProbe@@objc(cs)NSObject(im)graphiteProbe:")
        let graph = try DecodedIR(url: output)
        let method = try XCTUnwrap(graph.methods.first { graph.str($0.name) == "graphiteProbe(_:)" })
        let expected = "NSObject.graphiteProbe(_:)(Swift.Int) -> Swift.Int"
        XCTAssertEqual(graph.signature(method), expected)
        let call = try XCTUnwrap(graph.callSites.first { graph.str($0.callee.name) == "graphiteProbe(_:)" })
        XCTAssertEqual(graph.signature(call.callee), expected)
        XCTAssertEqual(graph.type(call.caller.declaringClass), "ExtensionProbe")
        XCTAssertEqual(graph.str(call.caller.name), "useProbe(_:)")
        XCTAssertEqual(call.arguments.map(graph.literal), ["3"])
        let fields = graph.nodes.values.compactMap { node -> GraphiteIRField? in
            if case .field(let field)? = node.kind { return field }
            return nil
        }
        XCTAssertEqual(fields.count, 1)
        let field = try XCTUnwrap(fields.first)
        XCTAssertEqual(graph.type(field.field.declaringClass), "NSObject")
        XCTAssertEqual(graph.str(field.field.name), "graphiteEnabled")
        XCTAssertEqual(graph.type(field.field.type), "Swift.Bool")
        // The @objc declaration comes first, but a later Swift member supplies
        // the demangler's owner. Both descriptors must use that same spelling.
        let mixed = try XCTUnwrap(graph.methods.first { graph.str($0.name) == "graphiteObjcMixed(_:)" })
        let swift = try XCTUnwrap(graph.methods.first { graph.str($0.name) == "graphiteSwiftMixed(_:)" })
        XCTAssertEqual(graph.signature(mixed), "__C.NSObject.graphiteObjcMixed(_:)(Swift.Int) -> Swift.Int")
        XCTAssertEqual(graph.type(swift.declaringClass), "__C.NSObject")
        let mixedCall = try XCTUnwrap(graph.callSites.first { graph.str($0.callee.name) == "graphiteObjcMixed(_:)" })
        XCTAssertEqual(graph.signature(mixedCall.callee), graph.signature(mixed))
        XCTAssertEqual(mixedCall.arguments.map(graph.literal), ["4"])
        #endif
    }

    func testExplicitOwnerModuleWinsOverSameNamedLocalType() throws {
        let path = "/src/Extension.swift"
        func at(_ line: Int) -> SourcePosition { SourcePosition(path: path, line: line, column: 1) }
        let unrelated = SymbolInfo(usr: "c:@M@Other@objc(cs)Owner", name: "Owner", kind: .class)
        let owner = SymbolInfo(usr: "c:@M@Original@objc(cs)Owner", name: "Owner", kind: .class)
        let method = SymbolInfo(usr: "c:@CM@Ext@Original@objc(cs)Owner(im)probe", name: "probe()", kind: .instanceMethod)
        let ext = SymbolInfo(usr: "s:e:" + method.usr, name: "Owner", kind: .extension)
        var model = IndexModel()
        model.types = [
            TypeDecl(symbol: unrelated, kind: .class, module: "Other", position: at(1), container: nil),
            TypeDecl(symbol: owner, kind: .class, module: "Original", position: at(2), container: nil),
            TypeDecl(symbol: ext, kind: .extension, module: "Ext", position: at(3), container: nil),
        ]
        model.members = [MemberDecl(symbol: method, kind: .method, isStatic: false, module: "Ext", position: at(4), container: ext.usr, overrides: [])]
        for symbol in [unrelated, owner, method, ext] { model.symbols[symbol.usr] = symbol }
        let output = FileManager.default.temporaryDirectory.appendingPathComponent("owner-module-\(UUID().uuidString).graphite-ir")
        defer { try? FileManager.default.removeItem(at: output) }
        let writer = try IRWriter(url: output, header: GraphiteIRHeader())
        _ = try Emitter(writer: writer, model: model, facts: [:], demangled: [:]).emit()
        let graph = try DecodedIR(url: output)
        XCTAssertEqual(graph.methods.count, 1)
        XCTAssertEqual(graph.type(try XCTUnwrap(graph.methods.first).declaringClass), "Original.Owner")
    }

    func testSwiftAnchorWinsRegardlessOfMemberOrder() throws {
        let path = "/src/Extension.swift"
        let ext = SymbolInfo(usr: "s:e:extension", name: "NSObject", kind: .extension)
        let objc = SymbolInfo(usr: "c:@CM@Ext@@objc(cs)NSObject(im)objcProbe", name: "objcProbe()", kind: .instanceMethod)
        let swift = SymbolInfo(usr: "s:swiftProbe", name: "swiftProbe()", kind: .instanceMethod)
        for symbols in [[objc, swift], [swift, objc]] {
            let position = SourcePosition(path: path, line: 1, column: 1)
            var model = IndexModel()
            model.types = [TypeDecl(symbol: ext, kind: .extension, module: "Ext", position: position, container: nil)]
            model.members = symbols.map {
                MemberDecl(symbol: $0, kind: .method, isStatic: false, module: "Ext", position: position, container: ext.usr, overrides: [])
            }
            let output = FileManager.default.temporaryDirectory.appendingPathComponent("mixed-owner-\(UUID().uuidString).graphite-ir")
            defer { try? FileManager.default.removeItem(at: output) }
            let writer = try IRWriter(url: output, header: GraphiteIRHeader())
            _ = try Emitter(writer: writer, model: model, facts: [:], demangled: [
                swift.usr: "(extension in Ext):__C.NSObject.swiftProbe() -> Swift.Int",
            ]).emit()
            let graph = try DecodedIR(url: output)
            XCTAssertEqual(graph.methods.count, 2)
            XCTAssertEqual(Set(graph.methods.map { graph.type($0.declaringClass) }), ["__C.NSObject"])
        }
    }
}
