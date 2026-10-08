import Foundation
import IndexStoreDB
import XCTest
@testable import GraphiteFrontend
@testable import GraphiteIR

final class ClangUSRTests: XCTestCase {
    func testObjectiveCForms() throws {
        let method = try XCTUnwrap(ClangUSR.parse("c:@M@AcmeApp@objc(cs)AppDelegate(im)application:didFinishLaunchingWithOptions:"))
        XCTAssertEqual(method.module, "AcmeApp")
        XCTAssertEqual(method.container, "AppDelegate")
        XCTAssertEqual(method.member, "application:didFinishLaunchingWithOptions:")
        XCTAssertEqual(method.memberKind, .instanceMethod)
        XCTAssertEqual(method.qualifiedContainer, "AcmeApp.AppDelegate")
        XCTAssertFalse(method.isProtocol)

        let imported = try XCTUnwrap(ClangUSR.parse("c:objc(cs)UIApplication(im)registerForRemoteNotifications"))
        XCTAssertNil(imported.module)
        XCTAssertEqual(imported.qualifiedContainer, "UIApplication")
        XCTAssertEqual(imported.member, "registerForRemoteNotifications")

        let classMethod = try XCTUnwrap(ClangUSR.parse("c:objc(cs)UIColor(cm)colorWithRed:green:blue:alpha:"))
        XCTAssertEqual(classMethod.memberKind, .classMethod)
        let property = try XCTUnwrap(ClangUSR.parse("c:objc(cs)UIView(py)frame"))
        XCTAssertEqual(property.memberKind, .property)
        let crossModule = try XCTUnwrap(ClangUSR.parse("c:@CM@SignalUI@SignalServiceKit@objc(cs)OutgoingStoryMessage(cm)prepareForMultisendingWithDestinations:state:transaction:error:"))
        XCTAssertEqual(crossModule.module, "SignalServiceKit")
        XCTAssertEqual(crossModule.qualifiedContainer, "SignalServiceKit.OutgoingStoryMessage")
        XCTAssertEqual(crossModule.member, "prepareForMultisendingWithDestinations:state:transaction:error:")
        XCTAssertEqual(crossModule.memberKind, .classMethod)
        XCTAssertEqual(property.member, "frame")

        let type = try XCTUnwrap(ClangUSR.parse("c:objc(cs)NSObject"))
        XCTAssertEqual(type.container, "NSObject")
        XCTAssertNil(type.member)
        let proto = try XCTUnwrap(ClangUSR.parse("c:objc(pl)UIApplicationDelegate"))
        XCTAssertTrue(proto.isProtocol)
        XCTAssertEqual(proto.qualifiedContainer, "UIApplicationDelegate")
        let category = try XCTUnwrap(ClangUSR.parse("c:objc(cy)NSString@Extras(im)shout"))
        XCTAssertEqual(category.container, "NSString")
        XCTAssertEqual(category.member, "shout")
        let swiftType = try XCTUnwrap(ClangUSR.parse("c:@M@AcmeApp@objc(cs)AppDelegate"))
        XCTAssertEqual(swiftType.qualifiedContainer, "AcmeApp.AppDelegate")
        XCTAssertNil(swiftType.member)
    }

    func testCForms() throws {
        XCTAssertEqual(ClangUSR.parse("c:@S@CGRect")?.container, "CGRect")
        XCTAssertEqual(ClangUSR.parse("c:@E@UIUserInterfaceStyle")?.qualifiedContainer, "UIUserInterfaceStyle")
        XCTAssertEqual(ClangUSR.parse("c:@T@NSInteger")?.container, "NSInteger")
        let function = try XCTUnwrap(ClangUSR.parse("c:@F@NSStringFromClass"))
        XCTAssertNil(function.container)
        XCTAssertEqual(function.member, "NSStringFromClass")
        XCTAssertEqual(function.memberKind, .function)
        XCTAssertNil(function.qualifiedContainer)
        let constant = try XCTUnwrap(ClangUSR.parse("c:@E@UIUserInterfaceStyle@UIUserInterfaceStyleDark"))
        XCTAssertEqual(constant.container, "UIUserInterfaceStyle")
        XCTAssertEqual(constant.member, "UIUserInterfaceStyleDark")
        XCTAssertEqual(ClangUSR.parse("c:@N@std@S@string")?.container, "string")
    }

