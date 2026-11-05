import Foundation

/// A Clang-style USR (`c:...`), as the index store keys Objective-C declarations and the
/// Swift declarations exposed to Objective-C (`@objc` members, `NSObject` subclasses'
/// overrides, UIKit delegate methods). `swift-demangle` reads none of these, so the
/// declaring type and the member come from the USR itself:
///
///     c:objc(cs)UIApplication                                        class, imported
///     c:objc(pl)UIApplicationDelegate                                protocol, imported
///     c:objc(cs)UIApplication(im)registerForRemoteNotifications      instance method
///     c:objc(cs)UIColor(cm)colorWithRed:green:blue:alpha:            class method
///     c:objc(cs)UIView(py)frame                                      property
///     c:objc(cs)UIDevice(cpy)currentDevice                            class property
///     c:objc(cs)LegacyStore@_hits                                     instance variable
///     c:objc(cy)LegacyStore@Extras(im)purge                           category member
///     c:@M@AcmeApp@objc(cs)AppDelegate(im)application:didFinishLaunchingWithOptions:
///                                                                    Swift @objc member, module AcmeApp
///     c:@CM@AcmeApp@@objc(cs)NSObject(cm)make                       Swift extension's @objc member
///     c:@S@CGRect, c:@E@UIUserInterfaceStyle, c:@T@NSInteger, c:@F@NSStringFromClass
///                                                                    C struct, enum, typedef, function
public struct ClangUSR: Equatable {
    public enum MemberKind: Equatable {
        case instanceMethod, classMethod, property, classProperty, instanceVariable, function
    }

    /// The Swift module for a Swift declaration (`@M@AcmeApp`); nil for imported declarations.
    public var module: String?
    /// The class, protocol, struct, enum or typedef name; nil for a free function.
    public var container: String?
    public var isProtocol = false
    /// The selector, property or function name; nil for a type.
    public var member: String?
    public var memberKind: MemberKind?

    /// `Module.Container` when the module is known, the bare container otherwise, the
    /// module for a free function.
    public var qualifiedContainer: String? {
        switch (module, container) {
        case (let module?, let container?): return module + "." + container
        case (nil, let container?): return container
        case (let module?, nil): return module
        case (nil, nil): return nil
        }
    }

    /// The USR of the class, protocol or category a member USR belongs to:
    /// `c:objc(cs)Foo(im)bar:` → `c:objc(cs)Foo`, `c:objc(cs)Foo@_bar` → `c:objc(cs)Foo`;
    /// nil for a type, a free function or a USR that is not Clang's.
    public static func containerUSR(of usr: String) -> String? {
        guard usr.hasPrefix("c:"), let parsed = parse(usr), parsed.member != nil, parsed.container != nil else { return nil }
        for marker in ["(im)", "(cm)", "(py)", "(cpy)"] {
            if let range = usr.range(of: marker) { return String(usr[..<range.lowerBound]) }
        }
        if parsed.memberKind == .instanceVariable, let at = usr.lastIndex(of: "@") { return String(usr[..<at]) }
        return nil
    }

    public static func parse(_ usr: String) -> ClangUSR? {
        guard usr.hasPrefix("c:") else { return nil }
        var rest = Substring(usr.dropFirst(2))
        var result = ClangUSR()
        if rest.hasPrefix("@CM@") {
            // Swift exposes an @objc member added by an extension as a category in
            // the extension's module. Imported ObjC owners carry an additional `@`.
            rest = rest.dropFirst(4)
            guard let end = rest.firstIndex(of: "@"), end != rest.startIndex else { return nil }
            result.module = String(rest[..<end])
            rest = rest[end...].dropFirst()
            if !rest.hasPrefix("objc("), let ownerEnd = rest.firstIndex(of: "@") {
                // Extensions of a type from another Swift module name that owner
                // module here; an imported ObjC owner has an empty module segment.
                result.module = ownerEnd == rest.startIndex ? nil : String(rest[..<ownerEnd])
                rest = rest[ownerEnd...].dropFirst()
            }
            guard rest.hasPrefix("objc(") else { return nil }
        } else if rest.hasPrefix("@M@") {
            rest = rest.dropFirst(3)
            guard let end = rest.firstIndex(of: "@") else { return nil }
            result.module = String(rest[..<end])
            rest = rest[end...].dropFirst()
        }
        if rest.hasPrefix("objc(") {
            rest = rest.dropFirst("objc(".count)
            guard let close = rest.firstIndex(of: ")") else { return nil }
            let kind = rest[..<close]
            rest = rest[close...].dropFirst()
            result.isProtocol = kind == "pl"
            let end = rest.firstIndex(of: "(") ?? rest.endIndex
            let name = rest[..<end]
            guard !name.isEmpty else { return nil }
            // A category `Class@Category` names its class; a class's `Class@ivar` is an
            // instance variable.
            let parts = name.split(separator: "@", maxSplits: 1, omittingEmptySubsequences: false)
            result.container = String(parts[0])
            if kind != "cy", parts.count == 2, !parts[1].isEmpty {
                result.member = String(parts[1])
                result.memberKind = .instanceVariable
            }
            rest = rest[end...]
            if rest.hasPrefix("(") {
                guard let memberClose = rest.firstIndex(of: ")") else { return nil }
                let role = rest[rest.index(after: rest.startIndex)..<memberClose]
                let member = rest[memberClose...].dropFirst()
                switch role {
                case "im": result.memberKind = .instanceMethod
                case "cm": result.memberKind = .classMethod
                case "py": result.memberKind = .property
                case "cpy": result.memberKind = .classProperty
                default: return nil
                }
                guard !member.isEmpty else { return nil }
                result.member = String(member)
            }
            return result
        }
        // `@S@Name`, `@SA@Name` (an anonymous struct's typedef), `@E@Name`, `@T@Name`,
        // `@F@name`, `@E@Enum@Case`, `@N@ns@S@Name`, `@SA@Name@FI@field`, `@kGlobal`; a
        // file-scoped (`static`) declaration carries its file first: `c:File.m@F@name`,
        // `c:File.m@name`.
        var parts = rest.split(separator: "@", omittingEmptySubsequences: false).map(String.init)
        guard parts.count >= 2 else { return nil }
        parts.removeFirst()
        var index = 0
        while index < parts.count {
            let tag = parts[index]
            let name = index + 1 < parts.count && !parts[index + 1].isEmpty ? parts[index + 1] : nil
            if let name, ["S", "SA", "E", "Ea", "T", "U", "UA", "N"].contains(tag) {
                result.container = name
                index += 2
            } else if let name, tag == "F" {
                result.member = name
                result.memberKind = .function
                index += 2
            } else if let name, tag == "FI" {
                result.member = name
                result.memberKind = .property
                index += 2
            } else if name == nil, !tag.isEmpty, result.member == nil {
                // A trailing name: the case of the enum before it, or a global variable.
                result.member = tag
                result.memberKind = .property
                index += 1
            } else {
                return nil
            }
        }
        return result.container == nil && result.member == nil ? nil : result
    }
}
