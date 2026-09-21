import Foundation

/// Demangles Swift symbols by running the toolchain's `swift-demangle` once over a batch.
///
/// The index store keys everything by USR (`s:` + mangled name); the demangled form carries
/// the fully qualified declaring type and the parameter and return types that the core
/// model's descriptors need, for external symbols (stdlib, Foundation) as much as for the
/// project's own.
public struct Demangler {
    public var executable: URL

    public init(executable: URL) {
        self.executable = executable
    }

    /// Locates `swift-demangle`: `GRAPHITE_SWIFT_DEMANGLE`, `GRAPHITE_SWIFT_TOOLCHAIN`,
    /// `xcrun --find`, then `PATH`.
    public static func locate(environment: [String: String] = ProcessInfo.processInfo.environment) -> Demangler? {
        if let explicit = environment["GRAPHITE_SWIFT_DEMANGLE"], FileManager.default.isExecutableFile(atPath: explicit) {
            return Demangler(executable: URL(fileURLWithPath: explicit))
        }
        var candidates: [String] = []
        if let toolchain = environment["GRAPHITE_SWIFT_TOOLCHAIN"] {
            candidates.append(toolchain + "/usr/bin/swift-demangle")
            candidates.append(toolchain + "/bin/swift-demangle")
        }
        for dir in (environment["PATH"] ?? "").split(separator: ":") {
            candidates.append("\(dir)/swift-demangle")
        }
        #if os(macOS)
        if let found = try? Demangler.run(URL(fileURLWithPath: "/usr/bin/xcrun"), arguments: ["--find", "swift-demangle"], input: "") {
            candidates.insert(found.trimmingCharacters(in: .whitespacesAndNewlines), at: 0)
        }
        #endif
        for path in candidates where FileManager.default.isExecutableFile(atPath: path) {
            return Demangler(executable: URL(fileURLWithPath: path))
        }
        return nil
    }

    /// Demangles Swift USRs (`s:...`) or mangled names (`$s...`); keys that are neither, or
    /// that the tool cannot demangle, are absent from the result.
    public func demangle(_ symbols: [String]) throws -> [String: String] {
        let mangled = symbols.compactMap { symbol -> (String, String)? in
            if symbol.hasPrefix("s:") { return (symbol, "$s" + symbol.dropFirst(2)) }
            if symbol.hasPrefix("$s") { return (symbol, symbol) }
            return nil
        }
        guard !mangled.isEmpty else { return [:] }
        let output = try Demangler.run(executable, arguments: ["-compact"], input: mangled.map(\.1).joined(separator: "\n") + "\n")
        let lines = output.split(separator: "\n", omittingEmptySubsequences: false)
        var result: [String: String] = [:]
        for (index, (key, name)) in mangled.enumerated() where index < lines.count {
            let line = String(lines[index])
            if line != name && !line.isEmpty { result[key] = line }
        }
        return result
    }

    static func run(_ executable: URL, arguments: [String], input: String) throws -> String {
        let process = Process()
        process.executableURL = executable
        process.arguments = arguments
        let stdin = Pipe()
        let stdout = Pipe()
        process.standardInput = stdin
        process.standardOutput = stdout
        process.standardError = FileHandle.nullDevice
        try process.run()
        let writer = Thread {
            stdin.fileHandleForWriting.write(input.data(using: .utf8) ?? Data())
            try? stdin.fileHandleForWriting.close()
        }
        writer.start()
        let data = stdout.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        return String(decoding: data, as: UTF8.self)
    }
}
