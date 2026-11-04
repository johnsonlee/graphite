import CGraphiteIndex
import Foundation
import IndexStoreDB

private extension GIndexString {
    var string: String {
        String(decoding: UnsafeRawBufferPointer(start: data, count: length), as: UTF8.self)
    }
}

// libIndexStore uses stable C enum values. IndexStoreDB exposes its own Swift
// enums. Never depend on their declaration order or memory representation.
private let indexKinds: [UInt32: IndexSymbolKind] = [
    1: .module, 2: .namespace, 3: .namespaceAlias, 4: .macro, 5: .enum, 6: .struct,
    7: .class, 8: .protocol, 9: .extension, 10: .union, 11: .typealias, 12: .function,
    13: .variable, 14: .field, 15: .enumConstant, 16: .instanceMethod, 17: .classMethod,
    18: .staticMethod, 19: .instanceProperty, 20: .classProperty, 21: .staticProperty,
    22: .constructor, 23: .destructor, 24: .conversionFunction, 25: .parameter,
    26: .using, 27: .concept, 1000: .commentTag,
]

private let indexLanguages: [UInt32: Language] = [0: .c, 1: .objc, 2: .cxx, 100: .swift]

private extension GIndexSymbol {
    var symbol: Symbol {
        return Symbol(usr: usr.string, name: name.string, kind: indexKinds[kind] ?? .unknown,
                      properties: SymbolProperty(rawValue: properties), language: indexLanguages[language] ?? .c)
    }
}

private func readError(_ message: UnsafeMutablePointer<CChar>?, fallback: String) -> IndexError {
    defer { free(message) }
    return .open(message.map { String(cString: $0) } ?? fallback)
}

/// Per-file IndexStoreDB queries stop at the first provider. Preserve that
/// established path for a single record. Read each distinct compiler record
/// directly when the same physical Swift source was built in multiple contexts.
final class RawIndexReader {
    struct Record: Hashable, Comparable {
        let module: String
        let name: String
        static func < (a: Self, b: Self) -> Bool { (a.module, a.name) < (b.module, b.name) }
    }
    struct Origin {
        let system: Bool
        let timestamp: Date
    }
    typealias Records = [Record: Origin]

    private let index: OpaquePointer

    init(store: String, library: String) throws {
        var error: UnsafeMutablePointer<CChar>?
        guard let index = gindex_open(library, store, &error) else { throw readError(error, fallback: store) }
        self.index = index
    }

    deinit { gindex_close(index) }

    func records(files: Set<String>) throws -> [String: Records] {
        final class Catalog {
            let requested: Set<String>
            var canonical: [String: String] = [:]
            var records: [String: Records] = [:]
            init(requested: Set<String>) { self.requested = requested }
        }
        var error: UnsafeMutablePointer<CChar>?
        let catalog = Catalog(requested: files)
        let ok = gindex_swift_records(index, Unmanaged.passUnretained(catalog).toOpaque(), {
            context, file, directory, module, name, system, seconds, nanos in
            let catalog = Unmanaged<Catalog>.fromOpaque(context!).takeUnretainedValue()
            let spelling = file.string
            let absolute = spelling.hasPrefix("/") ? spelling : directory.string + "/" + spelling
            let path = catalog.canonical[absolute] ?? SourcePosition.canonical(absolute)
            catalog.canonical[absolute] = path
            guard catalog.requested.contains(path) else { return }
            let key = Record(module: module.string, name: name.string)
            let timestamp = Date(timeIntervalSince1970: Double(seconds) + Double(nanos) / 1e9)
            catalog.records[path, default: [:]][key] = Origin(system: system, timestamp: timestamp)
        }, &error)
        guard ok else { throw readError(error, fallback: "cannot enumerate Swift compiler records") }
        return catalog.records
    }

    func occurrences(file: String, records: Records) throws -> [SymbolOccurrence] {
        final class Batch {
            let file: String
            let module: String
            let origin: Origin
            var occurrences: [SymbolOccurrence] = []
            init(file: String, module: String, origin: Origin) {
                self.file = file
                self.module = module
                self.origin = origin
            }
        }
        var result: [SymbolOccurrence] = []
        for record in records.keys.sorted() {
            let batch = Batch(file: file, module: record.module, origin: records[record]!)
            var error: UnsafeMutablePointer<CChar>?
            let ok = gindex_occurrences(index, record.name, Unmanaged.passUnretained(batch).toOpaque(), {
                context, rawSymbol, rawRoles, line, column, relations, count in
                let batch = Unmanaged<Batch>.fromOpaque(context!).takeUnretainedValue()
                let symbol = rawSymbol.symbol
                // Primary and relation role bits share the libIndexStore layout.
                // IndexStoreDB additionally tags the preferred definition/declaration.
                var roles = SymbolRole(rawValue: rawRoles)
                let preferDeclaration = symbol.language == .objc &&
                    [.class, .extension, .instanceProperty, .classProperty].contains(symbol.kind)
                if roles.contains(preferDeclaration ? .declaration : .definition) { roles.insert(.canonical) }
                let location = SymbolLocation(path: batch.file, timestamp: batch.origin.timestamp,
                                              moduleName: batch.module, isSystem: batch.origin.system,
                                              line: Int(line), utf8Column: Int(column))
                let related = UnsafeBufferPointer(start: relations, count: count).map {
                    SymbolRelation(symbol: $0.symbol.symbol, roles: SymbolRole(rawValue: $0.roles))
                }
                batch.occurrences.append(SymbolOccurrence(symbol: symbol, location: location, roles: roles,
                                                          symbolProvider: .swift, relations: related))
            }, &error)
            guard ok else { throw readError(error, fallback: "cannot read Swift compiler record \(record.name)") }
            result += batch.occurrences
        }
        return result
    }
}
