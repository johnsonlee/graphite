"""Two quiescent native Prometheus captures; strict counters, never latency quantiles."""
import hashlib, json, re, time, urllib.error, urllib.request
from decimal import Decimal
from pathlib import Path

BOUNDS = tuple(map(Decimal, ('0.01','0.05','0.1','0.5','1','5','30','120','Infinity')))
OUTCOMES = ('success','cancelled','timeout','budget_exceeded','failed')
HTTP = 'http_server_requests_seconds'
GUARD = 'graphite_cypher_query_duration_seconds'
SAMPLE = re.compile(r'([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*)\})?\s+([^\s]+)')
LABEL = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)=("(?:[^"\\]|\\.)*")(?:,|$)')

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def capture(url, directory, boundary, timeout):
    """One GET, retain raw body and headers on success/error, no redirect/retry."""
    directory = Path(directory); body_path = directory/f'metrics-{boundary}.prom'
    metadata_path = directory/f'metrics-{boundary}.json'
    if body_path.exists() or metadata_path.exists(): raise ValueError('Metrics capture already exists')
    record = {'url':url, 'method':'GET', 'boundary':boundary, 'startNs':time.perf_counter_ns()}
    raw = bytearray(); error = None
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        req = urllib.request.Request(url, headers={'Connection':'close'})
        try: response = opener.open(req, timeout=timeout)
        except urllib.error.HTTPError as exc: response = exc
        with response:
            record.update(httpStatus=response.status, headers=list(response.headers.items()))
            while True:
                chunk = response.read(65536)
                if not chunk: break
                raw.extend(chunk)
        record['bodyEndNs'] = time.perf_counter_ns()
        if record['httpStatus'] != 200: raise ValueError('Metrics HTTP status')
        if response.headers.get_content_type() != 'text/plain': raise ValueError('Metrics content type')
        length = response.headers.get('Content-Length')
        if length is not None and int(length) != len(raw): raise ValueError('Metrics incomplete Content-Length')
        parse(bytes(raw))
        record['status'] = 'PASS_COMPLETE_HTTP_AND_PARSE'
    except BaseException as exc:
        partial = getattr(exc, 'partial', None)
        if isinstance(partial, bytes): raw.extend(partial)
        error = exc; record.update(status='FAIL', error=repr(exc))
    finally:
        body_path.write_bytes(raw)
        record.update(bodyPath=str(body_path), bodyBytes=len(raw), bodySha256=hashlib.sha256(raw).hexdigest(), endNs=time.perf_counter_ns())
        metadata_path.write_text(json.dumps(record, indent=2, allow_nan=False)+'\n')
    if error: raise error
    return record

def parse(raw):
    samples = {}
    for line in raw.decode('utf-8', errors='strict').splitlines():
        if not line or line.startswith('#'): continue
        match = SAMPLE.fullmatch(line)
        if not match: raise ValueError('Malformed Prometheus sample')
        name, label_text, number = match.groups(); labels = {}; position = 0
        for label in LABEL.finditer(label_text or ''):
            if label.start() != position or label[1] in labels: raise ValueError('Malformed/duplicate metric label')
            labels[label[1]] = json.loads(label[2]); position = label.end()
        if position != len(label_text or ''): raise ValueError('Unparsed labels')
        key = (name, tuple(sorted(labels.items()))); value = Decimal(number)
        if key in samples or not value.is_finite() or value < 0: raise ValueError('Duplicate/nonfinite/negative sample')
        samples[key] = value
    if not samples: raise ValueError('Empty metrics response')
    return samples

def integer(value):
    value = Decimal(value)
    if value != value.to_integral_value(): raise ValueError('Noninteger counter')
    return int(value)

def histogram_valid(h):
    n = integer(h['count']); buckets = h['buckets']; total = h['sum']
    if len(buckets) != len(BOUNDS) or any(integer(v) < 0 for v in buckets): raise ValueError('Bucket schema')
    if list(buckets) != sorted(buckets) or buckets[-1] != n: raise ValueError('Bucket/count mismatch')
    if total < 0 or (not n and total): raise ValueError('Sum/count mismatch')
    lower = Decimal(0); upper = Decimal(0); prior_count = 0; prior_bound = Decimal(0)
    for bound, count in zip(BOUNDS, buckets):
        increment = count-prior_count; lower += increment*prior_bound
        if bound.is_finite(): upper += increment*bound
        elif increment: upper = Decimal('Infinity')
        prior_count = count; prior_bound = bound
    tolerance = Decimal('1e-9') + abs(total)*Decimal('1e-12')
    if total+tolerance < lower or total-tolerance > upper: raise ValueError('Sum outside bucket bounds')

