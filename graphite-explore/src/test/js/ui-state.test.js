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
    const controller = uiState.createLatestIntentController();
    const runCanvasAction = uiState.createInterruptingActionRunner(
        controller,
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
    const controller = uiState.createLatestIntentController();
    const runCanvasAction = uiState.createInterruptingActionRunner(
        controller,
        error => { throw error; }
    );
    const applied = [];
    let releaseFirst;

    const first = runCanvasAction(async intent => {
        const value = await new Promise(resolve => { releaseFirst = () => resolve('query-a'); });
        intent.commit(() => applied.push(value));
    });
    await Promise.resolve();

    await runCanvasAction(async intent => {
        intent.commit(() => applied.push('query-b'));
    });

    releaseFirst();
    await first;
    assert.deepEqual(applied, ['query-b']);
});

test('a newer non-query canvas action invalidates a delayed query neighborhood', async () => {
    const controller = uiState.createLatestIntentController();
    const applied = [];
    let releaseQuery;
    const runCanvasAction = uiState.createInterruptingActionRunner(
        controller,
        error => { throw error; }
    );

    const queryIntent = controller.begin();
    const query = (async () => {
        const value = await new Promise(resolve => { releaseQuery = () => resolve('stale-query'); });
        queryIntent.commit(() => applied.push(value));
    })();
    await Promise.resolve();

    await runCanvasAction(async intent => {
        intent.commit(() => applied.push('new-graph-view'));
    });
    releaseQuery();

    await query;
    assert.deepEqual(applied, ['new-graph-view']);
});

test('a newer canvas action suppresses an older action render and error', async () => {
    const controller = uiState.createLatestIntentController();
    const applied = [];
    const errors = [];
    let releaseOld;
    const runCanvasAction = uiState.createInterruptingActionRunner(
        controller,
        error => errors.push(error.message)
    );

    const oldAction = runCanvasAction(async intent => {
        const outcome = await new Promise(resolve => { releaseOld = resolve; });
        if (outcome === 'fail') throw new Error('stale failure');
        intent.commit(() => applied.push('old-view'));
    });
    await Promise.resolve();
    await runCanvasAction(async intent => {
        intent.commit(() => applied.push('new-view'));
    });
    releaseOld('fail');
    await oldAction;

    assert.deepEqual(applied, ['new-view']);
    assert.deepEqual(errors, []);
});

test('a successful retry can clear the current canvas error', async () => {
    const controller = uiState.createLatestIntentController();
    let errorVisible = false;
    const runCanvasAction = uiState.createInterruptingActionRunner(
        controller,
        () => { errorVisible = true; },
        () => { errorVisible = false; }
    );
    let attempts = 0;
    const action = async intent => {
        attempts += 1;
        if (attempts === 1) throw new Error('temporary failure');
        intent.commit(() => {});
    };

    await runCanvasAction(action);
    assert.equal(errorVisible, true);
    await runCanvasAction(action);
    assert.equal(errorVisible, false);
});

test('a newer synchronous action clears loading owned by an aborted action', async () => {
    const controller = uiState.createLatestIntentController();
    let loadingVisible = false;
    let releaseOld;
    const runCanvasAction = uiState.createInterruptingActionRunner(
        controller,
        error => { throw error; },
        undefined,
        () => { loadingVisible = false; }
    );

    const oldAction = runCanvasAction(async () => {
        loadingVisible = true;
        await new Promise(resolve => { releaseOld = resolve; });
    });
    await Promise.resolve();
    assert.equal(loadingVisible, true);

    await runCanvasAction(async intent => { intent.commit(() => {}); });
    assert.equal(loadingVisible, false);
    releaseOld();
    await oldAction;
    assert.equal(loadingVisible, false);
});
