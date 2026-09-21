#!/usr/bin/env python3
"""A deterministic Swift corpus at iOS-app scale for the Apple frontend gate.

`apple-frontend-corpus.json` pins a real 181-file SwiftPM package; that is a fast smoke, not
evidence for the frontend on an application of a few thousand files. This script writes such
an application from a seed: `--files` Swift files spread over `--modules` modules, every file a
service class with the same public API (so every cross-file and cross-module call the corpus
makes compiles), a support file per module, and one app entry point. The text is a pure
function of the manifest's `generator` block (seed, files, modules, layout, name), so the
corpus is reproducible on every runner and the gate can regenerate it and diff it against a
cached copy instead of trusting the cache.

Two layouts of the same sources:

    package    a SwiftPM package: one library target per module (`Sources/ModuleNN`), modules
               in layers so the debug build parallelises, an executable target as the entry
               point; measured on Linux with `graphite-frontend-apple build --package`.
    xcodeproj  an iOS application `.xcodeproj` with one app target holding every file (the
               module directories are groups), a shared scheme and a UIKit app delegate;
               measured on macOS with `graphite-frontend-apple build --project`.

    generate-apple-corpus.py --manifest backend/bench/apple-frontend-corpus-large.json --out corpus
    generate-apple-corpus.py --files 60 --modules 3 --seed 1 --layout xcodeproj --name Demo --out demo

Every file is generated from its own `Random("<seed>:<module>:<index>")`, so a file's text does
not depend on the order files are written, and directories are written in sorted order.
"""
import argparse, json, pathlib, random, sys

SCRIPT = "generate-apple-corpus.py"
LAYOUTS = ("package", "xcodeproj")
TOOLS_VERSION = "5.9"
IOS_DEPLOYMENT_TARGET = "16.0"

# Every feature file defines `<Prefix>Item<NNN>`, `<Prefix>Service<NNN>` and sometimes
# `<Prefix>Kind<NNN>`; every service has these methods, so a call to any service of any file
# the caller can see type-checks.
WORDS = ["order", "cart", "profile", "session", "feed", "search", "payment", "catalog", "review",
         "shipping", "inventory", "coupon", "wallet", "notice", "media", "chat", "map", "ticket"]
ADJECTIVES = ["primary", "cached", "remote", "pending", "stale", "draft", "final", "archived"]


