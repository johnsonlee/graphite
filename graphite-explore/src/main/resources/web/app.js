let cy;
let dashboardInfo;
let activeGraphId;
let resizeTimer;
let incomingLoadGeneration = 0;
const canvasIntentController = GraphiteUiState.createLatestIntentController();
const runCanvasAction = GraphiteUiState.createInterruptingActionRunner(
    canvasIntentController,
    function(error, action) { setCanvasError(error, action); },
    clearCanvasError,
    clearCanvasLoading
);

const NODE_COLORS = {
    Graph: '#58a6ff', Class: '#58a6ff',
    CallSiteNode: '#f85149',
    IntConstant: '#39d2c0', StringConstant: '#58a6ff', EnumConstant: '#d29922',
    FieldNode: '#bc8cff', ParameterNode: '#3fb950', ReturnNode: '#f778ba',
    LocalVariable: '#8b949e',
    LongConstant: '#39d2c0', FloatConstant: '#39d2c0', DoubleConstant: '#39d2c0',
    BooleanConstant: '#39d2c0', NullConstant: '#484f58'
};

const NODE_SIZES = {
    CallSiteNode: 28, FieldNode: 22, ParameterNode: 18, ReturnNode: 18, LocalVariable: 14
};

const EDGE_COLORS = { TopologyCall: '#f85149', DataFlow: '#30363d', Call: '#f85149', Type: '#bc8cff', ControlFlow: '#d29922' };

function initCytoscape() {
    cy = cytoscape({
        container: document.getElementById('cy'),
        style: [
            { selector: 'node', style: {
                'label': 'data(label)', 'background-color': 'data(color)', 'color': '#a8b6c8',
                'font-size': '10px', 'text-valign': 'bottom', 'text-margin-y': 7,
                'width': 'data(size)', 'height': 'data(size)',
                'border-width': 1, 'border-color': '#314158', 'text-max-width': '140px', 'text-wrap': 'ellipsis',
                'transition-property': 'border-color, border-width, opacity, background-color',
                'transition-duration': '160ms'
            }},
            { selector: 'edge', style: {
                'width': 'data(width)', 'line-color': 'data(color)', 'target-arrow-color': 'data(color)',
                'target-arrow-shape': 'triangle', 'arrow-scale': 0.8,
                'curve-style': 'bezier', 'opacity': 0.48
            }},
            { selector: 'node[nodeType = "Graph"]', style: {
                'shape': 'round-rectangle', 'width': 216, 'height': 86,
                'background-color': '#132131', 'background-fill': 'linear-gradient',
                'background-gradient-stop-colors': '#1d3b4e #1d3b4e #132131 #132131',
                'background-gradient-stop-positions': '0% 34% 34% 100%', 'background-gradient-direction': 'to-bottom',
                'color': '#f2f6fb', 'font-size': '11px', 'font-weight': 500,
                'text-valign': 'center', 'text-margin-y': 0, 'border-width': 1.5, 'border-color': '#477092',
                'text-max-width': '190px', 'text-wrap': 'wrap', 'text-overflow-wrap': 'anywhere',
                'text-justification': 'left', 'line-height': 1.6
            }},
            { selector: 'node[nodeType = "Class"]', style: {
                'shape': 'round-rectangle', 'width': 126, 'height': 36,
                'background-color': '#132131', 'color': '#dce8f5', 'font-size': '10px',
                'text-valign': 'center', 'text-margin-y': 0, 'border-color': '#36536d',
                'text-max-width': '108px', 'text-wrap': 'ellipsis'
            }},
            { selector: 'edge[edgeType = "TopologyCall"]', style: {
                'label': 'data(label)', 'color': '#ffb86b', 'font-size': '9px',
                'text-background-color': '#070b11', 'text-background-opacity': 0.9,
                'text-background-padding': '4px', 'text-rotation': 'autorotate', 'opacity': 0.68
            }},
            { selector: 'node:active', style: { 'overlay-opacity': 0.08, 'overlay-color': '#5ee4c2' }},
            { selector: 'node:selected', style: { 'border-width': 2.5, 'border-color': '#5ee4c2' }},
            { selector: 'node.highlighted', style: { 'border-width': 2.5, 'border-color': '#5ee4c2', 'z-index': 10 }},
            { selector: 'edge:selected', style: { 'width': 2.5, 'opacity': 1 }}
        ],
        layout: { name: 'preset' },
        minZoom: 0.1, maxZoom: 5
    });

    cy.on('tap', 'node', e => {
        const nodeId = e.target.data('nodeId');
        if (Number.isInteger(nodeId)) {
            runCanvasAction(function(intent) { return showNodeDetail(e.target.data('graphId'), nodeId, intent); });
        } else if (e.target.data('nodeType') === 'Graph') {
            runCanvasAction(function(intent) {
                intent.commit(function() { showGraphDetail(e.target.data('nodeData')); });
            });
        }
    });
    cy.on('dbltap', 'node', e => {
        const nodeId = e.target.data('nodeId');
        if (Number.isInteger(nodeId)) {
            exploreSubgraph(e.target.data('graphId'), nodeId, 2);
        } else if (e.target.data('nodeType') === 'Graph') {
            exploreGraph(e.target.data('graphId'));
        }
    });
    cy.on('tap', 'edge', e => {
        const nodeId = e.target.data('targetNodeId');
        if (Number.isInteger(nodeId)) {
            runCanvasAction(function(intent) { return showNodeDetail(e.target.data('graphId'), nodeId, intent); });
        } else if (e.target.data('edgeType') === 'TopologyCall') {
            runCanvasAction(function(intent) {
                intent.commit(function() { showTopologyRelationDetail(e.target.data('edgeData')); });
            });
        }
    });
}

