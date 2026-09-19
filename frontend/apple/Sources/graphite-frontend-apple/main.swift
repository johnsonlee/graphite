import ArgumentParser
import Foundation
import GraphiteFrontend
import GraphiteIR

struct GraphiteFrontendApple: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "graphite-frontend-apple",
        abstract: "Graphite frontend for Swift: builds a Graph IR from a SwiftPM package or an index store.",
        subcommands: [Describe.self, Build.self, Version.self]
    )
}

struct Describe: ParsableCommand {
    static let configuration = CommandConfiguration(abstract: "Print the frontend contract as JSON.")

    func run() throws {
        let json = """
        {"name": "\(frontendName)", "version": "\(frontendVersion)", "ir_schema": [\(irSchemaVersion)], \
        "inputs": ["Package.swift", "index-store"], "aliases": {"swift": "apple", "ios": "apple", "macos": "apple"}, \
        "indexable": ["swift"]}
        """
        print(json)
    }
}

struct Version: ParsableCommand {
    static let configuration = CommandConfiguration(abstract: "Print the frontend version.")

    func run() throws {
        print(frontendVersion)
    }
}

struct Build: ParsableCommand {
    static let configuration = CommandConfiguration(abstract: "Write the Graph IR for a Swift package.")

    @Option(name: .customLong("out"), help: "The .graphite-ir file to write.")
    var out: String

    @Option(name: .customLong("package"), help: "A SwiftPM package root; built with `swift build` unless --skip-build.")
    var package: String?

    @Option(name: .customLong("index-store"), help: "An existing index store (SwiftPM's .build/debug/index/store or Xcode's Index.noindex/DataStore).")
    var indexStore: String?

    @Option(name: .customLong("sources"), parsing: .upToNextOption, help: "Source roots to walk for .swift files (default: <package>/Sources).")
    var sources: [String] = []

    @Option(name: .customLong("configuration"), help: "The SwiftPM configuration whose index store to read.")
    var configuration = "debug"

    @Flag(name: .customLong("skip-build"), help: "Read the package's existing index store instead of running swift build.")
    var skipBuild = false

    func run() throws {
        var options = BuildOptions()
        options.package = package
        options.indexStore = indexStore
        options.sources = sources
        options.skipBuild = skipBuild
        options.configuration = configuration
        let stderr = FileHandle.standardError
        let frontend = Frontend(options: options) { progress in
            stderr.write((progress.json + "\n").data(using: .utf8)!)
        }
        do {
            let result = try frontend.build(to: URL(fileURLWithPath: out))
            let s = result.summary
            stderr.write(
                ("\(frontendName) \(frontendVersion): \(result.files) files, \(s.types) types, \(s.methods) methods, " +
                 "\(s.fields) fields, \(s.callSites) call sites, \(s.constants) constants, \(s.annotations) annotations; " +
                 "\(s.nodes) nodes, \(s.edges) edges, \(s.strings) strings -> \(out)\n").data(using: .utf8)!
            )
        } catch let error as FrontendError {
            stderr.write("error: \(error)\n".data(using: .utf8)!)
            if case .noInput = error { throw ExitCode(2) }
            throw ExitCode(1)
        } catch let error as IndexError {
            stderr.write("error: \(error)\n".data(using: .utf8)!)
            throw ExitCode(1)
        }
    }
}

GraphiteFrontendApple.main()
