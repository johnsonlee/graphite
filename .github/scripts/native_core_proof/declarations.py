"""Decode actual GTY01–05 bytes through an audited export row's dictionary.

The caller must bind the row to a completed export audit. Wire validity alone
never establishes source completeness, cross-arm equality or a query oracle.
"""
import hashlib
import json
import re
from pathlib import Path
from . import wire_gty05 as wire

SOURCE_SHA = '2957d2ddbcd32dd595e4496dfa12a597ef5dd88a07118a4e89fea611c9358835'


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def active_table(graph):
    """Require a bound active sidecar; reject unsupported property syntax.

    This narrow parser accepts the fresh writer's plain ASCII properties only.
    It fails closed on escaping/continuations and duplicate keys rather than
    interpreting a different authority from java.util.Properties.
    """
    path = graph/'forward.properties'
    wire.need(path.is_file() and not path.is_symlink(), 'missing actual declaration properties')
    try:
        lines = path.read_bytes().decode('ascii').splitlines()
    except UnicodeDecodeError:
        raise wire.Invalid('malformed actual declaration properties')
    props = {}
    for line in lines:
        line = line.strip()
        if not line or line.startswith(('#', '!')):
            continue
        wire.need('\\' not in line and '=' in line, 'malformed actual declaration properties')
        key, value = (part.strip() for part in line.split('=', 1))
        wire.need(re.fullmatch(r'[A-Za-z0-9_.-]+', key) and key not in props,
                  'malformed or duplicate actual declaration property')
        props[key] = value
    binding = props.get('graphite.declaredTypes.sha256')
    wire.need(binding is not None, 'orphan type sidecar is not an active declaration table')
    wire.need(re.fullmatch('[0-9a-fA-F]{64}', binding), 'malformed actual declaration digest')
    types = graph/'graph.types'
    wire.need(types.is_file() and not types.is_symlink(), 'missing bound declaration table')
    raw = types.read_bytes()
    wire.need(hashlib.sha256(raw).hexdigest() == binding.lower(), 'actual declaration property binding mismatch')
    return raw


def load(graph_root, export_row):
    graph = Path(graph_root).resolve()
    raw = active_table(graph)
    serialized = graph/'graph.strings'
    wire.need(export_row['input']['path'] == str(serialized) and
              export_row['input']['sha256'] == sha(serialized), 'actual graph dictionary export linkage')
    for key in ('stringsExport', 'stringsReceipt'):
        ref = export_row[key]
        wire.need(Path(ref['path']).is_absolute() and not Path(ref['path']).is_symlink() and
                  sha(ref['path']) == ref['sha256'], 'actual export evidence pin')
    helper = Path(__file__).with_name('ExportStrings.java')
    wire.need(sha(helper) == SOURCE_SHA, 'retained export helper source')
    context = wire.verified_strings_export(Path(export_row['stringsExport']['path']).read_bytes(),
        json.loads(Path(export_row['stringsReceipt']['path']).read_text()), sha(serialized),
        export_row['helperClassSha256'], SOURCE_SHA)
    wire.need(len(raw) >= 4 and raw[:3] == b'GTY' and 1 <= raw[3] <= 5, 'supported actual GTY header')
    return wire.declared_types(raw, sha(graph/'graph.metadata'), raw[3], context)
