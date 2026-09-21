import Foundation
import XCTest
@testable import GraphiteFrontend
@testable import GraphiteIR

/// Objective-C sources in the index store: the class, its methods and the calls between
/// them come out of the store the way Swift's do, from the `.m` and the header that
/// declares it. Clang writes the store on every platform the toolchain ships for, so
/// this runs on Linux as well as macOS; the Swift ↔ Objective-C directions need an Xcode
/// build and are asserted by the macOS CI job over the `AcmeApp` workspace.
final class ObjectiveCTests: XCTestCase {
    static let root = FileManager.default.temporaryDirectory.appendingPathComponent("graphite-objc-\(UUID().uuidString)")
    static var storePath: String { root.appendingPathComponent("store").path }
    static var compiled = false

    // A root class with scalar, pointer and block members, an ivar block, a class
    // property, a protocol and a category: everything Clang indexes without Foundation.
    static let header = """
    #pragma clang assume_nonnull begin
    extern const int kLegacyLimit;
    void LegacyReset(int mode);
    typedef struct { int x; int y; } LegacyPoint;
    @protocol Auditing
    @property (nonatomic) int badge;
    - (void)audit:(int)code;
    @end

    @interface LegacyBase
    @property (nonatomic) int level;
    @end

    @interface LegacyStore : LegacyBase <Auditing> {
        int _hits;
        void (^_handler)(int);
    }
    @property (nonatomic, readonly) int count;
    @property (class, nonatomic) int total;
    @property (nonatomic, copy, nullable) void (^onChange)(int, int);
    @property (nonatomic, assign) LegacyStore *parent;
    @property (nonatomic, getter=wasRead, setter=setRead:) int read;
    @property (nonatomic) char *contentType;
    + (instancetype)shared;
    - (int)storeValue:(int)value forKey:(int)key;
    - (nullable LegacyStore *)childNamed:(const char *)name __attribute__((swift_name("child(named:)")));
    @end
    #pragma clang assume_nonnull end

    @interface LegacyStore (Extras)
    - (void)purge;
    @end
    """
    static let implementation = """
    #import "LegacyStore.h"
    const int kLegacyLimit = 3;
    static int sCalls = 0;
    void LegacyReset(int mode) { sCalls = mode; }
    static inline int helper(int x) { return x + 1; }
    @implementation LegacyBase
    @end
    @implementation LegacyStore
    static char *const kTag = "tag";
    @synthesize contentType = _contentType;
    @synthesize badge = _badge;
    @dynamic level;
    + (instancetype)shared { return 0; }
    + (int)total { return 1; }
    + (void)setTotal:(int)total {}
    - (int)normalizeKey:(int)key { return key + 1; }
    - (int)storeValue:(int)value forKey:(int)key { _hits += 1; return [self normalizeKey:key] + value + helper(sCalls); }
    - (LegacyStore *)childNamed:(const char *)name { return 0; }
    @end
    @implementation LegacyStore (Extras)
    - (void)purge {}
    @end
    """

    /// `clang` of the toolchain the tests run with: next to `swift`, or the Xcode shim.
    static func locateClang() -> String? {
        let environment = ProcessInfo.processInfo.environment
        if let swift = Frontend.locateSwift(environment: environment) {
            let sibling = URL(fileURLWithPath: swift).resolvingSymlinksInPath().deletingLastPathComponent().appendingPathComponent("clang").path
            if FileManager.default.isExecutableFile(atPath: sibling) { return sibling }
        }
        for dir in (environment["PATH"] ?? "").split(separator: ":") where FileManager.default.isExecutableFile(atPath: "\(dir)/clang") {
            return "\(dir)/clang"
        }
        return FileManager.default.isExecutableFile(atPath: "/usr/bin/clang") ? "/usr/bin/clang" : nil
    }

