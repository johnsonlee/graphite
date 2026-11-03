import ts from 'typescript';
import path from 'node:path';
import fs from 'node:fs';
import { encodeArtifactString } from './strings.js';

export interface Method {
  declaringClass: string;
  name: string;
  parameterTypes: string[];
  returnType: string;
}
export interface GraphNode {
  id: number;
  kind: string;
  [key: string]: unknown;
}
export interface GraphEdge {
  from: number;
  to: number;
  kind: string;
  isVirtual?: boolean;
  isDynamic?: boolean;
}
export interface SourceGraph {
  version: 1;
  methods: Method[];
  nodes: GraphNode[];
  edges: GraphEdge[];
  classOrigins: { className: string; origin: string }[];
}
type UndefinedState = 'always' | 'never' | 'maybe';
interface ArgumentValue { id: number; state: UndefinedState }
type FunctionBody = ts.FunctionLikeDeclaration & { body: ts.ConciseBody };
interface Context { method: Method; parameters: number[]; returned?: number; body: ts.Node; declaration?: FunctionBody; defaults?: Map<number, ts.Expression> }
const isBody = (n: ts.Node): n is FunctionBody => ts.isFunctionLike(n) && 'body' in n && !!n.body;
const artifactExtension = /\.(?:[cm]?js)$/i;
const formatDiagnostics = (diagnostics: readonly ts.Diagnostic[]) => ts.formatDiagnostics(diagnostics, {
  getCanonicalFileName: f => f,
  getCurrentDirectory: () => process.cwd(),
  getNewLine: () => '\n',
});

/** Read emitted JavaScript only. Compilation is deliberately an explicit upstream step. */
export function project(input: string): { program: ts.Program; root: string } {
  const absolute = path.resolve(input);
  const stat = fs.statSync(absolute);
  const root = stat.isDirectory() ? absolute : path.dirname(absolute);
  const files: string[] = [];
  const collect = (directory: string): void => {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name, 'en'))) {
      const file = path.join(directory, entry.name);
      // Do not traverse symlinks, dependencies or Next's compiler cache. Include .next,
      // dist and build: these are precisely the directories this frontend consumes.
      if (entry.isDirectory() && !['node_modules', '.git', 'cache'].includes(entry.name)) collect(file);
      else if (entry.isFile() && artifactExtension.test(entry.name)) files.push(file);
    }
  };
  if (stat.isDirectory()) collect(absolute);
  else if (artifactExtension.test(absolute)) files.push(absolute);
  else throw new Error(`Expected compiled JavaScript (.js/.mjs/.cjs), not ${input}. Compile the JS/TS project first and pass its output directory.`);
  if (!files.length) throw new Error(`No compiled JavaScript artifacts found in ${input}. Compile the project first.`);
  const options: ts.CompilerOptions = {
    target: ts.ScriptTarget.ESNext, module: ts.ModuleKind.NodeNext,
    moduleResolution: ts.ModuleResolutionKind.NodeNext, allowJs: true, noEmit: true, types: [], strictNullChecks: true,
    moduleDetection: ts.ModuleDetectionKind.Force,
  };
  const host = ts.createCompilerHost(options);
  const selected = new Set(files);
  host.resolveModuleNames = (names, containingFile) => names.map(name => {
    const resolved = ts.resolveModuleName(name, containingFile, options, host).resolvedModule;
    // Never silently pull original TS source or installed dependencies into an artifact graph.
    return resolved && selected.has(resolved.resolvedFileName) ? resolved : undefined;
  });
  const program = ts.createProgram(files, options, host);
  const optionErrors = program.getOptionsDiagnostics();
  if (optionErrors.length) throw new Error(formatDiagnostics(optionErrors));
  const syntactic = program.getSyntacticDiagnostics();
  if (syntactic.length) throw new Error(formatDiagnostics(syntactic));
  return { program, root };
}

/** A flow-insensitive value graph of emitted JS. No artifacts or build scripts are executed. */
export function analyze(input: string, warn: (message: string) => void = () => {}): SourceGraph {
  const { program, root } = project(input);
  const diagnostics = program.getSemanticDiagnostics();
  if (diagnostics.length) warn(`${diagnostics.length} TypeScript semantic diagnostic(s); unresolved targets remain dynamic.\n${formatDiagnostics(diagnostics.slice(0, 5))}`);
  const graph = new Analyzer(program, root).run();
  // Type inference can also produce ill-formed UTF-16 in literal type names.
  // Encode every textual payload once at the protocol boundary, consistently
  // across method definitions and references, without changing numeric values.
  const text = (value: unknown): unknown => typeof value === 'string' ? encodeArtifactString(value) :
    Array.isArray(value) ? value.map(text) : value && typeof value === 'object'
      ? Object.fromEntries(Object.entries(value).map(([key, field]) => [key, text(field)])) : value;
  return text(graph) as SourceGraph;
}

