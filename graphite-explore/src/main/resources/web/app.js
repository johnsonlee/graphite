let cy;
let dashboardInfo;

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
    Graph: 72, Class: 32, CallSiteNode: 28, FieldNode: 22, ParameterNode: 18, ReturnNode: 18, LocalVariable: 14
};

const EDGE_COLORS = { GraphCall: '#f85149', DataFlow: '#30363d', Call: '#f85149', Type: '#bc8cff', ControlFlow: '#d29922' };

function initCytoscape() {
    cy = cytoscape({
        container: document.getElementById('cy'),
        style: [
            { selector: 'node', style: {
                'label': 'data(label)', 'background-color': 'data(color)', 'color': '#8b949e',
                'font-size': '9px', 'text-valign': 'bottom', 'text-margin-y': 5,
                'width': 'data(size)', 'height': 'data(size)',
                'border-width': 0, 'text-max-width': '120px', 'text-wrap': 'ellipsis'
            }},
            { selector: 'edge', style: {
                'width': 'data(width)', 'line-color': 'data(color)', 'target-arrow-color': 'data(color)',
                'target-arrow-shape': 'triangle', 'arrow-scale': 0.8,
                'curve-style': 'bezier', 'opacity': 0.6
            }},
            { selector: 'node[nodeType = "Graph"]', style: {
                'shape': 'round-rectangle', 'color': '#e6edf3', 'font-size': '12px', 'font-weight': 600,
                'text-valign': 'center', 'text-margin-y': 0, 'border-width': 2, 'border-color': '#79c0ff',
                'text-max-width': '150px'
            }},
            { selector: 'edge[edgeType = "GraphCall"]', style: {
                'label': 'data(label)', 'color': '#d29922', 'font-size': '10px',
                'text-background-color': '#0d1117', 'text-background-opacity': 0.85,
                'text-background-padding': '3px', 'text-rotation': 'autorotate', 'opacity': 0.85
            }},
            { selector: 'node:selected', style: { 'border-width': 2, 'border-color': '#58a6ff' }},
            { selector: 'node.highlighted', style: { 'border-width': 2, 'border-color': '#fff', 'z-index': 10 }},
            { selector: 'edge:selected', style: { 'width': 2.5, 'opacity': 1 }}
        ],
        layout: { name: 'preset' },
        minZoom: 0.1, maxZoom: 5, wheelSensitivity: 0.3
    });

    cy.on('tap', 'node', e => {
        const nodeId = e.target.data('nodeId');
        if (Number.isInteger(nodeId)) {
            showNodeDetail(e.target.data('graphId'), nodeId);
        } else if (e.target.data('nodeType') === 'Graph') {
            showGraphDetail(e.target.data('nodeData'));
        }
    });
    cy.on('dbltap', 'node', e => {
        const nodeId = e.target.data('nodeId');
        if (Number.isInteger(nodeId)) {
            loadSubgraph(e.target.data('graphId'), nodeId, 2);
        } else if (e.target.data('nodeType') === 'Graph') {
            loadSingleGraphOverview(e.target.data('graphId'));
        }
    });
    cy.on('tap', 'edge', e => {
        const nodeId = e.target.data('targetNodeId');
        if (Number.isInteger(nodeId)) {
            showNodeDetail(e.target.data('graphId'), nodeId);
        } else if (e.target.data('edgeType') === 'GraphCall') {
            showGraphRelationDetail(e.target.data('edgeData'));
        }
    });
}

async function loadDashboard() {
    const res = await fetch('/api/graphs');
    const info = await res.json();
    dashboardInfo = info;

    const totals = info.totals || info;
    document.querySelector('#stat-nodes .stat-value').textContent = totals.nodes.toLocaleString();
    document.querySelector('#stat-edges .stat-value').textContent = totals.edges.toLocaleString();
    document.querySelector('#stat-methods .stat-value').textContent = totals.methods.toLocaleString();
    document.querySelector('#stat-callsites .stat-value').textContent = totals.callSites.toLocaleString();

    if ((info.count || 0) > 1) {
        loadGraphList(info.graphs || []);
        await loadGraphTopology();
    } else {
        await loadTopClasses();
        await loadInitialGraph();
    }
}

