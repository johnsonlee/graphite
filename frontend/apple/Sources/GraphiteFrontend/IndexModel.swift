import Foundation
import IndexStoreDB

/// A position in a source file, as both the index store and SwiftSyntax report it:
/// 1-based line, 1-based UTF-8 column.
public struct SourcePosition: Hashable, Comparable {
    public var path: String
    public var line: Int
    public var column: Int

    public init(path: String, line: Int, column: Int) {
        self.path = path
        self.line = line
        self.column = column
    }

    public static func < (a: SourcePosition, b: SourcePosition) -> Bool {
        (a.path, a.line, a.column) < (b.path, b.line, b.column)
    }
}

public enum TypeKind: String, Sendable {
    case `class`, `struct`, `enum`, `protocol`, `extension`
}

public enum MemberKind: String, Sendable {
    case method, initializer, deinitializer, function, property, enumCase
}

/// A named symbol as the index store describes it.
public struct SymbolInfo: Hashable, Sendable {
    public var usr: String
    public var name: String
    public var kind: IndexSymbolKind

    public init(usr: String, name: String, kind: IndexSymbolKind) {
        self.usr = usr
        self.name = name
        self.kind = kind
    }
}

public struct TypeDecl: Equatable {
    public var symbol: SymbolInfo
    public var kind: TypeKind
    public var module: String
    public var position: SourcePosition
    public var container: String?
}

public struct MemberDecl: Equatable {
    public var symbol: SymbolInfo
    public var kind: MemberKind
    public var isStatic: Bool
    public var module: String
    public var position: SourcePosition
    /// USR of the enclosing type or extension; nil for a free function or global variable.
    public var container: String?
    /// USR of the protocol requirement or superclass member this one implements or overrides.
    public var overrides: [String]
}

public struct SupertypeRelation: Equatable {
    public var subtype: String
    public var supertype: SymbolInfo
}

public struct CallOccurrence: Equatable {
    public var callee: SymbolInfo
    /// The enclosing function, initializer or property initializer; nil at top level.
    public var caller: SymbolInfo?
    public var module: String
    public var position: SourcePosition
    public var isDynamic: Bool
}

/// What the frontend takes from the index store for one build.
public struct IndexModel {
    public var types: [TypeDecl] = []
    public var members: [MemberDecl] = []
    public var supertypes: [SupertypeRelation] = []
    public var calls: [CallOccurrence] = []
    /// Every symbol seen, for name lookups by USR.
    public var symbols: [String: SymbolInfo] = [:]

    public init() {}
}

public enum IndexError: Error, CustomStringConvertible {
    case noStore(String)
    case noLibrary(String)
    case open(String)

    public var description: String {
        switch self {
        case .noStore(let path): return "no index store at \(path); build the package first"
        case .noLibrary(let path): return "no libIndexStore at \(path)"
        case .open(let reason): return "cannot open the index store: \(reason)"
        }
    }
}

/// Reads declarations, type relations and calls for a set of source files out of an
/// index store written by the Swift compiler (`-index-store-path`, which SwiftPM and
/// Xcode enable by default).
public final class IndexReader {
    private let db: IndexStoreDB
    private let databasePath: String

    public init(storePath: String, libraryPath: String) throws {
        guard FileManager.default.fileExists(atPath: storePath) else { throw IndexError.noStore(storePath) }
        guard FileManager.default.fileExists(atPath: libraryPath) else { throw IndexError.noLibrary(libraryPath) }
        let library: IndexStoreLibrary
        do {
            library = try IndexStoreLibrary(dylibPath: libraryPath)
        } catch {
            throw IndexError.open("\(error)")
        }
        databasePath = NSTemporaryDirectory() + "/graphite-index-\(ProcessInfo.processInfo.processIdentifier)-\(UInt32.random(in: 0...UInt32.max))"
        do {
            db = try IndexStoreDB(
                storePath: storePath,
                databasePath: databasePath,
                library: library,
                waitUntilDoneInitializing: true,
                readonly: false,
                listenToUnitEvents: false
            )
        } catch {
            throw IndexError.open("\(error)")
        }
        db.pollForUnitChangesAndWait(isInitialScan: true)
    }

    deinit {
        try? FileManager.default.removeItem(atPath: databasePath)
    }

    /// Locates `libIndexStore` in the toolchain: `GRAPHITE_INDEXSTORE_LIBRARY`, the
    /// toolchain of `GRAPHITE_SWIFT_TOOLCHAIN` or of the `swift` on `PATH`, `xcrun`.
    public static func locateLibrary(environment: [String: String] = ProcessInfo.processInfo.environment) -> String? {
        if let explicit = environment["GRAPHITE_INDEXSTORE_LIBRARY"], FileManager.default.fileExists(atPath: explicit) {
            return explicit
        }
        #if os(macOS)
        let name = "libIndexStore.dylib"
        #else
        let name = "libIndexStore.so"
        #endif
        var roots: [String] = []
        if let toolchain = environment["GRAPHITE_SWIFT_TOOLCHAIN"] { roots.append(toolchain) }
        for dir in (environment["PATH"] ?? "").split(separator: ":") where FileManager.default.isExecutableFile(atPath: "\(dir)/swift") {
            var url = URL(fileURLWithPath: String(dir)).resolvingSymlinksInPath()
            if url.lastPathComponent == "bin" { url.deleteLastPathComponent() }
            if url.lastPathComponent == "usr" { url.deleteLastPathComponent() }
            roots.append(url.path)
        }
        #if os(macOS)
        if let found = try? Demangler.run(URL(fileURLWithPath: "/usr/bin/xcrun"), arguments: ["--find", "swift"], input: "") {
            var url = URL(fileURLWithPath: found.trimmingCharacters(in: .whitespacesAndNewlines)).resolvingSymlinksInPath()
            url.deleteLastPathComponent()  // bin
            url.deleteLastPathComponent()  // usr
            roots.insert(url.path, at: 0)
        }
        #endif
        for root in roots {
            for candidate in ["\(root)/usr/lib/\(name)", "\(root)/lib/\(name)"]
            where FileManager.default.fileExists(atPath: candidate) {
                return candidate
            }
        }
        return nil
    }