async function loadDashboard(intent) {
    setCanvasState('Loading workspace');
    setServerStatus('connecting', 'Connecting');
    try {
        const info = await fetchJson('/api/graphs', { signal: intent.signal });
        if (!intent.isCurrent()) return;
        dashboardInfo = info;

        const totals = info.totals || info;
        setMetric('stat-nodes', totals.nodes);
        setMetric('stat-edges', totals.edges);
        setMetric('stat-methods', totals.methods);
        setMetric('stat-callsites', totals.callSites);

        const graphs = info.graphs || [];
        const graphCount = GraphiteUiState.graphCount(info, graphs);
        const countBadge = document.getElementById('graph-count');
        const navigationSection = document.getElementById('navigation-section');
        countBadge.textContent = graphCount;
        countBadge.title = graphCount.toLocaleString() + (graphCount === 1 ? ' graph' : ' graphs');
        setServerStatus('connected', 'Connected');

        if (graphCount > 1) {
            navigationSection.hidden = false;
            loadGraphList(graphs);
            await loadGraphTopology(intent);
        } else {
            navigationSection.hidden = true;
            document.getElementById('class-list').innerHTML = '';
            activeGraphId = graphs.length === 1 ? graphs[0].id : null;
            await loadInitialGraph(intent);
        }
    } catch (error) {
        if (intent.isCurrent() && error.name !== 'AbortError') setServerStatus('error', 'Unavailable');
        throw error;
    }
}

