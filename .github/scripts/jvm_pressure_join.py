#!/usr/bin/env python3
"""Complete JVM35's pending core authority without altering measured inputs."""
import argparse
import copy
from pathlib import Path
import audit_native_pressure_artifacts as artifacts
import assemble_native_pressure_producers as assembled
import multigraph_pressure as common
import prepare_jvm_pressure_plan as prepare

SCHEMA='graphite.jvm-pressure.core-comparability-join.v1'
require=common.require


def complete(pending):
    pending=prepare.verify_bundle(pending,pending['baseRevision'],pending['candidateRevision'])
    return _complete_verified(pending)


def _complete_verified(pending):
    """Only complete a pending packet verified in the caller's active lifetime."""
    require(pending.get('measurementMode')==prepare.MODE and pending['status']==prepare.PENDING_STATUS,'original pending JVM35 authority')
    result=copy.deepcopy(pending)
    result.update(status=assembled.CORRECTED_STATUS,comparisonModel=assembled.CORRECTED_MODEL,missingAuthority=[],correctedComparisons={})
    for arm in ('A','B'):
        if common.same_accepted_artifacts(pending,arm):continue
        root=Path(pending['arms'][arm]['artifactAudit']['path']).parent/'core-equivalence'
        result['correctedComparisons'][arm]=assembled.corrected_comparison(result,arm,root/'audit.json')
    result['rawEdgeJoins']={}
    for arm,producer in pending['arms'].items():
        raw=common.pinned_authority_metadata(result,producer['rawExportAudit'])
        core_arm='B' if arm=='C' or common.same_accepted_artifacts(pending,arm) else arm
        comparison=result['correctedComparisons'][core_arm]
        audit=common.pinned_authority_metadata(result,comparison['audit'])
        require(len(raw['graphs'])==len(audit['graphs'])==64,'complete64 raw/core join')
        joins=[]
        for exported,verified in zip(raw['graphs'],audit['graphs']):
            require(exported['id']==verified['id'],'same ordered raw/core graph')
            topology=common.pinned_authority_metadata(result,verified['topology'])
            original=topology['rawEdgeExports']['reference' if arm=='C' or common.same_accepted_artifacts(pending,arm) else 'actual']
            edge=exported['edges']
            require(all(common.typed(edge[k])==common.typed(original[k]) for k in ('graphRoot','sourceInputs','nodeSlots','labeledEdges','mappingApplied')) and
                    edge['mappingApplied'] is False and edge['output']['sha256']==original['output']['sha256'],
                    'original-ID edge export differs from complete core raw bytes')
            require(all(result['pins'].get(r['path'])==r['sha256'] for r in (edge['output'],original['output'],exported['receipt'])),
                    'raw edge outputs and receipts in both audited closures')
            joins.append({'id':exported['id'],'rawReceipt':exported['receipt'],'topology':verified['topology'],
                          'coreArm':core_arm,'coreSide':'reference' if arm=='C' or common.same_accepted_artifacts(pending,arm) else 'actual',
                          'outputSha256':edge['output']['sha256']})
        result['rawEdgeJoins'][arm]={'rawAudit':producer['rawExportAudit'],'graphs':joins}
    assembled.corrected_fixture_bindings(result)
    return result


def verify(packet):
    original=prepare.assemble(packet['correctnessRoots'],packet['baseRevision'],packet['candidateRevision'],measurement_only=True)
    actual=complete(original)
    require(common.typed(packet)==common.typed(actual),'JVM corrected packet differs from full independent replay')
    return actual,original


def metadata(packet):
    refs=[]
    for join in packet['rawEdgeJoins'].values():
        refs.append(join['rawAudit'])
        for graph in join['graphs']:refs.extend((graph['rawReceipt'],graph['topology']))
    return refs


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--pending',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();require(not args.output.exists(),'fresh corrected packet')
    common.save(args.output,complete(common.read(args.pending)))
