// The toy world + trace builder + replay - pure logic, no React.
// Mirrors the real engine: DistanceField (reverse flood), SearchHeuristic (floor),
// Pathfinder (forward A*), AlternativeRoutesService (exclusion chain).

/* ───────────────────────── The toy world ─────────────────────────
   S start, G goal, # wall.
   Boat 'a' → 'A' (origin-bound, one-way, cost 8): note 'a' is WEST of S —
   the optimal boat route walks AWAY from the goal first.
   Teleports (origin-free): T (cast 10), U (cast 13) — landings only.        */
const MAP_ROWS = [
  "############################################",
  "#..........#...............#..............#",
  "#..........#...............#..............#",
  "#..a.......#......U........#.....T........#",
  "#..........#...............#..............#",
  "#....S.....#...............#...A....G.....#",
  "#..........#...............#..............#",
  "#..........#...............#..............#",
  "#..........#...............#..............#",
  "#..........#...............#..............#",
  "#..........#...............#..............#",
  "#..........#...............#..............#",
  "#..........................#..............#",
  "#..........#..............................#",
  "#..........#...............#..............#",
  "############################################",
];
const H = MAP_ROWS.length, W = MAP_ROWS[0].length;
const idx = (x, y) => y * W + x;
const XY = i => [i % W, Math.floor(i / W)];
const walkable = [];
let START = -1, GOAL = -1;
const specials = {};
for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
  const c = MAP_ROWS[y][x];
  walkable[idx(x, y)] = c !== '#';
  if (c === 'S') START = idx(x, y);
  if (c === 'G') GOAL = idx(x, y);
  if ('aAtTuU'.includes(c)) specials[c] = idx(x, y);
}
// Methods. Origin-bound transport: boat a->A. Origin-free teleports: T, U.
const BOAT = { id: 'boat', label: 'Boat a→A', kind: 'transport', origin: specials['a'], dest: specials['A'], cost: 8, color: '#4db6ac' };
const TELE_T = { id: 'teleT', label: 'Teleport T', kind: 'teleport', dest: specials['T'], cost: 10, color: '#b46fd4' };
const TELE_U = { id: 'teleU', label: 'Teleport U', kind: 'teleport', dest: specials['U'], cost: 13, color: '#e57399' };
const METHODS = [BOAT, TELE_T, TELE_U];

function* neighbors(i) { // 8-dir walking, cost 1 (run-tile model; corner rules omitted for clarity)
  const [x, y] = XY(i);
  for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
    if (!dx && !dy) continue;
    const nx = x + dx, ny = y + dy;
    if (nx >= 0 && ny >= 0 && nx < W && ny < H && walkable[idx(nx, ny)]) yield idx(nx, ny);
  }
}