function loadGraphList(graphs) {
    document.getElementById('navigation-title').textContent = 'Graphs';
    const list = document.getElementById('class-list');
    list.innerHTML = '';
    graphs.forEach(function(graph) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'item';
        button.innerHTML = '<span class="item-copy"><span class="item-label">' + escapeHtml(graph.id) + '</span>' +
            '<span class="item-subtitle">' + formatCompactMetric(graph.nodes) + ' nodes · ' +
            formatCompactMetric(graph.edges) + ' edges · ' + formatCompactMetric(graph.methods) + ' methods</span></span>' +
            '<span class="item-call-count">' + formatCompactMetric(graph.callSites) + '<small>calls</small></span>';
        button.title = graph.id + ' — ' + Number(graph.nodes || 0).toLocaleString() + ' nodes, ' +
            Number(graph.edges || 0).toLocaleString() + ' edges, ' + Number(graph.methods || 0).toLocaleString() +
            ' methods, ' + Number(graph.callSites || 0).toLocaleString() + ' call sites';
        button.onclick = function() {
            runCanvasAction(function(intent) {
                intent.commit(function() {
                    list.querySelectorAll('.item').forEach(function(item) { item.classList.remove('selected'); });
                    button.classList.add('selected');
                    activeGraphId = graph.id;
                    const node = cy.getElementById(graphElementId(graph.id));
                    if (node.length) {
                        cy.elements().removeClass('highlighted');
                        node.addClass('highlighted');
                        cy.animate({ center: { eles: node }, zoom: 1.3, duration: 250 });
                    }
                    showGraphDetail(graph);
                });
            });
        };
        list.appendChild(button);
    });
}

async function loadGraphTopology(intent) {
    setCanvasState('Loading graph topology');
    const data = await fetchJson('/api/topology', { signal: intent.signal });
    data.nodes.forEach(function(node) { node.elementId = graphElementId(node.id); });
    data.edges.forEach(function(edge) {
        edge.fromElementId = graphElementId(edge.from);
        edge.toElementId = graphElementId(edge.to);
    });
    intent.commit(function() {
        renderGraph(data, null, 'topology', intent);
        let info = data.graphCount + ' graphs, ' + data.relationCount + ' call relations, ' +
            Number(data.matchedRows || 0).toLocaleString() + ' matched rows';
        if (data.stale) info += ' (topology is stale)';
        setGraphInfo(info);
    });
}

async function loadInitialGraph(intent) {
    const graphs = dashboardInfo && Array.isArray(dashboardInfo.graphs) ? dashboardInfo.graphs : [];
    if (graphs.length === 1 && graphs[0].id) {
        await loadSingleGraphOverview(graphs[0].id, intent);
        return;
    }
    setCanvasState('Loading class overview');
    const response = await fetchJson('/api/overview', { signal: intent.signal });
    const data = mergeGroupedGraphs(response);
    intent.commit(function() {
        renderGraph(data, null, undefined, intent);
        setGraphInfo(`${data.nodes.length} classes, ${data.edges.length} calls`);
    });
}

async function loadSingleGraphOverview(graphId, intent) {
    setCanvasState('Loading ' + graphId);
    const data = await fetchJson('/api/graphs/' + encodeURIComponent(graphId) + '/overview', { signal: intent.signal });
    data.nodes.forEach(function(node) {
        node.graphId = graphId;
        node.elementId = graphId + ':' + node.id;
    });
    data.edges.forEach(function(edge) {
        edge.graphId = graphId;
        edge.fromElementId = graphId + ':' + edge.from;
        edge.toElementId = graphId + ':' + edge.to;
    });
    intent.commit(function() {
        renderGraph(data, null, undefined, intent);
        setGraphInfo(data.nodes.length + ' classes, ' + data.edges.length + ' calls');
    });
}

function showGraphDetail(graph) {
    cy.elements().removeClass('highlighted');
    const graphId = graph.graphId || graph.id;
    const cyNode = cy.getElementById(graphElementId(graphId));
    if (cyNode.length) cyNode.addClass('highlighted');
    const panel = document.getElementById('detail-content');
    panel.innerHTML = '<div class="detail-block"><h4>Graph</h4>' +
        '<div class="detail-row"><span class="detail-key">ID</span><span class="detail-value">' + escapeHtml(graphId) + '</span></div>' +
        graphMetricRow('Nodes', graph.nodes) + graphMetricRow('Edges', graph.edges) +
        graphMetricRow('Methods', graph.methods) + graphMetricRow('Call sites', graph.callSites) +
        '</div><div class="detail-block"><button onclick="exploreGraph(' + htmlJsString(graphId) + ')">Explore classes</button></div>' +
        '<p class="hint">Double-click the graph node to drill down.</p>';
}

