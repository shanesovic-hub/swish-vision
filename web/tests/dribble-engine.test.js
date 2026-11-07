// Tests for the Dribble Quest tracking engine. Run: node web/tests/dribble-engine.test.js
'use strict';
const DQ = require('../public/js/dribble-engine.js');

let failed = 0;
function check(name, ok, detail) {
  console.log((ok ? 'PASS ' : 'FAIL ') + name + (detail ? '  (' + detail + ')' : ''));
  if (!ok) failed++;
}

// Small seeded random numbers, so every run is the same.
let seed = 42;
function rnd() { seed = (seed * 1103515245 + 12345) >>> 0; return seed / 4294967296; }
function noise(a) { return (rnd() - 0.5) * 2 * a; }

const W = 144, H = 256, FPS = 30;

// ---------- frames drawn like a camera would see them ----------
function frame(ball, color, bgFn) {
  const data = new Uint8ClampedArray(W * H * 4);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const i = (y * W + x) * 4;
    const bg = bgFn(x, y);
    data[i] = bg[0]; data[i + 1] = bg[1]; data[i + 2] = bg[2]; data[i + 3] = 255;
    if (ball) {
      const dx = x - ball.x, dy = (y - ball.y) / (ball.stretch || 1);
      if (dx * dx + dy * dy <= ball.r * ball.r) {
        const shade = 0.75 + 0.25 * (1 - (y - ball.y + ball.r) / (2 * ball.r)); // darker underneath
        data[i] = color[0] * shade + noise(8); data[i + 1] = color[1] * shade + noise(8); data[i + 2] = color[2] * shade + noise(8);
      }
    }
  }
  return { width: W, height: H, data };
}
// Gym-ish background: gray wall, brown wood floor in the bottom quarter, a gray-blue shirt.
function gym(x, y) {
  if (y > H * 0.78) return [150 + noise(10), 110 + noise(10), 70 + noise(10)];          // wood floor
  if (x > 55 && x < 90 && y > 60 && y < 130) return [70 + noise(6), 80 + noise(6), 110 + noise(6)]; // shirt
  return [128 + noise(12), 128 + noise(12), 125 + noise(12)];
}
const ORANGE = [235, 110, 30], BROWN = [170, 85, 40], NEON = [210, 240, 40];

// A dribble path: the ball falls from topY to floorY and bounces, `hz` times a second.
function dribbleY(t, hz, topY, floorY) {
  const ph = (t * hz) % 1;
  return floorY - (floorY - topY) * Math.sin(Math.PI * ph);   // floor at ph=0 and 1 (sharp bounce), top at 0.5
}

// ---------- 1. color lock + tracking on drawn frames ----------
(function () {
  const t = new DQ.BallTracker();
  const s = t.sampleCircle(frame({ x: 72, y: 128, r: 12 }, ORANGE, gym), 72, 128, 10);
  check('locks onto an orange ball held in the circle', s.ok, 'hue ' + Math.round(s.h) + ', frac ' + s.frac.toFixed(2));
  const empty = t.sampleCircle(frame(null, ORANGE, gym), 72, 128, 10);
  check('does not lock onto a gray wall', !empty.ok);
  const ringBall = t.ringShare(frame({ x: 72, y: 128, r: 12 }, ORANGE, gym), 72, 128, 14, 22, s);
  const shirt = (x, y) => (x > 30 && x < 115 && y > 70 && y < 190) ? [235, 110, 30] : gym(x, y);
  const ringShirt = t.ringShare(frame(null, ORANGE, shirt), 72, 128, 14, 22, s);
  check('tells a ball apart from an orange shirt', ringBall < 0.3 && ringShirt > 0.8,
    'ring around ball ' + ringBall.toFixed(2) + ', around shirt ' + ringShirt.toFixed(2));
  t.lockColor(s, 12);
  // Calibration time: the kid stands holding the ball for 1.5 s, so the still floor turns into background.
  let held = 0;
  for (let i = 0; i < 45; i++) if (t.track(frame({ x: 72, y: 128, r: 12 }, ORANGE, gym), i / FPS).found) held++;
  check('a ball held still stays found', held === 45, held + '/45');
  let err = 0, n = 0;
  for (let i = 0; i < 60; i++) {
    const tt = 1.5 + i / FPS, bx = 40 + i, by = dribbleY(tt, 2.5, 120, 220);
    const r = t.track(frame({ x: bx, y: by, r: 11, stretch: 1.3 }, ORANGE, gym), tt);
    if (r.found) { err += Math.hypot(r.x - bx, r.y - by); n++; }
  }
  check('tracks a moving orange ball every frame', n === 60, n + '/60 found, avg error ' + (err / n).toFixed(2) + ' px');
  check('position error under 2 px', err / n < 2);

  const tb = new DQ.BallTracker();
  const sb = tb.sampleCircle(frame({ x: 72, y: 128, r: 12 }, BROWN, gym), 72, 128, 10);
  check('locks onto a brown ball', sb.ok, 'hue ' + Math.round(sb.h) + ', sat ' + sb.s.toFixed(2));
  tb.lockColor(sb, 12);
  for (let i = 0; i < 45; i++) tb.track(frame({ x: 72, y: 128, r: 12 }, BROWN, gym), i / FPS);
  let nb = 0, wrong = 0;
  for (let i = 0; i < 60; i++) {
    const tt = 1.5 + i / FPS, by = dribbleY(tt, 2.5, 120, 210);
    const r = tb.track(frame({ x: 60, y: by, r: 11 }, BROWN, gym), tt);
    if (r.found) { nb++; if (Math.hypot(r.x - 60, r.y - by) > 6) wrong++; }
  }
  check('tracks a brown ball against a wood floor', nb >= 54 && wrong <= 3, nb + '/60 found, ' + wrong + ' wrong');
})();

