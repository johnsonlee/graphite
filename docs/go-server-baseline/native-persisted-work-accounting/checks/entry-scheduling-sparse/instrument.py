"""Sparse exact-anchor diagnostic hooks; original v4 or frozen lazy-core-v1."""
from pathlib import Path
import re

IMPORT = '\t"github.com/johnsonlee/graphite/graphite-server/internal/entrytrace"\n'


def instrument(source):
    result, edits = {}, []
    def file(name):
        path = 'internal/' + name
        original = (source / path).read_text()
        assert 'entrytrace' not in original, path
        result[path] = original
        return path
    def replace(path, old, new, count=1):
        pattern = re.compile("^" + re.escape(old), re.MULTILINE)
        actual = len(pattern.findall(result[path]))
        assert actual == count, (path, old, actual, count)
        result[path] = pattern.sub(lambda _: new, result[path])
        edits.append({'path': path, 'before': old, 'after': new, 'occurrences': count})
    def after(path, anchor, hook, count=1):
        replace(path, anchor, anchor + hook, count)
    def imported(path):
        after(path, 'import (\n', IMPORT)
    def emit(kind, obj='nil', rel='nil', graph='""', a='0', b='0', c='0'):
        return f'entrytrace.Emit("{kind}", {obj}, {rel}, {graph}, {a}, {b}, {c})'

    p = file('query/main_fixed_workers.go'); imported(p)
    after(p, '\t\tc.err = err\n', '\t\t'+emit('worker_interrupt','c')+'\n')
    after(p, '\tf.run = nil\n\tf.worker = nil\n', '\t'+emit('future_finish','f')+'\n')
    after(p, '\tf.canceled = true\n', '\t'+emit('future_cancel','f','f.worker',a='entrytrace.Flag(f.started)')+'\n')
    after(p, '\tf.started = true\n', '\t'+emit('future_start','f','f.worker')+'\n')
    if 'func (e *mainFixedExecutor) runWorker(firstTask *mainFixedFuture)' in result[p]:
        after(p, '\te := &mainFixedExecutor{capacity: max(1, capacity)}\n', '\t'+emit('executor_create','e',a='int64(capacity)')+'\n')
        after(p, '\t\te.cores++\n', '\t\t'+emit('executor_submit_first','e','future',a='int64(e.cores)')+'\n')
        after(p, 'func (e *mainFixedExecutor) runWorker(firstTask *mainFixedFuture) {\n', '\t'+emit('executor_core_start','e','firstTask')+'\n\tdefer '+emit('executor_core_exit','e')+'\n')
    else:
        after(p, '\te := &mainFixedExecutor{}\n', '\t'+emit('executor_create','e',a='int64(capacity)')+'\n')
        after(p, '\t\tgo func() {\n', '\t\t\t'+emit('executor_core_start','e')+'\n\t\t\tdefer '+emit('executor_core_exit','e')+'\n')
    after(p, '\te.queue = append(e.queue, future)\n', '\t'+emit('executor_submit','e','future',a='int64(len(e.queue))')+'\n')
    after(p, '\tworkerCount := min(count, max(1, parallel))\n', '\t'+emit('fixed_runner_enter','ctx','executor',a='int64(count)',b='int64(workerCount)')+'\n')
    after(p, '\t\tfutures[i] = &mainFixedFuture{worker: worker, done: make(chan struct{})}\n', '\t\t'+emit('future_create','futures[i]','worker',a='int64(i)')+'\n')
    replace(p, '\t\tdefer func() { out.failure = recover() }()', '\t\tdefer func() { out.failure = recover(); '+emit('source_outcome','worker',a='int64(index)',b='entrytrace.Flag(out.failure != nil)')+' }()')
    after(p, '\t\tfuture.setRun(func() {\n', '\t\t\tdefer '+emit('worker_exit','worker','future')+'\n')
    after(p, '\t\t\t\tcase index = <-indexes:\n', '\t\t\t\t\t'+emit('source_dequeue','worker','future',a='int64(index)')+'\n')
    after(p, '\t\t\t\toutcomes <- out\n', '\t\t\t\t'+emit('outcome_sent','worker','future',a='int64(index)')+'\n')
    after(p, '\t\t\t<-future.done\n', '\t\t\t'+emit('future_joined','future')+'\n')
    after(p, '\t\tindexes <- nextTask\n', '\t\t'+emit('source_enqueued','ctx','executor',a='int64(nextTask)')+'\n')
    after(p, '\t\t\t\tindexes <- nextTask\n', '\t\t\t\t'+emit('source_enqueued','ctx','executor',a='int64(nextTask)')+'\n')
    after(p, '\t\tcase out = <-outcomes:\n', '\t\t\t'+emit('outcome_received','ctx','executor',a='int64(out.index)',b='entrytrace.Flag(out.failure != nil)')+'\n')
    after(p, '\t\t\tif consume(nextResult, ordered.value) {\n', '\t\t\t\t'+emit('fixed_limit_stop','ctx','executor',a='int64(nextResult)')+'\n')
    p = file('query/indexed_distinct.go'); imported(p)
    signature = 'func runDistinctTasks[T any](ctx context.Context, count, parallel int, orderedStop bool, task func(context.Context, int) T, consume func(int, T) bool) {\n'
    after(p, signature, '\t'+emit('legacy_runner_enter','ctx',a='int64(count)',b='int64(parallel)',c='entrytrace.Flag(orderedStop)')+'\n\tdefer '+emit('legacy_runner_exit','ctx')+'\n')
    after(p, '\tlaunch := func(i int) {\n', '\t\t'+emit('legacy_launch','local',a='int64(i)')+'\n')
    after(p, '\t\t\tout := outcome{index: i}\n', '\t\t\t'+emit('legacy_task_start','local',a='int64(i)')+'\n')
    replace(p, '\t\t\tdefer func() { out.failure = recover(); done <- out }()', '\t\t\tdefer func() { out.failure = recover(); '+emit('legacy_outcome','local',a='int64(i)',b='entrytrace.Flag(out.failure != nil)')+'; done <- out }()')
    after(p, '\tdefer func() {\n\t\tcancel()\n', '\t\t'+emit('legacy_cancel','local')+'\n')
    p = file('query/ordinary_projection.go'); imported(p)
    replace(p, '\t\t\t\treturn local.ordinarySourceRows(sources[i+1], &taskPlan, plan.limit)', '\t\t\t\t'+emit('ordinary_suffix_source','ctx','sources[i+1].Store','sources[i+1].ID','int64(i+1)','int64(plan.limit)')+'\n\t\t\t\treturn local.ordinarySourceRows(sources[i+1], &taskPlan, plan.limit)')
    after(p, '\t\t\tconsume := func(i int, rows []map[string]any) bool {\n\t\t\t\tremaining := plan.limit - len(result.Rows)\n\t\t\t\tresult.Rows = append(result.Rows, rows[:min(remaining, len(rows))]...)\n', '\t\t\t\t'+emit('ordinary_prefix_consume','e.ctx',graph='sources[i+1].ID',a='int64(i+1)',b='int64(len(result.Rows))',c='int64(plan.limit)')+'\n')
    p = file('query/main_string_mapped.go'); imported(p)
    old = '\t\t\t\tfailMainMappedRead(ctx.Err())' if 'failMainMappedRead(ctx.Err())' in result[p] else '\t\t\t\tfailMainStringRead(ctx.Err())'
    replace(p, old, old.replace('ctx.Err()', 'entrytrace.Error(ctx.Err(), "mapped_sequence_poll_rejected", ctx, source.Store, source.ID, int64(visited))'))
    p = file('query/ordinary_parallel.go'); imported(p)
    old = '\t\t\tfailMainMappedRead(e.ctx.Err())' if 'failMainMappedRead(e.ctx.Err())' in result[p] else '\t\t\tfailMainStringRead(e.ctx.Err())'
    replace(p, old, old.replace('e.ctx.Err()', 'entrytrace.Error(e.ctx.Err(), "mapped_anchor_poll_rejected", e.ctx, source.Store, source.ID, int64(position))'))
    p = file('store/main_mapped_cursor.go'); imported(p)
    after(p, '\t\t\tif err = ctx.Err(); err != nil {\n', '\t\t\t\t'+emit('range_poll_rejected','ctx','v.owner','v.owner.dir','int64(position)','int64(p)')+'\n')

    p = 'cmd/graphite-benchmark-replay/main.go'
    result[p] = (source / p).read_text(); imported(p)
    after(p, 'func run() error {\n', '\tentrytrace.Init()\n\tdefer func() { if err := entrytrace.Dump(); err != nil { fmt.Fprintln(os.Stderr, "diagnostic trace dump:", err) } }()\n')
    after(p, '\t\tqctx, cancel := context.WithTimeout(ctx, time.Duration(input.TimeoutMillis)*time.Millisecond)\n', '\t\tentrytrace.Begin(i)\n')
    after(p, '\t\tcancel() // ExecuteCross joins all source tasks before it returns.\n', '\t\tentrytrace.End()\n')
    after(p, '\t\tif validation, ok := record["validationError"]; ok {\n\t\t\treturn fmt.Errorf("%s: %s", c.ID, validation)\n\t\t}\n', '\t\tif i == 3 { break } // Diagnostic prefix; full workload input is unchanged.\n')
    replace(p, '\tresponses := 1267\n', '\tresponses := 4 // Diagnostic prefix response count.\n')
    # Mechanical inverse proves every original byte is retained by each edit.
    reverse = dict(result)
    for edit in reversed(edits):
        path = edit['path']
        pattern = re.compile('^' + re.escape(edit['after']), re.MULTILINE)
        assert len(pattern.findall(reverse[path])) == edit['occurrences'], edit
        reverse[path] = pattern.sub(lambda _: edit['before'], reverse[path])
    assert all(text == (source / path).read_text() for path, text in reverse.items())
    result['internal/entrytrace/trace.go'] = (Path(__file__).parent / 'trace.go.txt').read_text()
    return result, edits
