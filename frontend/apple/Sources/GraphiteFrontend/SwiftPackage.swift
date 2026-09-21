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

    /// The Swift files of every built target, absolute, in manifest order.
    public static func sourceFiles(fromDescribe data: Data, root: String) throws -> [String] {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let targets = object["targets"] as? [[String: Any]] else {
            throw FrontendError.packageUnreadable
        }
        var files: [String] = []
        for target in targets {
            guard let type = target["type"] as? String, builtTargetTypes.contains(type),
                  let path = target["path"] as? String,
                  let sources = target["sources"] as? [String] else { continue }
            let directory = path.hasPrefix("/") ? path : "\(root)/\(path)"
            for source in sources where Frontend.sourceExtensions.contains((source as NSString).pathExtension) {
                files.append("\(directory)/\(source)")
            }
        }
        return files
    }
}