    public func read(files: [String]) -> IndexModel {
        var model = IndexModel()
        var seen = Set<String>()
        for file in files.sorted() {
            for occurrence in db.symbolOccurrences(inFilePath: file) {
                let symbol = SymbolInfo(usr: occurrence.symbol.usr, name: occurrence.symbol.name, kind: occurrence.symbol.kind)
                model.symbols[symbol.usr] = symbol
                for relation in occurrence.relations {
                    let related = SymbolInfo(usr: relation.symbol.usr, name: relation.symbol.name, kind: relation.symbol.kind)
                    model.symbols[related.usr] = related
                }
                let position = SourcePosition(path: occurrence.location.path, line: occurrence.location.line, column: occurrence.location.utf8Column)
                let roles = occurrence.roles
                if roles.contains(.definition) && !roles.contains(.implicit) {
                    let key = "def \(symbol.usr) \(position.path):\(position.line):\(position.column)"
                    guard seen.insert(key).inserted else { continue }
                    IndexReader.recordDefinition(symbol, occurrence: occurrence, position: position, into: &model)
                } else if roles.contains(.baseOf) {
                    for relation in occurrence.relations where relation.roles.contains(.baseOf) {
                        let key = "base \(relation.symbol.usr) \(symbol.usr)"
                        guard seen.insert(key).inserted else { continue }
                        model.supertypes.append(SupertypeRelation(subtype: relation.symbol.usr, supertype: symbol))
                    }
                } else if roles.contains(.call) && !roles.contains(.implicit) {
                    let key = "call \(symbol.usr) \(position.path):\(position.line):\(position.column)"
                    guard seen.insert(key).inserted else { continue }
                    let container = occurrence.relations.first { $0.roles.contains(.calledBy) }
                        ?? occurrence.relations.first { $0.roles.contains(.containedBy) }
                    let caller = container.map { SymbolInfo(usr: $0.symbol.usr, name: $0.symbol.name, kind: $0.symbol.kind) }
                    model.calls.append(CallOccurrence(
                        callee: symbol,
                        caller: caller,
                        module: occurrence.location.moduleName,
                        position: position,
                        isDynamic: roles.contains(.dynamic)
                    ))
                }
            }
        }
        model.types.sort { $0.position < $1.position }
        model.members.sort { $0.position < $1.position }
        model.calls.sort { $0.position < $1.position }
        return model
    }

    private static func recordDefinition(_ symbol: SymbolInfo, occurrence: SymbolOccurrence, position: SourcePosition, into model: inout IndexModel) {
        let container = occurrence.relations.first { $0.roles.contains(.childOf) }?.symbol
        let overrides = occurrence.relations.filter { $0.roles.contains(.overrideOf) }.map(\.symbol.usr)
        let module = occurrence.location.moduleName
        let typeKind: TypeKind?
        switch symbol.kind {
        case .class: typeKind = .class
        case .struct: typeKind = .struct
        case .enum: typeKind = .enum
        case .protocol: typeKind = .protocol
        case .extension: typeKind = .extension
        default: typeKind = nil
        }
        if let typeKind {
            model.types.append(TypeDecl(symbol: symbol, kind: typeKind, module: module, position: position, container: container?.usr))
            return
        }
        // An explicit accessor (`var total: Double { ... }`) is a definition of its own;
        // the property it belongs to is the member the graph keeps.
        if let container, IndexReader.isProperty(container.kind), symbol.kind != .parameter { return }
        let memberKind: MemberKind
        var isStatic = false
        switch symbol.kind {
        case .instanceMethod: memberKind = .method
        case .classMethod, .staticMethod:
            memberKind = .method
            isStatic = true
        case .constructor: memberKind = .initializer
        case .destructor: memberKind = .deinitializer
        case .function:
            // Nested functions are locals of their enclosing function.
            guard container.map({ IndexReader.isTypeLike($0.kind) || $0.kind == .module }) ?? true else { return }
            memberKind = .function
            isStatic = true
        case .instanceProperty: memberKind = .property
        case .classProperty, .staticProperty:
            memberKind = .property
            isStatic = true
        case .variable, .field:
            // Locals live under a function; only globals (no container) are fields.
            guard container == nil else { return }
            memberKind = .property
            isStatic = true
        case .enumConstant: memberKind = .enumCase
        default: return
        }
        model.members.append(MemberDecl(
            symbol: symbol,
            kind: memberKind,
            isStatic: isStatic,
            module: module,
            position: position,
            container: container?.usr,
            overrides: overrides
        ))
    }

    static func isProperty(_ kind: IndexSymbolKind) -> Bool {
        switch kind {
        case .instanceProperty, .classProperty, .staticProperty, .variable, .field: return true
        default: return false
        }
    }

    static func isTypeLike(_ kind: IndexSymbolKind) -> Bool {
        switch kind {
        case .class, .struct, .enum, .protocol, .extension: return true
        default: return false
        }
    }
}
