import ts from 'typescript';
import path from 'node:path';
import fs from 'node:fs';
import { encodeArtifactString } from './strings.js';

export interface Method {
  declaringClass: string;
  name: string;
  parameterTypes: readonly string[];
  returnType: string;
}
export interface GraphNode {
  id: number;
  kind: string;
  [key: string]: unknown;
}
// Higher ranks retain the more specific operation when v3 cannot represent both.
const edgePriority = { ASSIGN: 0, ARRAY_LOAD: 1, FIELD_LOAD: 2, FIELD_STORE: 3, RETURN_VALUE: 4, PARAMETER_PASS: 5, CALL: 6 } as const;
type EdgeKind = keyof typeof edgePriority;
export interface GraphEdge {
  from: number;
  to: number;
  kind: EdgeKind;
  isVirtual?: boolean;
  isDynamic?: boolean;
}
function edgeRank(edge: GraphEdge): number {
  return edgePriority[edge.kind] * 4 + (edge.isDynamic ? 2 : 0) + (edge.isVirtual ? 1 : 0);
}
function edgeDescription(edge: GraphEdge): string {
  return edge.kind + (edge.isDynamic ? ' dynamic' : '') + (edge.isVirtual ? ' virtual' : '');
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
interface ReceiverOwner { owner: ts.Node; instance: boolean }
type FunctionBody = ts.FunctionLikeDeclaration & { body: ts.ConciseBody };
interface Context { method: Method; parameters: number[]; restIndex?: number; returned?: number; body: ts.Node; declaration?: FunctionBody; defaults?: Map<number, ts.Expression> }
const isBody = (n: ts.Node): n is FunctionBody => ts.isFunctionLike(n) && 'body' in n && !!n.body;
const artifactExtension = /\.(?:[cm]?js)$/i;
const formatDiagnostics = (diagnostics: readonly ts.Diagnostic[]) => ts.formatDiagnostics(diagnostics, {
  getCanonicalFileName: f => f,
  getCurrentDirectory: () => process.cwd(),
  getNewLine: () => '\n',
});

/** Read emitted JavaScript only. Compilation is deliberately an explicit upstream step. */
export function project(input: string, warn: (message: string) => void = console.error) {
  const absolute = path.resolve(input);
  const stat = fs.statSync(absolute);
  const root = stat.isDirectory() ? absolute : path.dirname(absolute);
  const files: string[] = [];
  const collect = (directory: string): void => {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name, 'en'))) {
      const file = path.join(directory, entry.name);
      // Do not traverse symlinks, dependencies or Next's compiler cache. Include .next,
      // dist and build: these are precisely the directories this frontend consumes.
      if (entry.isDirectory() && !['node_modules', '.git'].includes(entry.name) &&
          !(entry.name === 'cache' && path.basename(directory) === '.next')) collect(file);
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
  // TypeScript uses forward slashes for source and resolution paths, including on Windows.
  const roots = files.map(file => ts.sys.resolvePath(file));
  const key = (file: string) => host.getCanonicalFileName(ts.sys.resolvePath(file));
  const selected = new Set(roots.map(key));
  const skipped = new Map<string, ts.Diagnostic[]>();
  const realRoot = fs.realpathSync(root);
  const libraryDir = key(path.dirname(host.getDefaultLibFileName(options)));
  const permitted = (file: string): boolean => {
    if (skipped.has(key(file))) return false;
    if (selected.has(key(file))) return true;
    if (stat.isDirectory() || !artifactExtension.test(file)) return false;
    const relative = path.relative(root, file);
    const parts = relative.split(path.sep);
    if (path.isAbsolute(relative) || parts[0] === '..' || parts.includes('node_modules') || parts.includes('.git') ||
        parts.some((part, i) => part === 'cache' && (i ? parts[i - 1] : path.basename(root)) === '.next')) return false;
    return host.fileExists(file) && host.getCanonicalFileName(path.relative(realRoot, fs.realpathSync(file))) === host.getCanonicalFileName(relative);
  };
  // TypeScript normally tries original TS or declaration files before the
  // emitted JS named by an import. Only artifact files are resolution targets.
  const resolver = { ...host, fileExists: (file: string) =>
    host.fileExists(file) && (path.basename(file) === 'package.json' || permitted(file)) };
  host.resolveModuleNames = (names, containingFile) => names.map(name => {
    const resolved = ts.resolveModuleName(name, containingFile, options, resolver).resolvedModule;
    if (!resolved || !permitted(resolved.resolvedFileName)) return undefined;
    selected.add(key(resolved.resolvedFileName));
    return resolved;
  });
  // Triple-slash references bypass module resolution. Permit only selected
  // artifacts and TypeScript's own library declarations to enter the program.
  const read = host.getSourceFile.bind(host);
  host.getSourceFile = (file, ...args) => {
    const allowed = selected.has(key(file)) || (key(path.dirname(file)) === libraryDir && /^lib(?:\..+)?\.d\.ts$/i.test(path.basename(file)));
    return allowed && !skipped.has(key(file)) ? read(file, ...args) : undefined;
  };
  const result = (program: ts.Program) => ({
    program, root, sources: program.getSourceFiles().filter(file => selected.has(key(file.fileName))),
  });
  const program = ts.createProgram(roots, options, host);
  const optionErrors = program.getOptionsDiagnostics();
  if (optionErrors.length) throw new Error(formatDiagnostics(optionErrors));
  const syntactic = program.getSyntacticDiagnostics();
  if (!syntactic.length) return result(program);

  for (const diagnostic of syntactic) {
    const key = diagnostic.file && host.getCanonicalFileName(ts.sys.resolvePath(diagnostic.file.fileName));
    if (!key || !selected.has(key)) throw new Error(formatDiagnostics(syntactic));
    const errors = skipped.get(key);
    if (errors) errors.push(diagnostic);
    else skipped.set(key, [diagnostic]);
  }
  const valid = roots.filter(file => !skipped.has(host.getCanonicalFileName(file)));
  if (!valid.length) throw new Error(`No parseable JavaScript artifacts remain.
${formatDiagnostics(syntactic)}`);
  warn(`Warning: skipped ${skipped.size} unparsable JavaScript artifact(s) out of ${selected.size}.`);
  for (const [key, errors] of skipped) {
    selected.delete(key);
    warn(formatDiagnostics(errors.slice(0, 1)).trimEnd());
  }
  return result(ts.createProgram(valid, options, host));
}