function loadGraphList(graphs) {
    document.getElementById('navigation-title').textContent = 'Graphs';
    const list = document.getElementById('class-list');
    list.innerHTML = '';
    graphs.forEach(function(graph) {
        const div = document.createElement('div');
        div.className = 'item';
        div.innerHTML = '<span class="item-badge badge-graph">' + Number(graph.callSites || 0).toLocaleString() + '</span>' + graph.id;
        div.title = Number(graph.nodes || 0).toLocaleString() + ' nodes, ' + Number(graph.edges || 0).toLocaleString() + ' edges';
        div.onclick = function() {
            const node = cy.getElementById(graphElementId(graph.id));
            if (node.length) {
                cy.elements().removeClass('highlighted');
                node.addClass('highlighted');
                cy.animate({ center: { eles: node }, zoom: 1.3, duration: 250 });
            }
            showGraphDetail(graph);
        };
        list.appendChild(div);
    });
}

async function loadGraphTopology() {
    const res = await fetch('/api/graph-overview');
    const data = await res.json();
    data.nodes.forEach(function(node) { node.elementId = graphElementId(node.id); });
    data.edges.forEach(function(edge) {
        edge.fromElementId = graphElementId(edge.from);
        edge.toElementId = graphElementId(edge.to);
    });
    renderGraph(data, null, 'graphs');
    let info = data.graphCount + ' graphs, ' + data.relationCount + ' call relations, ' +
        Number(data.crossGraphCallSites || 0).toLocaleString() + ' cross-graph calls';
    if (data.truncated) info += ' (ownership sample limited)';
    document.getElementById('graph-info').textContent = info;
}

async function loadTopClasses() {
    document.getElementById('navigation-title').textContent = 'Top Classes';
    const res = await fetch('/api/methods?limit=200');
    const response = await res.json();
    const methods = flattenGroupedData(response);

    const classCounts = {};
    methods.forEach(m => {
        const cls = m.class || '';
        const key = m.graphId + ':' + cls;
        classCounts[key] = (classCounts[key] || 0) + 1;
    });

    const sorted = Object.entries(classCounts).sort((a, b) => b[1] - a[1]).slice(0, 15);
    const list = document.getElementById('class-list');
    list.innerHTML = '';

    sorted.forEach(([key, count]) => {
        const separator = key.indexOf(':');
        const graphId = key.substring(0, separator);
        const cls = key.substring(separator + 1);
        const div = document.createElement('div');
        div.className = 'item';
        const shortName = cls.split('.').pop();
        div.innerHTML = '<span class="item-badge badge-methods">' + count + '</span>' + shortName;
        div.title = graphId + ': ' + cls;
        div.onclick = () => searchByClass(cls);
        list.appendChild(div);
    });
}

async function loadInitialGraph() {
    const res = await fetch('/api/overview');
    const response = await res.json();
    const data = mergeGroupedGraphs(response);
    renderGraph(data, null);
    document.getElementById('graph-info').textContent = `${data.nodes.length} nodes, ${data.edges.length} edges`;
}

async function loadSingleGraphOverview(graphId) {
    const res = await fetch('/api/graphs/' + encodeURIComponent(graphId) + '/overview');
    const data = await res.json();
    data.nodes.forEach(function(node) {
        node.graphId = graphId;
        node.elementId = graphId + ':' + node.id;
    });
    data.edges.forEach(function(edge) {
        edge.graphId = graphId;
        edge.fromElementId = graphId + ':' + edge.from;
        edge.toElementId = graphId + ':' + edge.to;
    });
    renderGraph(data, null);
    document.getElementById('graph-info').textContent = graphId + ': ' + data.nodes.length + ' classes, ' + data.edges.length + ' calls';
}

function showGraphDetail(graph) {
    cy.elements().removeClass('highlighted');
    const cyNode = cy.getElementById(graphElementId(graph.graphId || graph.id));
    if (cyNode.length) cyNode.addClass('highlighted');
    const graphId = graph.graphId || graph.id;
    const panel = document.getElementById('detail-content');
    panel.innerHTML = '<div class="detail-block"><h4>Graph</h4>' +
        '<div class="detail-row"><span class="detail-key">ID</span><span class="detail-value">' + graphId + '</span></div>' +
        graphMetricRow('Nodes', graph.nodes) + graphMetricRow('Edges', graph.edges) +
        graphMetricRow('Methods', graph.methods) + graphMetricRow('Call sites', graph.callSites) +
        '</div><div class="detail-block"><button onclick="loadSingleGraphOverview(' + htmlJsString(graphId) + ')">Explore classes</button></div>' +
        '<p class="hint">Double-click the graph node to drill down.</p>';
}

