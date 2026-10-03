import Foundation

/// An Xcode project or workspace as a build input: `xcodebuild` builds one scheme of it
/// with the index store on and code signing off, and the index store is read from the
/// derived data directory of that build.
public struct XcodeProject: Equatable {
    /// The `.xcodeproj` or `.xcworkspace` path, standardized.
    public var path: String
    public var isWorkspace: Bool

    public init(path: String) {
        let url = URL(fileURLWithPath: path).standardizedFileURL
        self.path = url.path
        self.isWorkspace = url.pathExtension == "xcworkspace"
    }

    /// The directory holding the project: the default source root.
    public var root: String { URL(fileURLWithPath: path).deletingLastPathComponent().path }

    /// `AcmeApp` for `…/AcmeApp.xcodeproj`.
    public var name: String { URL(fileURLWithPath: path).deletingPathExtension().lastPathComponent }

    /// The IR header's source kind.
    public var kind: String { isWorkspace ? "xcworkspace" : "xcodeproj" }

    /// The `xcodebuild` argument that names this input.
    public var selector: [String] { [isWorkspace ? "-workspace" : "-project", path] }

    /// Where a build of this project puts its derived data when the caller names none:
    /// a per-project directory under the temporary directory, so repeated builds are
    /// incremental and the project directory stays clean.
    public func defaultDerivedData(environment: [String: String]) -> String {
        let base = environment["TMPDIR"].map { URL(fileURLWithPath: $0) } ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("graphite-frontend-apple/\(name)-DerivedData").path
    }

    /// The schemes `xcodebuild -list -json` prints for a project or a workspace.
    public static func schemes(fromList data: Data) throws -> [String] {
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw FrontendError.xcodebuildListUnreadable
        }
        let container = (object["project"] ?? object["workspace"]) as? [String: Any]
        guard let schemes = container?["schemes"] as? [String] else { throw FrontendError.xcodebuildListUnreadable }
        return schemes
    }

    /// The `xcodebuild` command line for the build whose index store is read: one scheme,
    /// one configuration, derived data at a known place, the index store forced on (it is
    /// off for Release by default), code signing off so device builds need no identity.
    public func buildArguments(scheme: String, configuration: String, destination: String?, derivedData: String) -> [String] {
        var arguments = selector
        arguments += ["-scheme", scheme, "-configuration", configuration, "-derivedDataPath", derivedData]
        if let destination { arguments += ["-destination", destination] }
        arguments += [
            "build",
            "COMPILER_INDEX_STORE_ENABLE=YES",
            "CODE_SIGNING_ALLOWED=NO",
            "CODE_SIGNING_REQUIRED=NO",
            "CODE_SIGN_IDENTITY=",
        ]
        return arguments
    }

    /// The index store inside a derived data directory: `Index.noindex/DataStore` since
    /// Xcode 14, `Index/DataStore` before; the newer layout when neither exists yet.
    public static func indexStore(inDerivedData derivedData: String) -> String {
        let candidates = ["\(derivedData)/Index.noindex/DataStore", "\(derivedData)/Index/DataStore"]
        return candidates.first { FileManager.default.fileExists(atPath: $0) } ?? candidates[0]
    }

    /// The SwiftPM spelling of a configuration is lower case; Xcode's is capitalized.
    public static func xcodeConfiguration(_ configuration: String) -> String {
        switch configuration {
        case "debug": return "Debug"
        case "release": return "Release"
        default: return configuration
        }
    }

    /// `xcodebuild`: `GRAPHITE_XCODEBUILD`, then `PATH`, then the Xcode command line tools' shim.
    public static func locateXcodebuild(environment: [String: String]) -> String? {
        if let explicit = environment["GRAPHITE_XCODEBUILD"], FileManager.default.isExecutableFile(atPath: explicit) { return explicit }
        for dir in (environment["PATH"] ?? "").split(separator: ":") where FileManager.default.isExecutableFile(atPath: "\(dir)/xcodebuild") {
            return "\(dir)/xcodebuild"
        }
        if FileManager.default.isExecutableFile(atPath: "/usr/bin/xcodebuild") { return "/usr/bin/xcodebuild" }
        return nil
    }
}
