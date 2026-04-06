let cy;

const NODE_COLORS = {
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

const EDGE_COLORS = { DataFlow: '#30363d', Call: '#f85149', Type: '#bc8cff', ControlFlow: '#d29922' };

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
                'width': 1.2, 'line-color': 'data(color)', 'target-arrow-color': 'data(color)',
                'target-arrow-shape': 'triangle', 'arrow-scale': 0.8,
                'curve-style': 'bezier', 'opacity': 0.6
            }},
            { selector: 'node:selected', style: { 'border-width': 2, 'border-color': '#58a6ff' }},
            { selector: 'node.highlighted', style: { 'border-width': 2, 'border-color': '#fff', 'z-index': 10 }},
            { selector: 'edge:selected', style: { 'width': 2.5, 'opacity': 1 }}
        ],
        layout: { name: 'preset' },
        minZoom: 0.1, maxZoom: 5, wheelSensitivity: 0.3
    });

    cy.on('tap', 'node', e => showNodeDetail(e.target.data('nodeId')));
    cy.on('dbltap', 'node', e => loadSubgraph(e.target.data('nodeId'), 2));
    cy.on('tap', 'edge', e => {
        const target = e.target.data('target').replace('n', '');
        showNodeDetail(parseInt(target));
    });
}

async function loadDashboard() {
    const res = await fetch('/api/info');
    const info = await res.json();

    document.querySelector('#stat-nodes .stat-value').textContent = info.nodes.toLocaleString();
    document.querySelector('#stat-edges .stat-value').textContent = info.edges.toLocaleString();
    document.querySelector('#stat-methods .stat-value').textContent = info.methods.toLocaleString();
    document.querySelector('#stat-callsites .stat-value').textContent = info.callSites.toLocaleString();

    await loadTopClasses();
    await loadInitialGraph();
}

async function loadTopClasses() {
    const res = await fetch('/api/methods?limit=200');
    const methods = await res.json();

    const classCounts = {};
    methods.forEach(m => {
        const cls = m.class || '';
        classCounts[cls] = (classCounts[cls] || 0) + 1;
    });

    const sorted = Object.entries(classCounts).sort((a, b) => b[1] - a[1]).slice(0, 15);
    const list = document.getElementById('class-list');
    list.innerHTML = '';

    sorted.forEach(([cls, count]) => {
        const div = document.createElement('div');
        div.className = 'item';
        const shortName = cls.split('.').pop();
        div.innerHTML = '<span class="item-badge badge-methods">' + count + '</span>' + shortName;
        div.title = cls;
        div.onclick = () => searchByClass(cls);
        list.appendChild(div);
    });
}

async function loadInitialGraph() {
    const res = await fetch('/api/overview');
    const data = await res.json();
    renderGraph(data, null);
    document.getElementById('graph-info').textContent = `${data.nodes.length} nodes, ${data.edges.length} edges`;
}

async function showNodeDetail(nodeId) {
    const [nodeRes, outRes, inRes] = await Promise.all([
        fetch('/api/node/' + nodeId), fetch('/api/node/' + nodeId + '/outgoing'), fetch('/api/node/' + nodeId + '/incoming')
    ]);
    const node = await nodeRes.json();
    const outgoing = await outRes.json();
    const incoming = await inRes.json();

    // Highlight in graph
    cy.elements().removeClass('highlighted');
    const cyNode = cy.getElementById('n' + nodeId);
    if (cyNode.length) cyNode.addClass('highlighted');

    const panel = document.getElementById('detail-content');
    let html = '<div class="detail-block"><h4>' + node.type + '</h4><pre>' + JSON.stringify(node, null, 2) + '</pre></div>';

    if (outgoing.length > 0) {
        html += '<div class="detail-block"><h4>Outgoing (' + outgoing.length + ')</h4>';
        outgoing.slice(0, 20).forEach(e => {
            html += '<div class="detail-edge" onclick="loadSubgraph(' + e.to + ', 1)">' + e.type + (e.kind ? '.' + e.kind : '') + ' &rarr; node#' + e.to + '</div>';
        });
        if (outgoing.length > 20) html += '<div class="hint">...and ' + (outgoing.length - 20) + ' more</div>';
        html += '</div>';
    }

    if (incoming.length > 0) {
        html += '<div class="detail-block"><h4>Incoming (' + incoming.length + ')</h4>';
        incoming.slice(0, 20).forEach(e => {
            html += '<div class="detail-edge" onclick="loadSubgraph(' + e.from + ', 1)">node#' + e.from + ' &rarr; ' + e.type + (e.kind ? '.' + e.kind : '') + '</div>';
        });
        if (incoming.length > 20) html += '<div class="hint">...and ' + (incoming.length - 20) + ' more</div>';
        html += '</div>';
    }

    panel.innerHTML = html;
}