function showGraphRelationDetail(edge) {
    const panel = document.getElementById('detail-content');
    panel.innerHTML = '<div class="detail-block"><h4>Cross-graph calls</h4>' +
        '<div class="relation-route"><span>' + edge.from + '</span><strong>&rarr;</strong><span>' + edge.to + '</span></div>' +
        graphMetricRow('Call sites', edge.weight) + '</div>';
}

function graphMetricRow(label, value) {
    return '<div class="detail-row"><span class="detail-key">' + label + '</span><span class="detail-value">' +
        Number(value || 0).toLocaleString() + '</span></div>';
}

function graphElementId(graphId) { return 'graph:' + graphId; }

async function showNodeDetail(graphId, nodeId) {
    const prefix = '/api/graphs/' + encodeURIComponent(graphId);
    const [nodeRes, outRes] = await Promise.all([
        fetch(prefix + '/node/' + nodeId), fetch(prefix + '/node/' + nodeId + '/outgoing?limit=200')
    ]);
    const node = await nodeRes.json();
    const outgoing = await outRes.json();

    // Highlight in graph
    cy.elements().removeClass('highlighted');
    const cyNode = cy.getElementById(graphId + ':' + nodeId);
    if (cyNode.length) cyNode.addClass('highlighted');

    const panel = document.getElementById('detail-content');
    let html = '<div class="detail-block"><h4>' + node.type + '</h4><pre>' + JSON.stringify(node, null, 2) + '</pre></div>';

    if (outgoing.length > 0) {
        html += '<div class="detail-block"><h4>Outgoing (' + outgoing.length + ')</h4>';
        outgoing.slice(0, 20).forEach(e => {
            html += '<div class="detail-edge" onclick="loadSubgraph(' + htmlJsString(graphId) + ', ' + e.to + ', 1)">' + e.type + (e.kind ? '.' + e.kind : '') + ' &rarr; node#' + e.to + '</div>';
        });
        if (outgoing.length > 20) html += '<div class="hint">...and ' + (outgoing.length - 20) + ' more</div>';
        html += '</div>';
    }

    html += '<div class="detail-block" id="incoming-block"><button onclick="loadIncomingEdges(' + htmlJsString(graphId) + ', ' + nodeId + ')">Load incoming</button></div>';

    panel.innerHTML = html;
}

async function loadIncomingEdges(graphId, nodeId) {
    const block = document.getElementById('incoming-block');
    block.innerHTML = '<h4>Incoming</h4><div class="hint">Loading...</div>';
    const res = await fetch('/api/graphs/' + encodeURIComponent(graphId) + '/node/' + nodeId + '/incoming?limit=200');
    const incoming = await res.json();
    let html = '<h4>Incoming (' + incoming.length + ')</h4>';
    if (incoming.length > 0) {
        incoming.slice(0, 20).forEach(e => {
            html += '<div class="detail-edge" onclick="loadSubgraph(' + htmlJsString(graphId) + ', ' + e.from + ', 1)">node#' + e.from + ' &rarr; ' + e.type + (e.kind ? '.' + e.kind : '') + '</div>';
        });
        if (incoming.length > 20) html += '<div class="hint">...and ' + (incoming.length - 20) + ' more</div>';
    } else {
        html += '<div class="hint">None</div>';
    }
    block.innerHTML = html;
}

async function loadSubgraph(graphId, centerId, depth) {
    const res = await fetch('/api/graphs/' + encodeURIComponent(graphId) + '/subgraph?center=' + centerId + '&depth=' + depth + '&direction=outgoing');
    const data = await res.json();
    data.nodes.forEach(n => { n.graphId = graphId; n.elementId = graphId + ':' + n.id; });
    data.edges.forEach(e => { e.graphId = graphId; e.fromElementId = graphId + ':' + e.from; e.toElementId = graphId + ':' + e.to; });
    renderGraph(data, graphId + ':' + centerId);
    document.getElementById('graph-info').textContent = data.nodes.length + ' nodes, ' + data.edges.length + ' edges';
}