// ---------- helper: run the detector on a path ----------
function pose(midX, hipY, opts) {
  opts = opts || {};
  const sw = 36;
  const lms = [];
  for (let i = 0; i < 33; i++) lms.push({ x: 0.5, y: 0.5, visibility: 0.9 });
  const set = (i, x, y) => { lms[i] = { x: x / W, y: y / H, visibility: 0.9 }; };
  // Kid faces the camera: their right side is on the image left.
  set(11, midX + sw / 2, hipY - 70); set(12, midX - sw / 2, hipY - 70);
  set(23, midX + 12, hipY); set(24, midX - 12, hipY);
  set(25, midX + 12, hipY + 45); set(26, midX - 12, hipY + 45);
  set(27, midX + 12, hipY + 90); set(28, midX - 12, hipY + 90);
  set(15, opts.lw ? opts.lw.x : midX + 30, opts.lw ? opts.lw.y : hipY - 10);
  set(16, opts.rw ? opts.rw.x : midX - 30, opts.rw ? opts.rw.y : hipY - 10);
  return DQ.poseFromLandmarks(lms, W, H);
}

function run(det, secs, pathFn, opts) {
  opts = opts || {};
  const events = [];
  for (let i = 0; i < secs * FPS; i++) {
    const t = i / FPS;
    const p = pathFn(t);
    const drop = opts.drop && rnd() < opts.drop;
    const ball = drop ? null : { x: p.x + noise(0.8), y: p.y + noise(0.8), r: 10 };
    const fr = { t: t + noise(0.003), ball, pose: p.pose || null };
    det.update(fr).forEach(e => events.push(e));
  }
  return events;
}

// ---------- 2. counting dribbles ----------
(function () {
  const det = new DQ.DribbleDetector(W, H);
  const ev = run(det, 10, t => ({ x: 50, y: dribbleY(t, 3, 130, 215) }), { drop: 0.05 });
  const b = ev.filter(e => e.type === 'bounce').length;
  check('counts 3 dribbles a second for 10 s (with 5% lost frames)', Math.abs(b - 30) <= 1, b + ' counted, 30 real');

  const det2 = new DQ.DribbleDetector(W, H);
  const ev2 = run(det2, 10, t => ({ x: 50, y: dribbleY(t, 5, 170, 215) }), { drop: 0.03 });
  const b2 = ev2.filter(e => e.type === 'bounce').length;
  check('counts fast low pound dribbles (5 a second)', Math.abs(b2 - 50) <= 2, b2 + ' counted, 50 real');

  const det3 = new DQ.DribbleDetector(W, H);
  const ev3 = run(det3, 10, t => ({ x: 50, y: 140 + 5 * Math.sin(t * 2 * Math.PI * 4) }));
  check('a ball shaken in the hands is not counted', ev3.filter(e => e.type === 'bounce').length === 0);

  const det4 = new DQ.DribbleDetector(W, H);
  det4.floorY = 225;
  const ev4 = run(det4, 6, t => ({ x: 50, y: dribbleY(t, 3, 100, 160) }));
  check('bounces that never reach the floor are not counted once the floor is known', ev4.filter(e => e.type === 'bounce').length === 0);

  const det5 = new DQ.DribbleDetector(W, H);
  det5.floorY = 225;
  const ev5 = run(det5, 6, t => ({ x: 50, y: dribbleY(t, 3, 130, 215) }));
  const b5 = ev5.filter(e => e.type === 'bounce').length;
  check('real bounces still count once the floor is known', Math.abs(b5 - 18) <= 1, b5 + ' counted, 18 real');
})();