// Bundlers emit (0, module.fn)(...) to call fn without binding module as `this`.
// Use the selected function for lookup and naming only; evaluate the original expression.
function unparenthesized(value: ts.Expression): ts.Expression {
  while (ts.isParenthesizedExpression(value)) value = value.expression;
  return value;
}

function calledExpression(value: ts.Expression): ts.Expression {
  value = unparenthesized(value);
  while (ts.isBinaryExpression(value) && value.operatorToken.kind === ts.SyntaxKind.CommaToken) value = unparenthesized(value.right);
  return value;
}

/** Preorder traversal with the same child order as forEachChild; a visitor can prune a subtree. */
function visitTree(root: ts.Node, context: Context, visit: (n: ts.Node, scope: Context) => Context | undefined): void {
  const nodes = [root], scopes = [context], children: ts.Node[] = [];
  while (nodes.length) {
    const n = nodes.pop()!;
    const next = visit(n, scopes.pop()!);
    if (!next) continue;
    children.length = 0;
    ts.forEachChild(n, child => { children.push(child); });
    for (let i = children.length - 1; i >= 0; i--) {
      nodes.push(children[i]!);
      scopes.push(next);
    }
  }
}

/** A flow-insensitive value graph of emitted JS. No artifacts or build scripts are executed. */
export function analyze(input: string, warn: (message: string) => void = console.warn): SourceGraph {
  const { program, root, sources } = project(input, warn);
  return new Analyzer(program, root, sources, warn).run();
}