    override func setUpWithError() throws {
        try super.setUpWithError()
        guard !Self.compiled else { return }
        guard let clang = Self.locateClang() else { throw XCTSkip("no clang to write an Objective-C index store with") }
        try FileManager.default.createDirectory(at: Self.root, withIntermediateDirectories: true)
        try Self.header.write(to: Self.root.appendingPathComponent("LegacyStore.h"), atomically: true, encoding: .utf8)
        try Self.implementation.write(to: Self.root.appendingPathComponent("LegacyStore.m"), atomically: true, encoding: .utf8)
        // `-fsyntax-only` still writes the index store; no runtime or Foundation is needed
        // for a root class with scalar members.
        let process = Process()
        process.executableURL = URL(fileURLWithPath: clang)
        process.currentDirectoryURL = Self.root
        // `-fsyntax-only` still writes the index store; no runtime or Foundation is needed
        // for a root class with scalar members. On Linux the default (fragile) runtime
        // cannot synthesize an instance variable, so the GNUstep 2 ABI stands in for the
        // modern one macOS uses by default.
        process.arguments = [
            "-fsyntax-only", "-fblocks", "-Wno-objc-root-class", "-Wno-objc-property-implementation",
            "-index-store-path", Self.storePath, "LegacyStore.m",
        ]
        #if os(Linux)
        process.arguments?.insert("-fobjc-runtime=gnustep-2.0", at: 2)
        #endif
        process.standardOutput = FileHandle.standardError
        process.standardError = FileHandle.standardError
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { throw XCTSkip("clang could not index the Objective-C fixture (exit \(process.terminationStatus))") }
        Self.compiled = true
    }

    func testObjectiveCFilesAreSourceFiles() throws {
        let files = try Frontend.sourceFiles(under: [Self.root.path])
        XCTAssertEqual(files.map { URL(fileURLWithPath: $0).lastPathComponent }, ["LegacyStore.h", "LegacyStore.m"])
        XCTAssertEqual(try Frontend.swiftFiles(under: [Self.root.path]), [])
        XCTAssertTrue(Frontend.isSwift("/a/b.swift"))
        XCTAssertFalse(Frontend.isSwift("/a/b.m"))
        XCTAssertEqual(Frontend.sourceExtensions, ["swift", "m", "mm", "h"])
    }

    func testTheStoreYieldsTheClassItsMembersAndItsCalls() throws {
        let library = try XCTUnwrap(IndexReader.locateLibrary(environment: ProcessInfo.processInfo.environment))
        let reader = try IndexReader(storePath: Self.storePath, libraryPath: library)
        let model = reader.read(files: try Frontend.sourceFiles(under: [Self.root.path]))
        // The `@interface` in the header and the `@implementation` are one class; the
        // protocol, which has no definition, is declared once; the category is an extension.
        // The anonymous struct has no name of its own in the index (the emitter names it by
        // its typedef, from the USR).
        XCTAssertEqual(model.types.map { "\($0.symbol.name):\($0.kind)" }, [":struct", "Auditing:protocol", "LegacyBase:class", "LegacyStore:class", "Extras:extension"])
        XCTAssertEqual(model.types[0].symbol.usr, "c:@SA@LegacyPoint")
        let members = model.members.map { "\($0.symbol.name)\($0.isStatic ? " static" : "")" }
        XCTAssertEqual(members.sorted(), [
            "LegacyReset static", "_badge", "_contentType", "_handler", "_hits", "audit:", "badge", "childNamed:",
            "contentType", "count", "helper static", "kLegacyLimit static", "kTag static", "level", "normalizeKey:",
            "onChange", "parent", "purge", "read", "sCalls static", "setRead:", "setTotal: static", "shared static",
            "storeValue:forKey:", "total", "total static", "wasRead", "x", "y",
        ])
        // The ivars `@synthesize` created are linked to the properties they back, a
        // protocol's included; a property is one member whether declared in a protocol or a
        // superclass and defined (`@synthesize`, `@dynamic`) in the class, under the class.
        XCTAssertEqual(model.synthesized["c:objc(cs)LegacyStore@_contentType"], "c:objc(cs)LegacyStore(py)contentType")
        XCTAssertEqual(model.synthesized["c:objc(cs)LegacyStore@_badge"], "c:objc(pl)Auditing(py)badge")
        XCTAssertEqual(model.members.filter { $0.symbol.usr == "c:objc(cs)LegacyBase(py)level" }.map { $0.container ?? "-" }, ["c:objc(cs)LegacyStore"])
        XCTAssertEqual(model.members.filter { $0.symbol.usr == "c:objc(pl)Auditing(py)badge" }.map { $0.container ?? "-" }, ["c:objc(cs)LegacyStore"])
        XCTAssertEqual(model.supertypes.filter { $0.subtype == "c:objc(cs)LegacyStore" }.map(\.supertype.usr).sorted(), ["c:objc(cs)LegacyBase", "c:objc(pl)Auditing"])
        XCTAssertEqual(model.members.first { $0.symbol.name == "count" }?.kind, .property)
        XCTAssertEqual(model.members.first { $0.symbol.name == "_hits" }?.kind, .property)
        XCTAssertEqual(model.members.first { $0.symbol.name == "shared" }?.kind, .method)
        let store = model.types[3].symbol.usr
        let ofStore = model.members.filter { $0.container != nil && !["audit:", "purge", "x", "y"].contains($0.symbol.name) }
        XCTAssertTrue(ofStore.allSatisfy { $0.container == store }, "\(ofStore.filter { $0.container != store }.map(\.symbol.usr))")
        XCTAssertEqual(model.members.first { $0.symbol.name == "x" }?.container, model.types[0].symbol.usr)
        XCTAssertNil(model.members.first { $0.symbol.name == "LegacyReset" }?.container)
        XCTAssertEqual(model.members.first { $0.symbol.name == "purge" }?.container, model.types[4].symbol.usr)
        XCTAssertEqual(model.members.first { $0.symbol.name == "audit:" }?.container, model.types[1].symbol.usr)
        // A method defined in the `.m` remembers its declaration in the header.
        let shared = try XCTUnwrap(model.members.first { $0.symbol.name == "shared" })
        XCTAssertTrue(shared.position.path.hasSuffix("LegacyStore.m"))
        XCTAssertEqual(shared.declaration.map { "\(URL(fileURLWithPath: $0.path).lastPathComponent):\($0.line):\($0.column)" }, "LegacyStore.h:24:17")
        XCTAssertNil(model.members.first { $0.symbol.name == "normalizeKey:" }?.declaration)
        XCTAssertNil(model.members.first { $0.symbol.name == "count" }?.declaration)
        XCTAssertEqual(model.calls.map { "\($0.caller?.name ?? "?") -> \($0.callee.name)" }.sorted(), ["storeValue:forKey: -> helper", "storeValue:forKey: -> normalizeKey:"])
        XCTAssertTrue(model.calls.allSatisfy { $0.position.path.hasSuffix("LegacyStore.m") && $0.position.line == 17 })
    }

