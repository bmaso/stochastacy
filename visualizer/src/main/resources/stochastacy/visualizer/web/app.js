'use strict';

const COLORS = [
  '#4e79a7', '#f28e2b', '#e15759', '#76b7b2',
  '#59a14f', '#edc948', '#b07aa1', '#ff9da7'
];

let timelineChart  = null;
let breakdownChart = null;

// ── workload name extraction ──────────────────────────────────────────────────

function extractWorkloadNames(yaml) {
  const names = [];
  let inWorkloads = false;
  for (const line of yaml.split('\n')) {
    if (/^workloads\s*:/.test(line)) { inWorkloads = true; continue; }
    if (inWorkloads) {
      // top-level workload keys are indented with exactly 2 spaces
      const m = line.match(/^  ([a-zA-Z0-9][a-zA-Z0-9_-]*)\s*:/);
      if (m) {
        names.push(m[1]);
      } else if (line.length > 0 && /^\S/.test(line)) {
        inWorkloads = false; // back to top level — no more workload keys
      }
    }
  }
  return names;
}

// ── status helper ─────────────────────────────────────────────────────────────

function setStatus(msg, isError = false) {
  const el = document.getElementById('status');
  el.textContent = msg;
  el.className = 'status' + (isError ? ' error' : '');
}

// ── YAML textarea → populate workload dropdown ────────────────────────────────

document.getElementById('yaml-input').addEventListener('input', function () {
  const names = extractWorkloadNames(this.value);
  const sel   = document.getElementById('workload-select');
  sel.innerHTML = names.length === 0
    ? '<option value="">— paste YAML above —</option>'
    : names.map(n => `<option value="${n}">${n}</option>`).join('');
});

// ── evaluate button ───────────────────────────────────────────────────────────

document.getElementById('evaluate-btn').addEventListener('click', async function () {
  const yaml     = document.getElementById('yaml-input').value.trim();
  const workload = document.getElementById('workload-select').value;
  const ticks    = document.getElementById('ticks-input').value;
  const seed     = document.getElementById('seed-input').value;

  if (!yaml)     { setStatus('Paste a workload YAML first.', true); return; }
  if (!workload) { setStatus('Select a workload name.', true);       return; }

  this.disabled = true;
  setStatus('Evaluating…');

  try {
    const url  = `/api/evaluate?workload=${encodeURIComponent(workload)}&ticks=${ticks}&seed=${seed}`;
    const resp = await fetch(url, {
      method:  'POST',
      body:    yaml,
      headers: { 'Content-Type': 'text/plain; charset=utf-8' }
    });

    if (!resp.ok) {
      const msg = await resp.text();
      setStatus(`Server error: ${msg}`, true);
      return;
    }

    const data = await resp.json();
    renderCharts(data, Number(ticks));
    setStatus(`${data.shapes.length} shape(s) × ${ticks} tick(s) rendered.`);
  } catch (e) {
    setStatus(`Request failed: ${e.message}`, true);
  } finally {
    this.disabled = false;
  }
});

// ── chart rendering ───────────────────────────────────────────────────────────

function pivotSamples(shapes, samples, tickCount) {
  const byShape = shapes.map(() => new Array(tickCount).fill(0));
  for (const s of samples) {
    if (s.shapeIndex >= 0 && s.shapeIndex < byShape.length && s.tick >= 1 && s.tick <= tickCount) {
      byShape[s.shapeIndex][s.tick - 1] = s.count;
    }
  }
  return byShape;
}

function makeDatasets(shapes, byShape, forBar) {
  return shapes.map((shape, i) => {
    const color = COLORS[i % COLORS.length];
    const base = {
      label:           shape.requestType,
      data:            byShape[i],
      backgroundColor: color + (forBar ? 'cc' : '33'),
      borderColor:     color,
      borderWidth:     forBar ? 0 : 1.5
    };
    return forBar
      ? base
      : { ...base, fill: false, tension: 0.2, pointRadius: 0 };
  });
}

function renderCharts(data, tickCount) {
  const { shapes, samples } = data;
  const labels   = Array.from({ length: tickCount }, (_, i) => i + 1);
  const byShape  = pivotSamples(shapes, samples, tickCount);
  const maxTicks = 40; // x-axis tick label density cap

  // hide the empty-state placeholder
  const emptyState = document.getElementById('empty-state');
  if (emptyState) emptyState.remove();

  const panel = document.getElementById('charts-panel');

  // ensure the two chart cards exist (idempotent)
  ['timeline-card', 'breakdown-card'].forEach((id, idx) => {
    if (!document.getElementById(id)) {
      const card    = document.createElement('div');
      card.id       = id;
      card.className = 'chart-card';
      card.innerHTML = `<h2>${idx === 0 ? 'Request Rate per Tick' : 'Traffic Mix per Tick (Stacked)'}</h2>
                        <canvas id="${idx === 0 ? 'timeline-chart' : 'breakdown-chart'}"></canvas>`;
      panel.appendChild(card);
    }
  });

  const sharedScaleX = {
    title: { display: true, text: 'Tick' },
    ticks: { maxTicksLimit: maxTicks }
  };

  // ── Timeline chart (line) ─────────────────────────────────────────────────
  if (timelineChart) timelineChart.destroy();
  timelineChart = new Chart(document.getElementById('timeline-chart'), {
    type: 'line',
    data: { labels, datasets: makeDatasets(shapes, byShape, false) },
    options: {
      responsive: true,
      animation:  false,
      scales: {
        x: sharedScaleX,
        y: { title: { display: true, text: 'Count' }, beginAtZero: true }
      },
      plugins: { legend: { position: 'bottom' } }
    }
  });

  // ── Breakdown chart (stacked bar) ─────────────────────────────────────────
  if (breakdownChart) breakdownChart.destroy();
  breakdownChart = new Chart(document.getElementById('breakdown-chart'), {
    type: 'bar',
    data: { labels, datasets: makeDatasets(shapes, byShape, true) },
    options: {
      responsive: true,
      animation:  false,
      scales: {
        x: { ...sharedScaleX, stacked: true },
        y: { stacked: true, title: { display: true, text: 'Count' }, beginAtZero: true }
      },
      plugins: { legend: { position: 'bottom' } }
    }
  });
}
