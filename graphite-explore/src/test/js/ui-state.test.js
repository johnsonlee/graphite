const test = require('node:test');
const assert = require('node:assert/strict');
const uiState = require('../../main/resources/web/ui-state.js');

test('an empty registry remains zero graphs', () => {
    assert.equal(uiState.graphCount({ count: 0 }, []), 0);
    assert.equal(uiState.graphCount({}, []), 0);
});

test('a failed interactive request is reported without an unhandled rejection', async () => {
    let reported;
    const result = await uiState.runRecoverable(
        async () => { throw new Error('Graph not loaded: hot-unloaded'); },
        error => { reported = error; }
    );

    assert.equal(result, undefined);
    assert.equal(reported.message, 'Graph not loaded: hot-unloaded');
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