class Corpus:
    """The plan of one corpus: which module holds which files, and what each module can see."""

    def __init__(self, files, modules, seed, layout, name):
        if files < modules + 1:
            raise ValueError(f"{files} files cannot hold {modules} support files and an entry point")
        if modules < 1:
            raise ValueError("at least one module")
        if layout not in LAYOUTS:
            raise ValueError(f"layout must be one of {LAYOUTS}")
        self.files, self.modules, self.seed, self.layout, self.name = files, modules, seed, layout, name
        feature = files - modules - 1
        # Feature files per module, the remainder on the first modules.
        self.counts = [feature // modules + (1 if m < feature % modules else 0) for m in range(modules)]
        # Modules in layers of about sqrt(modules): a module imports every module of the layer
        # before it, so cross-module calls exist and the build still parallelises per layer.
        width = max(1, round(modules ** 0.5))
        self.layer = [m // width for m in range(modules)]
        self.dependencies = [[d for d in range(modules) if self.layer[d] == self.layer[m] - 1] for m in range(modules)]

    def module(self, m):
        return f"Module{m:02d}"

    def prefix(self, m):
        return f"M{m:02d}"

    def visible(self, m):
        """(module, feature index) pairs a file of module `m` may call: its own module's files and
        the files of the modules it imports."""
        return [(d, k) for d in [*self.dependencies[m], m] for k in range(self.counts[d])]

    def entry_targets(self):
        """The files the entry point calls: the last layer's modules."""
        last = max(self.layer)
        return [(m, k) for m in range(self.modules) if self.layer[m] == last for k in range(min(2, self.counts[m]))]


def swift_string(text):
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"') + '"'


def feature_file(corpus, m, k):
    rng = random.Random(f"{corpus.seed}:{m}:{k}")
    p, own = corpus.prefix(m), f"{corpus.prefix(m)}Service{k:03d}"
    item, kind = f"{p}Item{k:03d}", f"{p}Kind{k:03d}"
    log = f"{p}Log"
    visible = [(d, j) for d, j in corpus.visible(m) if (d, j) != (m, k)]
    lines = ["import Foundation"]
    if corpus.layout == "package":
        for d in corpus.dependencies[m]:
            lines.append(f"import {corpus.module(d)}")
    lines += ["", f"/// Feature {k} of {corpus.module(m)}: generated, see backend/bench/{SCRIPT}.", ""]
    with_kind = rng.random() < 0.4
    if with_kind:
        cases = rng.sample(ADJECTIVES, 3)
        lines += [f"public enum {kind}: String, CaseIterable {{"]
        lines += [f"    case {c} = {swift_string(f'{p.lower()}-{k}-{c}')}" for c in cases]
        lines += ["", "    public var weight: Double {", "        switch self {"]
        lines += [f"        case .{c}: return {rng.randint(1, 9)}.{rng.randint(0, 9)}" for c in cases]
        lines += ["        }", "    }", "}", ""]
    lines += [
        f"public struct {item}: Equatable {{",
        "    public var key: String",
        "    public var value: Int",
        "    public var weight: Double",
        "",
        "    public init(key: String, value: Int, weight: Double) {",
        "        self.key = key",
        "        self.value = value",
        "        self.weight = weight",
        "    }",
        "}",
        "",
        f"public final class {own} {{",
        f"    private let log: {log}",
        f"    public private(set) var items: [{item}] = []",
        "    private var cache: [String: Int] = [:]",
        f"    public var limit: Int = {rng.randint(8, 64)}",
        f"    public static let name = {swift_string(f'{corpus.module(m)}.{k}.{rng.choice(WORDS)}')}",
        "",
        f"    public init(log: {log} = {log}(name: {swift_string(f'{p.lower()}.{k}')})) {{",
        "        self.log = log",
        "    }",
        "",
    ]

    def other():
        d, j = rng.choice(visible) if visible else (m, k)
        return f"{corpus.prefix(d)}Service{j:03d}", f"{corpus.prefix(d)}Item{j:03d}"

    def call_statement(indent="        "):
        """One statement calling a visible service, the log or the standard library, with
        literal and non-literal arguments in the mix the frontend sees in real apps."""
        service, other_item = other()
        word, count = rng.choice(WORDS), rng.randint(1, 99)
        forms = [
            f'log.info({swift_string(f"{word} {rng.choice(ADJECTIVES)}")})',
            f'log.count({swift_string(word)}, by: {count})',
            f'_ = {service}().load({swift_string(f"{word}-{count}")}, limit: {count})',
            f'{service}().store({swift_string(word)}, value: {count})',
            f'_ = {service}().compute({rng.randint(1, 9)}.{rng.randint(0, 9)}, {rng.randint(1, 9)}.5)',
            f'_ = {service}().validate({other_item}(key: {swift_string(word)}, value: {count}, weight: {rng.randint(0, 9)}.25))',
            f'{service}().flush()',
            f'_ = {service}().describe().count',
            f'cache[{swift_string(word)}, default: 0] += {count}',
            f'_ = max(limit, {count})',
            f'_ = normalize({swift_string(f"  {word}  ")})',
            f'items.append({item}(key: {swift_string(word)}, value: {count}, weight: {rng.randint(0, 9)}.5))',
            f'_ = {service}.name.hasPrefix({swift_string(corpus.module(m)[:6])})',
        ]
        return indent + rng.choice(forms)

    def body(count):
        return [call_statement() for _ in range(count)]

    lines += [
        "    public func load(_ key: String, limit: Int) -> Int {",
        f"        log.info({swift_string(f'load {rng.choice(WORDS)}')})",
        *body(rng.randint(5, 8)),
        "        return cache[normalize(key), default: limit]",
        "    }",
        "",
        "    @discardableResult",
        "    public func store(_ key: String, value: Int) -> Bool {",
        *body(rng.randint(5, 8)),
        "        cache[normalize(key)] = value",
        f"        return value > {rng.randint(0, 9)}",
        "    }",
        "",
        "    public func describe() -> String {",
        *body(rng.randint(3, 5)),
        f'        return {own}.name + {swift_string(":")} + String(items.count)',
        "    }",
        "",
        f"    public func validate(_ item: {item}) -> Bool {{",
        *body(rng.randint(3, 6)),
        f"        return item.value >= 0 && item.key.hasPrefix({swift_string(rng.choice(WORDS)[:2])}) == false",
        "    }",
        "",
        "    public func compute(_ base: Double, _ factor: Double) -> Double {",
        *body(rng.randint(3, 5)),
        f"        return base * factor + {rng.randint(0, 9)}.{rng.randint(0, 9)}",
        "    }",
        "",
        "    public func flush() {",
        *body(rng.randint(3, 6)),
        "        items.removeAll()",
        "        cache.removeAll()",
        "    }",
        "",
        "    @available(iOS 13.0, macOS 10.15, *)",
        "    public func refresh(after delay: Int) -> Int {",
        *body(rng.randint(3, 5)),
        f"        return max(delay, {rng.randint(1, 30)})",
        "    }",
        "",
        "    private func normalize(_ raw: String) -> String {",
        f"        log.count({swift_string('normalize')}, by: 1)",
        "        return raw.trimmingCharacters(in: .whitespaces).lowercased()",
        "    }",
        "}",
        "",
    ]
    return "\n".join(lines)


def support_file(corpus, m):
    p = corpus.prefix(m)
    return "\n".join([
        "import Foundation",
        "",
        f"/// Shared support of {corpus.module(m)}: generated, see backend/bench/{SCRIPT}.",
        f"public final class {p}Log {{",
        "    public let name: String",
        "    private var counters: [String: Int] = [:]",
        "",
        "    public init(name: String) {",
        "        self.name = name",
        "    }",
        "",
        "    public func info(_ message: String) {",
        f"        counters[{swift_string('info')}, default: 0] += 1",
        "        _ = message.count",
        "    }",
        "",
        "    public func count(_ key: String, by delta: Int) {",
        "        counters[key, default: 0] += delta",
        "    }",
        "}",
        "",
    ])


def entry_calls(corpus, indent):
    calls = []
    for m, k in corpus.entry_targets():
        service = f"{corpus.prefix(m)}Service{k:03d}"
        calls.append(f'{indent}_ = {service}().load({swift_string(f"boot-{m}-{k}")}, limit: {8 + m})')
    return calls


def package_entry(corpus):
    last = max(corpus.layer)
    imports = [f"import {corpus.module(m)}" for m in range(corpus.modules) if corpus.layer[m] == last]
    return "\n".join([*imports, "", f"/// Entry point: generated, see backend/bench/{SCRIPT}.", *entry_calls(corpus, ""), ""])


def xcode_entry(corpus):
    return "\n".join([
        "import UIKit",
        "",
        f"/// Entry point: generated, see backend/bench/{SCRIPT}.",
        "@main",
        "final class AppDelegate: UIResponder, UIApplicationDelegate {",
        "    var window: UIWindow?",
        "",
        "    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {",
        *entry_calls(corpus, "        "),
        "        return true",
        "    }",
        "}",
        "",
    ])


def package_manifest(corpus):
    targets = []
    for m in range(corpus.modules):
        deps = ", ".join(swift_string(corpus.module(d)) for d in corpus.dependencies[m])
        targets.append(f"        .target(name: {swift_string(corpus.module(m))}, dependencies: [{deps}]),")
    last = max(corpus.layer)
    entry_deps = ", ".join(swift_string(corpus.module(m)) for m in range(corpus.modules) if corpus.layer[m] == last)
    return "\n".join([
        f"// swift-tools-version:{TOOLS_VERSION}",
        "import PackageDescription",
        "",
        f"// Generated by backend/bench/{SCRIPT}: {corpus.files} files over {corpus.modules} modules, seed {corpus.seed}.",
        "let package = Package(",
        f"    name: {swift_string(corpus.name)},",
        "    targets: [",
        *targets,
        f"        .executableTarget(name: {swift_string(corpus.name)}, dependencies: [{entry_deps}]),",
        "    ]",
        ")",
        "",
    ])


def sources(corpus):
    """(relative path under the sources root, text) for every Swift file but the entry point.
    Basenames carry the module prefix: an Xcode target keys its per-file outputs by basename,
    so two `Feature000.swift` in one app target would collide."""
    for m in range(corpus.modules):
        yield f"{corpus.module(m)}/{corpus.prefix(m)}Support.swift", support_file(corpus, m)
        for k in range(corpus.counts[m]):
            yield f"{corpus.module(m)}/{corpus.prefix(m)}Feature{k:03d}.swift", feature_file(corpus, m, k)


def pbx_id(kind, index):
    """A 24-hex-digit object id: one hex digit of kind, then the index."""
    return f"{kind}{index:023X}"


def pbxproj(corpus, files):
    """The project.pbxproj of one iOS app target holding `files` (paths relative to the app
    directory, in order), modelled on frontend/apple/Fixtures/AcmeApp."""
    name = corpus.name
    target, project, products, root, app_group = pbx_id("D", 1), pbx_id("E", 1), pbx_id("C", 3), pbx_id("C", 1), pbx_id("C", 2)
    sources_phase, frameworks_phase, resources_phase = pbx_id("F", 1), pbx_id("F", 2), pbx_id("F", 3)
    product = pbx_id("B", 0)
    build_files, references, phase_files, groups = [], [], [], {}
    for index, path in enumerate(files, start=1):
        ref, build = pbx_id("B", index), pbx_id("A", index)
        base = path.rsplit("/", 1)[-1]
        build_files.append(f"\t\t{build} /* {base} in Sources */ = {{isa = PBXBuildFile; fileRef = {ref} /* {base} */; }};")
        references.append(f"\t\t{ref} /* {base} */ = {{isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = {base}; sourceTree = \"<group>\"; }};")
        phase_files.append(f"\t\t\t\t{build} /* {base} in Sources */,")
        groups.setdefault(path.rsplit("/", 1)[0] if "/" in path else "", []).append((ref, base))
    group_sections, app_children = [], []
    for index, directory in enumerate(sorted(groups), start=1):
        if directory == "":
            app_children += [f"\t\t\t\t{ref} /* {base} */," for ref, base in groups[directory]]
            continue
        gid = pbx_id("C", 16 + index)
        app_children.append(f"\t\t\t\t{gid} /* {directory} */,")
        group_sections += [
            f"\t\t{gid} /* {directory} */ = {{",
            "\t\t\tisa = PBXGroup;",
            "\t\t\tchildren = (",
            *[f"\t\t\t\t{ref} /* {base} */," for ref, base in groups[directory]],
            "\t\t\t);",
            f"\t\t\tpath = {directory};",
            "\t\t\tsourceTree = \"<group>\";",
            "\t\t};",
        ]
    debug_project, release_project, debug_target, release_target = pbx_id("9", 1), pbx_id("9", 2), pbx_id("9", 3), pbx_id("9", 4)
    list_project, list_target = pbx_id("8", 1), pbx_id("8", 2)
    shared = [
        "\t\t\t\tALWAYS_SEARCH_USER_PATHS = NO;",
        "\t\t\t\tCLANG_ENABLE_MODULES = YES;",
        "\t\t\t\tCLANG_ENABLE_OBJC_ARC = YES;",
        f"\t\t\t\tIPHONEOS_DEPLOYMENT_TARGET = {IOS_DEPLOYMENT_TARGET};",
        "\t\t\t\tSDKROOT = iphoneos;",
    ]
    target_settings = [
        "\t\t\t\tCODE_SIGN_STYLE = Automatic;",
        "\t\t\t\tCURRENT_PROJECT_VERSION = 1;",
        "\t\t\t\tGENERATE_INFOPLIST_FILE = YES;",
        "\t\t\t\tINFOPLIST_KEY_UIApplicationSupportsIndirectInputEvents = YES;",
        "\t\t\t\tINFOPLIST_KEY_UILaunchScreen_Generation = YES;",
        "\t\t\t\tLD_RUNPATH_SEARCH_PATHS = (",
        "\t\t\t\t\t\"$(inherited)\",",
        "\t\t\t\t\t\"@executable_path/Frameworks\",",
        "\t\t\t\t);",
        "\t\t\t\tMARKETING_VERSION = 1.0;",
        f"\t\t\t\tPRODUCT_BUNDLE_IDENTIFIER = io.johnsonlee.graphite.bench.{name};",
        "\t\t\t\tPRODUCT_NAME = \"$(TARGET_NAME)\";",
        "\t\t\t\tSWIFT_EMIT_LOC_STRINGS = YES;",
        "\t\t\t\tSWIFT_VERSION = 5.0;",
        "\t\t\t\tTARGETED_DEVICE_FAMILY = \"1,2\";",
    ]

    def configuration(cid, cname, settings):
        return [f"\t\t{cid} /* {cname} */ = {{", "\t\t\tisa = XCBuildConfiguration;", "\t\t\tbuildSettings = {", *settings, "\t\t\t};", f"\t\t\tname = {cname};", "\t\t};"]

    def configuration_list(lid, owner, debug, release):
        return [
            f"\t\t{lid} /* Build configuration list for {owner} */ = {{",
            "\t\t\tisa = XCConfigurationList;",
            "\t\t\tbuildConfigurations = (",
            f"\t\t\t\t{debug} /* Debug */,",
            f"\t\t\t\t{release} /* Release */,",
            "\t\t\t);",
            "\t\t\tdefaultConfigurationIsVisible = 0;",
            "\t\t\tdefaultConfigurationName = Release;",
            "\t\t};",
        ]

    def phase(pid, isa, comment, entries):
        return [f"\t\t{pid} /* {comment} */ = {{", f"\t\t\tisa = {isa};", "\t\t\tbuildActionMask = 2147483647;", "\t\t\tfiles = (", *entries, "\t\t\t);", "\t\t\trunOnlyForDeploymentPostprocessing = 0;", "\t\t};"]

    lines = [
        "// !$*UTF8*$!",
        "{",
        "\tarchiveVersion = 1;",
        "\tclasses = {",
        "\t};",
        "\tobjectVersion = 56;",
        "\tobjects = {",
        "",
        "/* Begin PBXBuildFile section */",
        *build_files,
        "/* End PBXBuildFile section */",
        "",
        "/* Begin PBXFileReference section */",
        *references,
        f"\t\t{product} /* {name}.app */ = {{isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = {name}.app; sourceTree = BUILT_PRODUCTS_DIR; }};",
        "/* End PBXFileReference section */",
        "",
        "/* Begin PBXFrameworksBuildPhase section */",
        *phase(frameworks_phase, "PBXFrameworksBuildPhase", "Frameworks", []),
        "/* End PBXFrameworksBuildPhase section */",
        "",
        "/* Begin PBXGroup section */",
        f"\t\t{root} = {{",
        "\t\t\tisa = PBXGroup;",
        "\t\t\tchildren = (",
        f"\t\t\t\t{app_group} /* {name} */,",
        f"\t\t\t\t{products} /* Products */,",
        "\t\t\t);",
        "\t\t\tsourceTree = \"<group>\";",
        "\t\t};",
        f"\t\t{app_group} /* {name} */ = {{",
        "\t\t\tisa = PBXGroup;",
        "\t\t\tchildren = (",
        *app_children,
        "\t\t\t);",
        f"\t\t\tpath = {name};",
        "\t\t\tsourceTree = \"<group>\";",
        "\t\t};",
        f"\t\t{products} /* Products */ = {{",
        "\t\t\tisa = PBXGroup;",
        "\t\t\tchildren = (",
        f"\t\t\t\t{product} /* {name}.app */,",
        "\t\t\t);",
        "\t\t\tname = Products;",
        "\t\t\tsourceTree = \"<group>\";",
        "\t\t};",
        *group_sections,
        "/* End PBXGroup section */",
        "",
        "/* Begin PBXNativeTarget section */",
        f"\t\t{target} /* {name} */ = {{",
        "\t\t\tisa = PBXNativeTarget;",
        f"\t\t\tbuildConfigurationList = {list_target} /* Build configuration list for PBXNativeTarget \"{name}\" */;",
        "\t\t\tbuildPhases = (",
        f"\t\t\t\t{sources_phase} /* Sources */,",
        f"\t\t\t\t{frameworks_phase} /* Frameworks */,",
        f"\t\t\t\t{resources_phase} /* Resources */,",
        "\t\t\t);",
        "\t\t\tbuildRules = (",
        "\t\t\t);",
        "\t\t\tdependencies = (",
        "\t\t\t);",
        f"\t\t\tname = {name};",
        f"\t\t\tproductName = {name};",
        f"\t\t\tproductReference = {product} /* {name}.app */;",
        "\t\t\tproductType = \"com.apple.product-type.application\";",
        "\t\t};",
        "/* End PBXNativeTarget section */",
        "",
        "/* Begin PBXProject section */",
        f"\t\t{project} /* Project object */ = {{",
        "\t\t\tisa = PBXProject;",
        "\t\t\tattributes = {",
        "\t\t\t\tBuildIndependentTargetsInParallel = 1;",
        "\t\t\t\tLastSwiftUpdateCheck = 1500;",
        "\t\t\t\tLastUpgradeCheck = 1500;",
        "\t\t\t\tTargetAttributes = {",
        f"\t\t\t\t\t{target} = {{",
        "\t\t\t\t\t\tCreatedOnToolsVersion = 15.0;",
        "\t\t\t\t\t};",
        "\t\t\t\t};",
        "\t\t\t};",
        f"\t\t\tbuildConfigurationList = {list_project} /* Build configuration list for PBXProject \"{name}\" */;",
        "\t\t\tcompatibilityVersion = \"Xcode 14.0\";",
        "\t\t\tdevelopmentRegion = en;",
        "\t\t\thasScannedForEncodings = 0;",
        "\t\t\tknownRegions = (",
        "\t\t\t\ten,",
        "\t\t\t\tBase,",
        "\t\t\t);",
        f"\t\t\tmainGroup = {root};",
        f"\t\t\tproductRefGroup = {products} /* Products */;",
        "\t\t\tprojectDirPath = \"\";",
        "\t\t\tprojectRoot = \"\";",
        "\t\t\ttargets = (",
        f"\t\t\t\t{target} /* {name} */,",
        "\t\t\t);",
        "\t\t};",
        "/* End PBXProject section */",
        "",
        "/* Begin PBXResourcesBuildPhase section */",
        *phase(resources_phase, "PBXResourcesBuildPhase", "Resources", []),
        "/* End PBXResourcesBuildPhase section */",
        "",
        "/* Begin PBXSourcesBuildPhase section */",
        *phase(sources_phase, "PBXSourcesBuildPhase", "Sources", phase_files),
        "/* End PBXSourcesBuildPhase section */",
        "",
        "/* Begin XCBuildConfiguration section */",
        *configuration(debug_project, "Debug", [
            *shared[:3],
            "\t\t\t\tCOPY_PHASE_STRIP = NO;",
            "\t\t\t\tDEBUG_INFORMATION_FORMAT = dwarf;",
            "\t\t\t\tENABLE_TESTABILITY = YES;",
            "\t\t\t\tGCC_OPTIMIZATION_LEVEL = 0;",
            *shared[3:],
            "\t\t\t\tONLY_ACTIVE_ARCH = YES;",
            "\t\t\t\tSWIFT_ACTIVE_COMPILATION_CONDITIONS = DEBUG;",
            "\t\t\t\tSWIFT_OPTIMIZATION_LEVEL = \"-Onone\";",
        ]),
        *configuration(release_project, "Release", [
            *shared[:3],
            "\t\t\t\tCOPY_PHASE_STRIP = NO;",
            "\t\t\t\tDEBUG_INFORMATION_FORMAT = \"dwarf-with-dsym\";",
            "\t\t\t\tENABLE_NS_ASSERTIONS = NO;",
            *shared[3:],
            "\t\t\t\tSWIFT_COMPILATION_MODE = wholemodule;",
            "\t\t\t\tSWIFT_OPTIMIZATION_LEVEL = \"-O\";",
            "\t\t\t\tVALIDATE_PRODUCT = YES;",
        ]),
        *configuration(debug_target, "Debug", target_settings),
        *configuration(release_target, "Release", target_settings),
        "/* End XCBuildConfiguration section */",
        "",
        "/* Begin XCConfigurationList section */",
        *configuration_list(list_project, f"PBXProject \"{name}\"", debug_project, release_project),
        *configuration_list(list_target, f"PBXNativeTarget \"{name}\"", debug_target, release_target),
        "/* End XCConfigurationList section */",
        "\t};",
        f"\trootObject = {project} /* Project object */;",
        "}",
        "",
    ]
    return "\n".join(lines)


def xcscheme(corpus):
    name, target = corpus.name, pbx_id("D", 1)
    reference = "\n".join([
        "            <BuildableReference",
        "               BuildableIdentifier = \"primary\"",
        f"               BlueprintIdentifier = \"{target}\"",
        f"               BuildableName = \"{name}.app\"",
        f"               BlueprintName = \"{name}\"",
        f"               ReferencedContainer = \"container:{name}.xcodeproj\">",
        "            </BuildableReference>",
    ])
    return "\n".join([
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
        "<Scheme",
        "   LastUpgradeVersion = \"1500\"",
        "   version = \"1.7\">",
        "   <BuildAction",
        "      parallelizeBuildables = \"YES\"",
        "      buildImplicitDependencies = \"YES\">",
        "      <BuildActionEntries>",
        "         <BuildActionEntry",
        "            buildForTesting = \"YES\"",
        "            buildForRunning = \"YES\"",
        "            buildForProfiling = \"YES\"",
        "            buildForArchiving = \"YES\"",
        "            buildForAnalyzing = \"YES\">",
        reference,
        "         </BuildActionEntry>",
        "      </BuildActionEntries>",
        "   </BuildAction>",
        "   <TestAction",
        "      buildConfiguration = \"Debug\"",
        "      selectedDebuggerIdentifier = \"Xcode.DebuggerFoundation.Debugger.LLDB\"",
        "      selectedLauncherIdentifier = \"Xcode.DebuggerFoundation.Launcher.LLDB\"",
        "      shouldUseLaunchSchemeArgsEnv = \"YES\">",
        "      <Testables>",
        "      </Testables>",
        "   </TestAction>",
        "   <LaunchAction",
        "      buildConfiguration = \"Debug\"",
        "      selectedDebuggerIdentifier = \"Xcode.DebuggerFoundation.Debugger.LLDB\"",
        "      selectedLauncherIdentifier = \"Xcode.DebuggerFoundation.Launcher.LLDB\"",
        "      launchStyle = \"0\"",
        "      useCustomWorkingDirectory = \"NO\"",
        "      ignoresPersistentStateOnLaunch = \"NO\"",
        "      debugDocumentVersioning = \"YES\"",
        "      debugServiceExtension = \"internal\"",
        "      allowLocationSimulation = \"YES\">",
        "      <BuildableProductRunnable",
        "         runnableDebuggingMode = \"0\">",
        reference.replace("            <", "         <").replace("               ", "            "),
        "      </BuildableProductRunnable>",
        "   </LaunchAction>",
        "   <ProfileAction",
        "      buildConfiguration = \"Release\"",
        "      shouldUseLaunchSchemeArgsEnv = \"YES\"",
        "      savedToolIdentifier = \"\"",
        "      useCustomWorkingDirectory = \"NO\"",
        "      debugDocumentVersioning = \"YES\">",
        "      <BuildableProductRunnable",
        "         runnableDebuggingMode = \"0\">",
        reference.replace("            <", "         <").replace("               ", "            "),
        "      </BuildableProductRunnable>",
        "   </ProfileAction>",
        "   <AnalyzeAction",
        "      buildConfiguration = \"Debug\">",
        "   </AnalyzeAction>",
        "   <ArchiveAction",
        "      buildConfiguration = \"Release\"",
        "      revealArchiveInOrganizer = \"YES\">",
        "   </ArchiveAction>",
        "</Scheme>",
        "",
    ])


def render(corpus):
    """Every file of the corpus as {relative path: text}, the layout's manifest included."""
    files = {}
    if corpus.layout == "package":
        files["Package.swift"] = package_manifest(corpus)
        for path, text in sources(corpus):
            files[f"Sources/{path}"] = text
        files[f"Sources/{corpus.name}/main.swift"] = package_entry(corpus)
    else:
        app = corpus.name
        relative = [path for path, _ in sources(corpus)] + ["AppDelegate.swift"]
        for path, text in sources(corpus):
            files[f"{app}/{path}"] = text
        files[f"{app}/AppDelegate.swift"] = xcode_entry(corpus)
        files[f"{app}.xcodeproj/project.pbxproj"] = pbxproj(corpus, relative)
        files[f"{app}.xcodeproj/xcshareddata/xcschemes/{app}.xcscheme"] = xcscheme(corpus)
    return files


def write(files, out):
    root = pathlib.Path(out)
    for path in sorted(files):
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(files[path], encoding="utf-8", newline="\n")
    return root


def corpus_from_manifest(manifest):
    generator = manifest.get("generator")
    if not isinstance(generator, dict):
        raise ValueError("the manifest has no generator block")
    if generator.get("script") != SCRIPT:
        raise ValueError(f"the manifest names generator {generator.get('script')!r}, this is {SCRIPT}")
    corpus = Corpus(int(generator["files"]), int(generator["modules"]), int(generator["seed"]), generator["layout"], generator["name"])
    pinned = manifest.get("files")
    if pinned is not None and int(pinned) != corpus.files:
        raise ValueError(f"the manifest pins {pinned} files but its generator block says {corpus.files}")
    return corpus


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--manifest", help="a corpus manifest whose generator block names the parameters")
    parser.add_argument("--files", type=int, help="Swift files in all (support files and the entry point included)")
    parser.add_argument("--modules", type=int)
    parser.add_argument("--seed", type=int)
    parser.add_argument("--layout", choices=LAYOUTS)
    parser.add_argument("--name", help="the package or app name")
    parser.add_argument("--out", required=True, help="the directory to write; must not exist or be empty")
    args = parser.parse_args()
    try:
        if args.manifest:
            if any(v is not None for v in (args.files, args.modules, args.seed, args.layout, args.name)):
                parser.error("--manifest carries the parameters; --files/--modules/--seed/--layout/--name are for a manifest-less run")
            corpus = corpus_from_manifest(json.load(open(args.manifest)))
        else:
            missing = [f for f, v in (("--files", args.files), ("--modules", args.modules), ("--seed", args.seed), ("--layout", args.layout), ("--name", args.name)) if v is None]
            if missing:
                parser.error("missing " + ", ".join(missing))
            corpus = Corpus(args.files, args.modules, args.seed, args.layout, args.name)
    except (ValueError, KeyError, TypeError) as error:
        parser.error(str(error))
    out = pathlib.Path(args.out)
    if out.exists() and any(out.iterdir()):
        parser.error(f"{out} exists and is not empty")
    files = render(corpus)
    write(files, out)
    swift = sum(1 for path in files if path.endswith(".swift") and path != "Package.swift")
    print(f"{corpus.name} ({corpus.layout}): {swift} Swift files over {corpus.modules} modules, seed {corpus.seed} -> {out}")


if __name__ == "__main__":
    main()
