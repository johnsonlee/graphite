const test = require('node:test');
const assert = require('node:assert/strict');
const uiState = require('../../main/resources/web/ui-state.js');

test('an empty registry remains zero graphs', () => {
    assert.equal(uiState.graphCount({ count: 0 }, []), 0);
    assert.equal(uiState.graphCount({}, []), 0);
});

test('a failed canvas action is reported with the same action for retry', async () => {
    let reported;
    let retry;
    const runner = uiState.createLatestTaskRunner();
    const runCanvasAction = uiState.createInterruptingActionRunner(
        runner,
        (error, action) => {
            reported = error;
            retry = action;
        }
    );
    const action = async () => { throw new Error('Graph not loaded: hot-unloaded'); };
    const result = await runCanvasAction(action);

    assert.equal(result, undefined);
    assert.equal(reported.message, 'Graph not loaded: hot-unloaded');
    assert.equal(retry, action);
});

test('a delayed first query cannot overwrite a newer query neighborhood', async () => {
    const runner = uiState.createLatestTaskRunner();
    const applied = [];
    let releaseFirst;

    const first = runner.run(
        () => new Promise(resolve => { releaseFirst = () => resolve('query-a'); }),
        value => applied.push(value)
    );
    await Promise.resolve();

    const second = runner.run(
        async () => 'query-b',
        value => applied.push(value)
    );
    assert.equal(await second, true);

    releaseFirst();
    assert.equal(await first, false);
    assert.deepEqual(applied, ['query-b']);
});

test('a newer non-query canvas action invalidates a delayed query neighborhood', async () => {
    const runner = uiState.createLatestTaskRunner();
    const applied = [];
    let releaseQuery;
    const runCanvasAction = uiState.createInterruptingActionRunner(
        runner,
        error => { throw error; }
    );

    const query = runner.run(
        () => new Promise(resolve => { releaseQuery = () => resolve('stale-query'); }),
        value => applied.push(value)
    );
    await Promise.resolve();

    await runCanvasAction(async () => { applied.push('new-graph-view'); });
    releaseQuery();

    assert.equal(await query, false);
    assert.deepEqual(applied, ['new-graph-view']);
});