def histograms(samples, prefix):
    groups = {}
    for (name, labels_tuple), value in samples.items():
        if not name.startswith(prefix+'_'): continue
        suffix = name[len(prefix)+1:]; labels = dict(labels_tuple)
        if prefix == HTTP and labels.get('uri') != '/api/cypher': continue
        bound = labels.pop('le', None)
        expected = {'outcome'} if prefix == GUARD else {'method','outcome','status','uri'}
        if set(labels) != expected or suffix not in ('bucket','count','sum','max'): raise ValueError('Unexpected histogram schema')
        group = groups.setdefault(tuple(sorted(labels.items())), {'bucketsByBound':{}})
        if suffix == 'bucket':
            if bound is None: raise ValueError('Missing bucket bound')
            numeric_bound = Decimal(bound)
            if numeric_bound in group['bucketsByBound']: raise ValueError('Duplicate numeric bucket bound')
            group['bucketsByBound'][numeric_bound] = integer(value)
        else:
            if bound is not None or suffix in group: raise ValueError('Unexpected bound/duplicate histogram member')
            group[suffix] = value
    for h in groups.values():
        if set(h) != {'bucketsByBound','count','sum','max'} or set(h['bucketsByBound']) != set(BOUNDS): raise ValueError('Incomplete histogram')
        h['buckets'] = [h['bucketsByBound'][b] for b in BOUNDS]
        del h['bucketsByBound']; histogram_valid(h)
        tolerance = Decimal('1e-9')+h['sum']*Decimal('1e-12')
        if h['max'] > h['sum']+tolerance or h['sum'] > h['max']*h['count']+tolerance: raise ValueError('Maximum/count/sum mismatch')
    return groups

def validate_window(before_raw, after_raw, expected_count=5):
    before, after = parse(before_raw), parse(after_raw); result = {'expectedQueries':expected_count, 'histograms':{}}
    for samples in (before, after):
        if samples[('graphite_cypher_queries_active',())] != 0: raise ValueError('Query still active at metrics boundary')
    limit = ('graphite_cypher_queries_limit',())
    if before[limit] != 4 or after[limit] != before[limit]: raise ValueError('Guard limit changed')
    rejected = ('graphite_cypher_queries_rejected_total',())
    if integer(after[rejected])-integer(before[rejected]) != 0: raise ValueError('Rejected query increment')
    for prefix in (HTTP, GUARD):
        prior, current = histograms(before,prefix), histograms(after,prefix)
        if prefix == GUARD:
            required = { (('outcome',o),) for o in OUTCOMES }
            if set(prior) != required or set(current) != required: raise ValueError('Guard outcomes changed/missing')
        if not current or not set(prior) <= set(current): raise ValueError('Missing/vanished query histogram')
        deltas = []; total_count = 0
        for key,h in current.items():
            old = prior.get(key, {'count':Decimal(0),'sum':Decimal(0),'max':Decimal(0),'buckets':[0]*len(BOUNDS)})
            delta = {'count':h['count']-old['count'],'sum':h['sum']-old['sum'],'buckets':[a-b for a,b in zip(h['buckets'],old['buckets'])]}
            histogram_valid(delta)
            if h['max'] < old['max']: raise ValueError('Cumulative max regressed')
            labels = dict(key); count = integer(delta['count']); total_count += count
            success = labels == {'outcome':'success'} if prefix == GUARD else labels == {'method':'POST','outcome':'SUCCESS','status':'200','uri':'/api/cypher'}
            if count != (expected_count if success else 0): raise ValueError('Unexpected success/failure count delta')
            deltas.append({'labels':labels,'countDelta':count,'sumSecondsDelta':str(delta['sum']),'cumulativeBucketDeltas':delta['buckets'],'cumulativeMaxSecondsBefore':str(old['max']),'cumulativeMaxSecondsAfter':str(h['max'])})
        if total_count != expected_count: raise ValueError('Total query count mismatch')
        result['histograms'][prefix] = deltas
    result.update(status='PASS_EXACT_QUERY_COUNTERS_AND_HISTOGRAM_CONSISTENCY',rejectedDelta=0,activeBefore=0,activeAfter=0,guardLimit=4,quantiles='Not estimated: 10ms first bucket and mixed route/outcome cases; sum/count is mean only',boundaries='HTTP ends at Response (includes query serialization); guard ends before result_body; neither is client full-body latency')
    return result
