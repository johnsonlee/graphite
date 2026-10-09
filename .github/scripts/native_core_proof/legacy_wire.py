"""Independent, strict readers for the frozen real3 GR*03 / GTY01/02 formats.

No Graphite, JVM property accessor, or query-engine implementation is imported.
Unknown versions/tags fail closed; this is a scoped oracle, not a permissive loader.
"""
import hashlib
import struct
from dataclasses import dataclass


class Invalid(ValueError):
    pass


def need(condition, message):
    if not condition:
        raise Invalid(message)


class Reader:
    def __init__(self, data, strings=()):
        self.data, self.pos, self.strings = data, 0, strings

    def take(self, n):
        need(0 <= n <= len(self.data) - self.pos, f"truncated at {self.pos}: {n}")
        start = self.pos
        self.pos += n
        return self.data[start:self.pos]

    def i(self):
        return struct.unpack('>i', self.take(4))[0]

    def q(self):
        return struct.unpack('>q', self.take(8))[0]

    def byte(self):
        return self.take(1)[0]

    def boolean(self):
        value = self.byte()
        need(value in (0, 1), 'noncanonical boolean')
        return bool(value)

    def count(self, minimum=4):
        value = self.i()
        need(0 <= value <= (len(self.data) - self.pos) // minimum, 'invalid count')
        return value

    def text(self):
        return self.take(self.count(1)).decode('utf-8', errors='strict')

    def sid(self):
        value = self.i()
        need(0 <= value < len(self.strings), f'invalid string ID {value}')
        return self.strings[value]

    def done(self):
        need(self.pos == len(self.data), f'trailing bytes at {self.pos}')


def unique_put(target, key, value, what):
    need(key not in target, f'duplicate {what}: {key!r}')
    target[key] = value


def descriptor(name):
    dimensions = 0
    while name.endswith('[]'):
        dimensions += 1
        name = name[:-2]
    primitives = dict(boolean='Z', byte='B', char='C', short='S', int='I', long='J',
                      float='F', double='D', void='V')
    return '[' * dimensions + primitives.get(name, 'L' + name.replace('.', '/') + ';')


@dataclass(frozen=True)
class Method:
    owner: str
    name: str
    parameters: tuple
    result: str

    @property
    def key(self):
        return (self.owner, self.name, '(' + ''.join(map(descriptor, self.parameters)) + ')' + descriptor(self.result))

    @property
    def signature(self):
        return f'{self.owner}.{self.name}({",".join(self.parameters)})'


def method(r):
    owner, name = r.sid(), r.sid()
    params = tuple(r.sid() for _ in range(r.count()))
    return Method(owner, name, params, r.sid())


def any_value(r, depth=0):
    need(depth <= 256, 'nested annotation/list exceeds oracle bound')
    tag = r.byte()
    if tag in (0, 3):
        r.take(4)
    elif tag in (1, 4):
        r.take(8)
    elif tag == 2:
        r.sid()
    elif tag == 5:
        r.boolean()
    elif tag == 6:
        pass
    elif tag == 7:
        r.sid(); r.sid()
    elif tag == 8:
        for _ in range(r.count(1)):
            any_value(r, depth + 1)
    else:
        raise Invalid(f'unknown value tag {tag}')


def strings_export(data):
    r = Reader(data)
    need(r.i() == 0x47534f01, 'expected GSO01 string export')
    values = [r.text() for _ in range(r.count())]
    r.done()
    # Java string ordering compares UTF-16 code units, not Unicode code points.
    encoded = [s.encode('utf-16-be') for s in values]
    need(all(a < b for a, b in zip(encoded, encoded[1:])), 'strings not strictly Java-sorted')
    digest = hashlib.sha256(struct.pack('>i', len(values)))
    for value in values:
        raw = value.encode('utf-8')
        digest.update(struct.pack('>i', len(raw))); digest.update(raw)
    return values, digest.hexdigest()


class TypeIndexCheck:
    def __init__(self, data):
        self.data, self.entries, self.used = data, {}, {}
        r=Reader(data); need(r.i()==0x47525403,'require GRT03')
        size=r.count(13); need(size==16,'expected every known node tag')
        for _ in range(size):
            tag,count,offset=r.byte(),r.i(),r.q()
            need(0<=tag<16 and 0<=count<=(len(data)//4),'type index entry')
            unique_put(self.entries,tag,(count,offset),'type index tag'); self.used[tag]=0
        end=r.pos
        for count,offset in sorted(self.entries.values(),key=lambda value:value[1]):
            need(offset==end and count<=(len(data)-end)//4,'type index spans/gaps')
            end+=4*count
        need(end==len(data),'type index trailing bytes')

    def consume(self, tag, node):
        count,offset=self.entries[tag]; ordinal=self.used[tag]
        need(ordinal<count,'extra node absent from type index')
        need(struct.unpack_from('>i',self.data,offset+4*ordinal)[0]==node,'type index node/order mismatch')
        self.used[tag]+=1

    def done(self):
        need(all(self.used[tag]==count for tag,(count,_) in self.entries.items()),'unconsumed type index nodes')


def node_records(data, offsets_data, strings, type_index_data=None):
    r, offsets = Reader(data, strings), Reader(offsets_data)
    need(r.i() == 0x47524e03 and offsets.i() == 0x47524c03, 'require GRN03/GRL03')
    count, slots = r.count(5), offsets.count(8)
    need(len(offsets.data) == 8 + 8 * slots, 'offset length')
    type_index=TypeIndexCheck(type_index_data) if type_index_data is not None else None
    seen = set()
    for _ in range(count):
        start, node_id = r.pos, r.i()
        need(0 <= node_id < slots and node_id not in seen, 'node ID range/duplicate')
        seen.add(node_id)
        offset = struct.unpack_from('>q', offsets.data, 8 + 8 * node_id)[0]
        need(offset == start + 1, 'node offset mismatch')
        tag = r.byte()
        record = {'id': node_id, 'tag': tag}
        if tag in (0, 3): r.take(4)
        elif tag == 1: r.sid()
        elif tag in (2, 4): r.take(8)
        elif tag == 5: r.boolean()
        elif tag == 6: pass
        elif tag == 7:
            r.sid(); r.sid()
            for _ in range(r.count(1)): any_value(r)
        elif tag == 8:
            r.sid(); r.sid(); method(r)
        elif tag == 9:
            owner, name, erased = r.sid(), r.sid(), r.sid()
            record.update(owner=owner, name=name, erased=erased, static=r.boolean(),
                          key=(owner, name, descriptor(erased)))
        elif tag == 10:
            ordinal, erased, member = r.i(), r.sid(), method(r)
            record.update(ordinal=ordinal, erased=erased, method=member, key=member.key)
        elif tag == 11:
            member = method(r)
            actual = r.sid() if r.boolean() else None
            record.update(method=member, actual=actual, key=member.key)
        elif tag == 12:
            method(r); method(r); r.i(); r.i()
            for _ in range(r.count()): r.i()
        elif tag == 13:
            r.sid(); r.sid(); r.sid()
            keys = set()
            for _ in range(r.count(5)):
                key = r.sid(); need(key not in keys, 'duplicate annotation key'); keys.add(key)
                any_value(r)
        elif tag == 14:
            r.sid(); r.sid(); any_value(r); r.sid()
            if r.boolean(): r.sid()
        elif tag == 15:
            r.sid(); r.sid(); r.sid()
            if r.boolean(): r.sid()
        else: raise Invalid(f'unknown node tag {tag}')
        if type_index is not None: type_index.consume(tag,node_id)
        yield record
    r.done()
    for node_id in range(slots):
        if node_id not in seen:
            need(struct.unpack_from('>q', offsets.data, 8 + 8 * node_id)[0] == 0, 'nonzero absent-node offset')
    if type_index is not None: type_index.done()


def metadata_methods(data, strings):
    r = Reader(data, strings)
    need(r.i() == 0x47524d03, 'require GRM03')
    methods = {}
    for _ in range(r.count(16)):
        value = method(r)
        unique_put(methods, value.key, value, 'metadata full method')
    # Mapped Method scans consume these raw rows; do not collapse by erased signature.
    def keyed_values(value_reader, minimum=8):
        seen = set()
        for _ in range(r.count(minimum)):
            key = r.sid(); need(key not in seen, 'duplicate metadata map key'); seen.add(key)
            value_reader()
    def string_set():
        values = [r.sid() for _ in range(r.count())]
        need(len(values) == len(set(values)), 'duplicate hierarchy set value')
    keyed_values(string_set); keyed_values(string_set)
    def values():
        for _ in range(r.count(1)): any_value(r)
    keyed_values(values)
    keyed_values(r.sid)
    keyed_values(lambda: keyed_values(r.i))
    def annotations():
        keyed_values(lambda: keyed_values(lambda: any_value(r)))
    keyed_values(annotations)
    for _ in range(r.count(36)):
        r.i(); method(r)
        op = r.i(); need(0 <= op < 6, 'comparison operator')
        r.i()
        for _branch in range(2):
            ids = [r.i() for _ in range(r.count())]
            need(len(ids) == len(set(ids)), 'duplicate branch member')
    optional = set()
    while r.pos < len(r.data):
        header = r.i(); need(header not in optional, 'duplicate optional metadata section'); optional.add(header)
        if header in (0x47525801, 0x47524202): r.take(32)
        elif header == 0x47525301:
            seen = set()
            for _ in range(r.count(20)):
                key = r.sid(); need(key not in seen, 'duplicate synthetic identity'); seen.add(key); r.take(16)
        else: raise Invalid(f'unknown optional metadata section {header:x}')
    r.done()
    return methods


class Types:
    def __init__(self, rows=(), fields=None, methods=None, classes=None):
        self.rows, self.fields, self.methods, self.classes = list(rows), fields or {}, methods or {}, classes or {}

    def validate(self):
        n = len(self.rows)
        def ref(i): need(type(i) is int and 0 <= i < n, f'invalid type reference {i}')
        def parameters(items):
            for p in items:
                for i in p['bounds']: ref(i)
        for i in self.fields.values(): ref(i)
        for m in self.methods.values():
            for i in m['parameters']: ref(i)
            ref(m['result']); parameters(m['formals'])
        for c in self.classes.values():
            if c['super'] is not None: ref(c['super'])
            for i in c['interfaces']: ref(i)
            parameters(c['formals'])
        for t in self.rows:
            k, owner, component, args, variance = (t[x] for x in ('kind','owner','component','arguments','variance'))
            if k == 'class': valid = bool(t['name']) and component is None and not variance
            elif k == 'primitive': valid = t['name'] in ('boolean','byte','char','short','int','long','float','double','void') and owner is None and component is None and not args and not variance
            elif k == 'array': valid = component is not None and owner is None and not args and not variance
            elif k == 'variable': valid = bool(t['name']) and bool(t['scope']) and owner is None and component is None and not args and not variance
            elif k == 'wildcard': valid = owner is None and not args and ((variance in ('extends','super') and component is not None) or (variance == 'unbounded' and component is None))
            else: valid = False
            need(valid, 'invalid type shape')
            for i in args + [i for i in (owner, component) if i is not None]: ref(i)
        state, cache = [0]*n, {}
        def visit(i, depth):
            need(depth <= 256 and state[i] != 1, 'cycle/depth')
            if state[i] == 2: return cache[i]
            state[i] = 1; t = self.rows[i]
            height, nodes = 1, 1
            size = sum(len(t[k].encode('utf-8')) for k in ('kind','name','scope','variance'))
            for child in t['arguments'] + [x for x in (t['owner'],t['component']) if x is not None]:
                h, count, length = visit(child, depth+1)
                height=max(height,h+1); nodes+=count; size+=length
            need(height <= 256 and nodes <= 100000 and size <= 1000000, 'projection expansion/depth')
            state[i]=2; cache[i]=(height,nodes,size)
            return cache[i]
        for i in range(n): visit(i,1)

    def render(self, i):
        t = self.rows[i]; kind=t['kind']
        if kind == 'array': return self.render(t['component'])+'[]'
        if kind == 'wildcard': return '?' if t['variance']=='unbounded' else '? '+t['variance']+' '+self.render(t['component'])
        if kind != 'class': return t['name']
        name=t['name']
        if t['owner'] is not None:
            prefix=self.rows[t['owner']]['name']+'$'
            simple=name[len(prefix):] if name.startswith(prefix) else name
            name=self.render(t['owner'])+'.'+simple
        return name+('<'+', '.join(map(self.render,t['arguments']))+'>' if t['arguments'] else '')

    def info(self, i):
        t=self.rows[i]; out={'kind':t['kind']}
        for k in ('name','scope'):
            if t[k]: out[k]=t[k]
        for k in ('owner','component'):
            if t[k] is not None: out[k]=self.info(t[k])
        if t['variance']: out['variance']=t['variance']
        out['arguments']=[self.info(x) for x in t['arguments']]
        return out

    def method_properties(self, m):
        return {'generic_return_type':self.render(m['result']), 'return_type_info':self.info(m['result']),
                'generic_parameter_types':[self.render(x) for x in m['parameters']],
                'parameter_type_info':[self.info(x) for x in m['parameters']],
                'type_parameters':[dict(name=p['name'],scope=p['scope'],bounds=[self.render(x) for x in p['bounds']],
                    bound_info=[self.info(x) for x in p['bounds']]) for p in m['formals']]}


def declared_types(data, metadata_sha, expected_version):
    r=Reader(data); header=r.i()
    need(header == 0x47545900 | expected_version and expected_version in (1,2), 'unexpected GTY version')
    need(r.take(32).hex() == metadata_sha, 'metadata digest binding')
    dictionary=[]
    if expected_version == 2:
        dictionary=[r.text() for _ in range(r.count())]
        need(len(dictionary)==len(set(dictionary)), 'duplicate dictionary text')
    def text():
        if expected_version == 1: return r.text()
        i=r.i(); need(0<=i<len(dictionary), 'dictionary ID'); return dictionary[i]
    def ref(optional=False):
        i=r.i()
        if optional and i==-1: return None
        need(0<=i<count, 'type reference range'); return i
    def refs(): return [ref() for _ in range(r.count())]
    def formals(): return [dict(name=text(),scope=text(),bounds=refs()) for _ in range(r.count(12))]
    count=r.count(28); rows=[]
    for _ in range(count):
        rows.append(dict(kind=text(),name=text(),scope=text(),owner=ref(True),component=ref(True),variance=text(),arguments=refs()))
    fields,methods,classes={},{},{}
    for _ in range(r.count(16)):
        key=(text(),text(),text()); unique_put(fields,key,ref(),'declared field')
    for _ in range(r.count(24)):
        key=(text(),text(),text()); unique_put(methods,key,dict(parameters=refs(),result=ref(),formals=formals()),'declared method')
    for _ in range(r.count(16)):
        key=text(); unique_put(classes,key,dict(formals=formals(),super=ref(True),interfaces=refs()),'declared class')
    r.done(); result=Types(rows,fields,methods,classes); result.validate(); return result
