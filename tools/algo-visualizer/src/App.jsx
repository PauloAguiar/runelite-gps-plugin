import { useState, useEffect, useRef, useMemo } from 'react'
import { W, H, XY, idx, walkable, START, GOAL, BOAT, TELE_T, TELE_U, buildTrace, makeState, applyEvent } from './engine.js'
import './styles.css'

/* ───────────────────── Rendering ───────────────────── */
const CELL = 17;
function draw(canvas, st, trace) {
  const ctx = canvas.getContext('2d');
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  const maxField = 60;
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const i = idx(x, y);
    let fill = walkable[i] ? '#191b1f' : '#33363e';
    const fd = st.field[i];
    if (walkable[i] && fd !== Infinity) {
      const t = Math.min(1, fd / maxField);
      fill = `rgba(${40 + 30 * t}, ${90 - 40 * t}, ${200 - 120 * t}, 0.55)`;   // blue→dim: the field heat
    }
    if (st.enq.has(i)) fill = '#7a5a18';
    if (st.settled.has(i)) fill = '#245c33';
    ctx.fillStyle = fill;
    ctx.fillRect(x * CELL, y * CELL, CELL - 1, CELL - 1);
  }
  // routes found so far (dim previous, bright latest)
  st.routes.forEach((r, ri) => {
    ctx.strokeStyle = ri === st.routes.length - 1 ? '#ff9a1f' : 'rgba(255,154,31,.35)';
    ctx.lineWidth = 2.5; ctx.beginPath();
    r.path.forEach((c, j) => { const [x, y] = XY(c); const px = x * CELL + CELL / 2, py = y * CELL + CELL / 2;
      j ? ctx.lineTo(px, py) : ctx.moveTo(px, py); });
    ctx.stroke();
  });
  if (st.lastCell >= 0) {  // current pop
    const [x, y] = XY(st.lastCell);
    ctx.strokeStyle = '#ffffff'; ctx.lineWidth = 2;
    ctx.strokeRect(x * CELL + 1, y * CELL + 1, CELL - 3, CELL - 3);
  }
  const badge = (i, txt, color) => { const [x, y] = XY(i);
    ctx.fillStyle = color; ctx.fillRect(x * CELL, y * CELL, CELL - 1, CELL - 1);
    ctx.fillStyle = '#101114'; ctx.font = 'bold 11px Consolas'; ctx.textAlign = 'center';
    ctx.fillText(txt, x * CELL + CELL / 2, y * CELL + CELL / 2 + 4); };
  badge(START, 'S', '#e8e8e8'); badge(GOAL, 'G', '#3cc86a');
  badge(BOAT.origin, 'a', BOAT.color); badge(BOAT.dest, 'A', BOAT.color);
  badge(TELE_T.dest, 'T', TELE_T.color); badge(TELE_U.dest, 'U', TELE_U.color);
}

/* ───────────────────── App ───────────────────── */
function App() {
  const [useH, setUseH] = useState(true);
  const [step, setStep] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState(6);           // events per frame
  const trace = useMemo(() => buildTrace(useH), [useH]);
  const canvasRef = useRef(null);
  const cacheRef = useRef({ at: -1, st: null });

  const stateAt = (t) => {
    let { at, st } = cacheRef.current;
    if (!st || t < at) { st = makeState(); at = -1; }
    for (let i = at + 1; i <= t && i < trace.ev.length; i++) applyEvent(st, trace.ev[i]);
    cacheRef.current = { at: Math.min(t, trace.ev.length - 1), st };
    return st;
  };

  useEffect(() => {
    if (!playing) return;
    const h = setInterval(() => setStep(s => {
      const n = Math.min(s + speed, trace.ev.length - 1);
      if (n === trace.ev.length - 1) setPlaying(false);
      return n;
    }), 30);
    return () => clearInterval(h);
  }, [playing, speed, trace]);

  const st = stateAt(step);
  useEffect(() => { if (canvasRef.current) draw(canvasRef.current, st, trace); });

  const jump = (name) => {
    const i = trace.ev.findIndex(e => e.t === 'phase' && e.phase.startsWith(name));
    if (i >= 0) { setStep(i); setPlaying(true); }
  };

  return <>
    <div className="top">
      <div>
        <canvas ref={canvasRef} width={W * CELL} height={H * CELL}/>
        <div className="panel legend" style={{ marginTop: 8 }}>
          <span><span className="sw" style={{ background: 'rgba(45,85,190,.6)' }}/>field (cost→goal)</span>
          <span><span className="sw" style={{ background: '#245c33' }}/>settled</span>
          <span><span className="sw" style={{ background: '#7a5a18' }}/>in queue</span>
          <span><span className="sw" style={{ background: '#ff9a1f' }}/>route</span>
          <span><span className="sw" style={{ background: BOAT.color }}/>boat a→A (cost 8)</span>
          <span><span className="sw" style={{ background: TELE_T.color }}/>teleport T (cast 10)</span>
          <span><span className="sw" style={{ background: TELE_U.color }}/>teleport U (cast 13)</span>
        </div>
      </div>
      <div className="side">
        <div className="panel">
          <div className="phase">{st.phase || '—'}</div>
          <div className="muted">{st.note}</div>
          <div className="muted">event {step} / {trace.ev.length - 1}</div>
        </div>
        <div className="panel">
          <h3>Priority queue (top of heap)</h3>
          <div className="pq">{'   f     g     h   tile\n' + st.pqTop.map(e => {
            const [x, y] = XY(e.cell);
            return `${String(e.f).padStart(4)}  ${String(e.g).padStart(4)}  ${String(e.f - e.g).padStart(4)}  (${x},${y})`;
          }).join('\n') || '(empty)'}</div>
        </div>
        <div className="panel routes">
          <h3>Routes found (the panel's list)</h3>
          {st.routes.map((r, i) => <div key={i}>#{i + 1} — {r.method}, cost {r.cost}</div>)}
          {!st.routes.length && <div className="muted">none yet</div>}
        </div>
        <div className="panel">
          <h3>Event log</h3>
          <div className="log">{st.log.slice(-60).map((m, i) => <div key={i}>{m}</div>)}</div>
        </div>
      </div>
    </div>
    <div className="controls">
      <button className="primary" onClick={() => setPlaying(p => !p)}>{playing ? 'Pause' : 'Play'}</button>
      <button onClick={() => setStep(s => Math.min(s + 1, trace.ev.length - 1))}>Step</button>
      <button onClick={() => { setStep(0); setPlaying(false); cacheRef.current = { at: -1, st: null }; }}>Reset</button>
      <label>speed <input type="range" min="1" max="40" value={speed} onChange={e => setSpeed(+e.target.value)}/></label>
      <span className="muted">jump:</span>
      <button onClick={() => jump('FIELD')}>field flood</button>
      <button onClick={() => jump('SEARCH #0')}>search #0</button>
      <button onClick={() => jump('SEARCH #1')}>search #1</button>
      <button onClick={() => jump('SEARCH #2')}>search #2</button>
      <label style={{ marginLeft: 12 }}>
        <input type="checkbox" checked={useH} onChange={e => { setUseH(e.target.checked); setStep(0); setPlaying(false); cacheRef.current = { at: -1, st: null }; }}/>
        {' '}use heuristic (off = pure Dijkstra — compare how much more it settles)
      </label>
      <input type="range" min="0" max={trace.ev.length - 1} value={step} style={{ flex: 1, minWidth: 160 }}
             onChange={e => { setStep(+e.target.value); setPlaying(false); }}/>
    </div>
  </>;
}

export default App