    func testSwiftExtensionObjectiveCForms() throws {
        let method = try XCTUnwrap(ClangUSR.parse("c:@CM@SignalServiceKit@@objc(cs)Cryptography(cm)computeSHA256Digest:truncatedToBytes:"))
        XCTAssertNil(method.module)
        XCTAssertEqual(method.qualifiedContainer, "Cryptography")
        XCTAssertEqual(method.container, "Cryptography")
        XCTAssertEqual(method.member, "computeSHA256Digest:truncatedToBytes:")
        XCTAssertEqual(method.memberKind, .classMethod)
        XCTAssertFalse(method.isProtocol)
        let property = try XCTUnwrap(ClangUSR.parse("c:@CM@App@objc(cs)Legacy(py)enabled"))
        XCTAssertEqual(property.module, "App")
        XCTAssertEqual(property.container, "Legacy")
        XCTAssertEqual(property.member, "enabled")
        XCTAssertEqual(property.memberKind, .property)
        for invalid in ["c:@CM@", "c:@CM@@@objc(cs)Legacy", "c:@CM@App@@@objc(cs)Legacy", "c:@CM@App@@objc(cs)"] {
            XCTAssertNil(ClangUSR.parse(invalid), invalid)
        }
    }

    /// The compiler uses @CM@ for these declarations, not the @M@ prefix of a
    /// member declared in a Swift class. Declaration types must reach both ends
    /// of a call, including a Swift extension on an Objective-C class.
    func testSwiftExtensionObjectiveCMembersKeepDeclaredAndCallSiteTypes() throws {
        let path = "/src/Legacy.swift"
        let source = """
        extension Legacy {
            @objc static func digest(_ data: Data, truncatedToBytes: UInt) -> Data? {
                return digest(data, truncatedToBytes: 16)
            }
            @objc var enabled: Bool { true }
        }
        class Lifetime: NSObject {
            deinit { }
        }
        """
        let facts = [path: SyntaxFacts.parse(source: source, path: path)]
        let owner = SymbolInfo(usr: "c:objc(cs)Legacy", name: "Legacy", kind: .class)
        let ext = SymbolInfo(usr: "s:e:c:@CM@App@@objc(cs)Legacy(cm)digest:truncatedToBytes:", name: "Legacy", kind: .extension)
        let digest = SymbolInfo(usr: "c:@CM@App@@objc(cs)Legacy(cm)digest:truncatedToBytes:", name: "digest(_:truncatedToBytes:)", kind: .classMethod)
        let enabled = SymbolInfo(usr: "c:@CM@App@@objc(cs)Legacy(py)enabled", name: "enabled", kind: .instanceProperty)
        let data = SymbolInfo(usr: "s:10Foundation4DataV", name: "Data", kind: .struct)
        let lifetime = SymbolInfo(usr: "c:@M@App@objc(cs)Lifetime", name: "Lifetime", kind: .class)
        let deinitSymbol = SymbolInfo(usr: "c:@M@App@objc(cs)Lifetime(im)dealloc", name: "deinit", kind: .destructor)
        func at(_ line: Int, _ column: Int) -> SourcePosition { SourcePosition(path: path, line: line, column: column) }
        var model = IndexModel()
        model.types = [
            TypeDecl(symbol: owner, kind: .class, module: "", position: at(1, 11), container: nil),
            TypeDecl(symbol: ext, kind: .extension, module: "App", position: at(1, 11), container: nil),
            TypeDecl(symbol: lifetime, kind: .class, module: "App", position: at(7, 7), container: nil),
        ]
        model.members = [
            MemberDecl(symbol: digest, kind: .method, isStatic: true, module: "App", position: at(2, 23), container: ext.usr, overrides: []),
            MemberDecl(symbol: enabled, kind: .property, isStatic: false, module: "App", position: at(5, 15), container: ext.usr, overrides: []),
            MemberDecl(symbol: deinitSymbol, kind: .deinitializer, isStatic: false, module: "App", position: at(8, 5), container: lifetime.usr, overrides: []),
        ]
        model.calls = [CallOccurrence(callee: digest, caller: digest, module: "App", position: at(3, 16), isDynamic: false)]
        for symbol in [owner, ext, digest, enabled, data, lifetime, deinitSymbol] { model.symbols[symbol.usr] = symbol }
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("extension-objc-\(UUID().uuidString).graphite-ir")
        defer { try? FileManager.default.removeItem(at: out) }
        var header = GraphiteIRHeader()
        header.language = "swift"
        let writer = try IRWriter(url: out, header: header)
        _ = try Emitter(writer: writer, model: model, facts: facts, demangled: [data.usr: "Foundation.Data"]).emit()
        let ir = try DecodedIR(url: out)
        let expected = "Legacy.digest(_:truncatedToBytes:)(Foundation.Data, Swift.UInt) -> Foundation.Data?"
        XCTAssertEqual(ir.methods.map(ir.signature), [expected, "App.Lifetime.deinit() -> Swift.Void"])
        let call = try XCTUnwrap(ir.callSites.first)
        XCTAssertEqual(ir.signature(call.caller), expected)
        XCTAssertEqual(ir.signature(call.callee), expected)
        XCTAssertEqual(call.arguments.map(ir.literal), ["<data>", "16"])
        let fields = ir.nodes.values.compactMap { node -> GraphiteIRField? in
            if case .field(let field)? = node.kind { return field }
            return nil
        }
        let field = try XCTUnwrap(fields.first)
        XCTAssertEqual(fields.count, 1)
        XCTAssertEqual(ir.type(field.field.declaringClass), "Legacy")
        XCTAssertEqual(ir.str(field.field.name), "enabled")
        XCTAssertEqual(ir.type(field.field.type), "Swift.Bool")
    }

