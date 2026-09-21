import Foundation
import XCTest
@testable import GraphiteFrontend

final class XcodeTests: XCTestCase {
    static let fixture = FrontendTests.fixture

    func testProjectPaths() {
        let project = XcodeProject(path: "/src/Acme/AcmeApp.xcodeproj/")
        XCTAssertEqual(project.path, "/src/Acme/AcmeApp.xcodeproj")
        XCTAssertFalse(project.isWorkspace)
        XCTAssertEqual(project.root, "/src/Acme")
        XCTAssertEqual(project.name, "AcmeApp")
        XCTAssertEqual(project.kind, "xcodeproj")
        XCTAssertEqual(project.selector, ["-project", "/src/Acme/AcmeApp.xcodeproj"])
        XCTAssertEqual(project.defaultDerivedData(environment: ["TMPDIR": "/tmp/t"]), "/tmp/t/graphite-frontend-apple/AcmeApp-DerivedData")
        XCTAssertTrue(project.defaultDerivedData(environment: [:]).hasSuffix("/graphite-frontend-apple/AcmeApp-DerivedData"))

        let workspace = XcodeProject(path: "Acme.xcworkspace")
        XCTAssertTrue(workspace.isWorkspace)
        XCTAssertEqual(workspace.kind, "xcworkspace")
        XCTAssertEqual(workspace.selector[0], "-workspace")
        XCTAssertTrue(workspace.path.hasSuffix("/Acme.xcworkspace"))
    }

    func testBuildArguments() {
        let project = XcodeProject(path: "/src/Acme/AcmeApp.xcworkspace")
        XCTAssertEqual(
            project.buildArguments(scheme: "AcmeApp", configuration: "Debug", destination: nil, derivedData: "/dd"),
            ["-workspace", "/src/Acme/AcmeApp.xcworkspace", "-scheme", "AcmeApp", "-configuration", "Debug",
             "-derivedDataPath", "/dd", "build", "COMPILER_INDEX_STORE_ENABLE=YES",
             "CODE_SIGNING_ALLOWED=NO", "CODE_SIGNING_REQUIRED=NO", "CODE_SIGN_IDENTITY="]
        )
        let withDestination = project.buildArguments(scheme: "AcmeApp", configuration: "Release", destination: "generic/platform=iOS Simulator", derivedData: "/dd")
        XCTAssertEqual(Array(withDestination[8...10]), ["-destination", "generic/platform=iOS Simulator", "build"])
        XCTAssertEqual(XcodeProject.xcodeConfiguration("debug"), "Debug")
        XCTAssertEqual(XcodeProject.xcodeConfiguration("release"), "Release")
        XCTAssertEqual(XcodeProject.xcodeConfiguration("Staging"), "Staging")
    }

    func testSchemesFromList() throws {
        let project = """
        {"project": {"configurations": ["Debug", "Release"], "name": "AcmeApp", "schemes": ["AcmeApp", "AcmeAppTests"], "targets": ["AcmeApp"]}}
        """
        XCTAssertEqual(try XcodeProject.schemes(fromList: Data(project.utf8)), ["AcmeApp", "AcmeAppTests"])
        let workspace = """
        {"workspace": {"name": "Acme", "schemes": ["Acme"]}}
        """
        XCTAssertEqual(try XcodeProject.schemes(fromList: Data(workspace.utf8)), ["Acme"])
        XCTAssertThrowsError(try XcodeProject.schemes(fromList: Data("{\"project\": {}}".utf8))) {
            XCTAssertEqual("\($0)", FrontendError.xcodebuildListUnreadable.description)
        }
        XCTAssertThrowsError(try XcodeProject.schemes(fromList: Data("[]".utf8)))
        XCTAssertEqual(FrontendError.schemeRequired([]).description, "the project has no shared scheme; pass --scheme")
        XCTAssertEqual(FrontendError.schemeRequired(["A", "B"]).description, "the project has several schemes; pass --scheme, one of: A, B")
        XCTAssertEqual(FrontendError.xcodebuildFailed(65).description, "xcodebuild failed with exit code 65")
    }

