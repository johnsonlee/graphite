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

// Exercise the shipped rendering path without a browser or a second Cytoscape dependency.
// Geometry and edge routing are covered by browser checks; this harness verifies which
// layout receives the complete topology, including cyclic and parallel relations.
function topologyHarness({ dagreAvailable = true, width = 1100 } = {}) {
    const fs = require('node:fs');
    const path = require('node:path');
    const vm = require('node:vm');
    const controls = new Map();
    let elements = [];
    let layout;
    let ran = false;
    const canvas = {
        elements: () => ({ remove() { elements = []; } }),
        add(value) { elements = value; },
        nodes: () => elements.filter(element => element.data.nodeType),
        edges: () => elements.filter(element => element.data.edgeType),
        width: () => width,
        layout(options) {
            layout = options;
            return { run() { ran = true; } };
        },
        one() {}
    };
    const context = vm.createContext({
        GraphiteUiState: uiState,
        testCanvas: canvas,
        cytoscape: (type, name) => type === 'layout' && name === 'dagre' && dagreAvailable,
        document: {
            getElementById(id) {
                if (!controls.has(id)) controls.set(id, { addEventListener() {} });
                return controls.get(id);
            }
        },
        window: {
            addEventListener() {},
            matchMedia: () => ({ matches: true })
        }
    });
    const source = fs.readFileSync(path.join(__dirname, '../../main/resources/web/app.js'), 'utf8');
    vm.runInContext(source.slice(0, source.lastIndexOf('// Init')), context);
    vm.runInContext('cy = testCanvas;', context);
    return {
        render(data) {
            context.renderGraph(data, null, 'topology');
            assert.equal(ran, true, 'the chosen layout must actually run');
            return { elements, layout };
        }
    };
}

function topologyFixture(ids, relations = []) {
    return {
        nodes: ids.map(id => ({ id, elementId: 'graph:' + id, type: 'Graph', nodes: 100 })),
        edges: relations.map(([from, to, protocol = 'http']) => ({
            from, to, protocol, weight: 5, type: 'TopologyCall',
            fromElementId: 'graph:' + from, toElementId: 'graph:' + to,
            operations: ['call ' + protocol]
        }))
    };
}

test('topology uses a responsive grid when no drawable relations remain', () => {
    for (const relations of [[], [['a', 'missing']]]) {
        const data = topologyFixture(['a', 'b', 'c'], relations);
        const { elements, layout } = topologyHarness({ width: 480 }).render(data);
        assert.equal(layout.name, 'grid');
        assert.equal(layout.cols, 1);
        assert.equal(layout.avoidOverlap, true);
        assert.equal(elements.length, 3);
        assert.deepEqual(Array.from(elements, element => element.data.nodeId), ['a', 'b', 'c']);
    }
});

test('connected topology uses routed layers even above the generic large-graph threshold', () => {
    for (const count of [3, 190]) {
        const ids = Array.from({ length: count }, (_, i) => 'service-' + i);
        const data = topologyFixture(ids, ids.map((id, i) => [id, ids[(i + 1) % count]]));
        const { elements, layout } = topologyHarness().render(data);
        assert.equal(layout.name, 'dagre');
        assert.equal(layout.useDagreEdgeControlPoints, true);
        assert.equal(layout.nodeDimensionsIncludeLabels, true);
        assert.equal(elements.filter(element => element.data.nodeType).length, count);
        assert.equal(elements.filter(element => element.data.edgeType).length, count);
    }
});

test('topology remains drawable when the optional Dagre script is unavailable', () => {
    const data = topologyFixture(['a', 'b'], [['a', 'b'], ['b', 'a']]);
    const { elements, layout } = topologyHarness({ dagreAvailable: false }).render(data);
    assert.equal(layout.name, 'breadthfirst');
    assert.equal(layout.directed, true);
    assert.equal(layout.avoidOverlap, true);
    assert.equal(elements.length, 4);
});

test('topology retains reciprocal and parallel protocol relations in stable rendering order', () => {
    const data = topologyFixture(['c', 'b', 'a'], [
        ['b', 'a', 'http'], ['a', 'b', 'rpc'], ['b', 'c', 'http'], ['a', 'b', 'http']
    ]);
    const render = data => topologyHarness().render(data).elements;
    const first = render(data);
    const second = render({ nodes: data.nodes.slice().reverse(), edges: data.edges.slice().reverse() });
    const signature = elements => Array.from(elements, element => ({
        id: element.data.id, source: element.data.source, target: element.data.target,
        label: element.data.label
    }));
    assert.deepEqual(signature(first), signature(second));
    const relations = first.filter(element => element.data.edgeType);
    assert.equal(relations.length, 4);
    assert.equal(new Set(relations.map(element => element.data.id)).size, 4);
    assert.deepEqual(Array.from(relations, element => [
        element.data.source, element.data.target, element.data.edgeData.protocol
    ]), [
        ['graph:a', 'graph:b', 'http'], ['graph:a', 'graph:b', 'rpc'],
        ['graph:b', 'graph:a', 'http'], ['graph:b', 'graph:c', 'http']
    ]);
    for (const relation of data.edges) {
        assert.ok(relations.some(element => element.data.edgeData === relation),
            'each original relation and its inspector evidence must be preserved');
    }
});

test('Rust and JVM explorers ship identical layout assets', () => {
    const fs = require('node:fs');
    const path = require('node:path');
    const jvmAssets = path.join(__dirname, '../../main/resources/web');
    const rustAssets = path.join(__dirname, '../../../../../../backend/explore/web');
    for (const asset of ['app.js', 'index.html', 'style.css', 'ui-state.js']) {
        assert.equal(fs.readFileSync(path.join(jvmAssets, asset), 'utf8'),
            fs.readFileSync(path.join(rustAssets, asset), 'utf8'), asset + ' must match across servers');
    }
});
