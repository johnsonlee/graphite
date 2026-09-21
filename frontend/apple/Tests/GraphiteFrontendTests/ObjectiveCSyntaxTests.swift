import Foundation
import XCTest
@testable import GraphiteFrontend

/// The Objective-C declaration pass: types of methods, properties and instance variables
/// as Clang spells them, keyed by the position of the declared name.
final class ObjectiveCSyntaxTests: XCTestCase {
    static let header = """
    #import <Foundation/Foundation.h>
    #define LEGACY_EXPORT __attribute__((visibility("default"))) \\
        __attribute__((used))
    @class Order; // a forward declaration, not a container
    @protocol Auditing, Purging;

    NS_ASSUME_NONNULL_BEGIN

    /* A protocol whose methods are declared but never defined here. */
    @protocol Auditing <NSObject>
    @required
    - (void)audit:(NSString *)event code:(NSInteger)code;
    @optional
    + (nullable instancetype)auditorNamed:(NSString *)name;
    @end

    LEGACY_EXPORT
    @interface LegacyStore<__covariant ItemType> : NSObject <Auditing, NSCopying> {
    @private
        NSUInteger _hits;
        void (^_handler)(BOOL, NSError * _Nullable);
        char _tag[8];
        int _a, *_b;
    }

    @property (nonatomic, readonly) NSUInteger writes;
    @property (class, nonatomic, strong) LegacyStore *shared NS_SWIFT_NAME(shared);
    @property (nonatomic, copy, nullable) void (^onChange)(NSString *key, id value);
    @property (nonatomic, weak, nullable) IBOutlet UIView *view;
    @property (nonatomic, strong) NSDictionary<NSString *, NSArray<NSNumber *> *> *counts;
    @property (nonatomic) id<Auditing> auditor;
    @property (nonatomic, assign) NSInteger x, y;
    @property (nonatomic, readonly, nullable) __kindof UIView *host;

    + (instancetype)storeWithName:(NSString *)name; // the class method
    - (BOOL)storeValue:(NSString *)value forKey:(NSString *)key;
    - (nullable NSString *)valueForKey:(NSString *)key default:(nullable NSString *)fallback API_AVAILABLE(ios(13.0));
    - (void)enumerate:(void (NS_NOESCAPE ^)(NSString *key, BOOL *stop))block;
    - (NSInteger)sum:(NSInteger)first, ... NS_REQUIRES_NIL_TERMINATION;
    - (void)reset:(int)a :(int)b;
    - (IBAction)tap:(nullable id)sender;
    - (SEL)selectorFor:(NSString *)name;
    - (void)raw:(void *)buffer length:(size_t)length;
    - (NSError * _Nullable * _Nullable)errors;
    - init;
    @end

    NS_ASSUME_NONNULL_END

    @interface LegacyStore (Extras)
    - (void)purge:(NSString *)reason;
    @end

    static inline int helper(int x) { return x + 1; }
    """

    static let implementation = """
    #import "LegacyStore.h"
    #pragma clang assume_nonnull begin
    @interface LegacyStore ()
    @property (nonatomic, readwrite) NSUInteger writes;
    @end
    #pragma clang assume_nonnull end

    @implementation LegacyStore {
        NSString *_name;
    }

    static NSString *const kTag = @"legacy; { not a member";

    + (instancetype)storeWithName:(NSString *)name {
        return [[self alloc] init];
    }

    - (BOOL)storeValue:(NSString *)value forKey:(NSString *)key {
        if ([key length] == 0) { return NO; }
        return YES;
    }

    - (NSString *)normalizeKey:(NSString *)key { return [key lowercaseString]; }

    @end
    """

    /// The position of the `nth` whole-word occurrence of `name` in `source`.
    static func position(of name: String, in source: String, nth: Int = 0, path: String) -> SourcePosition {
        var remaining = nth
        var line = 1
        for text in source.split(separator: "\n", omittingEmptySubsequences: false) {
            var searchStart = text.startIndex
            while let range = text.range(of: name, range: searchStart..<text.endIndex) {
                let before = range.lowerBound == text.startIndex ? " " : text[text.index(before: range.lowerBound)]
                let after = range.upperBound == text.endIndex ? " " : text[range.upperBound]
                let bounded = !(before.isLetter || before.isNumber || before == "_") && !(after.isLetter || after.isNumber || after == "_")
                if bounded {
                    if remaining == 0 {
                        let column = text.utf8.distance(from: text.utf8.startIndex, to: range.lowerBound) + 1
                        return SourcePosition(path: path, line: line, column: column)
                    }
                    remaining -= 1
                }
                searchStart = range.upperBound
            }
            line += 1
        }
        fatalError("\(name) #\(nth) not in the source")
    }

