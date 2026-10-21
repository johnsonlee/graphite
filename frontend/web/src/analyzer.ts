import ts from 'typescript';
import path from 'node:path';
import fs from 'node:fs';

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
type FunctionBody = ts.FunctionLikeDeclaration & { body: ts.ConciseBody };
interface Context { method: Method; parameters: number[]; returned?: number; body: ts.Node; declaration?: FunctionBody }
const isBody = (n: ts.Node): n is FunctionBody => ts.isFunctionLike(n) && 'body' in n && !!n.body;
const sourceExtension = /\.(?:[cm]?[jt]sx?)$/i;
const declarationExtension = /\.d\.[cm]?ts$/i;
const formatDiagnostics = (diagnostics: readonly ts.Diagnostic[]) => ts.formatDiagnostics(diagnostics, {
  getCanonicalFileName: f => f,
  getCurrentDirectory: () => process.cwd(),
  getNewLine: () => '\n',
});

/** Read tsconfig through TypeScript itself: includes, excludes, extends and path aliases apply. */
export function project(input: string): { program: ts.Program; root: string } {
  const absolute = path.resolve(input);
  const stat = fs.statSync(absolute);
  const config = stat.isDirectory()
    ? (fs.existsSync(path.join(absolute, 'tsconfig.json')) ? path.join(absolute, 'tsconfig.json') : undefined)
    : absolute.endsWith('.json') ? absolute : undefined;
  let root = stat.isDirectory() ? absolute : path.dirname(absolute);
  let files: string[];
  let options: ts.CompilerOptions = {
    target: ts.ScriptTarget.ESNext, module: ts.ModuleKind.NodeNext,
    moduleResolution: ts.ModuleResolutionKind.NodeNext, allowJs: true, noEmit: true,
  };
  if (config) {
    root = path.dirname(config);
    const read = ts.readConfigFile(config, ts.sys.readFile);
    if (read.error) throw new Error(formatDiagnostics([read.error]));
    const parsed = ts.parseJsonConfigFileContent(read.config, ts.sys, root, undefined, config);
    if (parsed.errors.length) throw new Error(formatDiagnostics(parsed.errors));
    if (parsed.projectReferences?.length) {
      throw new Error('Project references are not supported yet; build each referenced tsconfig separately.');
    }
    files = parsed.fileNames;
    options = { ...parsed.options, noEmit: true };
  } else if (stat.isDirectory()) {
    files = ts.sys.readDirectory(root, ['.ts', '.tsx', '.mts', '.cts', '.js', '.jsx', '.mjs', '.cjs'],
      ['**/node_modules/**', '**/.git/**', '**/dist/**', '**/build/**']);
  } else {
    if (!sourceExtension.test(absolute)) throw new Error(`Unsupported TypeScript/JavaScript input: ${input}`);
    files = [absolute];
  }
  files = files.filter(f => !declarationExtension.test(f)).sort();
  if (!files.length) throw new Error(`No TypeScript/JavaScript implementation files found in ${input}`);
  const program = ts.createProgram(files, options);
  const optionErrors = program.getOptionsDiagnostics();
  if (optionErrors.length) throw new Error(formatDiagnostics(optionErrors));
  const syntactic = program.getSyntacticDiagnostics();
  if (syntactic.length) throw new Error(formatDiagnostics(syntactic));
  return { program, root };
}

/** A source-level, flow-insensitive value graph. No user code or build scripts are executed. */
export function analyze(input: string, warn: (message: string) => void = () => {}): SourceGraph {
  const { program, root } = project(input);
  const diagnostics = program.getSemanticDiagnostics();
  if (diagnostics.length) warn(`${diagnostics.length} TypeScript semantic diagnostic(s); unresolved targets remain dynamic.\n${formatDiagnostics(diagnostics.slice(0, 5))}`);
  return new Analyzer(program, root).run();
}

