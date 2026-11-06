/** Method descriptors and node fields emitted by the JavaScript artifact frontend. */
export interface Method {
  declaringClass: string;
  name: string;
  parameterTypes: readonly string[];
  returnType: string;
}

export interface GraphNodeFields {
  IntConstant: { value: number };
  DoubleConstant: { value: number };
  StringConstant: { value: string };
  BooleanConstant: { value: boolean };
  NullConstant: { value?: never };
  LocalVariable: { name: string; varType: string; method: Method };
  Field: { declaringClass: string; name: string; fieldType: string; isStatic: boolean };
  Parameter: { index: number; paramType: string; method: Method };
  Return: { method: Method; actualType: string };
  CallSite: { caller: Method; callee: Method; line: number; receiver?: number; arguments: number[] };
}

export type NodeKind = keyof GraphNodeFields;
export type GraphNode = { [K in NodeKind]: { id: number; kind: K } & GraphNodeFields[K] }[NodeKind];
// A tuple union retains kind/fields correlation even when a caller holds a union of kinds.
export type NodeArguments = { [K in NodeKind]: [kind: K, fields: GraphNodeFields[K]] }[NodeKind];

export interface ConstantValues {
  IntConstant: number;
  DoubleConstant: number;
  StringConstant: string;
  BooleanConstant: boolean;
  NullConstant: undefined;
}
export type ConstantArguments = {
  [K in keyof ConstantValues]: ConstantValues[K] extends undefined ? [kind: K] : [kind: K, value: ConstantValues[K]]
}[keyof ConstantValues];