function showTopologyRelationDetail(edge) {
    const operations = (edge.operations || []).slice(0, 20).map(escapeHtml).join('<br>');
    const evidence = (edge.evidence || []).slice(0, 20).map(escapeHtml).join('<br>');
    const panel = document.getElementById('detail-content');
    panel.innerHTML = '<div class="detail-block"><h4>Topology call</h4>' +
        '<div class="relation-route"><span>' + escapeHtml(edge.from) + '</span><strong>&rarr;</strong><span>' + escapeHtml(edge.to) + '</span></div>' +
        '<div class="detail-row"><span class="detail-key">Protocol</span><span class="detail-value">' + escapeHtml(edge.protocol || 'call') + '</span></div>' +
        graphMetricRow('Weight', edge.weight) + '</div>' +
        (operations ? '<div class="detail-block"><h4>Operations</h4><pre>' + operations + '</pre></div>' : '') +
        (evidence ? '<div class="detail-block"><h4>Evidence</h4><pre>' + evidence + '</pre></div>' : '');
}

function graphMetricRow(label, value) {
    return '<div class="detail-row"><span class="detail-key">' + label + '</span><span class="detail-value">' +
        Number(value || 0).toLocaleString() + '</span></div>';
}

function graphElementId(graphId) { return 'graph:' + graphId; }

async function showNodeDetail(graphId, nodeId, intent) {
    const prefix = '/api/graphs/' + encodeURIComponent(graphId);
    const options = { signal: intent.signal };
    const [node, outgoing] = await Promise.all([
        fetchJson(prefix + '/node/' + nodeId, options),
        fetchJson(prefix + '/node/' + nodeId + '/outgoing?limit=200', options)
    ]);

    let html = '<div class="detail-block"><h4>' + escapeHtml(node.type) + '</h4><pre>' +
        escapeHtml(JSON.stringify(node, null, 2)) + '</pre></div>';

    if (outgoing.length > 0) {
        html += '<div class="detail-block"><h4>Outgoing (' + outgoing.length + ')</h4>';
        outgoing.slice(0, 20).forEach(e => {
            html += '<div class="detail-edge" onclick="exploreSubgraph(' + htmlJsString(graphId) + ', ' + e.to + ', 1)">' + e.type + (e.kind ? '.' + e.kind : '') + ' &rarr; node#' + e.to + '</div>';
        });
        if (outgoing.length > 20) html += '<div class="hint">...and ' + (outgoing.length - 20) + ' more</div>';
        html += '</div>';
    }

    html += '<div class="detail-block" id="incoming-block"><button onclick="exploreIncomingEdges(' + htmlJsString(graphId) + ', ' + nodeId + ')">Load incoming</button></div>';

    intent.commit(function() {
        cy.elements().removeClass('highlighted');
        const cyNode = cy.getElementById(graphId + ':' + nodeId);
        if (cyNode.length) cyNode.addClass('highlighted');
        document.getElementById('detail-content').innerHTML = html;
    });
}

async function loadIncomingEdges(graphId, nodeId, intent) {
    const block = document.getElementById('incoming-block');
    const idleHtml = block.innerHTML;
    const loadToken = String(++incomingLoadGeneration);
    block.dataset.incomingLoad = loadToken;
    block.innerHTML = '<h4>Incoming</h4><div class="hint">Loading...</div>';
    let incoming;
    try {
        incoming = await fetchJson(
            '/api/graphs/' + encodeURIComponent(graphId) + '/node/' + nodeId + '/incoming?limit=200',
            { signal: intent.signal }
        );
    } catch (error) {
        if (block.isConnected && block.dataset.incomingLoad === loadToken) {
            delete block.dataset.incomingLoad;
            block.innerHTML = idleHtml;
        }
        throw error;
    }
    let html = '<h4>Incoming (' + incoming.length + ')</h4>';
    if (incoming.length > 0) {
        incoming.slice(0, 20).forEach(e => {
            html += '<div class="detail-edge" onclick="exploreSubgraph(' + htmlJsString(graphId) + ', ' + e.from + ', 1)">node#' + e.from + ' &rarr; ' + e.type + (e.kind ? '.' + e.kind : '') + '</div>';
        });
        if (incoming.length > 20) html += '<div class="hint">...and ' + (incoming.length - 20) + ' more</div>';
    } else {
        html += '<div class="hint">None</div>';
    }
    intent.commit(function() {
        delete block.dataset.incomingLoad;
        block.innerHTML = html;
    });
}