    private func declaration(_ facts: SyntaxFacts, _ name: String, nth: Int = 0, in source: String, path: String) throws -> SyntaxDeclaration {
        let position = Self.position(of: name, in: source, nth: nth, path: path)
        return try XCTUnwrap(facts.declarations[position], "no declaration at \(position.line):\(position.column) for \(name)")
    }

    private func describe(_ declaration: SyntaxDeclaration) -> String {
        "\(declaration.isStatic ? "+" : "-")(\(declaration.returnType ?? "?"))\(declaration.parameterTypes)"
    }

    func testHeaderDeclarations() throws {
        let path = "/src/LegacyStore.h"
        let source = Self.header
        let facts = ObjectiveCSyntax.parse(source: source, path: path)
        func at(_ name: String, nth: Int = 0) throws -> String {
            describe(try declaration(facts, name, nth: nth, in: source, path: path))
        }
        // The protocol's methods, under an assumed-nonnull region.
        XCTAssertEqual(try at("audit"), "-(void)[\"NSString * _Nonnull\", \"NSInteger\"]")
        XCTAssertEqual(try at("auditorNamed"), "+(instancetype _Nullable)[\"NSString * _Nonnull\"]")
        // Instance variables.
        XCTAssertEqual(try at("_hits"), "-(NSUInteger)[]")
        XCTAssertEqual(try at("_handler"), "-(void (^ _Nonnull)(BOOL, NSError * _Nullable))[]")
        XCTAssertEqual(try at("_tag"), "-(char[8])[]")
        XCTAssertEqual(try at("_a"), "-(int)[]")
        XCTAssertEqual(try at("_b"), "-(int * _Nonnull)[]")
        // Properties.
        XCTAssertEqual(try at("writes"), "-(NSUInteger)[]")
        XCTAssertEqual(try at("shared"), "+(LegacyStore * _Nonnull)[]")
        XCTAssertEqual(try at("onChange"), "-(void (^ _Nullable)(NSString * key, id value))[]")
        XCTAssertEqual(try at("view"), "-(UIView * _Nullable)[]")
        XCTAssertEqual(try at("counts"), "-(NSDictionary<NSString *, NSArray<NSNumber *> *> * _Nonnull)[]")
        XCTAssertEqual(try at("auditor"), "-(id<Auditing> _Nonnull)[]")
        XCTAssertEqual(try at("x"), "-(NSInteger)[]")
        XCTAssertEqual(try at("y"), "-(NSInteger)[]")
        XCTAssertEqual(try at("host"), "-(__kindof UIView * _Nullable)[]")
        // Methods: the selector's first part names the position.
        XCTAssertEqual(try at("storeWithName"), "+(instancetype _Nonnull)[\"NSString * _Nonnull\"]")
        XCTAssertEqual(try at("storeValue"), "-(BOOL)[\"NSString * _Nonnull\", \"NSString * _Nonnull\"]")
        XCTAssertEqual(try at("valueForKey"), "-(NSString * _Nullable)[\"NSString * _Nonnull\", \"NSString * _Nullable\"]")
        XCTAssertEqual(try at("enumerate"), "-(void)[\"void (^ _Nonnull)(NSString * key, BOOL * stop)\"]")
        XCTAssertEqual(try at("sum"), "-(NSInteger)[\"NSInteger\"]")
        XCTAssertEqual(try at("reset"), "-(void)[\"int\", \"int\"]")
        XCTAssertEqual(try at("tap"), "-(void)[\"id _Nullable\"]")
        XCTAssertEqual(try at("selectorFor"), "-(SEL _Nonnull)[\"NSString * _Nonnull\"]")
        XCTAssertEqual(try at("raw"), "-(void)[\"void * _Nonnull\", \"size_t\"]")
        XCTAssertEqual(try at("errors"), "-(NSError * _Nullable * _Nullable)[]")
        XCTAssertEqual(try at("init"), "-(id _Nonnull)[]")
        // Outside the region, in a category; the C function at the end of the file.
        XCTAssertEqual(try at("purge"), "-(void)[\"NSString *\"]")
        XCTAssertEqual(try at("helper"), "+(int)[\"int\"]")
        // Nothing else is a declaration: not the forward declarations, the macro or the
        // parameter names.
        XCTAssertEqual(facts.declarations.count, 29)
        XCTAssertTrue(facts.calls.isEmpty)
        XCTAssertTrue(facts.attributes.isEmpty)
    }

