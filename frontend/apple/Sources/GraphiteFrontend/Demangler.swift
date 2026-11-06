import Foundation
#if canImport(Darwin)
import Darwin
#else
import Glibc
#endif

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
        let stderr = Pipe()
        process.standardInput = stdin
        process.standardOutput = stdout
        process.standardError = stderr
        defer {
            for pipe in [stdin, stdout, stderr] {
                try? pipe.fileHandleForReading.close()
                try? pipe.fileHandleForWriting.close()
            }
        }
        #if canImport(Darwin)
        // Darwin generates pipe signals for the process; suppress them on this descriptor.
        guard fcntl(stdin.fileHandleForWriting.fileDescriptor, F_SETNOSIGPIPE, 1) != -1 else {
            throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno))
        }
        #endif
        try process.run()

        // Both output pipes must drain while stdin is written: any of the three can exceed
        // pipe capacity. Join both dedicated workers before returning, including on failure.
        let diagnostics = DemanglerPipeTransfer {
            defer { try? stderr.fileHandleForReading.close() }
            return try stderr.fileHandleForReading.readToEnd() ?? Data()
        }
        let writer = DemanglerPipeTransfer {
            defer { try? stdin.fileHandleForWriting.close() }
            #if !canImport(Darwin)
            // Linux directs SIGPIPE to the writing thread. Keep it blocked for this dedicated
            // thread's lifetime so an early child exit reports EPIPE without a global handler.
            var signals = sigset_t()
            sigemptyset(&signals)
            sigaddset(&signals, SIGPIPE)
            let status = pthread_sigmask(SIG_BLOCK, &signals, nil)
            guard status == 0 else { throw NSError(domain: NSPOSIXErrorDomain, code: Int(status)) }
            #endif
            try writeDemanglerInput(Data(input.utf8), descriptor: stdin.fileHandleForWriting.fileDescriptor)
        }
        let output = Result {
            defer { try? stdout.fileHandleForReading.close() }
            return try stdout.fileHandleForReading.readToEnd() ?? Data()
        }
        process.waitUntilExit()
        let diagnosticResult = diagnostics.join()
        let writeResult = writer.join()
        let diagnostic = diagnosticResult.map { String(decoding: $0, as: UTF8.self) }
        guard process.terminationReason == .exit && process.terminationStatus == 0 else {
            throw DemanglerProcessError(
                executable: executable,
                status: process.terminationStatus,
                reason: process.terminationReason,
                diagnostic: (try? diagnostic.get()) ?? "could not read stderr"
            )
        }
        // A zero exit cannot turn a failed transfer into successful partial output either.
        try writeResult.get()
        _ = try diagnosticResult.get()
        return String(decoding: try output.get(), as: UTF8.self)
    }
}

/// Use the writer thread itself for write(2), rather than FileHandle's dispatch I/O,
/// so Linux SIGPIPE is generated on the thread whose mask we control.
private func writeDemanglerInput(_ data: Data, descriptor: Int32) throws {
    try data.withUnsafeBytes { bytes in
        guard let base = bytes.baseAddress else { return }
        var offset = 0
        while offset < bytes.count {
            #if canImport(Darwin)
            let written = Darwin.write(descriptor, base.advanced(by: offset), bytes.count - offset)
            #else
            let written = Glibc.write(descriptor, base.advanced(by: offset), bytes.count - offset)
            #endif
            if written > 0 {
                offset += written
            } else if written < 0 && errno == EINTR {
                continue
            } else {
                throw NSError(domain: NSPOSIXErrorDomain, code: Int(written < 0 ? errno : EIO))
            }
        }
    }
}

struct DemanglerProcessError: Error, CustomStringConvertible {
    let executable: URL
    let status: Int32
    let reason: Process.TerminationReason
    let diagnostic: String

    var description: String {
        let termination = reason == .exit ? "exit status \(status)" : "signal \(status)"
        let detail = diagnostic.trimmingCharacters(in: .whitespacesAndNewlines)
        return "\(executable.path) failed with \(termination)" + (detail.isEmpty ? "" : ": \(detail)")
    }
}

/// A single pipe operation with a result published under a lock and a joined lifetime.
private final class DemanglerPipeTransfer<Value>: @unchecked Sendable {
    private let finished = DispatchGroup()
    private let lock = NSLock()
    private var result: Result<Value, Error>?

    init(_ operation: @escaping () throws -> Value) {
        finished.enter()
        Thread {
            let value = Result(catching: operation)
            self.lock.lock()
            self.result = value
            self.lock.unlock()
            self.finished.leave()
        }.start()
    }

    func join() -> Result<Value, Error> {
        finished.wait()
        lock.lock()
        defer { lock.unlock() }
        // The worker publishes exactly once before leaving the group.
        return result!
    }
}