function exploreIncomingEdges(graphId, nodeId) {
    return runCanvasAction(function(intent) { return loadIncomingEdges(graphId, nodeId, intent); });
}

async function loadSubgraph(graphId, centerId, depth, intent) {
    setCanvasState('Loading neighborhood');
    const data = await fetchJson(
        '/api/graphs/' + encodeURIComponent(graphId) + '/subgraph?center=' + centerId + '&depth=' + depth + '&direction=outgoing',
        { signal: intent.signal }
    );
    data.nodes.forEach(n => { n.graphId = graphId; n.elementId = graphId + ':' + n.id; });
    data.edges.forEach(e => { e.graphId = graphId; e.fromElementId = graphId + ':' + e.from; e.toElementId = graphId + ':' + e.to; });
    intent.commit(function() {
        renderGraph(data, graphId + ':' + centerId, undefined, intent);
        setGraphInfo(data.nodes.length + ' nodes, ' + data.edges.length + ' edges');
    });
}

function exploreGraph(graphId) {
    return runCanvasAction(function(intent) { return loadSingleGraphOverview(graphId, intent); });
}

function exploreSubgraph(graphId, centerId, depth) {
    return runCanvasAction(function(intent) { return loadSubgraph(graphId, centerId, depth, intent); });
}

function renderGraph(data, centerId, viewMode, intent) {
    const elements = [];
    const seen = new Set();

    data.nodes.forEach(n => {
        const elementId = n.elementId || (n.graphId + ':' + n.id);
        if (seen.has(elementId)) return;
        seen.add(elementId);
        const isCardNode = n.type === 'Graph' || n.type === 'Class';
        elements.push({ data: {
            id: elementId, graphId: n.graphId, nodeId: n.id,
            nodeType: n.type, nodeData: n,
            label: n.type === 'Graph' ? graphCardLabel(n) :
                (isCardNode ? (n.label || n.id || n.type) : truncate(n.label || n.type, 32)),
            color: NODE_COLORS[n.type] || '#8b949e',
            size: NODE_SIZES[n.type] || 16
        }});
    });

    data.edges.forEach((e, i) => {
        const from = e.fromElementId || (e.graphId + ':' + e.from);
        const to = e.toElementId || (e.graphId + ':' + e.to);
        if (!seen.has(from) || !seen.has(to)) return;
        elements.push({ data: {
            id: (e.graphId || 'topology') + ':e:' + e.from + '-' + e.to + '-' + i,
            source: from, target: to, graphId: e.graphId, targetNodeId: e.to,
            edgeType: e.type, edgeData: e,
            label: e.type === 'TopologyCall' ? (e.protocol || 'call') + ' · ' + Number(e.weight || 0).toLocaleString() : '',
            color: EDGE_COLORS[e.type] || '#30363d', width: topologyEdgeWidth(e)
        }});
    });

    cy.elements().remove();
    cy.add(elements);

    const nodeCount = data.nodes.length;
    const edgeCount = data.edges.length;
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    let layoutOpts;
    if (viewMode === 'topology' && edgeCount === 0) {
        layoutOpts = topologyGridOptions();
    } else if (nodeCount > 180) {
        layoutOpts = {
            name: 'concentric', animate: false, minNodeSpacing: 16,
            concentric: function(node) { return node.degree(); }, levelWidth: function() { return 3; }
        };
    } else {
        layoutOpts = {
            name: 'cose', animate: !reduceMotion, animationDuration: 420,
            nodeRepulsion: function() { return viewMode === 'topology' ? 42000 : 15000; },
            idealEdgeLength: function() { return viewMode === 'topology' ? 210 : 92; },
            gravity: viewMode === 'topology' ? 0.16 : 0.28, numIter: 420
        };
    }

    const layout = cy.layout(layoutOpts);
    cy.one('layoutstop', function() {
        if (intent && !intent.isCurrent()) return;
        finishGraphLayout(centerId, reduceMotion);
        setCanvasState();
    });
    layout.run();
}