    func testRejectedForms() {
        XCTAssertNil(ClangUSR.parse("s:8AcmeShop11CartServiceC"))
        XCTAssertNil(ClangUSR.parse("c:"))
        XCTAssertNil(ClangUSR.parse("c:objc(cs)"))
        XCTAssertNil(ClangUSR.parse("c:objc(cs)A(xx)b"))
        XCTAssertNil(ClangUSR.parse("c:objc(cs)A(im)"))
        XCTAssertNil(ClangUSR.parse("c:objc(cs"))
        XCTAssertNil(ClangUSR.parse("c:@M@Mod"))
        XCTAssertNil(ClangUSR.parse("c:@X@y"))
        XCTAssertNil(ClangUSR.parse("c:@"))
        XCTAssertNil(ClangUSR.parse("c:@F@"))
        // C globals and file-scoped (`static`) declarations are members without a container.
        XCTAssertEqual(ClangUSR.parse("c:@kMsgLimit"), ClangUSR(module: nil, container: nil, isProtocol: false, member: "kMsgLimit", memberKind: .property))
        XCTAssertEqual(ClangUSR.parse("c:Msg.m@sCalls"), ClangUSR(module: nil, container: nil, isProtocol: false, member: "sCalls", memberKind: .property))
        XCTAssertEqual(ClangUSR.parse("c:Msg.m@F@helper"), ClangUSR(module: nil, container: nil, isProtocol: false, member: "helper", memberKind: .function))
        XCTAssertEqual(ClangUSR.parse("c:@SA@MsgPoint@FI@x"), ClangUSR(module: nil, container: "MsgPoint", isProtocol: false, member: "x", memberKind: .property))
        XCTAssertEqual(ClangUSR.parse("c:@Ea@MsgFlagA@MsgFlagB")?.member, "MsgFlagB")
    }

