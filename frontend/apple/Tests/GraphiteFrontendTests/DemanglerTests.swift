import Foundation
import XCTest
@testable import GraphiteFrontend

final class DemanglerTests: XCTestCase {
    private func withExecutable(_ body: String, test: (URL) throws -> Void) throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let executable = directory.appendingPathComponent("demangle")
        try ("#!/bin/sh\n" + body).write(to: executable, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: executable.path)
        try test(executable)
    }

    func testSuccessfulBatchKeepsOutputSeparateFromDiagnostics() throws {
        try withExecutable("""
            test "$1" = '-compact' || exit 2
            printf 'tool warning\\n' >&2
            while IFS= read -r symbol; do printf 'decoded:%s\\n' "$symbol"; done
            """) { executable in
            let decoded = try Demangler(executable: executable).demangle(["s:first", "$ssecond", "c:ignored"])
            XCTAssertEqual(decoded, ["s:first": "decoded:$sfirst", "$ssecond": "decoded:$ssecond"])
        }
    }

    func testNonzeroExitRejectsPartialOutputAndIncludesDiagnostic() throws {
        try withExecutable("printf 'partial\\n'; printf 'invalid mangling\\n' >&2; exit 17\n") { executable in
            XCTAssertThrowsError(try Demangler(executable: executable).demangle(["s:first"])) { error in
                guard let failure = error as? DemanglerProcessError else { return XCTFail("\(error)") }
                XCTAssertEqual(failure.status, 17)
                XCTAssertEqual(failure.reason, .exit)
                XCTAssertEqual(failure.diagnostic, "invalid mangling\n")
                XCTAssertTrue(failure.description.contains(executable.path))
                XCTAssertTrue(failure.description.contains("exit status 17"))
                XCTAssertTrue(failure.description.contains("invalid mangling"))
            }
        }
    }

    func testSignalTerminationRejectsOutput() throws {
        try withExecutable("printf 'partial\\n'; printf 'terminated\\n' >&2; kill -TERM $$\n") { executable in
            XCTAssertThrowsError(try Demangler.run(executable, arguments: [], input: "")) { error in
                guard let failure = error as? DemanglerProcessError else { return XCTFail("\(error)") }
                XCTAssertEqual(failure.status, 15)
                XCTAssertEqual(failure.reason, .uncaughtSignal)
                XCTAssertTrue(failure.description.contains("signal 15"))
                XCTAssertTrue(failure.description.contains("terminated"))
            }
        }
    }

    func testLargeStderrAndInputDrainConcurrently() throws {
        // Write more than a pipe can hold before reading stdin, then echo an equally large
        // input to stdout. Serial stderr draining or stdin writing deadlocks this exchange.
        try withExecutable("dd if=/dev/zero bs=65536 count=16 >&2 2>/dev/null\ncat\n") { executable in
            let input = String(repeating: "a Swift symbol\n", count: 100_000)
            XCTAssertEqual(try Demangler.run(executable, arguments: [], input: input), input)
        }
    }

    func testEarlyExitDuringLargeInputPreservesChildFailure() throws {
        try withExecutable("exec 0<&-; printf 'partial\\n'; printf 'rejected input\\n' >&2; exit 23\n") { executable in
            let input = String(repeating: "x", count: 1_000_000)
            XCTAssertThrowsError(try Demangler.run(executable, arguments: [], input: input)) { error in
                guard let failure = error as? DemanglerProcessError else { return XCTFail("\(error)") }
                XCTAssertEqual(failure.status, 23)
                XCTAssertEqual(failure.diagnostic, "rejected input\n")
            }
        }
    }

    func testSuccessfulExitWithUnconsumedInputStillRejectsPartialOutput() throws {
        try withExecutable("exec 0<&-; printf 'partial\\n'; exit 0\n") { executable in
            XCTAssertThrowsError(try Demangler.run(executable, arguments: [], input: String(repeating: "x", count: 1_000_000)))
        }
    }

    func testMissingExecutableThrowsLaunchError() {
        let executable = URL(fileURLWithPath: "/nonexistent/graphite-swift-demangle")
        XCTAssertThrowsError(try Demangler.run(executable, arguments: [], input: "symbol\n")) { error in
            XCTAssertFalse(error is DemanglerProcessError)
        }
    }
}
