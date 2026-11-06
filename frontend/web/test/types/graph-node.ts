import type { ConstantArguments, GraphNode, Method, NodeArguments } from '../../src/graph-node.js';

declare const node: (...args: NodeArguments) => number;
declare const constant: (...args: ConstantArguments) => number;
const method: Method = { declaringClass: 'example.Owner', name: 'read', parameterTypes: ['string'], returnType: 'void' };

node('Parameter', { index: 0, paramType: 'string', method });
node('Return', { method, actualType: 'void' });
node('Field', { declaringClass: 'example.Owner', name: 'value', fieldType: 'string', isStatic: false });
node('LocalVariable', { name: 'local', varType: 'string', method });
node('CallSite', { caller: method, callee: method, line: 1, arguments: [0] });
node('NullConstant', {});
constant('IntConstant', 1);
constant('DoubleConstant', -0);
constant('StringConstant', 'text');
constant('BooleanConstant', true);
constant('NullConstant');

// @ts-expect-error Unknown protocol kinds cannot be constructed.
node('Method', { method });
// @ts-expect-error A Parameter requires its method descriptor.
node('Parameter', { index: 0, paramType: 'string' });
// @ts-expect-error A LocalVariable must have a type and owning method at construction.
node('LocalVariable', { name: 'local' });
// @ts-expect-error A Field requires the static flag.
node('Field', { declaringClass: 'example.Owner', name: 'value', fieldType: 'string' });
// @ts-expect-error A Return requires the actual type emitted by this producer.
node('Return', { method });
// @ts-expect-error A CallSite requires its argument list.
node('CallSite', { caller: method, callee: method, line: 1 });
// @ts-expect-error Numeric constants cannot carry strings.
node('IntConstant', { value: '1' });
// @ts-expect-error String constants cannot carry numbers.
node('StringConstant', { value: 1 });
// @ts-expect-error NullConstant has no payload.
node('NullConstant', { value: null });
// @ts-expect-error Parameter index has a numeric type.
node('Parameter', { index: '0', paramType: 'string', method });
// @ts-expect-error CallSite arguments are node IDs, not descriptors.
node('CallSite', { caller: method, callee: method, line: 1, arguments: [method] });
// @ts-expect-error Method descriptors require their return type.
node('Return', { method: { declaringClass: 'owner', name: 'read', parameterTypes: [] }, actualType: 'void' });
// @ts-expect-error Constant helper retains the kind/value relationship.
constant('BooleanConstant', 'true');
// @ts-expect-error Constant helper requires a value for non-null kinds.
constant('StringConstant');
// @ts-expect-error NullConstant helper cannot receive a value.
constant('NullConstant', 1);
// @ts-expect-error Constant helper accepts only constant kinds.
constant('Parameter', 0);
declare const ambiguousKind: 'StringConstant' | 'BooleanConstant';
// @ts-expect-error A union kind cannot erase the relationship between kind and fields.
node(ambiguousKind, { value: 'text' });

function describe(value: GraphNode): string {
  if (value.kind === 'StringConstant') {
    const text: string = value.value;
    // @ts-expect-error Narrowing a StringConstant excludes fields of other node kinds.
    value.method;
    return text;
  }
  if (value.kind === 'CallSite') {
    const argument: number | undefined = value.arguments[0];
    return `${value.callee.name}:${argument}`;
  }
  return value.kind;
}
void describe;
// @ts-expect-error Source graphs cannot contain arbitrary records disguised by an open index signature.
const invalid: GraphNode = { id: 1, kind: 'DoubleConstant', value: 'bad' };
void invalid;