    func testIndexStoreLayouts() throws {
        let derivedData = FileManager.default.temporaryDirectory.appendingPathComponent("dd-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: derivedData) }
        XCTAssertEqual(XcodeProject.indexStore(inDerivedData: derivedData.path), derivedData.path + "/Index.noindex/DataStore")
        try FileManager.default.createDirectory(at: derivedData.appendingPathComponent("Index/DataStore"), withIntermediateDirectories: true)
        XCTAssertEqual(XcodeProject.indexStore(inDerivedData: derivedData.path), derivedData.path + "/Index/DataStore")
        try FileManager.default.createDirectory(at: derivedData.appendingPathComponent("Index.noindex/DataStore"), withIntermediateDirectories: true)
        XCTAssertEqual(XcodeProject.indexStore(inDerivedData: derivedData.path), derivedData.path + "/Index.noindex/DataStore")
    }

    func testXcodebuildLocation() {
        let bare = XcodeProject.locateXcodebuild(environment: ["GRAPHITE_XCODEBUILD": "/nonexistent/xcodebuild", "PATH": "/nonexistent"])
        #if os(macOS)
        // The command line tools' shim is found whatever PATH says.
        XCTAssertEqual(bare, "/usr/bin/xcodebuild")
        #else
        XCTAssertNil(bare)
        #endif
        let fake = FileManager.default.temporaryDirectory.appendingPathComponent("xcb-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: fake, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: fake) }
        let shim = fake.appendingPathComponent("xcodebuild")
        try? "#!/bin/sh\nexit 0\n".write(to: shim, atomically: true, encoding: .utf8)
        try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: shim.path)
        XCTAssertEqual(XcodeProject.locateXcodebuild(environment: ["GRAPHITE_XCODEBUILD": shim.path]), shim.path)
        XCTAssertEqual(XcodeProject.locateXcodebuild(environment: ["PATH": "/nonexistent:\(fake.path)"]), shim.path)
    }

    /// Writes an executable stub script.
    private func shim(_ script: String, in directory: URL) throws -> String {
        let path = directory.appendingPathComponent("xcodebuild")
        try script.write(to: path, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: path.path)
        return path.path
    }

    /// A project whose derived data already holds an index store (the fixture package's,
    /// copied under Xcode's layout) is indexed without xcodebuild: the whole project path
    /// runs on Linux, only the build itself needs Xcode. The project sits in the fixture
    /// directory for the test, since the index store records the fixture's file paths.
    func testBuildFromAProjectWithSkipBuild() throws {
        let manager = FileManager.default
        let project = URL(fileURLWithPath: Self.fixture).appendingPathComponent("AcmeShop.xcodeproj")
        try manager.createDirectory(at: project, withIntermediateDirectories: true)
        try "// !$*UTF8*$!\n".write(to: project.appendingPathComponent("project.pbxproj"), atomically: true, encoding: .utf8)
        let scratch = manager.temporaryDirectory.appendingPathComponent("acmeapp-\(UUID().uuidString)")
        let derivedData = scratch.appendingPathComponent("DerivedData")
        try manager.createDirectory(at: derivedData.appendingPathComponent("Index.noindex"), withIntermediateDirectories: true)
        try manager.copyItem(atPath: Self.fixture + "/.build/debug/index/store", toPath: derivedData.appendingPathComponent("Index.noindex/DataStore").path)
        defer {
            try? manager.removeItem(at: project)
            try? manager.removeItem(at: scratch)
        }

        var options = BuildOptions()
        options.project = project.path
        options.derivedData = derivedData.path
        options.skipBuild = true
        var phases: [String] = []
        let out = scratch.appendingPathComponent("acmeapp.graphite-ir")
        let result = try Frontend(options: options) { phases.append($0.phase) }.build(to: out)
        // Every .swift under the project's directory: the three sources and Package.swift.
        XCTAssertEqual(result.files, 4)
        XCTAssertEqual(result.indexStore, derivedData.path + "/Index.noindex/DataStore")
        XCTAssertEqual(Set(phases), ["index", "syntax", "demangle", "emit"])
        let decoded = try DecodedIR(url: out)
        XCTAssertEqual(decoded.header.source.kind, "xcodeproj")
        XCTAssertEqual(decoded.header.source.path, project.standardizedFileURL.path)
        XCTAssertEqual(decoded.header.options["index_store"], result.indexStore)
        XCTAssertEqual(decoded.origins.count, 10)
        XCTAssertEqual(decoded.methods.count, 14)

        // Without derived data the store is looked for under the temporary directory, and
        // without an index store there the build fails on the missing store.
        var noStore = options
        noStore.derivedData = nil
        noStore.environment["TMPDIR"] = scratch.appendingPathComponent("tmp").path
        XCTAssertThrowsError(try Frontend(options: noStore).build(to: out)) {
            XCTAssertTrue("\($0)".hasPrefix("no index store at " + scratch.path + "/tmp/graphite-frontend-apple/AcmeShop-DerivedData/Index.noindex/DataStore"), "\($0)")
        }
        // Explicit sources and index store override the project's defaults.
        var explicit = options
        explicit.sources = [Self.fixture + "/Sources/AcmeShop/Models.swift"]
        explicit.indexStore = Self.fixture + "/.build/debug/index/store"
        let one = try Frontend(options: explicit).build(to: out)
        XCTAssertEqual(one.files, 1)
        XCTAssertEqual(one.indexStore, Self.fixture + "/.build/debug/index/store")
    }

    func testBuildingAProjectNeedsXcodebuild() throws {
        var options = BuildOptions()
        options.project = "/src/Acme/AcmeApp.xcodeproj"
        options.environment = ["PATH": "/nonexistent"]
        let out = URL(fileURLWithPath: "/tmp/x")
        #if !os(macOS)
        XCTAssertThrowsError(try Frontend(options: options).build(to: out)) {
            XCTAssertEqual("\($0)", FrontendError.noXcodebuild.description)
        }
        #endif
        let fake = FileManager.default.temporaryDirectory.appendingPathComponent("xcb-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: fake, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: fake) }
        // A stub that lists two schemes and fails to build: the choice is asked for, then
        // the exit code is reported.
        options.environment = ["GRAPHITE_XCODEBUILD": try shim(
            "#!/bin/sh\nif [ \"$1\" = -list ]; then echo '{\"project\": {\"schemes\": [\"A\", \"B\"]}}'; exit 0; fi\nexit 65\n", in: fake)]
        XCTAssertThrowsError(try Frontend(options: options).build(to: out)) {
            XCTAssertEqual("\($0)", FrontendError.schemeRequired(["A", "B"]).description)
        }
        options.scheme = "A"
        XCTAssertThrowsError(try Frontend(options: options).build(to: out)) {
            XCTAssertEqual("\($0)", FrontendError.xcodebuildFailed(65).description)
        }
        // A stub that lists no readable schemes.
        options.environment = ["GRAPHITE_XCODEBUILD": try shim("#!/bin/sh\necho 'not json'\n", in: fake)]
        options.scheme = nil
        XCTAssertThrowsError(try Frontend(options: options).build(to: out)) {
            XCTAssertFalse("\($0)".hasPrefix("no xcodebuild"), "\($0)")
        }
        // A stub that fails to list.
        options.environment = ["GRAPHITE_XCODEBUILD": try shim("#!/bin/sh\nexit 70\n", in: fake)]
        XCTAssertThrowsError(try Frontend(options: options).build(to: out)) {
            XCTAssertEqual("\($0)", FrontendError.xcodebuildFailed(70).description)
        }
        // A stub whose build succeeds: the arguments it got are those of buildArguments, and
        // the build goes on to the (missing) index store.
        let log = fake.appendingPathComponent("args.log")
        options.environment = ["GRAPHITE_XCODEBUILD": try shim(
            "#!/bin/sh\nif [ \"$1\" = -list ]; then echo '{\"workspace\": {\"schemes\": [\"Only\"]}}'; exit 0; fi\nprintf '%s\\n' \"$@\" > '\(log.path)'\nexit 0\n", in: fake)]
        options.scheme = nil
        options.project = "/src/Acme/Acme.xcworkspace"
        options.derivedData = fake.appendingPathComponent("dd").path
        options.configuration = "release"
        options.destination = "generic/platform=iOS"
        var phases: [String] = []
        XCTAssertThrowsError(try Frontend(options: options) { phases.append($0.phase) }.build(to: out)) {
            XCTAssertTrue("\($0)".hasPrefix("no Swift sources under /src/Acme"), "\($0)")
        }
        XCTAssertEqual(phases, ["xcodebuild", "xcodebuild"])
        XCTAssertEqual(
            try String(contentsOf: log, encoding: .utf8),
            XcodeProject(path: options.project!).buildArguments(scheme: "Only", configuration: "Release", destination: "generic/platform=iOS", derivedData: options.derivedData!).joined(separator: "\n") + "\n"
        )
    }
}