function topologyGridOptions() {
    const canvasWidth = cy.width();
    const graphCount = cy.nodes().length;
    const graphCardWidth = 216;
    const cardGap = 28;
    const fitColumns = Math.max(1, Math.floor((canvasWidth - 72 + cardGap) / (graphCardWidth + cardGap)));
    const balancedColumns = Math.ceil(Math.sqrt(graphCount));
    const responsiveLimit = canvasWidth < 560 ? 1 : (canvasWidth < 960 ? 2 : 6);
    return {
        name: 'grid',
        cols: Math.min(graphCount, balancedColumns, fitColumns, responsiveLimit),
        avoidOverlap: true,
        avoidOverlapPadding: 24,
        condense: true,
        spacingFactor: 1.06
    };
}

function finishGraphLayout(centerId, reduceMotion) {
    if (centerId) {
        const centerNode = cy.getElementById(centerId);
        if (centerNode.length) {
            if (reduceMotion) {
                cy.center(centerNode);
                cy.zoom(Math.min(1.35, cy.zoom()));
            } else {
                cy.animate({ center: { eles: centerNode }, zoom: 1.28, duration: 260 });
            }
            return;
        }
    }
    cy.fit(null, 72);
    if (cy.zoom() > 1.05) {
        cy.zoom({ level: 1.05, renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } });
        cy.center();
    }
}

function topologyEdgeWidth(edge) {
    if (edge.type !== 'TopologyCall') return 1.2;
    return Math.max(1.5, Math.min(8, 1.5 + Math.log10(Number(edge.weight || 0) + 1) * 1.8));
}

function truncate(s, len) { return s.length > len ? s.substring(0, len) + '...' : s; }

function escapeHtml(value) {
    return String(value).replace(/[&<>"']/g, function(character) {
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character];
    });
}

async function fetchJson(url, options) {
    const response = await fetch(url, options);
    let data;
    try {
        data = await response.json();
    } catch (_) {
        data = null;
    }
    if (!response.ok) {
        const message = data && data.error ? data.error : response.status + ' ' + response.statusText;
        throw new Error(message);
    }
    return data;
}

function formatCompactMetric(value) {
    const number = Number(value || 0);
    return new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(number);
}

function graphCardLabel(graph) {
    const id = graph.label || graph.id || graph.graphId || 'Graph';
    return id + '\n\n' + formatCompactMetric(graph.nodes) + ' nodes  ·  ' + formatCompactMetric(graph.edges) + ' edges\n' +
        formatCompactMetric(graph.methods) + ' methods  ·  ' + formatCompactMetric(graph.callSites) + ' calls';
}

function setMetric(id, value) {
    const card = document.getElementById(id);
    const number = Number(value || 0);
    card.querySelector('.stat-value').textContent = formatCompactMetric(number);
    card.title = number.toLocaleString() + ' ' + card.querySelector('.stat-label').textContent.toLowerCase();
}

function setServerStatus(state, label) {
    const status = document.getElementById('server-status');
    status.classList.remove('connected', 'error');
    if (state === 'connected' || state === 'error') status.classList.add(state);
    status.querySelector('.status-label').textContent = label;
}

function setGraphInfo(info) {
    document.getElementById('graph-info').textContent = info;
}

function setCanvasState(message) {
    const state = document.getElementById('canvas-state');
    state.classList.remove('error');
    if (!message) {
        state.classList.add('hidden');
        return;
    }
    state.classList.remove('hidden');
    state.innerHTML = '<span class="spinner" aria-hidden="true"></span><span>' + escapeHtml(message) + '</span>';
}

