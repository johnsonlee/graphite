#!/usr/bin/env python3
"""Bind reviewed formatter source rules to a fresh independently audited pair.

This executable stage binds source only. It cannot substitute for owned Kotlin
production-test evidence or the complete core/topology/index comparison.
"""
import argparse
import copy
import json
from pathlib import Path
import sys
sys.dont_write_bytecode=True
import export_native_core_marker as marker
import multigraph_pressure as common
from prepare_native_pressure_plan import preparation_control_pins
from native_core_proof import formatter_binding


def _bind_with_marker(marker_audit,output):
    """Return the actual full replay alongside bindings; never accept cached authority."""
    path=Path(marker_audit).resolve();out=Path(output).resolve()
    marker_ref=marker.artifacts.ref(path)
    audit=common.read(path);checked=marker.audit(path.parent)
    common.require(common.typed(audit)==common.typed(checked),'actual marker audit differs from independent raw replay')
    sources={};fixtures={};artifact_refs={}
    for arm in ('C','B'):
        ref=checked['arms'][arm]['artifactAudit'];artifact=common.pinned_authority_metadata(checked,ref)
        source=common.pinned_authority_metadata(artifact,artifact['sourceManifest'])
        root=Path(artifact['producerPacket']['path']).parent
        common.require(not any(out.is_relative_to(Path(p)) for p in
                    (source['root'],root/'runtime',root/'graphs')),'formatter output outside measured source/runtime/graph roots')
        sources[arm]=artifact['sourceManifest'];fixtures[arm]=artifact['fixtureManifest'];artifact_refs[arm]=ref
    common.require(checked['arms']['C']['revision']!=checked['arms']['B']['revision'],
                   'identical writer pair requires strict comparison without a formatter correction rule')
    controls=preparation_control_pins()
    common.require(str(Path(__file__).resolve()) in controls,'reviewed formatter binder control')
    common.require(marker.artifacts.ref(path)==marker_ref,'marker audit changed during formatter binding')
    bindings=(sources,fixtures,controls,{'markerAudit':marker_ref,'artifactAudits':artifact_refs})
    return copy.deepcopy(bindings),copy.deepcopy(checked)


def bind(marker_audit,output):
    bindings,_=_bind_with_marker(marker_audit,output)
    return bindings


def execute(marker_audit,output):
    sources,fixtures,controls,upstream=bind(marker_audit,output)
    report=formatter_binding.save(output,sources,fixtures,controls)
    # Keep the source report's narrower claim. The upstream marker audit itself
    # remains retained and is replayed by bind; no historical receipt is rewritten.
    report['upstream']=upstream
    report['pins'].update({str(Path(__file__).resolve()):common.sha(__file__),
                          upstream['markerAudit']['path']:upstream['markerAudit']['sha256']})
    marker.artifacts.verify_pins(report['pins'])
    # save() created this file exclusively; this is the same owned output update.
    (Path(output)/'source-binding.json').write_text(json.dumps(report,indent=2)+'\n')
    return report


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--marker-audit',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();execute(args.marker_audit,args.output);return 0


if __name__=='__main__':sys.exit(main())