/* ───────────────────── Trace builder (runs everything up front) ───────────────────── */
function buildTrace(useHeuristic) {
  const ev = [];   // the full event stream the scrubber replays
  const field = new Array(W * H).fill(Infinity);

  // Phase 1 — DistanceField: reverse Dijkstra from the goal. Walking is symmetric;
  // the boat is reversed EXACTLY (settling its destination relaxes its origin);
  // teleports are origin-free and deliberately NOT flooded.
  ev.push({ t: 'phase', phase: 'FIELD', note: 'Reverse Dijkstra flood from the goal (DistanceField)' });
  {
    const pq = [[0, GOAL]];
    field[GOAL] = 0;
    while (pq.length) {
      pq.sort((p, q) => p[0] - q[0]);
      const [d, i] = pq.shift();
      if (d > field[i]) continue;
      ev.push({ t: 'fieldSettle', cell: i, d });
      for (const n of neighbors(i)) if (d + 1 < field[n]) { field[n] = d + 1; pq.push([d + 1, n]); }
      // exact reverse of the origin-bound boat: from its DESTINATION, relax its ORIGIN
      if (i === BOAT.dest && d + BOAT.cost < field[BOAT.origin]) {
        field[BOAT.origin] = d + BOAT.cost;
        pq.push([field[BOAT.origin], BOAT.origin]);
        ev.push({ t: 'log', msg: `field: reversed ${BOAT.label} — origin relaxed to ${field[BOAT.origin]}` });
      }
    }
  }

  // Exclusion chain: search, surface a route, exclude its primary method, repeat.
  const excluded = new Set();
  const routes = [];
  for (let k = 0; k < 4; k++) {
    const teles = [TELE_T, TELE_U].filter(m => !excluded.has(m.id));
    // Phase 2 — the FLOOR (SearchHeuristic): teleports usable from anywhere mean the true
    // remaining cost from ANY tile is <= cast + field(landing). Recomputed per search:
    // excluding good teleports RAISES it, strengthening the heuristic when searches get hard.
    let floor = Infinity;
    for (const m of teles) floor = Math.min(floor, m.cost + field[m.dest]);
    ev.push({ t: 'phase', phase: `FLOOR #${k}`, note: teles.length
      ? `floor = min(cast + field(landing)) = ${floor}` : 'no teleports left — floor = ∞' });
    for (const m of teles) ev.push({ t: 'log', msg: `floor: ${m.label} gives ${m.cost} + ${field[m.dest]} = ${m.cost + field[m.dest]}` });
    const h = i => useHeuristic ? Math.min(field[i], floor) : 0;

    // Phase 3 — forward A* (Pathfinder): settle-ordered by f = g + h; origin-free teleports
    // enter as landing candidates at g = cast cost (the degenerate one-band "hub").
    ev.push({ t: 'phase', phase: `SEARCH #${k}`, note: `forward A* (h = ${useHeuristic ? 'min(field, floor)' : '0 — Dijkstra'})`
      + (excluded.size ? `, excluded: ${[...excluded].join(', ')}` : '') });
    const g = new Array(W * H).fill(Infinity);
    const parent = {}, parentEdge = {};
    const settled = new Set();
    let pq = [];
    const push = (i, gi, via, edge) => {
      if (gi >= g[i]) return;
      g[i] = gi; parent[i] = via; parentEdge[i] = edge;
      pq.push([gi + h(i), gi, i]);
      ev.push({ t: 'enqueue', k, cell: i, g: gi, h: h(i), f: gi + h(i), pq: null });
    };
    push(START, 0, -1, null);
    for (const m of teles) {
      push(m.dest, m.cost, START, m.id);
      ev.push({ t: 'log', msg: `search #${k}: ${m.label} lands in the queue at g=${m.cost} (usable from anywhere ⇒ candidate immediately)` });
    }
    let goalCost = -1;
    while (pq.length) {
      pq.sort((p, q) => p[0] - q[0]);
      const [f, gi, i] = pq.shift();
      if (settled.has(i)) { ev.push({ t: 'dup', k, cell: i }); continue; }
      if (gi > g[i]) { ev.push({ t: 'dup', k, cell: i }); continue; }
      settled.add(i);
      ev.push({ t: 'settle', k, cell: i, g: gi, h: h(i), f, pqTop: pq.slice(0, 14).map(e => ({ f: e[0], g: e[1], cell: e[2] })) });
      if (i === GOAL) { goalCost = gi; ev.push({ t: 'log', msg: `search #${k}: GOAL POPPED at f = g = ${gi} — every cheaper f has been settled; this is optimal` }); break; }
      for (const n of neighbors(i)) push(n, gi + 1, i, null);
      if (i === BOAT.origin && !excluded.has(BOAT.id)) push(BOAT.dest, gi + BOAT.cost, i, BOAT.id);
    }
    if (goalCost < 0) { ev.push({ t: 'log', msg: `search #${k}: goal unreachable — chain ends` }); break; }

    // Extract the route + its primary method; then exclude it (the chain's next branch).
    const path = [];
    let cur = GOAL, primary = null;
    while (cur !== -1 && cur !== undefined) {
      path.push(cur);
      if (parentEdge[cur]) primary = parentEdge[cur];
      cur = parent[cur];
    }
    path.reverse();
    const label = primary ? METHODS.find(m => m.id === primary).label : 'Walk only';
    routes.push({ cost: goalCost, path, method: label });
    ev.push({ t: 'route', k, cost: goalCost, path, method: label });
    if (!primary) { ev.push({ t: 'log', msg: 'chain: walk-only route found — nothing cheaper than walking remains, chain stops' }); break; }
    excluded.add(primary);
    ev.push({ t: 'log', msg: `chain: excluding "${label}" and searching again (same field, higher floor)` });
  }
  ev.push({ t: 'phase', phase: 'DONE', note: `${routes.length} routes, cheapest first — exactly what the panel lists` });
  return { ev, field };
}

/* ───────────────────── Replay: fold events[0..step] into a drawable state ───────────────────── */
function makeState() {
  return { phase: '', note: '', field: new Array(W * H).fill(Infinity), settled: new Set(),
           enq: new Set(), searchK: -1, pqTop: [], routes: [], log: [], lastCell: -1, floorNote: '' };
}
function applyEvent(s, e) {
  switch (e.t) {
    case 'phase':
      s.phase = e.phase; s.note = e.note;
      if (e.phase.startsWith('SEARCH')) { s.settled = new Set(); s.enq = new Set(); s.pqTop = []; }
      s.log.push(`── ${e.phase}: ${e.note}`);
      break;
    case 'fieldSettle': s.field[e.cell] = e.d; s.lastCell = e.cell; break;
    case 'enqueue': s.enq.add(e.cell); break;
    case 'settle': s.settled.add(e.cell); s.lastCell = e.cell; s.pqTop = e.pqTop || []; s.searchK = e.k; break;
    case 'dup': break;
    case 'route': s.routes.push(e); s.log.push(`★ route ${s.routes.length}: ${e.method}, cost ${e.cost}`); break;
    case 'log': s.log.push(e.msg); break;
  }
  return s;
}


export { MAP_ROWS, W, H, idx, XY, walkable, START, GOAL, BOAT, TELE_T, TELE_U, METHODS, buildTrace, makeState, applyEvent };