function setCanvasError(error, retry) {
    const state = document.getElementById('canvas-state');
    state.classList.remove('hidden');
    state.classList.add('error');
    state.innerHTML = '<strong>Unable to load the graph</strong><span>' + escapeHtml(error.message || error) + '</span>';
    if (retry) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'retry-button';
        button.textContent = 'Try again';
        button.addEventListener('click', function() { runCanvasAction(retry); });
        state.appendChild(button);
    }
}

function clearCanvasError() {
    const state = document.getElementById('canvas-state');
    if (state.classList.contains('error')) setCanvasState();
}

function clearCanvasLoading() {
    const state = document.getElementById('canvas-state');
    if (!state.classList.contains('error')) setCanvasState();
}

function htmlJsString(value) {
    return JSON.stringify(value).replace(/"/g, '&quot;');
}

function mergeGroupedGraphs(response) {
    if (!response || !Array.isArray(response.results)) return response;
    var nodes = [];
    var edges = [];
    response.results.forEach(function(group) {
        var data = group.data || { nodes: [], edges: [] };
        (data.nodes || []).forEach(function(node) {
            nodes.push(Object.assign({}, node, {
                graphId: group.graphId,
                elementId: group.graphId + ':' + node.id
            }));
        });
        (data.edges || []).forEach(function(edge) {
            edges.push(Object.assign({}, edge, {
                graphId: group.graphId,
                fromElementId: group.graphId + ':' + edge.from,
                toElementId: group.graphId + ':' + edge.to
            }));
        });
    });
    return { nodes: nodes, edges: edges };
}

// Event listeners
document.getElementById('btn-fit').addEventListener('click', function() { finishGraphLayout(null, false); });
window.addEventListener('resize', function() {
    window.clearTimeout(resizeTimer);
    resizeTimer = window.setTimeout(function() {
        if (!cy || cy.nodes().length === 0) return;
        cy.resize();
        const isTopologyOverview = cy.edges().length === 0 && cy.nodes().every(function(node) {
            return node.data('nodeType') === 'Graph';
        });
        if (isTopologyOverview) {
            const layout = cy.layout(topologyGridOptions());
            cy.one('layoutstop', function() { finishGraphLayout(null, true); });
            layout.run();
        } else {
            finishGraphLayout(null, true);
        }
    }, 140);
});
document.getElementById('btn-reset').addEventListener('click', function() {
    cy.elements().remove();
    activeGraphId = null;
    document.querySelectorAll('.item-list .item').forEach(function(item) { item.classList.remove('selected'); });
    const reload = dashboardInfo && (dashboardInfo.count || 0) > 1 ? loadGraphTopology : loadInitialGraph;
    runCanvasAction(reload);
});

// Load Cypher result nodes onto the canvas
async function loadCypherResults(nodeRefs, intent) {
    if (nodeRefs.length === 0) return;
    var refs = nodeRefs.slice(0, 50);
    var allNodes = new Map();
    var allEdges = [];
    var firstError;

    for (var i = 0; i < refs.length; i++) {
        try {
            var ref = refs[i];
            var data = await fetchJson(
                '/api/graphs/' + encodeURIComponent(ref.graphId) + '/subgraph?center=' + ref.id + '&depth=1&direction=outgoing',
                { signal: intent.signal }
            );
            data.nodes.forEach(function(n) {
                n.graphId = ref.graphId;
                n.elementId = ref.graphId + ':' + n.id;
                if (!allNodes.has(n.elementId)) allNodes.set(n.elementId, n);
            });
            data.edges.forEach(function(e) {
                e.graphId = ref.graphId;
                e.fromElementId = ref.graphId + ':' + e.from;
                e.toElementId = ref.graphId + ':' + e.to;
                allEdges.push(e);
            });
        } catch (error) {
            if (!intent.isCurrent() || error.name === 'AbortError') throw error;
            if (!firstError) firstError = error;
        }
    }
    if (allNodes.size === 0 && firstError) throw firstError;
    var result = { nodes: Array.from(allNodes.values()), edges: allEdges };
    intent.commit(function() {
        renderGraph(result, refs[0].graphId + ':' + refs[0].id, undefined, intent);
        setGraphInfo(result.nodes.length + ' nodes, ' + result.edges.length + ' edges');
    });
}

// Cypher query support
async function runCypher() {
    var query = document.getElementById('cypher-input').value.trim();
    var runButton = document.getElementById('cypher-run');
    if (!query || runButton.disabled) return;
    clearCanvasLoading();
    var queryIntent = canvasIntentController.begin();

    var resultDiv = document.getElementById('cypher-result');
    runButton.disabled = true;
    runButton.classList.add('running');
    runButton.querySelector('.run-label').textContent = 'Running';
    resultDiv.setAttribute('aria-busy', 'true');
    resultDiv.innerHTML = '<div class="query-state">Running query…</div>';

    try {
        var data = await fetchJson('/api/cypher', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ query: query })
        });

        if (data.error) {
            resultDiv.innerHTML = '<div id="cypher-error">' + escapeHtml(data.error) + '</div>';
            return;
        }

        if (!data.columns || data.columns.length === 0) {
            resultDiv.innerHTML = '<div class="query-state">Query completed with no results.</div>';
            return;
        }

        var html = '<table><thead><tr>';
        data.columns.forEach(function(col) { html += '<th>' + escapeHtml(col) + '</th>'; });
        html += '</tr></thead><tbody>';
        data.rows.forEach(function(row) {
            html += '<tr>';
            data.columns.forEach(function(col) {
                var val = row[col];
                var display = val === null ? 'null' : (typeof val === 'object' ? JSON.stringify(val) : val);
                html += '<td title="' + escapeHtml(display) + '">' + escapeHtml(display) + '</td>';
            });
            html += '</tr>';
        });
        html += '</tbody></table>';
        html += '<div class="query-summary">' + Number(data.rowCount || data.rows.length).toLocaleString() + ' rows returned</div>';
        resultDiv.innerHTML = html;

        // Collect node IDs from results
        var nodeRefs = [];
        data.rows.forEach(function(row) {
            var graphIds = row.$metadata && row.$metadata.graphIds;
            data.columns.forEach(function(col) {
                var val = row[col];
                // Object with .id (node reference)
                if (typeof val === 'object' && val !== null && val.id !== undefined && val.graphId) {
                    nodeRefs.push({ graphId: val.graphId, id: val.id });
                }
                // A scalar local id is resolvable only when row provenance names one graph.
                else if (col.endsWith('.id') && typeof val === 'number' && graphIds && graphIds.length === 1) {
                    nodeRefs.push({ graphId: graphIds[0], id: val });
                }
                // Path variable (list of nodes/edges)
                else if (Array.isArray(val)) {
                    val.forEach(function(item) {
                        if (typeof item === 'object' && item !== null && item.id !== undefined && item.graphId) {
                            nodeRefs.push({ graphId: item.graphId, id: item.id });
                        }
                    });
                }
            });
        });
        // Deduplicate
        var uniqueRefs = new Map();
        nodeRefs.forEach(function(ref) { uniqueRefs.set(ref.graphId + ':' + ref.id, ref); });
        nodeRefs = Array.from(uniqueRefs.values());
        if (nodeRefs.length > 0 && queryIntent.isCurrent()) {
            runCanvasAction(function(intent) { return loadCypherResults(nodeRefs, intent); });
        }
    } catch (e) {
        resultDiv.innerHTML = '<div id="cypher-error">' + escapeHtml(e.message || e) + '</div>';
    } finally {
        runButton.disabled = false;
        runButton.classList.remove('running');
        runButton.querySelector('.run-label').textContent = 'Run query';
        resultDiv.removeAttribute('aria-busy');
    }
}

document.getElementById('cypher-run').addEventListener('click', runCypher);
document.getElementById('cypher-input').addEventListener('keydown', function(e) {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) runCypher();
});

// Init
initCytoscape();
runCanvasAction(loadDashboard);
