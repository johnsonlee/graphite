import CoreFoundation
import Foundation
import GraphiteIR

/// Inventory of an explicitly selected built iOS application or app extension.
/// A path in the graph includes the bundle identifier: ResourceValue has no separate
/// source field, and different bundles can contain identically named tables and keys.
public enum AppleResources {
    public struct Omission: Equatable {
        public let path: String
        public let key: String
        public let reason: String
    }

    public struct Invalid: Error, CustomStringConvertible {
        public let description: String
    }

    /// Visit one file at a time, in stable order. Missing/invalid packaged resources
    /// fail the read. Plist entries with no lossless IR representation are reported;
    /// a partially representable container is omitted whole, never truncated.
    @discardableResult
    public static func read(bundle: URL, visit: (GraphiteIRResourceFile, [GraphiteIRResourceValue]) throws -> Void) throws -> [Omission] {
        let root = bundle.resolvingSymlinksInPath().standardizedFileURL
        guard ["app", "appex"].contains(root.pathExtension),
              try root.resourceValues(forKeys: [.isDirectoryKey]).isDirectory == true else {
            throw Invalid(description: "expected a built iOS .app or .appex directory: \(bundle.path)")
        }
        let info = root.appendingPathComponent("Info.plist")
        guard try regularFile(info) else { throw Invalid(description: "expected a regular Info.plist in \(root.path)") }
        let metadata = try dictionary(in: info)
        guard let id = metadata["CFBundleIdentifier"] as? String, !id.isEmpty,
              id != ".", id != "..", !id.contains("/") else {
            throw Invalid(description: "missing or invalid CFBundleIdentifier in \(info.path)")
        }

        var omitted: [Omission] = []
        func emit(_ dictionary: [String: Any], path: String, format: String, locale: String?) throws {
            var file = GraphiteIRResourceFile()
            file.path = "\(id)/\(path)"
            file.source = id
            file.format = format
            if let locale { file.profile = locale }
            var values: [GraphiteIRResourceValue] = []
            for key in dictionary.keys.sorted() {
                let raw = dictionary[key]!
                if format == "strings" && !(raw is String) {
                    throw Invalid(description: "expected a string value for \(key) in \(file.path)")
                }
                var entry = GraphiteIRResourceValue()
                entry.path = file.path
                entry.key = key
                entry.format = format
                if let locale { entry.profile = locale }
                do {
                    entry.value = try typedValue(raw)
                    values.append(entry)
                } catch let error as Unsupported {
                    omitted.append(Omission(path: file.path, key: key, reason: error.reason))
                }
            }
            try visit(file, values)
        }

        try emit(metadata, path: "Info.plist", format: "plist", locale: nil)
        for directory in try children(of: root) where directory.pathExtension == "lproj" {
            let attrs = try directory.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
            guard attrs.isSymbolicLink != true, attrs.isDirectory == true else { continue }
            let locale = directory.deletingPathExtension().lastPathComponent
            for table in try children(of: directory) where table.pathExtension == "strings" {
                guard try regularFile(table) else { continue }
                try emit(dictionary(in: table), path: "\(directory.lastPathComponent)/\(table.lastPathComponent)", format: "strings", locale: locale)
            }
        }
        return omitted
    }

    private static func children(of directory: URL) throws -> [URL] {
        try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles])
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    private static func regularFile(_ url: URL) throws -> Bool {
        let attrs = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        return attrs.isSymbolicLink != true && attrs.isRegularFile == true
    }

    private static func dictionary(in file: URL) throws -> [String: Any] {
        do {
            let value = try PropertyListSerialization.propertyList(from: Data(contentsOf: file), options: [], format: nil)
            guard let result = value as? [String: Any] else {
                throw Invalid(description: "expected a property list dictionary")
            }
            return result
        } catch {
            throw Invalid(description: "cannot read \(file.path): \(error)")
        }
    }

    private struct Unsupported: Error { let reason: String }

    private static func typedValue(_ raw: Any) throws -> GraphiteIRValue {
        var value = GraphiteIRValue()
        switch raw {
        case let text as String:
            value.stringValue = text
        case let number as NSNumber:
            if CFGetTypeID(number) == CFBooleanGetTypeID() {
                value.boolValue = number.boolValue
            } else if ["f", "d"].contains(String(cString: number.objCType)) {
                value.doubleValue = number.doubleValue
            } else if let integer = Int64(number.stringValue) {
                value.intValue = integer
            } else {
                throw Unsupported(reason: "integer outside the IR int64 range")
            }
        case let array as [Any]:
            var list = GraphiteIRValueList()
            list.values = try array.map(typedValue)
            value.listValue = list
        case let dictionary as [String: Any]:
            var map = GraphiteIRValueMap()
            map.entries = try dictionary.keys.sorted().map { key in
                var named = GraphiteIRNamedValue()
                named.name = key
                named.value = try typedValue(dictionary[key]!)
                return named
            }
            value.mapValue = map
        case is Date:
            throw Unsupported(reason: "plist date has no IR value type")
        case is Data:
            throw Unsupported(reason: "plist data has no IR value type")
        default:
            throw Unsupported(reason: "unsupported plist value: \(type(of: raw))")
        }
        return value
    }
}
