// Compiled in a disposable copy of the pinned baseline package. The production
// frontend reads the same store independently and is built from the pristine tree.
import Foundation
import GraphiteFrontend
import IndexStoreDB

func symbol(_ value: Symbol) -> [String: Any] {
    ["usr": value.usr, "name": value.name, "kind": String(describing: value.kind),
     "language": String(describing: value.language), "properties": value.properties.rawValue]
}

func dump(root: String, store: String, output: URL) throws {
    guard let swift = Frontend.locateSwift(environment: ProcessInfo.processInfo.environment) else {
        throw FrontendError.noSwift
    }
    let process = Process()
    let pipe = Pipe()
    process.executableURL = URL(fileURLWithPath: swift)
    process.arguments = SwiftPackage.describeArguments(root: root)
    process.standardOutput = pipe
    try process.run()
    let description = pipe.fileHandleForReading.readDataToEndOfFile()
    process.waitUntilExit()
    try description.write(to: output.appendingPathComponent("package-describe.json"))
    guard process.terminationStatus == 0 else { throw FrontendError.describeFailed(process.terminationStatus) }
    let roots = try SwiftPackage.sourceFiles(fromDescribe: description, root: root)
    let files = try Frontend.sourceFiles(under: roots)
    let encoding: JSONSerialization.WritingOptions = [.sortedKeys, .withoutEscapingSlashes]
    try JSONSerialization.data(withJSONObject: files, options: encoding)
        .write(to: output.appendingPathComponent("selected-paths.json"))
    guard let libraryPath = IndexReader.locateLibrary() else { throw IndexError.noLibrary("libIndexStore") }
    try Data(libraryPath.utf8).write(to: output.appendingPathComponent("index-library.txt"))
    let database = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: database) }
    let db = try IndexStoreDB(storePath: store, databasePath: database.path,
                              library: IndexStoreLibrary(dylibPath: libraryPath),
                              waitUntilDoneInitializing: true, readonly: false, listenToUnitEvents: false)
    db.pollForUnitChangesAndWait(isInitialScan: true)
    let url = output.appendingPathComponent("occurrences.jsonl")
    try Data().write(to: url)
    let handle = try FileHandle(forWritingTo: url)
    defer { try? handle.close() }
    for file in files.sorted() {
        for fact in db.symbolOccurrences(inFilePath: file) {
            let location = fact.location
            let relations: [[String: Any]] = fact.relations.map { ["roles": $0.roles.rawValue, "symbol": symbol($0.symbol)] }
            let row: [String: Any] = ["file": file, "path": location.path, "line": location.line,
                                       "utf8Column": location.utf8Column, "module": location.moduleName,
                                       "isSystem": location.isSystem, "provider": String(describing: fact.symbolProvider),
                                       "roles": fact.roles.rawValue, "symbol": symbol(fact.symbol), "relations": relations]
            var data = try JSONSerialization.data(withJSONObject: row, options: encoding)
            data.append(10)
            try handle.write(contentsOf: data)
        }
    }
}

guard CommandLine.arguments.count == 4 else {
    throw NSError(domain: "Usage: apple-index-dump ROOT INDEX_STORE OUTPUT_DIR", code: 2)
}
try dump(root: CommandLine.arguments[1], store: CommandLine.arguments[2],
         output: URL(fileURLWithPath: CommandLine.arguments[3], isDirectory: true))