function renderGraph(data, centerId, viewMode) {
    const elements = [];
    const seen = new Set();

    data.nodes.forEach(n => {
        const elementId = n.elementId || (n.graphId + ':' + n.id);
        if (seen.has(elementId)) return;
        seen.add(elementId);
        elements.push({ data: {
            id: elementId, graphId: n.graphId, nodeId: n.id,
            nodeType: n.type, nodeData: n,
            label: truncate(n.label || n.type, 25),
            color: NODE_COLORS[n.type] || '#8b949e',
            size: n.type === 'Graph' ? graphNodeSize(n.callSites) : (NODE_SIZES[n.type] || 16)
        }});
    });

    data.edges.forEach((e, i) => {
        const from = e.fromElementId || (e.graphId + ':' + e.from);
        const to = e.toElementId || (e.graphId + ':' + e.to);
        if (!seen.has(from) || !seen.has(to)) return;
        elements.push({ data: {
            id: (e.graphId || 'graphs') + ':e:' + e.from + '-' + e.to + '-' + i,
            source: from, target: to, graphId: e.graphId, targetNodeId: e.to,
            edgeType: e.type, edgeData: e, label: e.type === 'GraphCall' ? Number(e.weight || 0).toLocaleString() : '',
            color: EDGE_COLORS[e.type] || '#30363d', width: graphEdgeWidth(e)
        }});
    });

    cy.elements().remove();
    cy.add(elements);

    // Use fast layout for large graphs, detailed layout for small subgraphs
    var isLarge = elements.length > 200;
    var layoutOpts = viewMode === 'graphs'
        ? { name: 'cose', animate: true, animationDuration: 500, nodeRepulsion: function() { return 28000; }, idealEdgeLength: function() { return 180; }, gravity: 0.2, numIter: 400 }
        : isLarge
        ? { name: 'concentric', concentric: function(n) { return n.degree(); }, levelWidth: function() { return 3; }, animate: false, minNodeSpacing: 10 }
        : { name: 'cose', animate: true, animationDuration: 400, nodeRepulsion: function() { return 12000; }, idealEdgeLength: function() { return 80; }, gravity: 0.3, numIter: 200 };
    cy.layout(layoutOpts).run();

    if (centerId) {
        setTimeout(function() {
            var centerNode = cy.getElementById(centerId);
            if (centerNode.length) {
                cy.animate({ center: { eles: centerNode }, zoom: 1.5, duration: 300 });
            }
        }, isLarge ? 100 : 500);
    } else {
        cy.fit(null, 30);
    }
}

function graphNodeSize(callSites) {
    return Math.max(NODE_SIZES.Graph, Math.min(112, 64 + Math.log10(Number(callSites || 0) + 1) * 8));
}

function graphEdgeWidth(edge) {
    if (edge.type !== 'GraphCall') return 1.2;
    return Math.max(1.5, Math.min(8, 1.5 + Math.log10(Number(edge.weight || 0) + 1) * 1.8));
}

function truncate(s, len) { return s.length > len ? s.substring(0, len) + '...' : s; }