    /// A UIKit delegate method in an `NSObject` subclass: the index gives it a Clang USR,
    /// the demangler nothing. Its descriptor comes from the USR (declaring type) and the
    /// source (parameter and return types), qualified where the names are known.
    func testObjectiveCBackedDeclarationsKeepTheirSignatures() throws {
        let path = "/src/AppDelegate.swift"
        let source = """
        import UIKit

        final class AppDelegate: NSObject, UIApplicationDelegate {
            var window: UIWindow?
            static var shared: AppDelegate?

            func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
                UIApplication.shared.registerForRemoteNotifications()
                return true
            }

            @objc func handle(order: Order, completion: @escaping (Bool) -> Void, values: Int...) {
            }

            @objc class func make() -> AppDelegate { AppDelegate() }
        }
        """
        let facts = [path: SyntaxFacts.parse(source: source, path: path)]
        let module = "AcmeApp"
        let delegate = SymbolInfo(usr: "s:7AcmeApp11AppDelegateC", name: "AppDelegate", kind: .class)
        let order = SymbolInfo(usr: "s:7AcmeApp5OrderV", name: "Order", kind: .struct)
        let uiApplication = SymbolInfo(usr: "c:objc(cs)UIApplication", name: "UIApplication", kind: .class)
        let launching = SymbolInfo(usr: "c:@M@AcmeApp@objc(cs)AppDelegate(im)application:didFinishLaunchingWithOptions:",
                                   name: "application(_:didFinishLaunchingWithOptions:)", kind: .instanceMethod)
        let handle = SymbolInfo(usr: "c:@M@AcmeApp@objc(cs)AppDelegate(im)handleWithOrder:completion:values:", name: "handle(order:completion:values:)", kind: .instanceMethod)
        let make = SymbolInfo(usr: "c:@M@AcmeApp@objc(cs)AppDelegate(cm)make", name: "make()", kind: .classMethod)
        let window = SymbolInfo(usr: "c:@M@AcmeApp@objc(cs)AppDelegate(py)window", name: "window", kind: .instanceProperty)
        let register = SymbolInfo(usr: "c:objc(cs)UIApplication(im)registerForRemoteNotifications", name: "registerForRemoteNotifications()", kind: .instanceMethod)
        func at(_ line: Int, _ column: Int) -> SourcePosition { SourcePosition(path: path, line: line, column: column) }

        var model = IndexModel()
        model.types = [TypeDecl(symbol: delegate, kind: .class, module: module, position: at(3, 13), container: nil)]
        model.members = [
            MemberDecl(symbol: window, kind: .property, isStatic: false, module: module, position: at(4, 9), container: delegate.usr, overrides: []),
            MemberDecl(symbol: launching, kind: .method, isStatic: false, module: module, position: at(7, 10), container: delegate.usr, overrides: []),
            MemberDecl(symbol: handle, kind: .method, isStatic: false, module: module, position: at(12, 16), container: delegate.usr, overrides: []),
            MemberDecl(symbol: make, kind: .method, isStatic: true, module: module, position: at(15, 22), container: delegate.usr, overrides: []),
        ]
        model.calls = [CallOccurrence(callee: register, caller: launching, module: module, position: at(8, 30), isDynamic: true)]
        for symbol in [delegate, order, uiApplication, launching, handle, make, window, register] { model.symbols[symbol.usr] = symbol }
        let demangled = [delegate.usr: "AcmeApp.AppDelegate", order.usr: "AcmeApp.Order"]

        let out = FileManager.default.temporaryDirectory.appendingPathComponent("objc-\(UUID().uuidString).graphite-ir")
        defer { try? FileManager.default.removeItem(at: out) }
        var header = GraphiteIRHeader()
        header.language = "swift"
        let writer = try IRWriter(url: out, header: header)
        let summary = try Emitter(writer: writer, model: model, facts: facts, demangled: demangled).emit()
        XCTAssertEqual(summary.methods, 3)
        XCTAssertEqual(summary.fields, 1)
        XCTAssertEqual(summary.callSites, 1)

        let ir = try DecodedIR(url: out)
        let methods = Set(ir.methods.map(ir.signature))
        XCTAssertEqual(methods, [
            "AcmeApp.AppDelegate.application(_:didFinishLaunchingWithOptions:)(UIApplication, [UIApplication.LaunchOptionsKey : Any]?) -> Swift.Bool",
            "AcmeApp.AppDelegate.handle(order:completion:values:)(AcmeApp.Order, (Swift.Bool) -> Swift.Void, Swift.Int...) -> Swift.Void",
            "AcmeApp.AppDelegate.make()() -> AcmeApp.AppDelegate",
        ])
        let fields = ir.nodes.values.compactMap { node -> String? in
            guard case .field(let f) = node.kind else { return nil }
            return "\(ir.type(f.field.declaringClass)).\(ir.str(f.field.name)): \(ir.type(f.field.type))"
        }
        XCTAssertEqual(fields, ["AcmeApp.AppDelegate.window: UIWindow?"])
        let site = try XCTUnwrap(ir.callSites.first)
        XCTAssertEqual(ir.type(site.callee.declaringClass), "UIApplication")
        XCTAssertEqual(ir.str(site.callee.name), "registerForRemoteNotifications()")
        XCTAssertEqual(ir.signature(site.caller), "AcmeApp.AppDelegate.application(_:didFinishLaunchingWithOptions:)(UIApplication, [UIApplication.LaunchOptionsKey : Any]?) -> Swift.Bool")
    }