// ---------- 3. hands, crossovers, stance ----------
(function () {
  // Right-handed pounding: ball on the image left (kid's right), right wrist on top of it.
  const det = new DQ.DribbleDetector(W, H);
  const ev = run(det, 6, t => {
    const y = dribbleY(t, 3, 135, 215);
    return { x: 40, y, pose: pose(72, 150, { rw: { x: 40, y: Math.min(y - 12, 150) } }) };
  });
  const bs = ev.filter(e => e.type === 'bounce');
  const right = bs.filter(e => e.hand === 'right').length;
  check('right-hand dribbles are credited to the right hand', right >= bs.length - 1 && bs.length >= 17, right + ' of ' + bs.length);

  // Crossovers: the ball switches sides on every second bounce.
  const det2 = new DQ.DribbleDetector(W, H);
  const hz = 3;
  const ev2 = run(det2, 10, t => {
    const k = Math.floor(t * hz);                 // which bounce we're on
    const side = Math.floor(k / 2) % 2 ? 1 : -1;  // two bounces per side
    const ph = (t * hz) % 1;
    let x = 72 + side * 32;
    if (k % 2 === 1) x = 72 + side * 32 * Math.cos(Math.PI * ph); // the crossing bounce sweeps across the middle
    return { x, y: dribbleY(t, hz, 140, 215), pose: pose(72, 150) };
  });
  const crosses = ev2.filter(e => e.type === 'cross').length;
  check('counts crossovers', Math.abs(crosses - 15) <= 1, crosses + ' counted, 15 real');

  // Swinging the ball across in the hands (no bounce) is not a crossover.
  const det3 = new DQ.DribbleDetector(W, H);
  const ev3 = run(det3, 6, t => ({ x: 72 + 35 * Math.sin(t * 2 * Math.PI), y: 150, pose: pose(72, 150) }));
  check('swinging the ball side to side without bouncing is not a crossover', ev3.filter(e => e.type === 'cross').length === 0);

  // Stance: hips above the line = not low, below = low.
  const cal = new DQ.Calibrator();
  for (let i = 0; i < 20; i++) cal.addTall(pose(72, 140));
  for (let i = 0; i < 20; i++) cal.addStance(pose(72, 160));
  const c = cal.result();
  check('calibration sets a stance line between standing and stance', c.stanceY > 140 && c.stanceY < 160, 'stanceY ' + c.stanceY);
  const det4 = new DQ.DribbleDetector(W, H);
  det4.stanceY = c.stanceY;
  const evTall = run(det4, 3, t => ({ x: 40, y: dribbleY(t, 3, 135, 215), pose: pose(72, 141) })).filter(e => e.type === 'bounce');
  det4.reset();
  const evLow = run(det4, 3, t => ({ x: 40, y: dribbleY(t, 3, 160, 215), pose: pose(72, 162) })).filter(e => e.type === 'bounce');
  check('standing-tall bounces are not "low"', evTall.length > 0 && evTall.every(e => e.low === false));
  check('stance bounces are "low"', evLow.length > 0 && evLow.every(e => e.low === true));

  const spots = DQ.targetSpots(pose(72, 150), 230, 10);
  const tg = spots[0];
  check('a bounce inside a floor target hits it', DQ.bounceInTarget({ type: 'bounce', x: tg.x + 3, y: 220 }, tg));
  check('a bounce outside it misses', !DQ.bounceInTarget({ type: 'bounce', x: tg.x + tg.r + 4, y: 220 }, tg));
  check('the ball just passing through (no bounce) misses', !DQ.bounceInTarget({ type: 'cross', x: tg.x, y: 220 }, tg));
  const onScreen = spots.filter(s => s.x > s.r * 0.5 && s.x < W - s.r * 0.5 && s.y > 0 && s.y < H);
  check('at least 4 of 6 floor targets fit on a narrow portrait screen', onScreen.length >= 4 && spots.every(s => s.y > 200),
    spots.map(s => Math.round(s.x) + ',' + Math.round(s.y)).join(' '));
})();