function htmlJsString(value) {
    return JSON.stringify(value).replace(/"/g, '&quot;');
}

function flattenGroupedData(response) {
    if (!response || !Array.isArray(response.results)) return Array.isArray(response) ? response : [];
    var values = [];
    response.results.forEach(function(group) {
        var data = Array.isArray(group.data) ? group.data : [];
        data.forEach(function(item) { values.push(Object.assign({ graphId: group.graphId }, item)); });
    });
    return values;
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

async function search() {
    var query = document.getElementById('search').value.trim();
    var type = document.getElementById('search-type').value;
    if (!query) return;

    var nodeRef = query.match(/^([A-Za-z0-9][A-Za-z0-9._-]{0,127}):(\d+)$/);
    if (nodeRef && type === 'nodes') {
        loadSubgraph(nodeRef[1], parseInt(nodeRef[2]), 2);
        return;
    }

    var url;
    switch (type) {
        case 'call-sites': url = '/api/call-sites?class=' + encodeURIComponent(query) + '&limit=50'; break;
        case 'methods': url = '/api/methods?class=' + encodeURIComponent(query) + '&limit=50'; break;
        case 'nodes': url = '/api/nodes?type=' + encodeURIComponent(query) + '&limit=50'; break;
    }

    var res = await fetch(url);
    var results = flattenGroupedData(await res.json());
    showResults(results, type);
}

function searchByClass(className) {
    document.getElementById('search').value = className;
    document.getElementById('search-type').value = 'call-sites';
    search();
}

function showResults(results, type) {
    var section = document.getElementById('results-section');
    var list = document.getElementById('results-list');
    list.innerHTML = '';

    results.forEach(function(item) {
        var div = document.createElement('div');
        div.className = 'item';
        if (type === 'methods') {
            var shortClass = (item.class || '').split('.').pop();
            div.innerHTML = '<span class="item-badge badge-methods">M</span>' + shortClass + '.' + item.name + '()';
            div.title = item.graphId + ': ' + item.class + '.' + item.name + '(' + (item.returnType || '') + ')';
        } else if (item.id !== undefined) {
            var badge = type === 'call-sites' ? '<span class="item-badge badge-callsite">CS</span>' : '';
            div.innerHTML = badge + (item.label || item.type);
            div.title = item.graphId + ':node#' + item.id;
            div.onclick = (function(graphId, id) {
                return function() { loadSubgraph(graphId, id, 2); };
            })(item.graphId, item.id);
        }
        list.appendChild(div);
    });

    section.style.display = 'block';
}

// Event listeners
document.getElementById('search-btn').addEventListener('click', search);
document.getElementById('search').addEventListener('keypress', function(e) { if (e.key === 'Enter') search(); });
document.getElementById('close-results').addEventListener('click', function() { document.getElementById('results-section').style.display = 'none'; });
document.getElementById('btn-fit').addEventListener('click', function() { cy.fit(null, 30); });
document.getElementById('btn-reset').addEventListener('click', function() {
    cy.elements().remove();
    if (dashboardInfo && (dashboardInfo.count || 0) > 1) loadGraphTopology(); else loadInitialGraph();
});

// Load Cypher result nodes onto the canvas
async function loadCypherResults(nodeRefs) {
    if (nodeRefs.length === 0) return;
    // Fetch subgraphs for all result nodes and merge them
    var allNodes = new Map();
    var allEdges = [];

    // Limit to first 50 nodes to avoid overloading
    var refs = nodeRefs.slice(0, 50);

    for (var i = 0; i < refs.length; i++) {
        try {
            var ref = refs[i];
            var res = await fetch('/api/graphs/' + encodeURIComponent(ref.graphId) + '/subgraph?center=' + ref.id + '&depth=1&direction=outgoing');
            var data = await res.json();
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
        } catch (e) { /* skip failed fetches */ }
    }

    renderGraph({ nodes: Array.from(allNodes.values()), edges: allEdges }, refs[0].graphId + ':' + refs[0].id);
    document.getElementById('graph-info').textContent = allNodes.size + ' nodes, ' + allEdges.length + ' edges';
}

// Cypher query support
async function runCypher() {
    var query = document.getElementById('cypher-input').value.trim();
    if (!query) return;

    var resultDiv = document.getElementById('cypher-result');
    resultDiv.innerHTML = '<span style="color: var(--text-muted)">Running...</span>';

    try {
        var res = await fetch('/api/cypher', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ query: query })
        });
        var data = await res.json();

        if (data.error) {
            resultDiv.innerHTML = '<div id="cypher-error">' + data.error + '</div>';
            return;
        }

        if (!data.columns || data.columns.length === 0) {
            resultDiv.innerHTML = '<span style="color: var(--text-muted)">(no results)</span>';
            return;
        }

        var html = '<table><tr>';
        data.columns.forEach(function(col) { html += '<th>' + col + '</th>'; });
        html += '</tr>';
        data.rows.forEach(function(row) {
            html += '<tr>';
            data.columns.forEach(function(col) {
                var val = row[col];
                var display = val === null ? 'null' : (typeof val === 'object' ? JSON.stringify(val) : val);
                html += '<td>' + display + '</td>';
            });
            html += '</tr>';
        });
        html += '</table>';
        html += '<div style="color: var(--text-muted); margin-top: 4px; font-size: 10px;">' + data.rowCount + ' row(s)</div>';
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
        if (nodeRefs.length > 0) {
            loadCypherResults(nodeRefs);
        }
    } catch (e) {
        resultDiv.innerHTML = '<div id="cypher-error">Error: ' + e.message + '</div>';
    }
}

document.getElementById('cypher-run').addEventListener('click', runCypher);
document.getElementById('cypher-input').addEventListener('keydown', function(e) {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) runCypher();
});

// Init
initCytoscape();
loadDashboard();