    func testQualification() throws {
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("q-\(UUID().uuidString).graphite-ir")
        defer { try? FileManager.default.removeItem(at: out) }
        let writer = try IRWriter(url: out, header: GraphiteIRHeader())
        var model = IndexModel()
        let order = SymbolInfo(usr: "s:7AcmeApp5OrderV", name: "Order", kind: .struct)
        let dupA = SymbolInfo(usr: "s:1A4ItemV", name: "Item", kind: .struct)
        let dupB = SymbolInfo(usr: "s:1B4ItemV", name: "Item", kind: .struct)
        for symbol in [order, dupA, dupB] { model.symbols[symbol.usr] = symbol }
        let emitter = Emitter(writer: writer, model: model, facts: [:], demangled: [order.usr: "AcmeApp.Order", dupA.usr: "A.Item", dupB.usr: "B.Item"])
        XCTAssertEqual(emitter.qualify("Bool"), "Swift.Bool")
        XCTAssertEqual(emitter.qualify("()"), "Swift.Void")
        XCTAssertEqual(emitter.qualify("[String: Order]"), "[Swift.String : AcmeApp.Order]")
        XCTAssertEqual(emitter.qualify("[String : Order]"), "[Swift.String : AcmeApp.Order]")
        XCTAssertEqual(emitter.qualify("Item"), "Item", "an ambiguous name stays as written")
        XCTAssertEqual(emitter.qualify("Order.Type"), "AcmeApp.Order.Type")
        XCTAssertEqual(emitter.qualify("Result<Order, Error>"), "Swift.Result<AcmeApp.Order, Swift.Error>")
        XCTAssertEqual(emitter.qualify("(Int) -> Void"), "(Swift.Int) -> Swift.Void")
        XCTAssertEqual(emitter.qualify("inout Set<Int>"), "inout Swift.Set<Swift.Int>")
        XCTAssertEqual(emitter.qualify("UIApplication.LaunchOptionsKey"), "UIApplication.LaunchOptionsKey")
        XCTAssertEqual(emitter.qualify("my_Type2"), "my_Type2")
    }

