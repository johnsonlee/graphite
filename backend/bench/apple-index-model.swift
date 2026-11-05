// A diagnostic executable compiled against the candidate's public frontend API.
// The independent oracle reads libIndexStore directly in Python.
import Foundation
import GraphiteFrontend

func save(_ value: Any, _ url: URL) throws {
    try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .withoutEscapingSlashes])
        .write(to: url)
}

func rows(_ model: IndexModel, root: String) -> [String: Any] {
    func position(_ p: SourcePosition) -> [String] {
        let prefix = root + "/"
        return [p.path.hasPrefix(prefix) ? String(p.path.dropFirst(prefix.count)) : p.path,
                String(p.line), String(p.column)]
    }
    let types = model.types.map {
        [$0.module, $0.symbol.usr, $0.symbol.name, String(describing: $0.kind), $0.container ?? ""] + position($0.position)
    }
    let members = model.members.map {
        [$0.module, $0.symbol.usr, $0.symbol.name, String(describing: $0.kind), $0.container ?? "", String($0.isStatic)] +
            position($0.position) + ($0.declaration.map(position) ?? []) + $0.overrides.sorted()
    }
    let calls = model.calls.map {
        [$0.module, $0.caller?.usr ?? "", $0.callee.usr, String($0.isDynamic)] + position($0.position)
    }
    let relations = model.supertypes.map { [$0.subtype, $0.supertype.usr] }
    return ["types": types.sorted { $0.lexicographicallyPrecedes($1) },
            "members": members.sorted { $0.lexicographicallyPrecedes($1) },
            "calls": calls.sorted { $0.lexicographicallyPrecedes($1) },
            "supertypes": relations.sorted { $0.lexicographicallyPrecedes($1) },
            "synthesized": model.synthesized, "usrs": model.symbols.keys.sorted()]
}

let arguments = CommandLine.arguments
guard arguments.count >= 4 else {
    throw NSError(domain: "Usage: apple-index-model ROOT EVIDENCE SHARED_SOURCE...", code: 2)
}
let root = SourcePosition.canonical(arguments[1])
let output = URL(fileURLWithPath: arguments[2], isDirectory: true)
let sources = try SwiftPackage.sourceFiles(fromDescribe: Data(contentsOf: output.appendingPathComponent("describe.json")),
                                           root: root, manifest: Data(contentsOf: output.appendingPathComponent("dump-package.json")))
let files = try Frontend.sourceFiles(under: sources)
try save(files, output.appendingPathComponent("selected-paths.json"))
guard let library = IndexReader.locateLibrary() else { throw IndexError.noLibrary("libIndexStore") }
try Data(library.utf8).write(to: output.appendingPathComponent("index-library.txt"))
let reader = try IndexReader(storePath: root + "/.build/debug/index/store", libraryPath: library)
try save(rows(reader.read(files: files), root: root), output.appendingPathComponent("model.json"))
var shared: [String: Any] = [:]
for relative in arguments.dropFirst(3) {
    let path = SourcePosition.canonical(root + "/" + relative)
    guard files.contains(path) else { throw FrontendError.noSources(path) }
    shared[relative] = try rows(reader.read(files: [path]), root: root)
}
try save(shared, output.appendingPathComponent("shared-models.json"))
