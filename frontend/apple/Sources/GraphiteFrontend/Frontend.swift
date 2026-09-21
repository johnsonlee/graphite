import Foundation
import GraphiteIR

public let frontendName = "graphite-frontend-apple"
public let frontendVersion = "0.1.0"

public enum FrontendError: Error, CustomStringConvertible {
    case noInput
    case buildFailed(Int32)
    case noSwift
    case noSources(String)
    case noDemangler
    case noXcodebuild
    case xcodebuildFailed(Int32)
    case xcodebuildListUnreadable
    case schemeRequired([String])

    public var description: String {
        switch self {
        case .noInput:
            return "give --package <dir> (a SwiftPM package), --project <.xcodeproj or .xcworkspace>, " +
                "or --index-store <dir> with --sources <dir>"
        case .buildFailed(let code): return "swift build failed with exit code \(code)"
        case .noSwift: return "no swift executable found; set GRAPHITE_SWIFT or put swift on PATH"
        case .noSources(let path): return "no Swift sources under \(path)"
        case .noDemangler: return "no swift-demangle found; set GRAPHITE_SWIFT_DEMANGLE or put the toolchain on PATH"
        case .noXcodebuild: return "no xcodebuild found; an Xcode project needs Xcode (set GRAPHITE_XCODEBUILD or put it on PATH)"
        case .xcodebuildFailed(let code): return "xcodebuild failed with exit code \(code)"
        case .xcodebuildListUnreadable: return "xcodebuild -list printed no schemes"
        case .schemeRequired(let schemes):
            return schemes.isEmpty
                ? "the project has no shared scheme; pass --scheme"
                : "the project has several schemes; pass --scheme, one of: \(schemes.joined(separator: ", "))"
        }
    }
}

/// How to obtain the index store and the sources for one build.
public struct BuildOptions {
    /// A SwiftPM package root: built with `swift build` unless `skipBuild`, indexed from
    /// `.build/<configuration>/index/store`, sources under `Sources/`.
    public var package: String?
    /// An Xcode project or workspace: built with `xcodebuild` unless `skipBuild`, indexed
    /// from its derived data, sources under the project's directory.
    public var project: String?
    /// The scheme `xcodebuild` builds; the project's only scheme when nil.
    public var scheme: String?
    /// An `xcodebuild -destination`; the scheme's default when nil.
    public var destination: String?
    /// The derived data directory of the Xcode build; a per-project temporary directory when nil.
    public var derivedData: String?
    /// An existing index store (SwiftPM's, or Xcode's `Index.noindex/DataStore`).
    public var indexStore: String?
    /// Source roots to walk for `.swift` files; defaults to `<package>/Sources`.
    public var sources: [String] = []
    public var skipBuild = false
    public var configuration = "debug"
    public var environment = ProcessInfo.processInfo.environment

    public init() {}
}

/// Progress as the frontend protocol reports it: JSON lines on stderr.
public struct Progress {
    public var phase: String
    public var done: Int
    public var total: Int

    public var json: String {
        "{\"phase\": \"\(phase)\", \"done\": \(done), \"total\": \(total)}"
    }
}

public struct BuildResult {
    public var summary: EmitSummary
    public var files: Int
    public var indexStore: String
}

/// Runs a build end to end: SwiftPM build (when asked), index store, syntax pass,
/// demangling, IR emission.
public struct Frontend {
    public var options: BuildOptions
    public var progress: (Progress) -> Void

    public init(options: BuildOptions, progress: @escaping (Progress) -> Void = { _ in }) {
        self.options = options
        self.progress = progress
    }

    public func build(to output: URL) throws -> BuildResult {
        let (storePath, roots, sourceKind, sourcePath) = try resolveInputs()
        let files = try Frontend.swiftFiles(under: roots)
        guard !files.isEmpty else { throw FrontendError.noSources(roots.joined(separator: ", ")) }
        guard let libraryPath = IndexReader.locateLibrary(environment: options.environment) else {
            throw IndexError.noLibrary("libIndexStore (set GRAPHITE_INDEXSTORE_LIBRARY)")
        }
        guard let demangler = Demangler.locate(environment: options.environment) else { throw FrontendError.noDemangler }

        progress(Progress(phase: "index", done: 0, total: files.count))
        let reader = try IndexReader(storePath: storePath, libraryPath: libraryPath)
        let model = reader.read(files: files)
        progress(Progress(phase: "index", done: files.count, total: files.count))

        var facts: [String: SyntaxFacts] = [:]
        for (index, file) in files.enumerated() {
            facts[file] = try SyntaxFacts.parse(path: file)
            progress(Progress(phase: "syntax", done: index + 1, total: files.count))
        }

        progress(Progress(phase: "demangle", done: 0, total: model.symbols.count))
        let demangled = try demangler.demangle(Array(model.symbols.keys).sorted())
        progress(Progress(phase: "demangle", done: model.symbols.count, total: model.symbols.count))

        var header = GraphiteIRHeader()
        header.schemaVersion = irSchemaVersion
        header.language = "swift"
        header.frontend.name = frontendName
        header.frontend.version = frontendVersion
        header.source.kind = sourceKind
        header.source.path = sourcePath
        header.options["index_store"] = storePath
        header.options["files"] = String(files.count)
        let writer = try IRWriter(url: output, header: header)
        let emitter = Emitter(writer: writer, model: model, facts: facts, demangled: demangled)
        progress(Progress(phase: "emit", done: 0, total: 1))
        let summary = try emitter.emit()
        progress(Progress(phase: "emit", done: 1, total: 1))
        return BuildResult(summary: summary, files: files.count, indexStore: storePath)
    }