    func testImplementationDeclarations() throws {
        let path = "/src/LegacyStore.m"
        let source = Self.implementation
        let facts = ObjectiveCSyntax.parse(source: source, path: path)
        func at(_ name: String, nth: Int = 0) throws -> String {
            describe(try declaration(facts, name, nth: nth, in: source, path: path))
        }
        // The class extension's property, under the pragma region.
        XCTAssertEqual(try at("writes"), "-(NSUInteger)[]")
        // The implementation's instance variable and methods, outside it.
        XCTAssertEqual(try at("_name"), "-(NSString *)[]")
        XCTAssertEqual(try at("storeWithName"), "+(instancetype)[\"NSString *\"]")
        XCTAssertEqual(try at("storeValue"), "-(BOOL)[\"NSString *\", \"NSString *\"]")
        XCTAssertEqual(try at("normalizeKey"), "-(NSString *)[\"NSString *\"]")
        // The file-scoped global inside the `@implementation`; its initializer is not parsed.
        XCTAssertEqual(try at("kTag"), "+(NSString *const)[]")
        XCTAssertEqual(facts.declarations.count, 6, "\(facts.declarations.keys.map { "\($0.line):\($0.column)" }.sorted())")
    }

    func testAFileWithoutObjectiveCDeclarationsIsEmpty() throws {
        let facts = ObjectiveCSyntax.parse(source: "#include <stdio.h>\nint main(void) { @\"text\"; return 0; }\n", path: "/src/main.m")
        XCTAssertEqual(facts.declarations.values.map(describe), ["+(int)[]"], "a C file's function is a declaration; nothing else is")
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("graphite-objc-\(UUID().uuidString).h")
        try "@interface Latin1 : NSObject\n// caf\u{E9}\n- (int)count;\n@end\n".data(using: .isoLatin1)!.write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        let parsed = try ObjectiveCSyntax.parse(path: file.path)
        XCTAssertEqual(parsed.declarations.values.map(\.returnType), ["int"])
        XCTAssertEqual(parsed.declarations.keys.map { "\($0.line):\($0.column)" }, ["3:8"])
    }

    /// A `{` after the header that is not the instance variable block: `typedef NS_ENUM`,
    /// `enum`, `struct` bodies declared between the header and the first method (Signal's
    /// `Cryptography.h`). The parse terminates and the methods are the class's.
    func testALaterEnumBodyIsNotTheInstanceVariableBlock() {
        let source = """
        @interface C : NSObject
        typedef NS_ENUM(int, E) { A };
        + (void)f;
        @end
        @interface D<T> : NSObject <P, Q> {
            int _a;
        }
        enum Kind { KindA, KindB };
        struct Pair { int x; int y; };
        - (int)g:(T)value;
        @end
        @interface E (Extras) {
            int _b;
        }
        - (void)h;
        @end
        @implementation F {
            NSString *_name;
        }
        - (void)i {}
        @end
        """
        let facts = ObjectiveCSyntax.parse(source: source, path: "/src/Later.h")
        let described = facts.declarations.values
            .map { "\($0.position.line):\($0.position.column) \($0.isStatic ? "+" : "-")(\($0.returnType ?? ""))\($0.parameterTypes)" }
            .sorted()
        XCTAssertEqual(described, [
            "10:8 -(int)[\"T\"]",
            "15:9 -(void)[]",
            "20:9 -(void)[]",
            "3:9 +(void)[]",
            "6:9 -(int)[]",
            "9:19 -(int)[]",
            "9:26 -(int)[]",
            "13:9 -(int)[]",
            "18:15 -(NSString *)[]",
        ].sorted(), "the struct's fields are declarations, the enum's cases are not")
    }