class Analyzer {
  private checker: ts.TypeChecker;
  private graph: SourceGraph = { version: 1, methods: [], nodes: [], edges: [], classOrigins: [] };
  private contexts = new Map<ts.Node, Context>();
  private functions: Context[] = [];
  private values = new Map<ts.Node, number>();
  private symbols = new Map<ts.Symbol, number>();
  private constants = new Map<string, number>();
  private edgePairs = new Set<string>();
  private origins = new Set<string>();
  private methodKeys = new Set<string>();
  private sources: ts.SourceFile[];
  constructor(private program: ts.Program, private root: string) {
    this.checker = program.getTypeChecker();
    this.sources = program.getSourceFiles().filter(s => !s.isDeclarationFile &&
      !program.isSourceFileFromExternalLibrary(s) && sourceExtension.test(s.fileName))
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
  private edge(from: number, to: number, kind: string, extra = {}): void {
    const key = `${from}:${to}`;
    if (!this.edgePairs.has(key)) {
      this.edgePairs.add(key);
      this.graph.edges.push({ from, to, kind, ...extra });
    }
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
  private register(n: ts.Node, parent: Context): void {
    let context = parent;
    if (isBody(n)) {
      const method = this.descriptor(n);
      const key = JSON.stringify([method.declaringClass, method.name, method.parameterTypes]);
      if (this.methodKeys.has(key)) method.declaringClass += `#declaration@${this.position(n)}`;
      this.methodKeys.add(JSON.stringify([method.declaringClass, method.name, method.parameterTypes]));
      context = { method, parameters: [], body: n.body, declaration: n };
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
    if (symbol && this.symbols.has(symbol)) return this.symbols.get(symbol)!;
    const id = this.node('LocalVariable', { name: n.getText(), varType: this.type(n), method: context.method });
    if (symbol) this.symbols.set(symbol, id);
    return id;
  }
  private constant(kind: string, value?: unknown): number {
    const key = JSON.stringify([kind, value]);
    let id = this.constants.get(key);
    if (id === undefined) { id = this.node(kind, value === undefined ? {} : { value }); this.constants.set(key, id); }
    return id;
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
  private value(n: ts.Expression, context: Context): number {
    const cached = this.values.get(n);
    if (cached !== undefined) return cached;
    let id: number;
    if (ts.isStringLiteralLike(n)) id = this.constant('StringConstant', n.text);
    else if (ts.isNumericLiteral(n) || (ts.isPrefixUnaryExpression(n) && ts.isNumericLiteral(n.operand) && (n.operator === ts.SyntaxKind.MinusToken || n.operator === ts.SyntaxKind.PlusToken))) {
      const value = ts.isNumericLiteral(n) ? Number(n.text) : Number(n.operand.getText()) * (n.operator === ts.SyntaxKind.MinusToken ? -1 : 1);
      if (!Number.isFinite(value)) throw new Error(`Non-finite numeric literal at ${this.module(n)}:${this.position(n)}`);
      id = this.constant(Number.isInteger(value) && value >= -2147483648 && value <= 2147483647 ? 'IntConstant' : 'DoubleConstant', value);
    } else if (n.kind === ts.SyntaxKind.TrueKeyword || n.kind === ts.SyntaxKind.FalseKeyword) id = this.constant('BooleanConstant', n.kind === ts.SyntaxKind.TrueKeyword);
    else if (n.kind === ts.SyntaxKind.NullKeyword) id = this.constant('NullConstant');
    else if (ts.isIdentifier(n)) id = this.local(n, context);
    else if (ts.isParenthesizedExpression(n) || ts.isAsExpression(n) || ts.isTypeAssertionExpression(n) || ts.isNonNullExpression(n) || ts.isSatisfiesExpression(n)) id = this.value(n.expression, context);
    else if (ts.isCallExpression(n) || ts.isNewExpression(n)) {
      const target = this.target(n);
      const args = (n.arguments ?? []).map(a => this.value(a, context));
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
    } else if (ts.isPropertyAccessExpression(n)) {
      const symbol = this.symbol(n.name);
      const field = symbol && this.symbols.get(symbol);
      id = field ?? this.node('LocalVariable', { name: n.getText(), varType: this.type(n), method: context.method });
      const receiver = this.value(n.expression, context);
      if (field === undefined) this.edge(receiver, id, 'FIELD_LOAD');
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
    this.values.set(n, id);
    return id;
  }
  private walk(n: ts.Node, context: Context): void {
    if (isBody(n)) return; // Each body is analyzed exactly once in its own lexical scope.
    if (ts.isTypeNode(n) || ts.isImportDeclaration(n) || ts.isExportDeclaration(n)) return;
    if (ts.isForOfStatement(n)) {
      const iterable = this.value(n.expression, context);
      if (ts.isVariableDeclarationList(n.initializer)) {
        n.initializer.declarations.forEach(declaration => {
          if (ts.isIdentifier(declaration.name)) this.edge(iterable, this.local(declaration.name, context), 'ARRAY_LOAD');
          else this.bind(declaration.name, iterable, context);
        });
      } else this.edge(iterable, this.value(n.initializer, context), 'ARRAY_LOAD');
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
      const assigned = n.operatorToken.kind === ts.SyntaxKind.EqualsToken ? this.value(n.right, context) : this.value(n, context);
      this.edge(assigned, this.value(n.left, context), ts.isPropertyAccessExpression(n.left) ? 'FIELD_STORE' : 'ASSIGN');
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
      context.declaration?.parameters.forEach((parameter, index) => {
        if (parameter.initializer) {
          this.edge(this.value(parameter.initializer, context), context.parameters[index]!, 'ASSIGN');
          this.walk(parameter.initializer, context);
        }
      });
      if (ts.isExpression(context.body)) {
        if (context.returned !== undefined) this.edge(this.value(context.body, context), context.returned, 'RETURN_VALUE');
      }
      this.walk(context.body, context);
    }
    return this.graph;
  }
}
