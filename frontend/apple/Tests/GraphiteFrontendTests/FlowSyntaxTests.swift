import Foundation
import XCTest
@testable import GraphiteFrontend

final class FlowSyntaxTests: XCTestCase {
    private let path = "/src/Test.swift"

    private func anchor(_ source: String, _ line: Int, _ token: String) throws -> SourcePosition {
        let text = source.components(separatedBy: "\n")[line - 1]
        let start = try XCTUnwrap(text.range(of: token)?.lowerBound)
        return SourcePosition(path: path, line: line, column: text[..<start].utf8.count + 1)
    }

    func testSignalLocalizationWrapperBindsTheInternalParametersAndReturn() throws {
        // Signal-iOS 7.19.1.208, SignalServiceKit/Util/OWSLocalizedString.swift:25–26.
        let source = """
        public func OWSLocalizedString(_ key: String, tableName: String? = nil, value: String = "", comment: String) -> String {
            return NSLocalizedString(key, tableName: tableName, bundle: .main.app, value: value, comment: comment)
        }
        """
        let facts = SyntaxFacts.parse(source: source, path: path)
        let owner = try anchor(source, 1, "OWSLocalizedString")
        let site = try anchor(source, 2, "NSLocalizedString")
        let flow = try XCTUnwrap(facts.functionFlows[owner])
        XCTAssertEqual(flow.parameters.map(\.name), ["key", "tableName", "value", "comment"])
        XCTAssertEqual(flow.parameters.map(\.index), [0, 1, 2, 3])
        XCTAssertEqual(flow.parameters.first?.position, try anchor(source, 1, "key:"))
        XCTAssertEqual(flow.calls, [site: SyntaxCallFlow(parameterByArgument: [0: 0, 1: 1, 3: 2, 4: 3], returnsResult: true)])
        XCTAssertEqual(facts.calls[site]?.arguments, ["key", "tableName", ".main.app", "value", "comment"])
        XCTAssertNotNil(facts.declarations[owner])
    }

    func testLocalShadowingAndNestedScopesNeverUseTheOuterBinding() throws {
        let source = """
        func wrapper(external key: String, _ other: String) -> String {
            trace(key, 17)
            let key = other
            trace(key, other)
            invoke { key in trace(key, other) }
            func nested(_ inner: String) -> String { return process(key, inner) }
            do { try risky() } catch let key { trace(key, other) }
            return process(key)
        }
        """
        let facts = SyntaxFacts.parse(source: source, path: path)
        let flow = try XCTUnwrap(facts.functionFlows[try anchor(source, 1, "wrapper")])
        XCTAssertEqual(flow.parameters.map(\.name), ["key", "other"])
        XCTAssertEqual(flow.calls, [
            try anchor(source, 2, "trace"): SyntaxCallFlow(parameterByArgument: [0: 0], returnsResult: false),
            try anchor(source, 4, "trace"): SyntaxCallFlow(parameterByArgument: [1: 1], returnsResult: false),
            try anchor(source, 8, "process"): SyntaxCallFlow(parameterByArgument: [:], returnsResult: true),
        ])
        let nested = try XCTUnwrap(facts.functionFlows[try anchor(source, 6, "nested")])
        XCTAssertEqual(nested.calls, [
            try anchor(source, 6, "process"): SyntaxCallFlow(parameterByArgument: [1: 0], returnsResult: true)
        ])
        XCTAssertNotNil(facts.calls[try anchor(source, 5, "trace")])
        XCTAssertNotNil(facts.calls[try anchor(source, 7, "trace")])
        XCTAssertEqual(facts.calls[try anchor(source, 2, "trace")]?.literals.map(\.value), [.int(17)])
    }

    func testUnsupportedBindingsAndIndirectReturnsStayUnknown() throws {
        let source = """
        struct Test {
            init(_ input: String, _ : Bool) { record(input) }
            func test(_ input: String?, mutable: inout String) -> String {
                record(mutable)
                guard let input else { return fallback() }
                observe(input)
                return outer(inner(input)) + suffix()
            }
        }
        """
        let facts = SyntaxFacts.parse(source: source, path: path)
        let initial = try XCTUnwrap(facts.functionFlows[try anchor(source, 2, "init")])
        XCTAssertEqual(initial.parameters.map(\.name), ["input", nil])
        XCTAssertEqual(initial.calls[try anchor(source, 2, "record")]?.parameterByArgument, [0: 0])
        let method = try XCTUnwrap(facts.functionFlows[try anchor(source, 3, "test")])
        XCTAssertTrue(method.calls.isEmpty)
        for (line, token) in [(4, "record"), (5, "fallback"), (6, "observe"), (7, "outer"), (7, "inner"), (7, "suffix")] {
            XCTAssertNotNil(facts.calls[try anchor(source, line, token)])
        }
    }
}
