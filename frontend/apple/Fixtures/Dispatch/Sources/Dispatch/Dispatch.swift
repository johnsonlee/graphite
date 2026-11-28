// The dispatch cases of the completeness evidence (docs/analysis-completeness.md, "The Apple
// frontend"); backend/bench/apple-completeness-e2e.py builds this package and checks what
// both query engines answer for every call of drive().
public protocol Performing {
    func perform()
    func idle()
    func rest()
}
public extension Performing {
    func idle() {}
    func rest() {}
}
public struct Custom: Performing {
    public func perform() {}
    public func idle() {}
}
open class Base: Performing {
    public init() {}
    open func perform() {}
    open func alone() {}
    public final func sealed() {}
}
public final class Leaf: Base {
    public override func perform() { super.perform(); sealed() }
}
public struct Value {
    public func read() -> Int { 1 }
}
public func drive(_ worker: Performing, _ base: Base, _ leaf: Leaf) {
    worker.perform()
    worker.idle()
    worker.rest()
    base.perform()
    base.alone()
    leaf.perform()
    base.sealed()
    _ = Value().read()
    print("done")
}