class Analyzer {
  private checker: ts.TypeChecker;
  private graph: SourceGraph = { version: 1, methods: [], nodes: [], edges: [], classOrigins: [] };
  private contexts = new Map<ts.Node, Context>();
  private descriptors = new Map<ts.SignatureDeclaration, Method>();
  private implicitConstructors = new Map<ts.ClassLikeDeclaration, Method>();
  private encodedText = new Map<string, string>();
  private encodedMethods = new WeakMap<Method, Method>();
  private droppedEdges = new Map<number, Set<string>>();
  private functions: Context[] = [];
  private values = new Map<ts.Node, number>();
  private symbols = new Map<ts.Symbol, number>();
  private constants = new Map<string, number>();
  private properties = new Map<string, number>();
  private readSlots = new Map<number, number>();
  private fieldBindings = new Map<number, number>();
  private instanceReceivers = new WeakMap<ts.Node, number>();
  private otherReceivers = new WeakMap<ts.Node, number>();
  private edgePairs = new Map<string, number>();
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
  constructor(private program: ts.Program, private root: string, sources: ts.SourceFile[], private warn: (message: string) => void) {
    this.checker = program.getTypeChecker();
    this.sources = sources.sort((a, b) => a.fileName.localeCompare(b.fileName, 'en'));
  }
  private module(n: ts.Node): string {
    const source = n.getSourceFile();
    if (source.isDeclarationFile && this.program.isSourceFileDefaultLibrary(source)) {
      // Global library symbols and namespaces may be declared or augmented in
      // different lib files according to the compiler target and version.
      const names: string[] = [];
      for (let parent = n.parent; parent && !ts.isSourceFile(parent); parent = parent.parent) {
        if (ts.isModuleDeclaration(parent) && !(parent.flags & ts.NodeFlags.GlobalAugmentation))
          names.push(parent.name.text);
        if (ts.isTypeLiteralNode(parent)) {
          const declaration = parent.parent;
          if (ts.isVariableDeclaration(declaration) || ts.isTypeAliasDeclaration(declaration) || ts.isPropertySignature(declaration))
            names.push(this.name(declaration)!);
        }
      }
      return ['external:typescript/lib', ...names.reverse()].join('.');
    }
    return path.relative(this.root, source.fileName).split(path.sep).join('/');
  }
  private position(n: ts.Node): string {
    const p = n.getSourceFile().getLineAndCharacterOfPosition(n.getStart());
    return `${p.line + 1}:${p.character + 1}`;
  }
  private type(n: ts.Node, type = this.checker.getTypeAtLocation(n)): string {
    return this.checker.typeToString(type, n,
      ts.TypeFormatFlags.NoTruncation | ts.TypeFormatFlags.UseAliasDefinedOutsideCurrentScope);
  }
  private symbol(n: ts.Node): ts.Symbol | undefined {
    // A shorthand name denotes both a property and the lexical value it reads.
    const shorthand = ts.isIdentifier(n) && ts.isShorthandPropertyAssignment(n.parent) && n.parent.name === n
      ? this.checker.getShorthandAssignmentValueSymbol(n.parent) : undefined;
    let symbol = shorthand ?? this.checker.getSymbolAtLocation(n);
    if (symbol && symbol.flags & ts.SymbolFlags.Alias) symbol = this.checker.getAliasedSymbol(symbol);
    return symbol;
  }
  private text(raw: string): string {
    let encoded = this.encodedText.get(raw);
    if (encoded === undefined) {
      encoded = encodeArtifactString(raw);
      this.encodedText.set(raw, encoded);
    }
    return encoded;
  }
  // Contexts keep raw descriptors for name composition and symbol lookup. The
  // graph shares an immutable, encoded descriptor across definitions and uses.
  private graphMethod(raw: Method): Method {
    let method = this.encodedMethods.get(raw);
    if (!method) {
      method = Object.freeze({ declaringClass: this.text(raw.declaringClass), name: this.text(raw.name),
        parameterTypes: Object.freeze(raw.parameterTypes.map(t => this.text(t))), returnType: this.text(raw.returnType) });
      this.encodedMethods.set(raw, method);
    }
    return method;
  }
  private node(kind: string, fields: Record<string, string | number | boolean | undefined | Method | number[]>): number {
    const id = this.graph.nodes.length;
    for (const [key, value] of Object.entries(fields)) {
      if (typeof value === 'string') fields[key] = this.text(value);
      else if (typeof value === 'object' && !Array.isArray(value)) fields[key] = this.graphMethod(value);
    }
    this.graph.nodes.push({ id, kind, ...fields });
    return id;
  }
  private edge(from: number, to: number, kind: EdgeKind, extra: Pick<GraphEdge, 'isVirtual' | 'isDynamic'> = {}): void {
    const key = `${from}:${to}`;
    const index = this.edgePairs.get(key);
    if (index !== undefined) {
      const previous = this.graph.edges[index]!;
      if (previous.kind !== kind || !!previous.isVirtual !== !!extra.isVirtual || !!previous.isDynamic !== !!extra.isDynamic) {
        const candidate = { from, to, kind, ...extra };
        const replace = edgeRank(candidate) > edgeRank(previous);
        const discarded = replace ? previous : candidate;
        let dropped = this.droppedEdges.get(index);
        if (!dropped) this.droppedEdges.set(index, dropped = new Set());
        dropped.add(edgeDescription(discarded));
        if (replace) this.graph.edges[index] = candidate;
      }
      return; // v3 can encode only one relationship per ordered endpoint pair.
    }
    const edge = { from, to, kind, ...extra };
    this.edgePairs.set(key, this.graph.edges.length);
    this.graph.edges.push(edge);
  }
  private addMethod(method: Method, origin: string): void {
    const encoded = this.graphMethod(method);
    this.graph.methods.push(encoded);
    if (!this.origins.has(method.declaringClass)) {
      this.origins.add(method.declaringClass);
      this.graph.classOrigins.push({ className: encoded.declaringClass, origin: this.text(origin) });
    }
  }
  private name(n: ts.Node & { name?: ts.DeclarationName }): string | undefined {
    return n.name?.getText().replace(/^['"]|['"]$/g, '');
  }
  private classOwner(n: ts.ClassLikeDeclaration | ts.InterfaceDeclaration): string {
    return `${this.module(n)}.${this.name(n) ?? `anonymous@${this.position(n)}`}`;
  }
  private descriptor(n: ts.SignatureDeclaration): Method {
    const cached = this.descriptors.get(n);
    if (cached) return cached;
    let owner = this.module(n);
    let name = ts.isConstructorDeclaration(n) || ts.isConstructSignatureDeclaration(n) ? 'constructor' : this.name(n);
    if (!name && (ts.isVariableDeclaration(n.parent) || ts.isPropertyAssignment(n.parent) || ts.isPropertyDeclaration(n.parent))) {
      name = this.name(n.parent);
    }
    name ??= `anonymous@${this.position(n)}`;
    let parent = n.parent;
    while (parent && !ts.isSourceFile(parent)) {
      if (ts.isClassLike(parent) || ts.isInterfaceDeclaration(parent)) {
        owner = this.classOwner(parent);
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
    const method: Method = {
      declaringClass: owner, name,
      parameterTypes: n.parameters.map(p => this.type(p)),
      returnType: signature ? this.type(n, signature.getReturnType()) : 'unknown',
    };
    this.descriptors.set(n, method);
    return method;
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
  private registerMethod(method: Method, declaration: ts.Node): void {
    const key = JSON.stringify([method.declaringClass, method.name, method.parameterTypes]);
    if (this.methodKeys.has(key)) method.declaringClass += `#declaration@${this.position(declaration)}`;
    this.methodKeys.add(JSON.stringify([method.declaringClass, method.name, method.parameterTypes]));
    this.addMethod(method, this.module(declaration));
  }
  private register(n: ts.Node, parent: Context): Context {
    let context = parent;
    if (isBody(n)) {
      const method = this.descriptor(n);
      context = { method, parameters: [], body: n.body, declaration: n, defaults: this.defaults(n) };
      this.contexts.set(n, context);
      this.functions.push(context);
      this.registerMethod(method, n);
      n.parameters.forEach((parameter, index) => {
        if (parameter.dotDotDotToken) context.restIndex = index;
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
        const className = this.classOwner(n.parent);
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
      const symbol = this.referenceSymbol(target);
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
        const binding = this.node('LocalVariable', { name: `@binding:${target.text}` });
        // These fields already crossed the encoding boundary on the formal node.
        Object.assign(this.graph.nodes[binding]!, { varType: formal.paramType, method: formal.method });
        this.edge(formal.id, binding, 'ASSIGN');
        this.symbols.set(symbol, binding);
      }
    }
    return context;
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
  private constant(kind: string, value?: string | number | boolean): number {
    const key = JSON.stringify([kind, Object.is(value, -0) ? '-0' : value]);
    let id = this.constants.get(key);
    if (id === undefined) { id = this.node(kind, value === undefined ? {} : { value }); this.constants.set(key, id); }
    return id;
  }
  private receiverOwner(n: ts.Expression): ReceiverOwner | undefined {
    while (ts.isParenthesizedExpression(n)) n = n.expression;
    if (ts.isIdentifier(n)) {
      const declaration = this.symbol(n)?.valueDeclaration;
      if (declaration && ts.isClassLike(declaration)) return { owner: declaration, instance: false };
      if (declaration && ts.isVariableDeclaration(declaration) && ts.isVariableDeclarationList(declaration.parent) &&
          declaration.parent.flags & ts.NodeFlags.Const && declaration.initializer && ts.isClassExpression(declaration.initializer))
        return { owner: declaration.initializer, instance: false };
    }
    if (n.kind !== ts.SyntaxKind.ThisKeyword) return undefined;
    let child: ts.Node = n;
    for (let scope: ts.Node = n.parent; scope; child = scope, scope = scope.parent) {
      if (ts.isClassStaticBlockDeclaration(scope)) return { owner: scope.parent, instance: false };
      const field = ts.isPropertyDeclaration(scope) && child === scope.initializer;
      const body = isBody(scope) && !ts.isArrowFunction(scope) && (child === scope.body || ts.isParameter(child));
      if (field || body) {
        if (ts.isClassLike(scope.parent)) return { owner: scope.parent,
          instance: !(ts.getCombinedModifierFlags(scope as ts.Declaration) & ts.ModifierFlags.Static) };
        return { owner: scope, instance: false };
      }
      if (ts.isSourceFile(scope)) return { owner: scope, instance: false };
    }
    return undefined;
  }
  private location(value: number, owner: ReceiverOwner | undefined): number {
    // Resolved identifiers already use their binding's value id. Each read of a
    // nested property has a fresh value id, but refers to the same underlying slot.
    const slot = this.readSlots.get(value);
    if (slot !== undefined) return slot;
    if (!owner) return value;
    const receivers = owner.instance ? this.instanceReceivers : this.otherReceivers;
    const existing = receivers.get(owner.owner);
    if (existing !== undefined) return existing;
    receivers.set(owner.owner, value);
    return value;
  }
  private property(n: ts.PropertyAccessExpression | ts.ElementAccessExpression, context: Context): {
    slot: number; receiver: number; fieldSource?: number;
  } {
    const receiver = this.value(n.expression, context);
    const owner = this.receiverOwner(n.expression);
    const base = this.location(receiver, owner);
    let key: string | number;
    if (ts.isPropertyAccessExpression(n)) key = n.name.text;
    else {
      const arg = n.argumentExpression;
      key = ts.isStringLiteralLike(arg) || ts.isNumericLiteral(arg) ? arg.text :
        this.location(this.value(arg, context), this.receiverOwner(arg));
    }
    // Literal spellings and computed binding ids have disjoint key types.
    const identity = JSON.stringify([base, key]);
    const symbol = typeof key === 'string' ? this.referenceSymbol(n) : undefined;
    const declaration = symbol?.valueDeclaration;
    const fieldSymbol = declaration && ts.isPropertyDeclaration(declaration) && this.symbol(declaration.name);
    const field = fieldSymbol ? this.symbols.get(fieldSymbol) : undefined;
    let bindings: number | undefined;
    if (field !== undefined && declaration) {
      // Initializers and stores via class 'this' may reach any named receiver.
      // Named receiver writes may reach 'this' reads, but not unrelated roots.
      bindings = this.fieldBindings.get(field);
      if (bindings === undefined) {
        bindings = this.node('LocalVariable', { name: `@field-bindings:${field}`, varType: this.type(n), method: context.method });
        this.fieldBindings.set(field, bindings);
      }
      if (owner?.owner === declaration.parent &&
          owner.instance !== !!(ts.getCombinedModifierFlags(declaration) & ts.ModifierFlags.Static))
        return { slot: field, receiver, fieldSource: bindings };
    }
    let slot = this.properties.get(identity);
    if (slot === undefined) {
      const name = typeof key === 'number' ? `@computed-property:${base}:${key}` : `@property:${base}:${key}`;
      slot = this.node('LocalVariable', { name, varType: this.type(n), method: context.method });
      this.properties.set(identity, slot);
    }
    if (bindings !== undefined) this.edge(slot, bindings, 'FIELD_LOAD');
    return { slot, receiver, fieldSource: field };
  }
  private assigned(n: ts.Expression, context: Context): number {
    return ts.isPropertyAccessExpression(n) || ts.isElementAccessExpression(n) ? this.property(n, context).slot : this.value(n, context);
  }
  private assign(n: ts.Expression, value: number, context: Context, rhs?: ts.Expression, kind: EdgeKind = 'ASSIGN'): void {
    while (ts.isParenthesizedExpression(n)) n = n.expression;
    for (const { target, defaults } of this.targets(n)) {
      const symbol = ts.isIdentifier(target) ? this.symbol(target) : undefined;
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
  private allocationClass(expr: ts.Expression): ts.ClassLikeDeclaration | undefined {
    const seen = new Set<ts.Symbol>();
    for (;;) {
      expr = calledExpression(expr);
      if (ts.isClassExpression(expr)) return expr;
      const symbol = this.referenceSymbol(expr);
      if (!symbol || this.writes.has(symbol) || seen.has(symbol)) return undefined;
      seen.add(symbol);
      const declaration = symbol.valueDeclaration;
      if (declaration && ts.isClassLike(declaration)) return declaration;
      if (!declaration || !ts.isVariableDeclaration(declaration) || !declaration.initializer) return undefined;
      expr = declaration.initializer;
    }
  }
  private implicitConstructor(cls: ts.ClassLikeDeclaration, inherited?: Method): Method {
    let method = this.implicitConstructors.get(cls);
    if (!method) {
      const type = this.checker.getTypeAtLocation(cls);
      method = { declaringClass: this.classOwner(cls), name: 'constructor', parameterTypes: inherited?.parameterTypes ?? [],
        returnType: this.type(cls, type.symbol ? this.checker.getDeclaredTypeOfSymbol(type.symbol) : type) };
      this.registerMethod(method, cls);
      this.implicitConstructors.set(cls, method);
    }
    return method;
  }
  private target(n: ts.CallExpression | ts.NewExpression): { method: Method; context?: Context; implicit?: boolean } {
    let declaration = this.checker.getResolvedSignature(n)?.declaration;
    const callee = calledExpression(n.expression);
    const expr = ts.isPropertyAccessExpression(callee) ? callee.name : callee;
    const symbol = this.symbol(expr);
    let allocation: ts.ClassLikeDeclaration | undefined;
    if (ts.isNewExpression(n)) allocation = this.allocationClass(n.expression);
    else if (n.expression.kind === ts.SyntaxKind.SuperKeyword) {
      let owner: ts.Node | undefined = n.parent;
      while (owner && !ts.isClassLike(owner)) owner = owner.parent;
      const base = owner?.heritageClauses?.find(clause => clause.token === ts.SyntaxKind.ExtendsKeyword)?.types[0];
      if (base) allocation = this.allocationClass(base.expression);
    }
    if (allocation && !allocation.getSourceFile().isDeclarationFile && declaration?.parent !== allocation) {
      const own = allocation.members.find(ts.isConstructorDeclaration);
      if (own) declaration = own;
      else {
        // An implicit derived constructor forwards to the inherited implementation.
        // Preserve its formal/default flows without adding a source super() call.
        const context = declaration && this.contexts.get(declaration);
        return { method: this.implicitConstructor(allocation, context?.method), context, implicit: true };
      }
    }
    // Structurally compatible constructors can collapse to a single inferred
    // signature even when a runtime choice determines the allocated class.
    if (!allocation && ts.isNewExpression(n) && declaration && ts.isConstructorDeclaration(declaration))
      declaration = undefined;
    // Overload signatures resolve to their implementation body when it is available.
    const implementation = symbol?.declarations?.find(isBody);
    if (implementation) declaration = implementation;
    const context = declaration ? this.contexts.get(declaration) : undefined;
    if (context) return { method: context.method, context };
    if (declaration && !ts.isJSDocSignature(declaration)) return { method: this.descriptor(declaration) };
    let name = '<call>';
    if (ts.isIdentifier(callee)) name = callee.text;
    else if (ts.isPropertyAccessExpression(callee)) name = callee.name.text;
    else if (ts.isElementAccessExpression(callee) && ts.isStringLiteralLike(callee.argumentExpression)) name = callee.argumentExpression.text;
    else if (callee.kind === ts.SyntaxKind.ImportKeyword || callee.kind === ts.SyntaxKind.SuperKeyword || callee.kind === ts.SyntaxKind.ThisKeyword)
      name = callee.getText();
    return { method: {
      declaringClass: '<dynamic>', name,
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
  // Minifiers produce long left-associated chains. Allocate ordinary result nodes
  // in preorder, then evaluate operands left to right, as for a shallow expression.
  private binaryValue(n: ts.BinaryExpression, context: Context): number {
    const chain: { node: ts.BinaryExpression; result?: number }[] = [];
    let left: ts.Expression = n;
    while (ts.isBinaryExpression(left) && left.operatorToken.kind !== ts.SyntaxKind.EqualsToken && !this.values.has(left)) {
      const result = left.operatorToken.kind === ts.SyntaxKind.CommaToken ? undefined :
        this.node('LocalVariable', { name: `@${this.position(left)}`, varType: this.type(left), method: context.method });
      chain.push({ node: left, result });
      left = left.left;
    }
    let id = this.value(left, context);
    for (let i = chain.length - 1; i >= 0; i--) {
      const { node, result } = chain[i]!;
      if (result !== undefined) this.edge(id, result, 'ASSIGN');
      const right = this.value(node.right, context);
      if (result !== undefined) this.edge(right, result, 'ASSIGN');
      id = result ?? right;
      this.values.set(node, id);
    }
    return id;
  }

  private value(n: ts.Expression, context: Context): number {
    const cached = this.values.get(n);
    if (cached !== undefined) return cached;
    let id: number;
    if (ts.isStringLiteralLike(n)) id = this.constant('StringConstant', n.text);
    else if (ts.isNumericLiteral(n) || (ts.isPrefixUnaryExpression(n) && ts.isNumericLiteral(n.operand) && (n.operator === ts.SyntaxKind.MinusToken || n.operator === ts.SyntaxKind.PlusToken))) {
      const value = ts.isNumericLiteral(n) ? Number(n.text) : Number((n.operand as ts.NumericLiteral).text) * (n.operator === ts.SyntaxKind.MinusToken ? -1 : 1);
      // Overflow is valid JavaScript, but the interchange accepts only finite
      // constants. Keep a numeric value at this expression so calls and returns
      // still carry it instead of inventing a null constant or failing the build.
      id = Number.isFinite(value)
        ? this.constant(!Object.is(value, -0) && Number.isInteger(value) && value >= -2147483648 && value <= 2147483647 ? 'IntConstant' : 'DoubleConstant', value)
        : this.node('LocalVariable', { name: `@${this.position(n)}`, varType: 'number', method: context.method });
    } else if (n.kind === ts.SyntaxKind.TrueKeyword || n.kind === ts.SyntaxKind.FalseKeyword) id = this.constant('BooleanConstant', n.kind === ts.SyntaxKind.TrueKeyword);
    else if (n.kind === ts.SyntaxKind.NullKeyword) id = this.constant('NullConstant');
    else if (ts.isIdentifier(n)) id = this.local(n, context);
    else if (ts.isParenthesizedExpression(n) || ts.isAsExpression(n) || ts.isTypeAssertionExpression(n) || ts.isNonNullExpression(n) || ts.isSatisfiesExpression(n)) id = this.value(n.expression, context);
    else if (ts.isBinaryExpression(n) && n.operatorToken.kind === ts.SyntaxKind.EqualsToken) {
      id = this.value(n.right, context);
      this.assign(n.left, id, context, n.right);
    } else if (ts.isBinaryExpression(n)) id = this.binaryValue(n, context);
    else if (ts.isCallExpression(n) || ts.isNewExpression(n)) {
      const target = this.target(n);
      const reference = unparenthesized(n.expression);
      let receiver: number | undefined;
      if (ts.isPropertyAccessExpression(reference) || ts.isElementAccessExpression(reference)) {
        const object = this.value(reference.expression, context);
        if (ts.isCallExpression(n)) receiver = object;
        if (ts.isElementAccessExpression(reference)) this.value(reference.argumentExpression, context);
      } else if (!ts.isIdentifier(reference)) {
        // Evaluate the complete callee, including calls discarded by a comma expression.
        this.value(n.expression, context);
      }
      const args = this.arguments(n, context, target.context);
      id = this.node('CallSite', { caller: context.method, callee: target.method,
        line: n.getSourceFile().getLineAndCharacterOfPosition(n.getStart()).line + 1,
        ...(receiver === undefined ? {} : { receiver }), arguments: args });
      this.edge(id, id, 'CALL', { isVirtual: receiver !== undefined, isDynamic: !target.context && !target.implicit });
      const rest = target.context?.restIndex;
      args.forEach((argument, index) => {
        this.edge(argument, id, 'PARAMETER_PASS');
        const parameterIndex = rest !== undefined && index >= rest ? rest : index;
        const parameter = target.context?.parameters[parameterIndex];
        if (parameter !== undefined) this.edge(argument, parameter, 'PARAMETER_PASS');
      });
      if (target.context?.returned !== undefined) this.edge(target.context.returned, id, 'RETURN_VALUE');
    } else if (ts.isPropertyAccessExpression(n) || ts.isElementAccessExpression(n)) {
      const { slot, receiver, fieldSource } = this.property(n, context);
      // A location and a read from that location are different values. In x.p=x,
      // loading x.p must not share endpoints with storing x into the property.
      id = this.node('LocalVariable', { name: `@read:${n.getText()}@${this.position(n)}`, varType: this.type(n), method: context.method });
      this.readSlots.set(id, slot);
      if (fieldSource !== undefined) this.edge(fieldSource, id, 'FIELD_LOAD');
      this.edge(slot, id, 'FIELD_LOAD');
      this.edge(receiver, id, 'FIELD_LOAD');
    } else {
      // Keep computed values distinct from operands. This records dependencies, never claims a fold.
      id = this.node('LocalVariable', { name: `@${this.position(n)}`, varType: this.type(n), method: context.method });
      this.values.set(n, id);
      if (!isBody(n)) ts.forEachChild(n, child => {
        if (ts.isSpreadAssignment(child) || ts.isSpreadElement(child)) this.edge(this.value(child.expression, context), id, 'ASSIGN');
        else if (ts.isExpression(child)) this.edge(this.value(child, context), id, 'ASSIGN');
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
    visitTree(n, context, (node, scope) => this.walkNode(node, scope));
  }

  private walkNode(n: ts.Node, context: Context): Context | undefined {
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
    return context;
  }
  run(): SourceGraph {
    if (!this.sources.length) throw new Error('No implementation source files were loaded.');
    for (const source of this.sources) {
      const method: Method = { declaringClass: this.module(source), name: '<module>', parameterTypes: [], returnType: 'void' };
      const context = { method, parameters: [], body: source };
      this.contexts.set(source, context);
      this.addMethod(method, this.module(source));
      this.functions.push(context);
      visitTree(source, context, (n, parent) => this.register(n, parent));
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
    if (this.droppedEdges.size) {
      this.warn(`Warning: conflicting v3 relationships on ${this.droppedEdges.size} node pair(s).`);
      for (const [index, dropped] of this.droppedEdges) {
        const edge = this.graph.edges[index]!;
        this.warn(`  ${edge.from} -> ${edge.to}: kept ${edgeDescription(edge)}, dropped ${[...dropped].sort().join(', ')}`);
      }
    }
    return this.graph;
  }
}