async function loadSubgraph(centerId, depth) {
    const res = await fetch('/api/subgraph?center=' + centerId + '&depth=' + depth);
    const data = await res.json();
    renderGraph(data, centerId);
    document.getElementById('graph-info').textContent = data.nodes.length + ' nodes, ' + data.edges.length + ' edges';
}

function renderGraph(data, centerId) {
    const elements = [];
    const seen = new Set();

    data.nodes.forEach(n => {
        if (seen.has(n.id)) return;
        seen.add(n.id);
        elements.push({ data: {
            id: 'n' + n.id, nodeId: n.id,
            label: truncate(n.label || n.type, 25),
            color: NODE_COLORS[n.type] || '#8b949e',
            size: NODE_SIZES[n.type] || 16
        }});
    });

    data.edges.forEach((e, i) => {
        if (!seen.has(e.from) || !seen.has(e.to)) return;
        elements.push({ data: {
            id: 'e' + e.from + '-' + e.to + '-' + i,
            source: 'n' + e.from, target: 'n' + e.to,
            color: EDGE_COLORS[e.type] || '#30363d'
        }});
    });

    cy.elements().remove();
    cy.add(elements);

    // Use fast layout for large graphs, detailed layout for small subgraphs
    var isLarge = elements.length > 200;
    var layoutOpts = isLarge
        ? { name: 'concentric', concentric: function(n) { return n.degree(); }, levelWidth: function() { return 3; }, animate: false, minNodeSpacing: 10 }
        : { name: 'cose', animate: true, animationDuration: 400, nodeRepulsion: function() { return 12000; }, idealEdgeLength: function() { return 80; }, gravity: 0.3, numIter: 200 };
    cy.layout(layoutOpts).run();

    if (centerId) {
        setTimeout(function() {
            var centerNode = cy.getElementById('n' + centerId);
            if (centerNode.length) {
                cy.animate({ center: { eles: centerNode }, zoom: 1.5, duration: 300 });
            }
        }, isLarge ? 100 : 500);
    } else {
        cy.fit(null, 30);
    }
}

function truncate(s, len) { return s.length > len ? s.substring(0, len) + '...' : s; }

async function search() {
    var query = document.getElementById('search').value.trim();
    var type = document.getElementById('search-type').value;
    if (!query) return;

    var nodeId = parseInt(query);
    if (!isNaN(nodeId) && type === 'nodes') {
        loadSubgraph(nodeId, 2);
        return;
    }

    var url;
    switch (type) {
        case 'call-sites': url = '/api/call-sites?class=' + encodeURIComponent(query) + '&limit=50'; break;
        case 'methods': url = '/api/methods?class=' + encodeURIComponent(query) + '&limit=50'; break;
        case 'nodes': url = '/api/nodes?type=' + encodeURIComponent(query) + '&limit=50'; break;
    }

    var res = await fetch(url);
    var results = await res.json();
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
            div.title = item.class + '.' + item.name + '(' + (item.returnType || '') + ')';
        } else if (item.id !== undefined) {
            var badge = type === 'call-sites' ? '<span class="item-badge badge-callsite">CS</span>' : '';
            div.innerHTML = badge + (item.label || item.type);
            div.title = 'node#' + item.id;
            div.onclick = (function(id) { return function() { loadSubgraph(id, 2); }; })(item.id);
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
document.getElementById('btn-reset').addEventListener('click', function() { cy.elements().remove(); loadInitialGraph(); });

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
                var nodeId = (typeof val === 'object' && val !== null && val.id) ? val.id : null;
                if (nodeId) {
                    html += '<td onclick="loadSubgraph(' + nodeId + ', 2)">' + display + '</td>';
                } else {
                    html += '<td>' + display + '</td>';
                }
            });
            html += '</tr>';
        });
        html += '</table>';
        html += '<div style="color: var(--text-muted); margin-top: 4px; font-size: 10px;">' + data.rowCount + ' row(s)</div>';
        resultDiv.innerHTML = html;

        var nodeIds = [];
        data.rows.forEach(function(row) {
            data.columns.forEach(function(col) {
                var val = row[col];
                if (typeof val === 'object' && val !== null && val.id) {
                    nodeIds.push(val.id);
                }
            });
        });
        if (nodeIds.length > 0 && nodeIds.length <= 50) {
            loadSubgraph(nodeIds[0], 1);
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
