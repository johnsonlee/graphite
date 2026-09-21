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
///     c:@M@AcmeApp@objc(cs)AppDelegate(im)application:didFinishLaunchingWithOptions:
///                                                                    Swift @objc member, module AcmeApp
///     c:@S@CGRect, c:@E@UIUserInterfaceStyle, c:@T@NSInteger, c:@F@NSStringFromClass
///                                                                    C struct, enum, typedef, function
public struct ClangUSR: Equatable {
    public enum MemberKind: Equatable {
        case instanceMethod, classMethod, property, function
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

    public static func parse(_ usr: String) -> ClangUSR? {
        guard usr.hasPrefix("c:") else { return nil }
        var rest = Substring(usr.dropFirst(2))
        var result = ClangUSR()
        if rest.hasPrefix("@M@") {
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
            // A category `Class@Category` names its class.
            result.container = String(name.split(separator: "@", maxSplits: 1, omittingEmptySubsequences: false)[0])
            rest = rest[end...]
            if rest.hasPrefix("(") {
                guard let memberClose = rest.firstIndex(of: ")") else { return nil }
                let role = rest[rest.index(after: rest.startIndex)..<memberClose]
                let member = rest[memberClose...].dropFirst()
                switch role {
                case "im": result.memberKind = .instanceMethod
                case "cm": result.memberKind = .classMethod
                case "py": result.memberKind = .property
                default: return nil
                }
                guard !member.isEmpty else { return nil }
                result.member = String(member)
            }
            return result
        }
        // `@S@Name`, `@E@Name`, `@T@Name`, `@F@name`, `@E@Enum@Case`, `@N@ns@S@Name`.
        let parts = rest.split(separator: "@", omittingEmptySubsequences: false).map(String.init)
        guard parts.count >= 3, parts[0].isEmpty else { return nil }
        var index = 1
        while index + 1 < parts.count {
            let tag = parts[index]
            let name = parts[index + 1]
            switch tag {
            case "S", "E", "T", "U", "N":
                result.container = name
            case "F":
                result.member = name
                result.memberKind = .function
            default:
                // `@E@Enum@Case`: the case belongs to the enum.
                if result.container != nil, result.member == nil, tag.isEmpty == false {
                    result.member = tag
                    result.memberKind = .property
                }
                return result.container == nil && result.member == nil ? nil : result
            }
            index += 2
        }
        if index < parts.count, result.container != nil {
            result.member = parts[index]
            result.memberKind = .property
        }
        return result.container == nil && result.member == nil ? nil : result
    }
}