    private func resolveInputs() throws -> (store: String, roots: [String], kind: String, path: String) {
        if let package = options.package {
            let root = URL(fileURLWithPath: package).standardizedFileURL.path
            if !options.skipBuild {
                try runSwiftBuild(package: root)
            }
            let store = options.indexStore ?? "\(root)/.build/\(options.configuration)/index/store"
            let roots = options.sources.isEmpty ? ["\(root)/Sources"] : options.sources
            return (store, roots, "swiftpm", root)
        }
        if let path = options.project {
            let project = XcodeProject(path: path)
            let derivedData = options.derivedData ?? project.defaultDerivedData(environment: options.environment)
            if !options.skipBuild {
                try runXcodebuild(project: project, derivedData: derivedData)
            }
            let store = options.indexStore ?? XcodeProject.indexStore(inDerivedData: derivedData)
            let roots = options.sources.isEmpty ? [project.root] : options.sources
            return (store, roots, project.kind, project.path)
        }
        if let store = options.indexStore, !options.sources.isEmpty {
            return (store, options.sources, "index-store", store)
        }
        throw FrontendError.noInput
    }

    private func runXcodebuild(project: XcodeProject, derivedData: String) throws {
        guard let xcodebuild = XcodeProject.locateXcodebuild(environment: options.environment) else {
            throw FrontendError.noXcodebuild
        }
        let scheme = try options.scheme ?? onlyScheme(of: project, xcodebuild: xcodebuild)
        progress(Progress(phase: "xcodebuild", done: 0, total: 1))
        let process = Process()
        process.executableURL = URL(fileURLWithPath: xcodebuild)
        process.arguments = project.buildArguments(
            scheme: scheme,
            configuration: XcodeProject.xcodeConfiguration(options.configuration),
            destination: options.destination,
            derivedData: derivedData
        )
        process.standardOutput = FileHandle.standardError
        process.standardError = FileHandle.standardError
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { throw FrontendError.xcodebuildFailed(process.terminationStatus) }
        progress(Progress(phase: "xcodebuild", done: 1, total: 1))
    }

    /// The project's single scheme, from `xcodebuild -list`; an error naming the choices otherwise.
    private func onlyScheme(of project: XcodeProject, xcodebuild: String) throws -> String {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: xcodebuild)
        process.arguments = ["-list", "-json"] + project.selector
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = FileHandle.standardError
        try process.run()
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { throw FrontendError.xcodebuildFailed(process.terminationStatus) }
        let schemes = try XcodeProject.schemes(fromList: data)
        guard schemes.count == 1 else { throw FrontendError.schemeRequired(schemes) }
        return schemes[0]
    }

    private func runSwiftBuild(package: String) throws {
        guard let swift = Frontend.locateSwift(environment: options.environment) else { throw FrontendError.noSwift }
        progress(Progress(phase: "swift build", done: 0, total: 1))
        let process = Process()
        process.executableURL = URL(fileURLWithPath: swift)
        process.arguments = ["build", "--package-path", package, "-c", options.configuration]
        process.standardOutput = FileHandle.standardError
        process.standardError = FileHandle.standardError
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { throw FrontendError.buildFailed(process.terminationStatus) }
        progress(Progress(phase: "swift build", done: 1, total: 1))
    }

    public static func locateSwift(environment: [String: String]) -> String? {
        if let explicit = environment["GRAPHITE_SWIFT"], FileManager.default.isExecutableFile(atPath: explicit) { return explicit }
        if let toolchain = environment["GRAPHITE_SWIFT_TOOLCHAIN"] {
            for candidate in ["\(toolchain)/usr/bin/swift", "\(toolchain)/bin/swift"]
            where FileManager.default.isExecutableFile(atPath: candidate) {
                return candidate
            }
        }
        for dir in (environment["PATH"] ?? "").split(separator: ":") where FileManager.default.isExecutableFile(atPath: "\(dir)/swift") {
            return "\(dir)/swift"
        }
        return nil
    }

    /// Every `.swift` file under the roots, sorted, `.build` and hidden directories skipped.
    public static func swiftFiles(under roots: [String]) throws -> [String] {
        var files: [String] = []
        let manager = FileManager.default
        for root in roots {
            let rootURL = URL(fileURLWithPath: root).standardizedFileURL
            var isDirectory: ObjCBool = false
            guard manager.fileExists(atPath: rootURL.path, isDirectory: &isDirectory) else { continue }
            if !isDirectory.boolValue {
                if rootURL.pathExtension == "swift" { files.append(rootURL.path) }
                continue
            }
            guard let enumerator = manager.enumerator(at: rootURL, includingPropertiesForKeys: [.isDirectoryKey]) else { continue }
            for case let url as URL in enumerator {
                let name = url.lastPathComponent
                if name.hasPrefix(".") || name == ".build" {
                    enumerator.skipDescendants()
                    continue
                }
                if url.pathExtension == "swift" { files.append(url.standardizedFileURL.path) }
            }
        }
        return Array(Set(files)).sorted()
    }
}