    func testTheGraphOfAnObjectiveCClass() throws {
        var options = BuildOptions()
        options.indexStore = Self.storePath
        options.sources = [Self.root.path]
        let out = Self.root.appendingPathComponent("legacy.graphite-ir")
        let result = try Frontend(options: options).build(to: out)
        XCTAssertEqual(result.files, 2)
        XCTAssertEqual(result.summary.types, 4)
        XCTAssertEqual(result.summary.methods, 12)
        XCTAssertEqual(result.summary.fields, 17)
        XCTAssertEqual(result.summary.callSites, 2)
        let decoded = try DecodedIR(url: out)
        XCTAssertEqual(decoded.header.options["files"], "2")
        XCTAssertEqual(decoded.header.options["swift_files"], "0")
        XCTAssertEqual(decoded.header.options["objc_files"], "2")
        // Clang indexes the class without a module: the class name stands alone, and the
        // declaration and the call site agree on it. The types come from the source, as
        // Clang spells them, the header's declaration before the `.m`'s definition.
        XCTAssertEqual(decoded.origins.map(\.className), ["LegacyPoint", "Auditing", "LegacyBase", "LegacyStore"])
        // C functions have no declaring type; the accessors a property names are methods
        // with the property's type; a function's parameter names are not part of its type.
        XCTAssertEqual(decoded.methods.map(decoded.signature).sorted(), [
            ".LegacyReset(int) -> void",
            ".helper(int) -> int",
            "Auditing.audit:(int) -> void",
            "LegacyStore.childNamed:(const char * _Nonnull) -> LegacyStore * _Nullable",
            "LegacyStore.normalizeKey:(int) -> int",
            "LegacyStore.purge() -> void",
            "LegacyStore.setRead:(int) -> void",
            "LegacyStore.setTotal:(int) -> void",
            "LegacyStore.shared() -> instancetype _Nonnull",
            "LegacyStore.storeValue:forKey:(int, int) -> int",
            "LegacyStore.total() -> int",
            "LegacyStore.wasRead() -> int",
        ])
        let calls = decoded.callSites
        XCTAssertEqual(calls.count, 2)
        XCTAssertEqual(calls.map { decoded.signature($0.caller) }, Array(repeating: "LegacyStore.storeValue:forKey:(int, int) -> int", count: 2))
        XCTAssertEqual(calls.map { decoded.signature($0.callee) }.sorted(), [".helper(int) -> int", "LegacyStore.normalizeKey:(int) -> int"],
                       "a call into a file-scoped C function carries the function's descriptor")
        XCTAssertEqual(calls.map(\.line), [17, 17])
        XCTAssertEqual(calls.flatMap(\.arguments), [], "no syntax pass reads Objective-C call arguments, so a call carries no argument nodes")
        let fields = decoded.nodes.values.filter { $0.field != GraphiteIRField() }.map(\.field)
        let described = fields.map { field in
            decoded.type(field.field.declaringClass) + "." + decoded.str(field.field.name) + ": " + decoded.type(field.field.type) + (field.isStatic ? " static" : "")
        }
        // Globals (file-scoped or not) are static fields without a declaring type, a
        // struct's fields are the struct's, and the instance variables `@synthesize` created
        // take their properties' types, the protocol's `badge` included; `@dynamic level`
        // is the superclass's property under the class, with its type.
        XCTAssertEqual(described.sorted(), [
            ".kLegacyLimit: const int static",
            ".kTag: char *const static",
            ".sCalls: int static",
            "LegacyPoint.x: int",
            "LegacyPoint.y: int",
            "LegacyStore._badge: int",
            "LegacyStore._contentType: char * _Nonnull",
            "LegacyStore._handler: void (^ _Nonnull)(int)",
            "LegacyStore._hits: int",
            "LegacyStore.badge: int",
            "LegacyStore.contentType: char * _Nonnull",
            "LegacyStore.count: int",
            "LegacyStore.level: int",
            "LegacyStore.onChange: void (^ _Nullable)(int, int)",
            "LegacyStore.parent: LegacyStore * _Nonnull",
            "LegacyStore.read: int",
            "LegacyStore.total: int static",
        ])
    }