// ---------- 4. game layer ----------
(function () {
  const data = DQ.emptyData();
  const day = new Date(2026, 10, 7, 10, 0, 0);
  const r1 = DQ.recordSession(data, { drillId: 'pound', score: 72, dribbles: 72, quality: 0.9 }, day, 't');
  check('2 stars for 72 pound dribbles', r1.stars === 2 && r1.pb === true, JSON.stringify(r1));
  check('first-100 badge waits until 100 dribbles', data.badges.indexOf('first100') < 0);
  const r2 = DQ.recordSession(data, { drillId: 'pound', score: 40, dribbles: 40, quality: 0.9 }, day, 't');
  check('a lower score is not a PB', r2.pb === false && data.bests.pound === 72);
  check('first-100 badge after 112 total dribbles', r2.newBadges.some(b => b.id === 'first100'));
  const bad = DQ.recordSession(data, { drillId: 'pound', score: 9999, dribbles: 9999, quality: 0.9 }, day, 't');
  check('impossible scores are rejected', bad.counted === false && data.bests.pound === 72);

  // Daily Quest + streak over two days.
  const d1 = DQ.dailyDrills(DQ.dayKey(day));
  check('daily quest has 3 different drills', d1.length === 3 && new Set(d1).size === 3, d1.join(','));
  let done = false;
  d1.forEach(id => { const r = DQ.recordSession(data, { drillId: id, score: 5, quality: 0.9 }, day, 't'); done = done || r.dailyDone; });
  check('finishing all 3 completes the Daily Quest', done && data.streak.count === 1);
  const next = new Date(2026, 10, 8, 9, 0, 0);
  DQ.dailyDrills(DQ.dayKey(next)).forEach(id => DQ.recordSession(data, { drillId: id, score: 5, quality: 0.9 }, next, 't'));
  check('next day keeps the streak going', data.streak.count === 2, 'streak ' + data.streak.count);

  const merged = DQ.mergeData(data, { bests: { pound: 120 }, stars: { pound: 3 }, badges: ['club1000'], sessions: [] });
  check('merging keeps the best of both copies', merged.bests.pound === 120 && merged.stars.pound === 3 &&
    merged.badges.indexOf('club1000') >= 0 && merged.sessions.length === data.sessions.length);
  // Two devices each played a different drill since they last synced: both sessions are kept.
  const phone = DQ.emptyData(), tablet = DQ.emptyData();
  DQ.recordSession(phone, { drillId: 'pound', score: 50, dribbles: 50, quality: 0.9 }, new Date(2026, 10, 9, 9), 't');
  DQ.recordSession(tablet, { drillId: 'cross', score: 20, dribbles: 60, quality: 0.9 }, new Date(2026, 10, 9, 10), 't');
  const both = DQ.mergeData(phone, tablet);
  check('merging two devices keeps both sessions and adds up dribbles', both.sessions.length === 2 &&
    both.totalDribbles === 110 && both.bests.pound === 50 && both.bests.cross === 20 && both.xp === phone.xp + tablet.xp,
    both.sessions.length + ' sessions, ' + both.totalDribbles + ' dribbles, xp ' + both.xp);
  const again = DQ.mergeData(both, phone);
  check('merging the same copy twice does not double anything', again.sessions.length === 2 && again.totalDribbles === 110);
  const junk = DQ.normalizeData({ bests: { pound: 'x' }, sessions: [{ d: 'nope' }], strongHand: 'both', __proto__: { evil: 1 } });
  check('junk data is cleaned up', !junk.bests.pound && junk.sessions.length === 0 && junk.strongHand === 'right');
})();

console.log(failed ? '\n' + failed + ' FAILED' : '\nAll passed');
process.exit(failed ? 1 : 0);