    /// An instance variable block that is never closed, a header cut short, and every
    /// arrangement of the tokens the parser reacts to: the parse always terminates.
    func testEveryParserLoopAdvancesOrExits() {
        let unclosed = ObjectiveCSyntax.parse(source: """
        @interface C : NSObject {
            int _a
        - (void)f;
        @property int p
        + (void)g
        @end
        @interface
        @implementation D (
        @protocol P <
        - (
        """, path: "/src/Unclosed.h")
        // A member marker ends an unterminated declaration, which is still recorded.
        XCTAssertEqual(unclosed.declarations.values.map { "\($0.position.line):\($0.position.column)" }.sorted(), ["2:9", "3:9", "4:15", "5:9"])
        let vocabulary = [
            "@interface", "@implementation", "@protocol", "@end", "@property", "@required", "@class", "@public",
            "-", "+", "(", ")", "{", "}", "<", ">", ":", ";", ",", "*", "^", "[", "]", "...", "@",
            "C", "NSObject", "int", "void", "nullable", "NS_ASSUME_NONNULL_BEGIN", "typedef", "NS_ENUM",
            "#pragma clang assume_nonnull begin\n", "\n", "\"s;{\"", "/* } */", "// @end\n", "1.5", "IBAction",
        ]
        var state: UInt64 = 0x9E3779B97F4A7C15
        func next() -> Int {
            state = state &* 6364136223846793005 &+ 1442695040888963407
            return Int(truncatingIfNeeded: state >> 33)
        }
        for _ in 0..<3000 {
            let count = 1 + next() % 40
            let source = (0..<count).map { _ in vocabulary[next() % vocabulary.count] }.joined(separator: " ")
            _ = ObjectiveCSyntax.parse(source: source, path: "/src/Random.h")
        }
    }