class Analyzer {
  private checker: ts.TypeChecker;
  private graph: SourceGraph = { version: 1, methods: [], nodes: [], edges: [], classOrigins: [] };
  private contexts = new Map<ts.Node, Context>();
  private functions: Context[] = [];
  private values = new Map<ts.Node, number>();
  private symbols = new Map<ts.Symbol, number>();
  private constants = new Map<string, number>();
  private properties = new Map<string, number>();
  private edgePairs = new Map<string, GraphEdge>();
  private writes = new Map<ts.Symbol, UndefinedState>();
  private patternDefaults = new Set<ts.BinaryExpression>();
  private handledFallbacks = new Set<ts.Expression>();
  private writeCache = new Map<ts.Symbol, UndefinedState | undefined>();
  private undefinedValues = new Set<number>();
  private argumentBindings = new Map<ts.Symbol, ArgumentValue>();
  private activeDefaults = new Set<ts.ParameterDeclaration>();
  private evaluatedDefaults = new Set<ts.ParameterDeclaration>();
  private normalizedGuards = new Set<ts.Node>();
  private normalizedAssignments = new Set<ts.Node>();
  private origins = new Set<string>();
  private methodKeys = new Set<string>();
  private sources: ts.SourceFile[];
  constructor(private program: ts.Program, private root: string) {
    this.checker = program.getTypeChecker();
    const selected = new Set(program.getRootFileNames());
    this.sources = program.getSourceFiles().filter(s => selected.has(s.fileName))
      .sort((a, b) => a.fileName.localeCompare(b.fileName, 'en'));
  }
  private module(n: ts.Node): string {
    const file = n.getSourceFile().fileName;
    const normalized = file.split(path.sep).join('/');
    const dependency = normalized.lastIndexOf('/node_modules/');
    if (dependency >= 0) return `external:${normalized.slice(dependency + '/node_modules/'.length)}`;
    return path.relative(this.root, file).split(path.sep).join('/');
  }
  private position(n: ts.Node): string {
    const p = n.getSourceFile().getLineAndCharacterOfPosition(n.getStart());
    return `${p.line + 1}:${p.character + 1}`;
  }
  private type(n: ts.Node): string {
    return this.checker.typeToString(this.checker.getTypeAtLocation(n), n,
      ts.TypeFormatFlags.NoTruncation | ts.TypeFormatFlags.UseAliasDefinedOutsideCurrentScope);
  }
  private symbol(n: ts.Node): ts.Symbol | undefined {
    let symbol = this.checker.getSymbolAtLocation(n);
    if (symbol && symbol.flags & ts.SymbolFlags.Alias) symbol = this.checker.getAliasedSymbol(symbol);
    return symbol;
  }
  private node(kind: string, fields: Record<string, unknown>): number {
    const id = this.graph.nodes.length;
    this.graph.nodes.push({ id, kind, ...fields });
    return id;
  }
  private edge(from: number, to: number, kind: string, extra: Pick<GraphEdge, 'isVirtual' | 'isDynamic'> = {}): void {
    const key = `${from}:${to}`;
    const previous = this.edgePairs.get(key);
    if (previous && (previous.kind !== kind || !!previous.isVirtual !== !!extra.isVirtual || !!previous.isDynamic !== !!extra.isDynamic))
      throw new Error(`Conflicting relations ${previous.kind} and ${kind} for nodes ${from} (${this.graph.nodes[from]?.kind}:${this.graph.nodes[from]?.name ?? this.graph.nodes[from]?.value}) -> ${to} (${this.graph.nodes[to]?.kind}:${this.graph.nodes[to]?.name ?? JSON.stringify(this.graph.nodes[to]?.method)}); graph was not saved.`);
    if (previous) return;
    const edge = { from, to, kind, ...extra };
    this.edgePairs.set(key, edge);
    this.graph.edges.push(edge);
  }
  private addMethod(method: Method, origin: string): void {
    this.graph.methods.push(method);
    if (!this.origins.has(method.declaringClass)) {
      this.origins.add(method.declaringClass);
      this.graph.classOrigins.push({ className: method.declaringClass, origin });
    }
  }
  private name(n: ts.Node & { name?: ts.DeclarationName }): string | undefined {
    return n.name?.getText().replace(/^['"]|['"]$/g, '');
  }
  private descriptor(n: ts.SignatureDeclaration): Method {
    let owner = this.module(n);
    let name = ts.isConstructorDeclaration(n) ? 'constructor' : this.name(n);
    if (!name && (ts.isVariableDeclaration(n.parent) || ts.isPropertyAssignment(n.parent) || ts.isPropertyDeclaration(n.parent))) {
      name = this.name(n.parent);
    }
    name ??= `anonymous@${this.position(n)}`;
    let parent = n.parent;
    while (parent && !ts.isSourceFile(parent)) {
      if (ts.isClassLike(parent) || ts.isInterfaceDeclaration(parent)) {
        owner += `.${this.name(parent) ?? `anonymous@${this.position(parent)}`}`;
        break;
      }
      if (ts.isObjectLiteralExpression(parent)) {
        const binding = parent.parent;
        const objectName = ts.isVariableDeclaration(binding) || ts.isPropertyAssignment(binding) || ts.isPropertyDeclaration(binding)
          ? this.name(binding) : undefined;
        owner += objectName ? `.${objectName}` : `#object@${this.position(parent)}`;
      }
      if (isBody(parent)) {
        const context = this.contexts.get(parent);
        if (context) { owner += `#${context.method.name}@${this.position(parent)}`; break; }
      }
      parent = parent.parent;
    }
    const signature = this.checker.getSignatureFromDeclaration(n);
    return {
      declaringClass: owner, name,
      parameterTypes: n.parameters.map(p => this.type(p)),
      returnType: signature ? this.checker.typeToString(signature.getReturnType()) : 'unknown',
    };
  }
  private defaults(n: FunctionBody): Map<number, ts.Expression> {
    const result = new Map<number, ts.Expression>();
    n.parameters.forEach((p, i) => { if (p.initializer) result.set(i, p.initializer); });
    if (!ts.isBlock(n.body)) return result;
    // TypeScript's ES5 lowering preserves defaults as leading strict undefined
    // guards. Lift only this exact form, in parameter order, without an else.
    const undefinedLiteral = (node: ts.Expression): boolean => ts.isParenthesizedExpression(node) ? undefinedLiteral(node.expression) :
      ts.isVoidExpression(node) && ts.isNumericLiteral(node.expression) && Number(node.expression.text) === 0 ||
      ts.isIdentifier(node) && node.text === 'undefined' && !this.symbol(node)?.declarations?.length;
    let previous = -1;
    for (const statement of n.body.statements) {
      if (ts.isExpressionStatement(statement) && ts.isStringLiteral(statement.expression)) continue;
      if (!ts.isIfStatement(statement) || statement.elseStatement || !ts.isBinaryExpression(statement.expression) ||
          statement.expression.operatorToken.kind !== ts.SyntaxKind.EqualsEqualsEqualsToken) break;
      const condition = statement.expression;
      const name = ts.isIdentifier(condition.left) && undefinedLiteral(condition.right) ? condition.left :
        ts.isIdentifier(condition.right) && undefinedLiteral(condition.left) ? condition.right : undefined;
      if (!name) break;
      const index = n.parameters.findIndex(p => ts.isIdentifier(p.name) && this.symbol(p.name) === this.symbol(name));
      if (index < 0 || index <= previous || result.has(index)) break;
      const body = ts.isBlock(statement.thenStatement) && statement.thenStatement.statements.length === 1
        ? statement.thenStatement.statements[0]! : statement.thenStatement;
      if (!ts.isExpressionStatement(body) || !ts.isBinaryExpression(body.expression) ||
          body.expression.operatorToken.kind !== ts.SyntaxKind.EqualsToken || !ts.isIdentifier(body.expression.left) ||
          this.symbol(body.expression.left) !== this.symbol(name)) break;
      result.set(index, body.expression.right);
      this.normalizedGuards.add(statement);
      this.normalizedAssignments.add(body.expression);
      previous = index;
    }
    return result;
  }
  // Only assignment targets are written. Object keys, computed names and
  // property receivers are reads; never classify them as reassigned variables.
  private *targets(n: ts.Expression, defaults: readonly ts.Expression[] = []): Generator<{ target: ts.Expression; defaults: readonly ts.Expression[] }> {
    if (ts.isParenthesizedExpression(n) || ts.isSpreadElement(n)) yield* this.targets(n.expression, defaults);
    else if (ts.isArrayLiteralExpression(n)) {
      for (const e of n.elements) if (!ts.isOmittedExpression(e)) yield* this.targets(e, defaults);
    } else if (ts.isObjectLiteralExpression(n)) {
      for (const p of n.properties) {
        if (ts.isShorthandPropertyAssignment(p)) yield { target: p.name, defaults: p.objectAssignmentInitializer ? [...defaults, p.objectAssignmentInitializer] : defaults };
        else if (ts.isPropertyAssignment(p)) yield* this.targets(p.initializer, defaults);
        else if (ts.isSpreadAssignment(p)) yield* this.targets(p.expression, defaults);
      }
    } else if (ts.isBinaryExpression(n) && n.operatorToken.kind === ts.SyntaxKind.EqualsToken) {
      this.patternDefaults.add(n);
      yield* this.targets(n.left, [...defaults, n.right]);
    } else yield { target: n, defaults };
  }
  private register(n: ts.Node, parent: Context): void {
    let context = parent;
    if (isBody(n)) {
      const method = this.descriptor(n);
      const key = JSON.stringify([method.declaringClass, method.name, method.parameterTypes]);
      if (this.methodKeys.has(key)) method.declaringClass += `#declaration@${this.position(n)}`;
      this.methodKeys.add(JSON.stringify([method.declaringClass, method.name, method.parameterTypes]));
      context = { method, parameters: [], body: n.body, declaration: n, defaults: this.defaults(n) };
      this.contexts.set(n, context);
      this.functions.push(context);
      this.addMethod(method, this.module(n));
      n.parameters.forEach((parameter, index) => {
        const id = this.node('Parameter', { index, paramType: method.parameterTypes[index], method });
        context.parameters.push(id);
        this.bind(parameter.name, id, context);
      });
      context.returned = this.node('Return', { method, actualType: method.returnType });
    }
    // Declarations are indexed before bodies run, including captured variables and forward references.
    if (ts.isVariableDeclaration(n) || ts.isBindingElement(n)) {
      if (ts.isIdentifier(n.name)) this.local(n.name, context);
    } else if (ts.isPropertyDeclaration(n) && n.name) {
      const symbol = this.symbol(n.name);
      if (symbol && !this.symbols.has(symbol)) {
        const className = `${this.module(n)}.${this.name(n.parent) ?? 'anonymous'}`;
        const id = this.node('Field', { declaringClass: className, name: this.name(n), fieldType: this.type(n),
          isStatic: !!ts.getModifiers(n)?.some(m => m.kind === ts.SyntaxKind.StaticKeyword) });
        this.symbols.set(symbol, id);
      }
    }
    const assigned = ts.isBinaryExpression(n) && n.operatorToken.kind >= ts.SyntaxKind.FirstAssignment && n.operatorToken.kind <= ts.SyntaxKind.LastAssignment ? n.left :
      (ts.isPrefixUnaryExpression(n) || ts.isPostfixUnaryExpression(n)) &&
      (n.operator === ts.SyntaxKind.PlusPlusToken || n.operator === ts.SyntaxKind.MinusMinusToken) ? n.operand : undefined;
    const loop = ts.isForOfStatement(n) || ts.isForInStatement(n) ? n : undefined;
    const lhs = assigned ?? (loop && !ts.isVariableDeclarationList(loop.initializer) ? loop.initializer : undefined);
    if (lhs && !this.normalizedAssignments.has(n)) for (const { target } of this.targets(lhs)) {
      const symbol = ts.isIdentifier(target) ? this.assignmentSymbol(target) : this.referenceSymbol(target);
      if (!symbol) continue;
      const rhs = ts.isBinaryExpression(n) && n.operatorToken.kind === ts.SyntaxKind.EqualsToken ? n.right : undefined;
      let unwrapped = lhs;
      while (ts.isParenthesizedExpression(unwrapped)) unwrapped = unwrapped.expression;
      const write = ts.isForInStatement(n) ? 'never' :
        rhs && ts.isIdentifier(unwrapped) ? this.literalUndefined(rhs) ?? 'maybe' : 'maybe';
      const prior = this.writes.get(symbol);
      this.writes.set(symbol, prior && prior !== write ? 'maybe' : write);
      const previous = this.symbols.get(symbol);
      const formal = previous === undefined ? undefined : this.graph.nodes[previous];
      if (formal?.kind === 'Parameter' && ts.isIdentifier(target)) {
        // A formal denotes the incoming argument; a reassignment writes its binding.
        const binding = this.node('LocalVariable', { name: `@binding:${target.text}`, varType: formal.paramType, method: formal.method });
        this.edge(formal.id, binding, 'ASSIGN');
        this.symbols.set(symbol, binding);
      }
    }
    ts.forEachChild(n, child => this.register(child, context));
  }
  private bind(name: ts.BindingName, source: number, context: Context): void {
    if (ts.isIdentifier(name)) {
      const symbol = this.symbol(name);
      if (symbol) this.symbols.set(symbol, source);
    } else {
      for (const element of name.elements) if (ts.isBindingElement(element)) {
        const local = ts.isIdentifier(element.name) ? this.local(element.name, context) :
          this.node('LocalVariable', { name: element.name.getText(), varType: this.type(element), method: context.method });
        this.edge(source, local, 'ASSIGN');
        this.bind(element.name, local, context);
      }
    }
  }
  private local(n: ts.Node, context: Context): number {
    const symbol = this.symbol(n);
    if (symbol && this.argumentBindings.has(symbol)) return this.argumentBindings.get(symbol)!.id;
    if (symbol && this.symbols.has(symbol)) return this.symbols.get(symbol)!;
    const id = this.node('LocalVariable', { name: n.getText(), varType: this.type(n), method: context.method });
    if (symbol) this.symbols.set(symbol, id);
    return id;
  }
  private constant(kind: string, value?: unknown): number {
    const key = JSON.stringify([kind, Object.is(value, -0) ? '-0' : value]);
    let id = this.constants.get(key);
    if (id === undefined) { id = this.node(kind, value === undefined ? {} : { value }); this.constants.set(key, id); }
    return id;
  }
  private property(n: ts.PropertyAccessExpression | ts.ElementAccessExpression, context: Context): { slot: number; receiver: number } {
    const receiver = this.value(n.expression, context);
    const symbol = ts.isPropertyAccessExpression(n) ? this.symbol(n.name) : undefined;
    const declared = symbol && this.symbols.get(symbol);
    if (declared !== undefined) return { slot: declared, receiver };
    const name = ts.isPropertyAccessExpression(n) ? n.name.text :
      ts.isStringLiteralLike(n.argumentExpression) || ts.isNumericLiteral(n.argumentExpression)
        ? n.argumentExpression.text : `@computed:${this.value(n.argumentExpression, context)}`;
    const key = JSON.stringify([receiver, name]);
    let slot = this.properties.get(key);
    if (slot === undefined) {
      slot = this.node('LocalVariable', { name: `@property:${receiver}:${name}`, varType: this.type(n), method: context.method });
      this.properties.set(key, slot);
    }
    return { slot, receiver };
  }
  private assigned(n: ts.Expression, context: Context): number {
    return ts.isPropertyAccessExpression(n) || ts.isElementAccessExpression(n) ? this.property(n, context).slot : this.value(n, context);
  }
  private assignmentSymbol(n: ts.Identifier): ts.Symbol | undefined {
    return (ts.isShorthandPropertyAssignment(n.parent) && this.checker.getShorthandAssignmentValueSymbol(n.parent)) || this.symbol(n);
  }
  private assign(n: ts.Expression, value: number, context: Context, rhs?: ts.Expression, kind = 'ASSIGN'): void {
    while (ts.isParenthesizedExpression(n)) n = n.expression;
    for (const { target, defaults } of this.targets(n)) {
      const symbol = ts.isIdentifier(target) ? this.assignmentSymbol(target) : undefined;
      const unpacked = target !== n || kind !== 'ASSIGN';
      let incoming = value;
      if (defaults.length || (unpacked && symbol && this.argumentBindings.has(symbol))) {
        // An unpacked item's exact position/value is unknown; keep its container
        // and any pattern default as possible inputs, without overwriting the binding.
        incoming = this.node('LocalVariable', { name: `@item:${this.position(target)}`, varType: 'unknown', method: context.method });
        this.edge(value, incoming, kind);
        for (const init of defaults) {
          const fallback = this.possibleBindingEffects(this.argumentBindings, context, init, () => this.value(init, context));
          this.edge(fallback, incoming, 'ASSIGN');
          this.handledFallbacks.add(init);
        }
      }
      if (symbol && this.argumentBindings.has(symbol)) {
        this.argumentBindings.set(symbol, { id: incoming, state: !unpacked && rhs ? this.undefinedState(rhs, incoming) : 'maybe' });
        this.values.delete(target);
      } else {
        const location = symbol && this.symbols.get(symbol);
        this.edge(incoming, location ?? this.assigned(target, context),
          ts.isPropertyAccessExpression(target) || ts.isElementAccessExpression(target) ? 'FIELD_STORE' : kind);
      }
    }
  }
  private target(n: ts.CallExpression | ts.NewExpression): { method: Method; context?: Context } {
    let declaration = this.checker.getResolvedSignature(n)?.declaration;
    const expr = ts.isPropertyAccessExpression(n.expression) ? n.expression.name : n.expression;
    const symbol = this.symbol(expr);
    // Overload signatures resolve to their implementation body when it is available.
    const implementation = symbol?.declarations?.find(isBody);
    if (implementation) declaration = implementation;
    const context = declaration ? this.contexts.get(declaration) : undefined;
    if (context) return { method: context.method, context };
    if (declaration && !ts.isJSDocSignature(declaration)) return { method: this.descriptor(declaration) };
    return { method: {
      declaringClass: '<dynamic>',
      name: ts.isPropertyAccessExpression(n.expression) ? n.expression.name.text : n.expression.getText(),
      parameterTypes: (n.arguments ?? []).map(a => this.type(a)), returnType: this.type(n),
    } };
  }
  private referenceSymbol(n: ts.Expression): ts.Symbol | undefined {
    if (ts.isIdentifier(n)) return this.symbol(n);
    if (ts.isPropertyAccessExpression(n)) return this.symbol(n.name);
    if (ts.isElementAccessExpression(n) && ts.isStringLiteralLike(n.argumentExpression))
      return this.checker.getPropertyOfType(this.checker.getTypeAtLocation(n.expression), n.argumentExpression.text);
    return undefined;
  }
  // Follow simple declaration aliases to their writes, without assuming any
  // execution order. A cycle has no independent known value.
  private writeEvidence(symbol: ts.Symbol): UndefinedState | undefined {
    const path = new Set<ts.Symbol>();
    let current: ts.Symbol | undefined = symbol;
    while (current && !this.writeCache.has(current) && !path.has(current)) {
      path.add(current);
      const declaration: ts.Declaration | undefined = current.valueDeclaration;
      let init: ts.Expression | undefined = declaration && ts.isVariableDeclaration(declaration) ? declaration.initializer : undefined;
      while (init && ts.isParenthesizedExpression(init)) init = init.expression;
      current = init && this.referenceSymbol(init);
    }
    let state = current && (path.has(current) ? 'maybe' as const : this.writeCache.get(current));
    for (const entry of [...path].reverse()) {
      const write = this.writes.get(entry);
      if (write) state = state && state !== write ? 'maybe' : write;
      this.writeCache.set(entry, state);
    }
    return state;
  }
  private literalUndefined(n: ts.Expression): UndefinedState | undefined {
    if (ts.isParenthesizedExpression(n)) return this.literalUndefined(n.expression);
    if (ts.isVoidExpression(n) || ts.isOmittedExpression(n) ||
        ts.isIdentifier(n) && n.text === 'undefined' && !this.symbol(n)?.declarations?.length) return 'always';
    if (ts.isLiteralExpression(n) || n.kind === ts.SyntaxKind.NullKeyword ||
        n.kind === ts.SyntaxKind.TrueKeyword || n.kind === ts.SyntaxKind.FalseKeyword) return 'never';
    return undefined;
  }
  private undefinedState(n: ts.Expression | undefined, value?: number): UndefinedState {
    if (!n) return 'always';
    if (ts.isParenthesizedExpression(n)) return this.undefinedState(n.expression, value);
    if (ts.isBinaryExpression(n) && (n.operatorToken.kind === ts.SyntaxKind.EqualsToken || n.operatorToken.kind === ts.SyntaxKind.CommaToken)) return this.undefinedState(n.right, value);
    const literal = this.literalUndefined(n);
    if (literal) return literal;
    if (value !== undefined) {
      if (this.undefinedValues.has(value)) return 'always';
      if (this.graph.nodes[value]!.kind.endsWith('Constant')) return 'never';
    }
    const symbol = this.referenceSymbol(n);
    const bound = symbol && this.argumentBindings.get(symbol);
    if (bound && bound.id === value) return bound.state;
    const type = this.checker.getTypeAtLocation(n);
    const members = type.isUnion() ? type.types : [type];
    const state: UndefinedState = members.every(t => !!(t.flags & ts.TypeFlags.Undefined)) ? 'always' :
      members.some(t => !!(t.flags & (ts.TypeFlags.Undefined | ts.TypeFlags.Void | ts.TypeFlags.Any | ts.TypeFlags.Unknown | ts.TypeFlags.TypeParameter))) ? 'maybe' : 'never';
    // In emitted JS the checker can retain a binding's initializer type even at
    // a read after an incompatible write. A binding represents all its writes.
    const write = symbol && this.writeEvidence(symbol);
    return write && write !== state ? 'maybe' : state;
  }
  private possibleBindingEffects<T>(bindings: Map<ts.Symbol, ArgumentValue>, context: Context, n: ts.Node, evaluate: () => T): T {
    const prior = this.argumentBindings;
    const branch = new Map(bindings);
    this.argumentBindings = branch;
    try {
      const result = evaluate();
      for (const [symbol, possible] of branch) {
        const before = bindings.get(symbol);
        if (!before || before === possible) continue;
        let id = before.id;
        if (id !== possible.id) {
          id = this.node('LocalVariable', { name: `@default-write:${symbol.name}@${this.position(n)}`, varType: 'unknown', method: context.method });
          this.edge(before.id, id, 'ASSIGN');
          this.edge(possible.id, id, 'ASSIGN');
        }
        bindings.set(symbol, { id, state: before.state === possible.state ? before.state : 'maybe' });
      }
      return result;
    } finally { this.argumentBindings = prior; }
  }
  private defaultValue(parameter: ts.ParameterDeclaration, initializer: ts.Expression, target: Context, bindings: Map<ts.Symbol, ArgumentValue>): ArgumentValue {
    this.evaluatedDefaults.add(parameter);
    if (this.activeDefaults.has(parameter)) {
      // Recursive default evaluation has no finite expansion. Keep its result unknown;
      // the enclosing call still records the recursive target and parameter flow.
      return { id: this.node('LocalVariable', { name: `@recursive-default:${this.position(parameter)}`, varType: 'unknown', method: target.method }), state: 'maybe' };
    }
    const savedValues = this.values;
    const savedBindings = this.argumentBindings;
    this.values = new Map();
    this.argumentBindings = bindings;
    this.activeDefaults.add(parameter);
    try {
      const result = this.value(initializer, target);
      this.walk(initializer, target);
      return { id: result, state: this.undefinedState(initializer, result) };
    } finally {
      this.activeDefaults.delete(parameter);
      this.values = savedValues;
      this.argumentBindings = savedBindings;
    }
  }
  private arguments(n: ts.CallExpression | ts.NewExpression, caller: Context, target?: Context): number[] {
    const expressions = [...(n.arguments ?? [])];
    // A fixed literal spread has a known arity; an unknown spread is kept as a
    // value dependency and is not mistaken for a single ordinary actual argument.
    const expanded: ts.Expression[] = [];
    let unknownSpread = -1;
    for (const expression of expressions) {
      if (ts.isSpreadElement(expression) && ts.isArrayLiteralExpression(expression.expression) &&
          expression.expression.elements.every(e => !ts.isSpreadElement(e))) {
        expanded.push(...expression.expression.elements);
      } else {
        if (ts.isSpreadElement(expression) && unknownSpread < 0) unknownSpread = expanded.length;
        expanded.push(expression);
      }
    }
    // A later argument may write a binding read by an earlier argument.
    const states: UndefinedState[] = [];
    const args = expanded.map(a => {
      const id = this.value(a, caller);
      if (target?.declaration) states.push(this.undefinedState(a, id));
      return id;
    });
    const supplied = [...args];
    if (!target?.declaration) return args;
    const bindings = new Map(this.argumentBindings);
    for (const [index, parameter] of target.declaration.parameters.entries()) {
      if (parameter.dotDotDotToken && unknownSpread < 0) break;
      let actual = args[index];
      const uncertain = unknownSpread >= 0 && index >= unknownSpread;
      const initializer = target.defaults?.get(index);
      let state: UndefinedState = uncertain ? 'maybe' : states[index] ?? 'always';
      if (uncertain) {
        actual = this.node('LocalVariable', { name: `@spread-argument:${index}@${this.position(n)}`, varType: 'unknown', method: caller.method });
        supplied.slice(unknownSpread).forEach(value => this.edge(value, actual!, 'ASSIGN'));
      }
      if (initializer && state !== 'never') {
        const fallback = state === 'always' ? this.defaultValue(parameter, initializer, target, bindings) :
          this.possibleBindingEffects(bindings, target, initializer, () => this.defaultValue(parameter, initializer, target, this.argumentBindings));
        if (state === 'always') { actual = fallback.id; state = fallback.state; }
        else {
          const joined = this.node('LocalVariable', {
            name: `@argument:${index}:undefined-select@${this.position(n)}`, varType: 'unknown', method: caller.method,
          });
          if (actual !== undefined) this.edge(actual, joined, 'ASSIGN');
          this.edge(fallback.id, joined, 'ASSIGN');
          actual = joined;
          // The supplied branch of the selection is necessarily defined.
          state = fallback.state === 'never' ? 'never' : 'maybe';
        }
      } else if (actual === undefined) {
        actual = this.node('LocalVariable', { name: `undefined@argument:${index}@${this.position(n)}`, varType: 'undefined', method: caller.method });
        this.undefinedValues.add(actual);
      }
      args[index] = actual!;
      if (ts.isIdentifier(parameter.name)) {
        const symbol = this.symbol(parameter.name);
        if (symbol) bindings.set(symbol, { id: actual!, state });
      }
    }
    // A later default can assign an earlier parameter. These are the effective
    // explicit arguments entering the body after the parameter prologue runs.
    target.declaration.parameters.forEach((parameter, index) => {
      const symbol = ts.isIdentifier(parameter.name) ? this.symbol(parameter.name) : undefined;
      if (symbol && bindings.has(symbol)) args[index] = bindings.get(symbol)!.id;
    });
    return args;
  }
  private value(n: ts.Expression, context: Context): number {
    const cached = this.values.get(n);
    if (cached !== undefined) return cached;
    let id: number;
    if (ts.isStringLiteralLike(n)) id = this.constant('StringConstant', n.text);
    else if (ts.isNumericLiteral(n) || (ts.isPrefixUnaryExpression(n) && ts.isNumericLiteral(n.operand) && (n.operator === ts.SyntaxKind.MinusToken || n.operator === ts.SyntaxKind.PlusToken))) {
      const value = ts.isNumericLiteral(n) ? Number(n.text) : Number((n.operand as ts.NumericLiteral).text) * (n.operator === ts.SyntaxKind.MinusToken ? -1 : 1);
      if (!Number.isFinite(value)) throw new Error(`Non-finite numeric literal at ${this.module(n)}:${this.position(n)}`);
      id = this.constant(!Object.is(value, -0) && Number.isInteger(value) && value >= -2147483648 && value <= 2147483647 ? 'IntConstant' : 'DoubleConstant', value);
    } else if (n.kind === ts.SyntaxKind.TrueKeyword || n.kind === ts.SyntaxKind.FalseKeyword) id = this.constant('BooleanConstant', n.kind === ts.SyntaxKind.TrueKeyword);
    else if (n.kind === ts.SyntaxKind.NullKeyword) id = this.constant('NullConstant');
    else if (ts.isIdentifier(n)) id = this.local(n, context);
    else if (ts.isParenthesizedExpression(n) || ts.isAsExpression(n) || ts.isTypeAssertionExpression(n) || ts.isNonNullExpression(n) || ts.isSatisfiesExpression(n)) id = this.value(n.expression, context);
    else if (ts.isBinaryExpression(n) && n.operatorToken.kind === ts.SyntaxKind.EqualsToken) {
      id = this.value(n.right, context);
      this.assign(n.left, id, context, n.right);
    } else if (ts.isBinaryExpression(n) && n.operatorToken.kind === ts.SyntaxKind.CommaToken) {
      this.value(n.left, context);
      id = this.value(n.right, context);
    }
    else if (ts.isCallExpression(n) || ts.isNewExpression(n)) {
      const target = this.target(n);
      const args = this.arguments(n, context, target.context);
      const receiver = ts.isPropertyAccessExpression(n.expression) || ts.isElementAccessExpression(n.expression)
        ? this.value(n.expression.expression, context) : undefined;
      // Evaluating an immediately invoked expression also records any calls producing its target.
      if (!ts.isIdentifier(n.expression) && !ts.isPropertyAccessExpression(n.expression) && !ts.isElementAccessExpression(n.expression)) this.value(n.expression, context);
      id = this.node('CallSite', { caller: context.method, callee: target.method,
        line: n.getSourceFile().getLineAndCharacterOfPosition(n.getStart()).line + 1,
        ...(receiver === undefined ? {} : { receiver }), arguments: args });
      this.edge(id, id, 'CALL', { isVirtual: receiver !== undefined, isDynamic: !target.context });
      args.forEach((argument, index) => {
        this.edge(argument, id, 'PARAMETER_PASS');
        const rest = target.context?.declaration?.parameters.findIndex(p => !!p.dotDotDotToken) ?? -1;
        const parameterIndex = rest >= 0 && index >= rest ? rest : index;
        const parameter = target.context?.parameters[parameterIndex];
        if (parameter !== undefined) this.edge(argument, parameter, 'PARAMETER_PASS');
      });
      if (target.context?.returned !== undefined) this.edge(target.context.returned, id, 'RETURN_VALUE');
    } else if (ts.isPropertyAccessExpression(n) || ts.isElementAccessExpression(n)) {
      const { slot, receiver } = this.property(n, context);
      // A location and a read from that location are different values. In x.p=x,
      // loading x.p must not share endpoints with storing x into the property.
      id = this.node('LocalVariable', { name: `@read:${n.getText()}@${this.position(n)}`, varType: this.type(n), method: context.method });
      this.edge(slot, id, 'FIELD_LOAD');
      this.edge(receiver, id, 'FIELD_LOAD');
    } else {
      // Keep computed values distinct from operands. This records dependencies, never claims a fold.
      id = this.node('LocalVariable', { name: `@${this.position(n)}`, varType: this.type(n), method: context.method });
      this.values.set(n, id);
      if (!isBody(n)) ts.forEachChild(n, child => {
        if (ts.isExpression(child)) this.edge(this.value(child, context), id, 'ASSIGN');
        else if (ts.isPropertyAssignment(child)) this.edge(this.value(child.initializer, context), id, 'ASSIGN');
        else if (ts.isShorthandPropertyAssignment(child)) this.edge(this.value(child.name, context), id, 'ASSIGN');
        else if (ts.isTemplateSpan(child)) this.edge(this.value(child.expression, context), id, 'ASSIGN');
      });
    }
    if (this.literalUndefined(n) === 'always') this.undefinedValues.add(id);
    this.values.set(n, id);
    return id;
  }
  private walk(n: ts.Node, context: Context): void {
    if (this.normalizedGuards.has(n) || ts.isBinaryExpression(n) && this.patternDefaults.has(n) || ts.isExpression(n) && this.handledFallbacks.has(n)) return;
    if (isBody(n)) return; // Each body is analyzed exactly once in its own lexical scope.
    if (ts.isTypeNode(n) || ts.isImportDeclaration(n) || ts.isExportDeclaration(n)) return;
    if (ts.isForOfStatement(n) || ts.isForInStatement(n)) {
      const kind = ts.isForOfStatement(n) ? 'ARRAY_LOAD' : 'ASSIGN';
      const iterable = this.value(n.expression, context);
      if (ts.isVariableDeclarationList(n.initializer)) {
        n.initializer.declarations.forEach(declaration => {
          if (ts.isIdentifier(declaration.name)) this.edge(iterable, this.local(declaration.name, context), kind);
          else this.bind(declaration.name, iterable, context);
        });
      } else this.assign(n.initializer, iterable, context, undefined, kind);
    } else if (ts.isVariableDeclaration(n) && n.initializer) {
      const value = this.value(n.initializer, context);
      if (ts.isIdentifier(n.name)) this.edge(value, this.local(n.name, context), 'ASSIGN');
      else this.bind(n.name, value, context);
    } else if (ts.isPropertyDeclaration(n) && n.initializer) {
      const symbol = this.symbol(n.name);
      const field = symbol && this.symbols.get(symbol);
      if (field !== undefined) this.edge(this.value(n.initializer, context), field, 'FIELD_STORE');
    } else if (ts.isReturnStatement(n) && n.expression && context.returned !== undefined) {
      this.edge(this.value(n.expression, context), context.returned, 'RETURN_VALUE');
    } else if (ts.isBinaryExpression(n) && n.operatorToken.kind >= ts.SyntaxKind.FirstAssignment && n.operatorToken.kind <= ts.SyntaxKind.LastAssignment) {
      const assigned = this.value(n, context); // '=' already stored its RHS exactly once, including pattern defaults.
      if (n.operatorToken.kind !== ts.SyntaxKind.EqualsToken) this.assign(n.left, assigned, context);
    } else if (ts.isCallExpression(n) || ts.isNewExpression(n)) this.value(n, context);
    ts.forEachChild(n, child => this.walk(child, context));
  }
  run(): SourceGraph {
    if (!this.sources.length) throw new Error('No implementation source files were loaded.');
    for (const source of this.sources) {
      const method: Method = { declaringClass: this.module(source), name: '<module>', parameterTypes: [], returnType: 'void' };
      const context = { method, parameters: [], body: source };
      this.contexts.set(source, context);
      this.addMethod(method, this.module(source));
      this.functions.push(context);
      this.register(source, context);
    }
    for (const context of this.functions) {
      if (ts.isExpression(context.body)) {
        if (context.returned !== undefined) this.edge(this.value(context.body, context), context.returned, 'RETURN_VALUE');
      }
      this.walk(context.body, context);
    }
    // Exported functions and callbacks can be entered by code outside this graph.
    // Keep unevaluated default-expression code and its possible argument flow,
    // without inventing a source call site or an unconditional assignment edge.
    for (const context of this.functions) {
      const bindings = new Map<ts.Symbol, ArgumentValue>();
      context.declaration?.parameters.forEach((parameter, index) => {
        const initializer = context.defaults?.get(index);
        if (initializer && !this.evaluatedDefaults.has(parameter)) {
          const fallback = this.defaultValue(parameter, initializer, context, bindings);
          this.edge(fallback.id, context.parameters[index]!, 'PARAMETER_PASS');
        }
        if (ts.isIdentifier(parameter.name)) {
          const symbol = this.symbol(parameter.name);
          if (symbol) bindings.set(symbol, { id: context.parameters[index]!, state: 'maybe' });
        }
      });
      context.declaration?.parameters.forEach((parameter, index) => {
        const symbol = ts.isIdentifier(parameter.name) ? this.symbol(parameter.name) : undefined;
        const updated = symbol && bindings.get(symbol)?.id;
        if (updated !== undefined && updated !== context.parameters[index]) this.edge(updated, context.parameters[index]!, 'PARAMETER_PASS');
      });
    }
    return this.graph;
  }
}
