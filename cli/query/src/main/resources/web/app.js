let cy;
let currentGraph = null;

// Color scheme by node type
const NODE_COLORS = {
    CallSiteNode: '#e94560',
    IntConstant: '#4ecdc4',
    StringConstant: '#45b7d1',
    EnumConstant: '#f9ca24',
    FieldNode: '#6c5ce7',
    ParameterNode: '#00b894',
    ReturnNode: '#fd79a8',
    LocalVariable: '#636e72',
    LongConstant: '#4ecdc4',
    FloatConstant: '#4ecdc4',
    DoubleConstant: '#4ecdc4',
    BooleanConstant: '#4ecdc4',
    NullConstant: '#b2bec3'
};

const EDGE_COLORS = {
    DataFlow: '#555',
    Call: '#e94560',
    Type: '#6c5ce7',
    ControlFlow: '#fdcb6e'
};

function initCytoscape() {
    cy = cytoscape({
        container: document.getElementById('cy'),
        style: [
            { selector: 'node', style: {
                'label': 'data(label)',
                'background-color': 'data(color)',
                'color': '#e0e0e0',
                'font-size': '10px',
                'text-valign': 'bottom',
                'text-margin-y': 4,
                'width': 20,
                'height': 20
            }},
            { selector: 'edge', style: {
                'width': 1.5,
                'line-color': 'data(color)',
                'target-arrow-color': 'data(color)',
                'target-arrow-shape': 'triangle',
                'curve-style': 'bezier',
                'label': 'data(label)',
                'font-size': '8px',
                'color': '#666',
                'text-rotation': 'autorotate'
            }},
            { selector: 'node:selected', style: {
                'border-width': 3,
                'border-color': '#fff'
            }}
        ],
        layout: { name: 'cose', animate: false, nodeRepulsion: 8000 }
    });

    cy.on('tap', 'node', function(evt) {
        const nodeId = evt.target.data('nodeId');
        showNodeDetail(nodeId);
    });

    cy.on('tap', 'node', function(evt) {
        const nodeId = evt.target.data('nodeId');
        loadSubgraph(nodeId, 1);
    });
}

async function loadInfo() {
    const res = await fetch('/api/info');
    const info = await res.json();
    document.getElementById('stats').textContent =
        `${info.nodes} nodes | ${info.edges} edges | ${info.methods} methods | ${info.callSites} call sites`;
}

async function showNodeDetail(nodeId) {
    const [nodeRes, outRes, inRes] = await Promise.all([
        fetch(`/api/node/${nodeId}`),
        fetch(`/api/node/${nodeId}/outgoing`),
        fetch(`/api/node/${nodeId}/incoming`)
    ]);
    const node = await nodeRes.json();
    const outgoing = await outRes.json();
    const incoming = await inRes.json();

    const panel = document.getElementById('detail-content');
    let html = `<div class="detail-section"><h4>Node</h4><pre>${JSON.stringify(node, null, 2)}</pre></div>`;
    html += `<div class="detail-section"><h4>Outgoing (${outgoing.length})</h4>`;
    outgoing.forEach(e => {
        html += `<div class="detail-row"><span class="detail-key">${e.type}</span><span class="detail-value">${e.kind || ''} -> ${e.to}</span></div>`;
    });
    html += `</div>`;
    html += `<div class="detail-section"><h4>Incoming (${incoming.length})</h4>`;
    incoming.forEach(e => {
        html += `<div class="detail-row"><span class="detail-key">${e.type}</span><span class="detail-value">${e.from} -> ${e.kind || ''}</span></div>`;
    });
    html += `</div>`;
    panel.innerHTML = html;
}

async function loadSubgraph(centerId, depth) {
    const res = await fetch(`/api/subgraph?center=${centerId}&depth=${depth}`);
    const data = await res.json();
    renderGraph(data);
}

function renderGraph(data) {
    const elements = [];
    const seen = new Set();

    data.nodes.forEach(n => {
        if (seen.has(n.id)) return;
        seen.add(n.id);
        elements.push({
            data: {
                id: 'n' + n.id,
                nodeId: n.id,
                label: n.label || n.type,
                color: NODE_COLORS[n.type] || '#636e72'
            }
        });
    });

    data.edges.forEach((e, i) => {
        const edgeId = `e${e.from}-${e.to}-${i}`;
        elements.push({
            data: {
                id: edgeId,
                source: 'n' + e.from,
                target: 'n' + e.to,
                label: e.kind || e.type,
                color: EDGE_COLORS[e.type] || '#555'
            }
        });
    });

    cy.elements().remove();
    cy.add(elements);
    cy.layout({ name: 'cose', animate: true, animationDuration: 500, nodeRepulsion: 8000 }).run();
}

async function search() {
    const query = document.getElementById('search').value.trim();
    const type = document.getElementById('search-type').value;
    if (!query) return;

    let url;
    const nodeId = parseInt(query);
    if (!isNaN(nodeId) && type === 'nodes') {
        loadSubgraph(nodeId, 2);
        return;
    }

    switch (type) {
        case 'call-sites':
            url = `/api/call-sites?class=${encodeURIComponent(query)}&limit=50`;
            break;
        case 'methods':
            url = `/api/methods?class=${encodeURIComponent(query)}&limit=50`;
            break;
        case 'nodes':
            url = `/api/nodes?type=${encodeURIComponent(query)}&limit=50`;
            break;
    }

    const res = await fetch(url);
    const results = await res.json();
    showResults(results, type);
}

function showResults(results, type) {
    const panel = document.getElementById('results-panel');
    const list = document.getElementById('results-list');
    const count = document.getElementById('result-count');

    count.textContent = `${results.length} result(s)`;
    list.innerHTML = '';

    results.forEach(item => {
        const div = document.createElement('div');
        div.className = 'result-item';
        if (type === 'methods') {
            div.textContent = `${item.class}.${item.name}() -> ${item.returnType}`;
        } else if (item.id !== undefined) {
            div.textContent = `[${item.id}] ${item.label || item.type}`;
            div.onclick = () => {
                loadSubgraph(item.id, 2);
                panel.style.display = 'none';
            };
        } else {
            div.textContent = JSON.stringify(item);
        }
        list.appendChild(div);
    });

    panel.style.display = 'block';
}

// Init
document.getElementById('search-btn').addEventListener('click', search);
document.getElementById('search').addEventListener('keypress', e => { if (e.key === 'Enter') search(); });
document.getElementById('close-results').addEventListener('click', () => { document.getElementById('results-panel').style.display = 'none'; });

initCytoscape();
loadInfo();