    /// The Clang declarations the reader emits besides a container's members: C functions
    /// and globals at file scope and inside an `@implementation`, the fields of a `struct`,
    /// the accessors a property names (`getter=`, `setter=`) and the instance variables
    /// `@synthesize` creates, which take the property's type.
    func testCDeclarationsAccessorsAndSynthesizedInstanceVariables() throws {
        let header = """
        #pragma clang assume_nonnull begin
        extern const int kMsgLimit;
        FOUNDATION_EXPORT NSString *const MsgErrorDomain;
        UIKIT_EXTERN void MsgReset(int mode);
        NSUInteger MsgCount(NSArray<NSString *> *items, void (^filter)(NSString *), ...) NS_SWIFT_NAME(count(_:filter:));
        int MsgCompare(const char *, unsigned long) __attribute__((pure));
        typedef struct { int x; int y; } MsgPoint;
        struct MsgRange { NSUInteger location, length; };
        typedef NS_ENUM(NSInteger, MsgKind) { MsgKindA, MsgKindB };
        enum { MsgFlagA = 1 << 0, MsgFlagB = 1 << 1 };
        @class Other;
        @interface Msg {
            int _direct;
        }
        @property (nonatomic, getter=wasRead, setter=setRead:) int read;
        @property (nonatomic) char *contentType;
        @property (class, nonatomic, readonly, getter=isShared) BOOL shared;
        - (int)count;
        @end
        #pragma clang assume_nonnull end
        """
        let implementation = """
        #import "Msg.h"
        @import Foundation;
        const int kMsgLimit = 3;
        static int sCalls = 0, sTotal;
        void MsgReset(int mode) { sCalls = mode; }
        static inline int helper(int x) { if (x > 0) { return x + 1; } return 0; }
        int (*MsgHook)(int) = 0;
        static NSDictionary<NSString *, id> *sIndex = nil;
        static int sTable[] = { 1, 2, 3 };
        NSString *const MsgErrorDomain = @"msg";
        @implementation Msg
        static char *const kTag = "tag; { not a member";
        @synthesize contentType = _contentType;
        @synthesize read;
        @dynamic shared;
        - (int)count { return helper(sCalls) + [self wasRead]; }
        + (BOOL)isShared { return YES; }
        @end
        """
        let headerFacts = ObjectiveCSyntax.parse(source: header, path: "/src/Msg.h")
        func at(_ name: String, nth: Int = 0) throws -> String {
            describe(try declaration(headerFacts, name, nth: nth, in: header, path: "/src/Msg.h"))
        }
        XCTAssertEqual(try at("kMsgLimit"), "+(const int)[]")
        XCTAssertEqual(try at("MsgErrorDomain"), "+(NSString *const _Nonnull)[]")
        XCTAssertEqual(try at("MsgReset"), "+(void)[\"int\"]")
        XCTAssertEqual(try at("MsgCount"), "+(NSUInteger)[\"NSArray<NSString *> * _Nonnull\", \"void (^ _Nonnull)(NSString *)\"]")
        XCTAssertEqual(try at("MsgCompare"), "+(int)[\"const char * _Nonnull\", \"unsigned long\"]")
        XCTAssertEqual(try at("x"), "-(int)[]")
        XCTAssertEqual(try at("y"), "-(int)[]")
        XCTAssertEqual(try at("location"), "-(NSUInteger)[]")
        XCTAssertEqual(try at("length"), "-(NSUInteger)[]")
        XCTAssertEqual(try at("_direct"), "-(int)[]")
        XCTAssertEqual(try at("read"), "-(int)[]")
        XCTAssertEqual(try at("wasRead"), "-(int)[]")
        XCTAssertEqual(try at("setRead"), "-(void)[\"int\"]")
        XCTAssertEqual(try at("contentType"), "-(char * _Nonnull)[]")
        XCTAssertEqual(try at("shared"), "+(BOOL)[]")
        XCTAssertEqual(try at("isShared"), "+(BOOL)[]")
        XCTAssertEqual(try at("count", nth: 1), "-(int)[]")
        XCTAssertEqual(headerFacts.declarations.count, 17, "\(headerFacts.declarations.keys.map { "\($0.line):\($0.column)" }.sorted())")

        let facts = ObjectiveCSyntax.parse(source: implementation, path: "/src/Msg.m")
        func atImplementation(_ name: String, nth: Int = 0) throws -> String {
            describe(try declaration(facts, name, nth: nth, in: implementation, path: "/src/Msg.m"))
        }
        XCTAssertEqual(try atImplementation("kMsgLimit"), "+(const int)[]")
        XCTAssertEqual(try atImplementation("sCalls"), "+(int)[]")
        XCTAssertEqual(try atImplementation("sTotal"), "+(int)[]")
        XCTAssertEqual(try atImplementation("MsgReset"), "+(void)[\"int\"]")
        XCTAssertEqual(try atImplementation("helper"), "+(int)[\"int\"]")
        XCTAssertEqual(try atImplementation("MsgHook"), "+(int (*)(int))[]")
        XCTAssertEqual(try atImplementation("sIndex"), "+(NSDictionary<NSString *, id> *)[]")
        XCTAssertEqual(try atImplementation("sTable"), "+(int[])[]")
        XCTAssertEqual(try atImplementation("MsgErrorDomain"), "+(NSString *const)[]")
        XCTAssertEqual(try atImplementation("kTag"), "+(char *const)[]")
        XCTAssertEqual(try atImplementation("count"), "-(int)[]")
        XCTAssertEqual(try atImplementation("isShared"), "+(BOOL)[]")
        XCTAssertEqual(facts.declarations.count, 12, "\(facts.declarations.keys.map { "\($0.line):\($0.column)" }.sorted())")
        // `@synthesize` maps each instance variable's position to its property.
        let synthesized = facts.synthesized.map { "\($0.key.line):\($0.key.column) \($0.value)" }.sorted()
        XCTAssertEqual(synthesized, ["13:27 contentType", "14:13 read"])
    }

    func testUnfinishedDeclarationsDoNotStopTheParse() {
        let facts = ObjectiveCSyntax.parse(source: """
        @interface Broken
        - (void)
        @property int
        - (int)still;
        @end
        @implementation Broken
        - (int)still { return 1; }
        @end
        """, path: "/src/Broken.h")
        XCTAssertEqual(facts.declarations.values.map { "\($0.position.line):\($0.position.column) \($0.returnType ?? "")" }.sorted(), ["4:8 int", "7:8 int"])
    }
}