    func testContainerUSRs() {
        XCTAssertEqual(ClangUSR.containerUSR(of: "c:objc(cs)LegacyStore(im)storeValue:forKey:"), "c:objc(cs)LegacyStore")
        XCTAssertEqual(ClangUSR.containerUSR(of: "c:objc(cs)UIColor(cm)colorWithRed:green:blue:alpha:"), "c:objc(cs)UIColor")
        XCTAssertEqual(ClangUSR.containerUSR(of: "c:objc(cs)UIView(py)frame"), "c:objc(cs)UIView")
        XCTAssertEqual(ClangUSR.containerUSR(of: "c:@M@AcmeApp@objc(cs)AppDelegate(im)application:didFinishLaunchingWithOptions:"), "c:@M@AcmeApp@objc(cs)AppDelegate")
        XCTAssertEqual(ClangUSR.containerUSR(of: "c:objc(cy)LegacyStore@Extras(im)purge"), "c:objc(cy)LegacyStore@Extras")
        XCTAssertEqual(ClangUSR.containerUSR(of: "c:objc(cs)LegacyStore(cpy)total"), "c:objc(cs)LegacyStore")
        XCTAssertEqual(ClangUSR.containerUSR(of: "c:objc(cs)LegacyStore@_hits"), "c:objc(cs)LegacyStore")
        XCTAssertEqual(ClangUSR.parse("c:objc(cs)LegacyStore(cpy)total"), ClangUSR(module: nil, container: "LegacyStore", isProtocol: false, member: "total", memberKind: .classProperty))
        XCTAssertEqual(ClangUSR.parse("c:objc(cs)LegacyStore@_hits"), ClangUSR(module: nil, container: "LegacyStore", isProtocol: false, member: "_hits", memberKind: .instanceVariable))
        XCTAssertEqual(ClangUSR.parse("c:objc(cy)LegacyStore@Extras")?.member, nil)
        XCTAssertNil(ClangUSR.containerUSR(of: "c:objc(cs)LegacyStore"))
        XCTAssertNil(ClangUSR.containerUSR(of: "c:@F@NSStringFromClass"))
        XCTAssertNil(ClangUSR.containerUSR(of: "s:8AcmeShop11CartServiceC"))
    }
}
