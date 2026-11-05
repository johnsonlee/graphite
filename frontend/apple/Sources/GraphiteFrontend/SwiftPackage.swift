import Foundation

/// The source files of a SwiftPM package as `swift package describe --type json` lists
/// them: one entry per target with its directory (`path`, relative to the package root,
/// a custom `path:` included) and the files SwiftPM compiles from it (`sources`, with
/// `exclude` and an explicit `sources:` already applied). Walking `Sources/` instead
/// would drop every target the manifest puts elsewhere, an executable under
/// `CommandLineTool/` say, although `swift build` compiled and indexed it.
public enum SwiftPackage {
    /// The target types `swift build` compiles by default: tests need `--build-tests`,
    /// plugins run on the host, binary and system targets carry no Swift.
    static let builtTargetTypes: Set<String> = ["library", "executable", "macro"]

    /// The `swift package describe` command line for a package root.
    public static func describeArguments(root: String) -> [String] {
        ["package", "--package-path", root, "describe", "--type", "json"]
    }

    static func hasClangTargets(fromDescribe data: Data) throws -> Bool {
        try targets(from: data).contains {
            builtTargetTypes.contains($0["type"] as? String ?? "") && $0["module_type"] as? String == "ClangTarget"
        }
    }

    private static func targets(from data: Data) throws -> [[String: Any]] {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let targets = object["targets"] as? [[String: Any]] else {
            throw FrontendError.packageUnreadable
        }
        return targets
    }

    /// Swift and Objective-C sources of every built target, plus Clang headers omitted
    /// by `describe`. The optional `dump-package` manifest supplies header exclusions.
    public static func sourceFiles(fromDescribe data: Data, root: String, manifest: Data? = nil) throws -> [String] {
        let targets = try targets(from: data)
        let manifestTargets = try manifest.map { try Self.targets(from: $0) } ?? []
        var files: [String] = []
        for target in targets {
            guard let type = target["type"] as? String, builtTargetTypes.contains(type),
                  let path = target["path"] as? String,
                  let sources = target["sources"] as? [String] else { continue }
            let directory = path.hasPrefix("/") ? path : "\(root)/\(path)"
            for source in sources where Frontend.sourceExtensions.contains((source as NSString).pathExtension) {
                files.append("\(directory)/\(source)")
            }
            // `describe` omits Clang headers. Include public headers (including a
            // custom publicHeadersPath) and private headers inside this built target,
            // never sibling targets or unrelated package files. `dump-package` carries
            // the excludes that describe has already applied to implementation files.
            if target["module_type"] as? String == "ClangTarget" {
                let declaration = manifestTargets.first { $0["name"] as? String == target["name"] as? String }
                let excluded = (declaration?["exclude"] as? [String] ?? []).map {
                    SourcePosition.canonical("\(directory)/\($0)")
                }
                let headers = try Frontend.sourceFiles(under: [directory]).filter { file in
                    file.hasSuffix(".h") && !excluded.contains { file == $0 || file.hasPrefix($0 + "/") }
                }
                files += headers
            }
        }
        return files
    }
}