    /// Qualified names come from indexes built once: nested types through their container,
    /// extensions through a demangled member, a declared type of the same name, or the
    /// module; and resolving every type of a large flat model stays linear.
    func testQualifiedNamesResolveThroughIndexes() throws {
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("names-\(UUID().uuidString).graphite-ir")
        defer { try? FileManager.default.removeItem(at: out) }
        func at(_ line: Int) -> SourcePosition { SourcePosition(path: "/src/A.swift", line: line, column: 1) }
        let outer = SymbolInfo(usr: "s:1M5OuterC", name: "Outer", kind: .class)
        let inner = SymbolInfo(usr: "s:1M5OuterC5InnerV", name: "Inner", kind: .struct)
        let deep = SymbolInfo(usr: "s:1M5OuterC5InnerV4DeepO", name: "Deep", kind: .enum)
        let ext = SymbolInfo(usr: "s:e:s:1M5OuterC5shoutyyF", name: "Outer", kind: .extension)
        let shout = SymbolInfo(usr: "s:1M5OuterC5shoutyyF", name: "shout()", kind: .instanceMethod)
        let ext2 = SymbolInfo(usr: "s:e:s:1M5OuterC4quietyyF", name: "Outer", kind: .extension)
        let ext3 = SymbolInfo(usr: "s:e:s:2SS5otherV", name: "Elsewhere", kind: .extension)
        var model = IndexModel()
        model.types = [
            TypeDecl(symbol: outer, kind: .class, module: "M", position: at(1), container: nil),
            TypeDecl(symbol: inner, kind: .struct, module: "M", position: at(2), container: outer.usr),
            TypeDecl(symbol: deep, kind: .enum, module: "M", position: at(3), container: inner.usr),
            TypeDecl(symbol: ext, kind: .extension, module: "M", position: at(10), container: nil),
            TypeDecl(symbol: ext2, kind: .extension, module: "M", position: at(20), container: nil),
            TypeDecl(symbol: ext3, kind: .extension, module: "M", position: at(30), container: nil),
        ]
        model.members = [MemberDecl(symbol: shout, kind: .method, isStatic: false, module: "M", position: at(11), container: ext.usr, overrides: [])]
        for symbol in [outer, inner, deep, ext, ext2, ext3, shout] { model.symbols[symbol.usr] = symbol }
        let writer = try IRWriter(url: out, header: GraphiteIRHeader())
        let emitter = Emitter(writer: writer, model: model, facts: [:], demangled: [shout.usr: "(extension in M):M.Outer.shout() -> ()"])
        XCTAssertEqual(emitter.qualifiedName(ofType: outer.usr), "M.Outer")
        XCTAssertEqual(emitter.qualifiedName(ofType: inner.usr), "M.Outer.Inner")
        XCTAssertEqual(emitter.qualifiedName(ofType: deep.usr), "M.Outer.Inner.Deep")
        XCTAssertEqual(emitter.qualifiedName(ofType: ext.usr), "M.Outer", "through the demangled member")
        XCTAssertEqual(emitter.qualifiedName(ofType: ext2.usr), "M.Outer", "through the declared type of the same name")
        XCTAssertEqual(emitter.qualifiedName(ofType: ext3.usr), "M.Elsewhere", "through the module")
        XCTAssertEqual(emitter.qualifiedName(ofType: "c:objc(cs)NSObject"), "NSObject")
        XCTAssertEqual(emitter.qualifiedName(ofType: "s:unknown"), "s:unknown")

        var flat = IndexModel()
        for index in 0..<20_000 {
            let symbol = SymbolInfo(usr: "s:1M1T\(index)C", name: "T\(index)", kind: .class)
            flat.types.append(TypeDecl(symbol: symbol, kind: .class, module: "M", position: at(index), container: nil))
            flat.symbols[symbol.usr] = symbol
        }
        let big = Emitter(writer: try IRWriter(url: out, header: GraphiteIRHeader()), model: flat, facts: [:], demangled: [:])
        let start = Date()
        for type in flat.types { XCTAssertEqual(big.qualifiedName(ofType: type.symbol.usr), "M." + type.symbol.name) }
        XCTAssertLessThan(Date().timeIntervalSince(start), 5, "linear resolution of 20,000 types")
    }
}
