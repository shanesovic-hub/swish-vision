/*
 * Dribble Quest tracking engine (Swish Quest).
 *
 * Pure logic, no camera or page code, so it can be tested on its own (node web/tests/dribble-engine.test.js).
 * Everything works in the pixels of the small "processing frame" the page draws each camera frame into
 * (about 144 x 256), with y pointing DOWN (bigger y = lower on screen).
 *
 *   BallTracker     finds a solid-color ball every frame by its color.
 *   DribbleDetector turns ball + body positions into events: bounces (with hand and stance) and crossovers.
 *   Calibrator      measures the kid at the start: standing hips, stance hips, floor line, body size.
 *   DRILLS, scoring, stars, badges, the Daily Quest and saving rules.
 */
(function (root) {
  'use strict';

  // ------------------------------------------------------------------ color helpers

  function rgbToHsv(r, g, b) {
    r /= 255; g /= 255; b /= 255;
    var max = Math.max(r, g, b), min = Math.min(r, g, b), d = max - min, h = 0;
    if (d > 0) {
      if (max === r) h = ((g - b) / d) % 6;
      else if (max === g) h = (b - r) / d + 2;
      else h = (r - g) / d + 4;
      h *= 60;
      if (h < 0) h += 360;
    }
    return [h, max === 0 ? 0 : d / max, max];
  }

  function hueDiff(a, b) {
    var d = Math.abs(a - b) % 360;
    return d > 180 ? 360 - d : d;
  }

  function median(arr) {
    if (!arr.length) return 0;
    var a = arr.slice().sort(function (x, y) { return x - y; });
    var m = a.length >> 1;
    return a.length % 2 ? a[m] : (a[m - 1] + a[m]) / 2;
  }

  // ------------------------------------------------------------------ ball tracker

  var STILL_FRAMES = 30;   // ball-colored for this many frames in a row without moving = background
  var STILL_DIFF = 60;     // how different (R+G+B steps) the ball must look from a ball-colored background spot

  function BallTracker() {
    this.color = null;      // { h, s, v, hTol, sMin, vMin }
    this.expectR = 0;       // radius seen when the color was locked (pixels)
    this.last = null;       // { x, y, r, t }
    this.vx = 0; this.vy = 0;
    this.lost = 99;
    this._mask = null; this._label = null; this._stack = null;
  }

  // Look inside a circle and decide whether it is filled by one solid color (the ball).
  // Returns { ok, h, s, v, frac } — ok when most of the circle is one saturated hue.
  BallTracker.prototype.sampleCircle = function (img, cx, cy, rad) {
    var W = img.width, H = img.height, d = img.data;
    var bins = new Array(36).fill(0), total = 0, sat = 0, samples = [];
    var r2 = rad * rad;
    for (var y = Math.max(0, Math.floor(cy - rad)); y <= Math.min(H - 1, Math.ceil(cy + rad)); y++) {
      for (var x = Math.max(0, Math.floor(cx - rad)); x <= Math.min(W - 1, Math.ceil(cx + rad)); x++) {
        var dx = x - cx, dy = y - cy;
        if (dx * dx + dy * dy > r2) continue;
        var i = (y * W + x) * 4;
        var hsv = rgbToHsv(d[i], d[i + 1], d[i + 2]);
        total++;
        if (hsv[1] >= 0.28 && hsv[2] >= 0.18) {
          sat++;
          bins[Math.floor(hsv[0] / 10) % 36]++;
          samples.push(hsv);
        }
      }
    }
    if (!total || sat < 8) return { ok: false, frac: 0 };
    // Best window of 3 neighbouring hue bins (30 degrees).
    var best = 0, bestI = 0;
    for (var b = 0; b < 36; b++) {
      var c = bins[b] + bins[(b + 1) % 36] + bins[(b + 35) % 36];
      if (c > best) { best = c; bestI = b; }
    }
    var center = bestI * 10 + 5;
    var hs = [], ss = [], vs = [];
    samples.forEach(function (p) {
      if (hueDiff(p[0], center) <= 15) { hs.push(p[0]); ss.push(p[1]); vs.push(p[2]); }
    });
    // Circular mean of the matching hues.
    var sx = 0, sy = 0;
    hs.forEach(function (h) { sx += Math.cos(h * Math.PI / 180); sy += Math.sin(h * Math.PI / 180); });
    var h = Math.atan2(sy, sx) * 180 / Math.PI;
    if (h < 0) h += 360;
    var frac = best / total;
    return { ok: frac >= 0.55 && sat / total >= 0.6, h: h, s: median(ss), v: median(vs), frac: frac };
  };

  // Share of the ring between r0 and r1 around (cx, cy) that has the same color as `sample`.
  // A ball held in the circle leaves the ring mostly other colors; a shirt or wall fills it.
  BallTracker.prototype.ringShare = function (img, cx, cy, r0, r1, sample) {
    var W = img.width, H = img.height, d = img.data, n = 0, same = 0;
    var sMin = Math.max(0.22, sample.s * 0.5), vMin = Math.max(0.1, sample.v * 0.35);
    for (var y = Math.max(0, Math.floor(cy - r1)); y <= Math.min(H - 1, Math.ceil(cy + r1)); y++) {
      for (var x = Math.max(0, Math.floor(cx - r1)); x <= Math.min(W - 1, Math.ceil(cx + r1)); x++) {
        var dd = Math.hypot(x - cx, y - cy);
        if (dd < r0 || dd > r1) continue;
        var i = (y * W + x) * 4, hsv = rgbToHsv(d[i], d[i + 1], d[i + 2]);
        n++;
        if (hsv[1] >= sMin && hsv[2] >= vMin && hueDiff(hsv[0], sample.h) <= 18) same++;
      }
    }
    return n ? same / n : 1;
  };

  BallTracker.prototype.lockColor = function (sample, radius) {
    this.color = {
      h: sample.h,
      s: sample.s,
      v: sample.v,
      hTol: sample.s < 0.45 ? 14 : 18,          // dull (brown) balls get a tighter hue window
      sMin: Math.max(0.22, sample.s * 0.5),
      vMin: Math.max(0.1, sample.v * 0.35)
    };
    this.expectR = radius || 0;
    this.last = null; this.lost = 99;
    if (this._static) this._static.fill(0);
  };

  BallTracker.prototype.matches = function (r, g, b) {
    var c = this.color, hsv = rgbToHsv(r, g, b);
    return hsv[1] >= c.sMin && hsv[2] >= c.vMin && hueDiff(hsv[0], c.h) <= c.hTol;
  };

  // Find the ball in one processing frame. Returns { found, x, y, r, area }.
  //
  // Wood floors, skin and some shirts can be close to the ball's color. Anything ball-colored that stays
  // put for about a second is treated as background and ignored, so only the moving ball is left.
  // The spot where the ball was last seen never turns into background, so a ball held still stays found.
  BallTracker.prototype.track = function (img, t) {
    if (!this.color) return { found: false };
    var W = img.width, H = img.height, d = img.data;
    var n = W * H;
    if (!this._mask || this._mask.length !== n) {
      this._mask = new Uint8Array(n); this._label = new Int32Array(n); this._stack = new Int32Array(n);
      this._static = new Uint8Array(n); this._ref = new Uint8ClampedArray(n * 3);
    }
    var mask = this._mask, label = this._label, stack = this._stack, still = this._static, ref = this._ref;

    var keep = this.last && this.lost < 4 ? this.last : null;
    var kr2 = keep ? Math.pow(keep.r * 1.5 + 2, 2) : 0;
    for (var yy = 0; yy < H; yy++) {
      for (var xx = 0; xx < W; xx++) {
        var k = yy * W + xx, q = k * 4, f = k * 3;
        var R = d[q], G = d[q + 1], B = d[q + 2];
        if (this.matches(R, G, B)) {
          var inBall = keep && ((xx - keep.x) * (xx - keep.x) + (yy - keep.y) * (yy - keep.y) <= kr2);
          if (!inBall) {
            // Remember what this background spot looks like (a slow running average).
            if (still[k] === 0) { ref[f] = R; ref[f + 1] = G; ref[f + 2] = B; }
            else { ref[f] += (R - ref[f]) / 8; ref[f + 1] += (G - ref[f + 1]) / 8; ref[f + 2] += (B - ref[f + 2]) / 8; }
            if (still[k] < 255) still[k]++;
          }
          // Background spot: only counts if it looks clearly different from usual (the ball passing over it).
          mask[k] = still[k] < STILL_FRAMES ||
            Math.abs(R - ref[f]) + Math.abs(G - ref[f + 1]) + Math.abs(B - ref[f + 2]) > STILL_DIFF ? 1 : 0;
        } else {
          still[k] = still[k] > 3 ? still[k] - 3 : 0;
          mask[k] = 0;
        }
      }
    }

    // Search near where the ball should be; the whole frame when it has been lost for a while.
    var x0 = 0, y0 = 0, x1 = W - 1, y1 = H - 1, px = null, py = null;
    if (this.last && this.lost < 6) {
      var dt = Math.min(0.2, Math.max(0, t - this.last.t));
      px = this.last.x + this.vx * dt; py = this.last.y + this.vy * dt;
      var reach = Math.max(this.last.r * 7, H * 0.22);
      x0 = Math.max(0, Math.floor(px - reach)); x1 = Math.min(W - 1, Math.ceil(px + reach));
      y0 = Math.max(0, Math.floor(py - reach)); y1 = Math.min(H - 1, Math.ceil(py + reach));
    }
    var res = this._search(d, W, H, x0, y0, x1, y1, px, py, mask, label, stack);
    if (!res && (x0 > 0 || y0 > 0 || x1 < W - 1 || y1 < H - 1)) {
      res = this._search(d, W, H, 0, 0, W - 1, H - 1, null, null, mask, label, stack);
    }
    if (!res) {
      this.lost++;
      return { found: false };
    }
    if (this.last && this.lost < 6) {
      var dt2 = t - this.last.t;
      if (dt2 > 0.001) {
        var nvx = (res.x - this.last.x) / dt2, nvy = (res.y - this.last.y) / dt2;
        this.vx = 0.5 * this.vx + 0.5 * nvx; this.vy = 0.5 * this.vy + 0.5 * nvy;
      }
    } else { this.vx = 0; this.vy = 0; }
    this.last = { x: res.x, y: res.y, r: res.r, t: t };
    this.lost = 0;
    if (!this.expectR) this.expectR = res.r;
    else this.expectR = 0.97 * this.expectR + 0.03 * res.r;
    return { found: true, x: res.x, y: res.y, r: res.r, area: res.area };
  };

  BallTracker.prototype._search = function (d, W, H, x0, y0, x1, y1, px, py, mask, label, stack) {
    var x, y, i;
    for (y = y0; y <= y1; y++) {
      for (x = x0; x <= x1; x++) label[y * W + x] = 0;
    }
    var minArea = Math.max(5, this.expectR ? Math.PI * Math.pow(this.expectR * 0.35, 2) : 5);
    var maxArea = this.expectR ? Math.PI * Math.pow(this.expectR * 3, 2) : W * H * 0.25;
    var best = null, bestScore = -1, id = 0;
    for (y = y0; y <= y1; y++) {
      for (x = x0; x <= x1; x++) {
        i = y * W + x;
        if (!mask[i] || label[i]) continue;
        id++;
        var sp = 0, area = 0, sx = 0, sy = 0, bx0 = x, bx1 = x, by0 = y, by1 = y;
        stack[sp++] = i; label[i] = id;
        while (sp) {
          var j = stack[--sp], jx = j % W, jy = (j - jx) / W;
          area++; sx += jx; sy += jy;
          if (jx < bx0) bx0 = jx; if (jx > bx1) bx1 = jx; if (jy < by0) by0 = jy; if (jy > by1) by1 = jy;
          // 4-neighbours inside the search window
          if (jx > x0) { var a = j - 1; if (mask[a] && !label[a]) { label[a] = id; stack[sp++] = a; } }
          if (jx < x1) { var b = j + 1; if (mask[b] && !label[b]) { label[b] = id; stack[sp++] = b; } }
          if (jy > y0) { var c = j - W; if (mask[c] && !label[c]) { label[c] = id; stack[sp++] = c; } }
          if (jy < y1) { var e = j + W; if (mask[e] && !label[e]) { label[e] = id; stack[sp++] = e; } }
        }
        if (area < minArea || area > maxArea) continue;
        var bw = bx1 - bx0 + 1, bh = by1 - by0 + 1;
        var fill = area / (bw * bh);
        var aspect = Math.max(bw, bh) / Math.min(bw, bh);
        if (fill < 0.35 || aspect > 3.2) continue;   // not ball-shaped (a fast ball blurs into an oval)
        var cx = sx / area, cy = sy / area, r = Math.sqrt(area / Math.PI);
        var score = area * (0.5 + fill);
        if (this.expectR) score *= 1 / (1 + Math.abs(Math.log(r / this.expectR)));
        if (px !== null) {
          var dist = Math.hypot(cx - px, cy - py);
          score *= 1 / (1 + dist / Math.max(4, r * 2));
        }
        if (score > bestScore) { bestScore = score; best = { x: cx, y: cy, r: r, area: area }; }
      }
    }
    return best;
  };

  // ------------------------------------------------------------------ body helpers

  // MediaPipe Pose landmark numbers (the kid's own left/right).
  var LM = { nose: 0, ls: 11, rs: 12, lw: 15, rw: 16, lh: 23, rh: 24, lk: 25, rk: 26, la: 27, ra: 28 };

  // Turn MediaPipe's 0..1 landmarks into processing-frame pixels. Returns null if the body is not clear.
  function poseFromLandmarks(lms, W, H) {
    if (!lms || lms.length < 29) return null;
    function p(k) { var l = lms[LM[k]]; return { x: l.x * W, y: l.y * H, vis: l.visibility == null ? 1 : l.visibility }; }
    var out = {};
    Object.keys(LM).forEach(function (k) { out[k] = p(k); });
    if (out.lh.vis < 0.3 || out.rh.vis < 0.3 || out.ls.vis < 0.3 || out.rs.vis < 0.3) return null;
    out.midX = (out.lh.x + out.rh.x) / 2;
    out.hipY = (out.lh.y + out.rh.y) / 2;
    out.shoulderW = Math.max(4, Math.abs(out.ls.x - out.rs.x));
    var ankVis = Math.max(out.la.vis, out.ra.vis);
    out.ankleY = ankVis >= 0.3 ? Math.max(out.la.vis >= 0.3 ? out.la.y : 0, out.ra.vis >= 0.3 ? out.ra.y : 0) : null;
    out.kneeY = (out.lk.y + out.rk.y) / 2;
    return out;
  }

  // ------------------------------------------------------------------ dribble detector

  function DribbleDetector(W, H) {
    this.W = W; this.H = H;
    this.floorY = null;   // where the ball's bottom touches the floor (pixels)
    this.stanceY = null;  // hips lower (bigger y) than this = "low"
    this.reset();
  }

  DribbleDetector.prototype.reset = function () {
    this.phase = null;          // 'down' (falling, track lowest point) or 'up' (rising, track highest point)
    this.ext = null;            // current extreme { x, y, r, t, pose }
    this.lastTop = null;        // highest point before the current fall
    this.lastBounceT = -1;
    this.lostFrames = 0;
    this.side = 0;              // which side of the body the ball is on: -1 / 0 / 1 (image left / right)
    this.bouncesSinceFlip = 0;
    this.pose = null;
  };

  DribbleDetector.prototype.handNear = function (ball, pose) {
    if (!pose || !ball) return null;
    var dl = pose.lw.vis >= 0.3 ? Math.hypot(ball.x - pose.lw.x, ball.y - pose.lw.y) : Infinity;
    var dr = pose.rw.vis >= 0.3 ? Math.hypot(ball.x - pose.rw.x, ball.y - pose.rw.y) : Infinity;
    if (dl === Infinity && dr === Infinity) return null;
    if (Math.abs(dl - dr) < 0.15 * pose.shoulderW) return null;   // ball right between the hands: can't tell
    return dl < dr ? 'left' : 'right';
  };

  // frame: { t (seconds), ball: {x,y,r} or null, pose: from poseFromLandmarks or null }
  // Returns a list of events: { type:'bounce', t, hand, low } and { type:'cross', t }.
  DribbleDetector.prototype.update = function (frame) {
    var ev = [], b = frame.ball, t = frame.t;
    if (frame.pose) this.pose = frame.pose;
    if (!b) {
      if (++this.lostFrames > 8) { this.phase = null; this.ext = null; this.lastTop = null; }
      return ev;
    }
    this.lostFrames = 0;
    var hyst = Math.max(0.45 * b.r, this.H * 0.012);
    var snap = { x: b.x, y: b.y, r: b.r, t: t, pose: this.pose };

    if (this.phase === null) {
      // Where the ball is first seen counts as a "top", so even the first bounce needs a real fall.
      this.phase = 'down'; this.ext = snap; this.lastTop = snap;
    } else if (this.phase === 'down') {
      if (b.y >= this.ext.y) this.ext = snap;
      else if (this.ext.y - b.y >= hyst) {
        var bounce = this._bounce(this.ext);
        if (bounce) ev.push(bounce);
        this.phase = 'up'; this.ext = snap;
      }
    } else { // up
      if (b.y <= this.ext.y) this.ext = snap;
      else if (b.y - this.ext.y >= hyst) {
        this.lastTop = this.ext;
        this.phase = 'down'; this.ext = snap;
      }
    }

    // Crossovers: the ball moves from one side of the hips to the other with a bounce in between.
    var pose = this.pose;
    if (pose) {
      var dead = 0.18 * pose.shoulderW;
      var s = b.x < pose.midX - dead ? -1 : (b.x > pose.midX + dead ? 1 : 0);
      if (s !== 0) {
        if (this.side !== 0 && s !== this.side && this.bouncesSinceFlip >= 1 && b.y > pose.hipY - b.r) {
          ev.push({ type: 'cross', t: t });
        }
        if (s !== this.side) this.bouncesSinceFlip = 0;
        this.side = s;
      }
    }
    return ev;
  };

  DribbleDetector.prototype._bounce = function (low) {
    if (low.t - this.lastBounceT < 0.11) return null;
    var r = low.r;
    // The ball has to fall a real distance (not just jiggle) ...
    if (this.lastTop && low.y - this.lastTop.y < 1.4 * r) return null;
    // ... and its bottom has to reach the floor line once we know where the floor is.
    if (this.floorY !== null && low.y + r < this.floorY - 1.8 * r) return null;
    this.lastBounceT = low.t;
    this.bouncesSinceFlip++;
    var pose = low.pose || this.pose;
    var hand = this.handNear(this.lastTop || low, (this.lastTop && this.lastTop.pose) || pose);
    var lowStance = null;
    if (pose && this.stanceY !== null) lowStance = pose.hipY >= this.stanceY;
    return { type: 'bounce', t: low.t, hand: hand, low: lowStance, x: low.x, y: low.y };
  };

  // ------------------------------------------------------------------ calibration

  function Calibrator() {
    this.tall = []; this.stance = []; this.ankles = []; this.shoulders = []; this.bounceFloor = [];
  }
  Calibrator.prototype.addTall = function (pose) {
    if (!pose) return;
    this.tall.push(pose.hipY); this.shoulders.push(pose.shoulderW);
    if (pose.ankleY !== null) this.ankles.push(pose.ankleY);
  };
  Calibrator.prototype.addStance = function (pose) { if (pose) this.stance.push(pose.hipY); };
  Calibrator.prototype.addBounce = function (ev, r) { this.bounceFloor.push(ev.y + r); };
  // Returns { floorY, stanceY, tallHipY, shoulderW } (any of them may be null).
  Calibrator.prototype.result = function () {
    var tall = this.tall.length ? median(this.tall) : null;
    var st = this.stance.length ? median(this.stance) : null;
    var ank = this.ankles.length ? median(this.ankles) : null;
    var stanceY = null;
    if (tall !== null) {
      var leg = ank !== null ? Math.max(10, ank - tall) : 40;
      var minDrop = 0.06 * leg;   // "low" must be at least a little lower than standing
      stanceY = st !== null ? tall + Math.max(minDrop, 0.5 * (st - tall)) : tall + 0.12 * leg;
    }
    var floorY = null;
    if (this.bounceFloor.length >= 2) floorY = median(this.bounceFloor);
    else if (ank !== null) floorY = ank + 6;
    return { floorY: floorY, stanceY: stanceY, tallHipY: tall, shoulderW: this.shoulders.length ? median(this.shoulders) : null };
  };

  // ------------------------------------------------------------------ targets (Target Tap)

  // Six circles on the floor around the kid, scaled to their body: near, middle and far on each side.
  // A target counts when the ball BOUNCES inside it (passing through doesn't count).
  function targetSpots(pose, floorY, ballR) {
    var sw = pose.shoulderW, mid = pose.midX;
    var floor = floorY !== null ? floorY : (pose.ankleY !== null ? pose.ankleY + ballR : pose.hipY + 3 * sw);
    var y = floor - ballR;
    var rad = Math.max(1.6 * ballR, 0.35 * sw);
    return [1.0, 1.6, 2.2].reduce(function (a, k) {
      a.push({ x: mid - k * sw, y: y, r: rad }, { x: mid + k * sw, y: y, r: rad });
      return a;
    }, []);
  }

  function bounceInTarget(ev, tg) {
    return !!ev && ev.type === 'bounce' && Math.abs(ev.x - tg.x) <= tg.r;
  }

  // ------------------------------------------------------------------ drills, scoring, game layer

  var DRILLS = [
    { id: 'pound', name: 'Pound It', emoji: '💥', secs: 30, needsBody: false,
      how: 'Hard, fast dribbles with your strong hand. Every bounce counts.', unit: 'dribbles', stars: [40, 70, 100] },
    { id: 'weak', name: 'Weak Hand Pound', emoji: '🫲', secs: 30, needsBody: true,
      how: 'Pound it with your weak hand only. Only weak-hand bounces count.', unit: 'weak-hand dribbles', stars: [30, 55, 80] },
    { id: 'cross', name: 'Crossover Count', emoji: '🔀', secs: 30, needsBody: true,
      how: 'Cross the ball in front of you, hand to hand. Each clean crossover counts.', unit: 'crossovers', stars: [15, 30, 45] },
    { id: 'target', name: 'Target Tap', emoji: '🎯', secs: 30, needsBody: true,
      how: 'A circle lights up on the floor beside you. Bounce the ball inside it before it fades.', unit: 'targets', stars: [6, 10, 14] },
    { id: 'low', name: 'Stay Low', emoji: '🦵', secs: 30, needsBody: true,
      how: 'Dribble while you stay in your stance. Bounces only count while your hips are low.', unit: 'low dribbles', stars: [30, 55, 80] },
    { id: 'call', name: 'Call It Out', emoji: '📣', secs: 30, needsBody: true,
      how: 'Do the move the app calls: RIGHT, LEFT or CROSS. Be quick!', unit: 'moves', stars: [6, 10, 14] }
  ];
  function drill(id) { for (var i = 0; i < DRILLS.length; i++) if (DRILLS[i].id === id) return DRILLS[i]; return null; }

  function starsFor(id, score) {
    var d = drill(id); if (!d) return 0;
    var s = 0; d.stars.forEach(function (c) { if (score >= c) s++; }); return s;
  }

  // Sessions only count for leaderboards when the ball (and body, for body drills) were tracked most of the time.
  var MIN_QUALITY = 0.6;
  // Highest believable score per drill (anything above is rejected as a tracking glitch).
  var MAX_SCORE = { pound: 300, weak: 300, cross: 150, target: 60, low: 300, call: 60 };

  var BADGES = [
    { id: 'first100', name: 'First 100 Dribbles', emoji: '🏀', test: function (dq) { return dq.totalDribbles >= 100; } },
    { id: 'club1000', name: '1,000 Club', emoji: '💯', test: function (dq) { return dq.totalDribbles >= 1000; } },
    { id: 'club10000', name: '10,000 Club', emoji: '🚀', test: function (dq) { return dq.totalDribbles >= 10000; } },
    { id: 'weakhero', name: 'Weak Hand Hero', emoji: '🦸', test: function (dq) { return (dq.stars.weak || 0) >= 3; } },
    { id: 'crossking', name: 'Crossover King/Queen', emoji: '👑', test: function (dq) { return (dq.stars.cross || 0) >= 3; } },
    { id: 'targetmaster', name: 'Target Master', emoji: '🎯', test: function (dq) { return (dq.stars.target || 0) >= 3; } },
    { id: 'streak7', name: '7-Day Streak', emoji: '🔥', test: function (dq) { return (dq.streak.best || 0) >= 7; } },
    { id: 'allstars', name: '3 Stars Everywhere', emoji: '🌟', test: function (dq) {
      return DRILLS.every(function (d) { return (dq.stars[d.id] || 0) >= 3; }); } }
  ];

  function emptyData() {
    return { v: 1, strongHand: 'right', xp: 0, totalDribbles: 0, bests: {}, stars: {}, badges: [],
      streak: { count: 0, best: 0, last: null }, daily: { date: null, done: [] }, sessions: [] };
  }

  // Clean up data loaded from storage (anything odd gets replaced by safe defaults).
  function normalizeData(x) {
    var d = emptyData();
    if (!x || typeof x !== 'object') return d;
    if (x.strongHand === 'left' || x.strongHand === 'right') d.strongHand = x.strongHand;
    d.xp = Math.max(0, Number(x.xp) || 0);
    d.totalDribbles = Math.max(0, Number(x.totalDribbles) || 0);
    DRILLS.forEach(function (dr) {
      if (x.bests && Number(x.bests[dr.id]) > 0) d.bests[dr.id] = Number(x.bests[dr.id]);
      if (x.stars && Number(x.stars[dr.id]) > 0) d.stars[dr.id] = Math.min(3, Number(x.stars[dr.id]));
    });
    if (Array.isArray(x.badges)) d.badges = x.badges.filter(function (b) { return typeof b === 'string'; }).slice(0, 50);
    if (x.streak && typeof x.streak === 'object') {
      d.streak.count = Number(x.streak.count) || 0; d.streak.best = Number(x.streak.best) || 0;
      d.streak.last = typeof x.streak.last === 'string' ? x.streak.last : null;
    }
    if (x.daily && typeof x.daily === 'object' && typeof x.daily.date === 'string') {
      d.daily.date = x.daily.date;
      d.daily.done = Array.isArray(x.daily.done) ? x.daily.done.filter(function (s) { return !!drill(s); }) : [];
    }
    if (Array.isArray(x.sessions)) d.sessions = x.sessions.filter(function (s) { return s && drill(s.d); }).slice(-300);
    return d;
  }

  function dayKey(date) {
    var dt = date || new Date();
    return dt.getFullYear() + '-' + String(dt.getMonth() + 1).padStart(2, '0') + '-' + String(dt.getDate()).padStart(2, '0');
  }
  function prevDayKey(key) {
    var p = key.split('-').map(Number);
    return dayKey(new Date(p[0], p[1] - 1, p[2] - 1));
  }

  // Today's three drills: the same for everyone on a given day, different from day to day.
  function dailyDrills(key) {
    var seed = 0;
    for (var i = 0; i < key.length; i++) seed = (seed * 31 + key.charCodeAt(i)) >>> 0;
    var ids = DRILLS.map(function (d) { return d.id; });
    for (var j = ids.length - 1; j > 0; j--) {
      seed = (seed * 1103515245 + 12345) >>> 0;
      var k = seed % (j + 1), tmp = ids[j]; ids[j] = ids[k]; ids[k] = tmp;
    }
    return ids.slice(0, 3);
  }

  /*
   * Record a finished drill. result = { drillId, score, dribbles, right, left, crossovers,
   *   targetsHit, targetsTotal, avgReactionMs, quality }.
   * Changes data in place and returns what happened: { stars, pb, xp, newBadges, dailyDone, counted }.
   */
  function recordSession(data, result, now, appVersion) {
    var id = result.drillId, d = drill(id);
    var score = Math.max(0, Math.round(result.score || 0));
    var counted = !!d && score <= (MAX_SCORE[id] || 300);
    var out = { stars: 0, pb: false, xp: 0, newBadges: [], dailyDone: false, counted: counted, prevBest: data.bests[id] || 0 };
    if (!counted) return out;
    var stars = starsFor(id, score);
    var pb = score > (data.bests[id] || 0) && score > 0;
    var xp = 10 + 5 * stars + (pb ? 10 : 0);
    if (pb) data.bests[id] = score;
    if (stars > (data.stars[id] || 0)) data.stars[id] = stars;
    data.xp += xp;
    data.totalDribbles += Math.max(0, Math.round(result.dribbles || 0));

    var key = dayKey(now);
    if (data.daily.date !== key) data.daily = { date: key, done: [] };
    var todays = dailyDrills(key);
    if (todays.indexOf(id) >= 0 && data.daily.done.indexOf(id) < 0) {
      data.daily.done.push(id);
      if (todays.every(function (x) { return data.daily.done.indexOf(x) >= 0; })) {
        out.dailyDone = true;
        data.xp += 25; xp += 25;
        if (data.streak.last !== key) {
          data.streak.count = data.streak.last === prevDayKey(key) ? data.streak.count + 1 : 1;
          data.streak.last = key;
          data.streak.best = Math.max(data.streak.best, data.streak.count);
        }
      }
    }

    data.sessions.push({
      d: id, t: (now || new Date()).toISOString(), s: score,
      dr: Math.round(result.dribbles || 0), r: Math.round(result.right || 0), l: Math.round(result.left || 0),
      x: Math.round(result.crossovers || 0), th: Math.round(result.targetsHit || 0), tt: Math.round(result.targetsTotal || 0),
      rt: Math.round(result.avgReactionMs || 0), q: Math.round((result.quality || 0) * 100) / 100,
      st: stars, xp: xp, ok: (result.quality || 0) >= MIN_QUALITY, v: appVersion || ''
    });
    if (data.sessions.length > 300) data.sessions = data.sessions.slice(-300);

    BADGES.forEach(function (b) {
      if (data.badges.indexOf(b.id) < 0 && b.test(data)) { data.badges.push(b.id); out.newBadges.push(b); }
    });
    out.stars = stars; out.pb = pb; out.xp = xp;
    return out;
  }

  // Combine the copy on this phone with the copy in the cloud. Sessions from both are kept (each once),
  // and no personal best, star, badge or streak record that either copy has is lost.
  function mergeData(a, b) {
    a = normalizeData(a); b = normalizeData(b);
    var base = a.xp >= b.xp ? a : b, other = base === a ? b : a;
    var key = function (s) { return s.d + '|' + s.t; };
    var seen = {};
    base.sessions.forEach(function (s) { seen[key(s)] = true; });
    other.sessions.forEach(function (s) {
      if (seen[key(s)]) return;
      seen[key(s)] = true;
      base.sessions.push(s);
      base.xp += Math.max(0, Number(s.xp) || 0);
      base.totalDribbles += Math.max(0, Number(s.dr) || 0);
    });
    base.sessions.sort(function (x, y) { return x.t < y.t ? -1 : x.t > y.t ? 1 : 0; });
    if (base.sessions.length > 300) base.sessions = base.sessions.slice(-300);
    if (other.streak.last && (!base.streak.last || other.streak.last > base.streak.last)) {
      base.streak.count = other.streak.count; base.streak.last = other.streak.last;
    }
    if (other.daily.date && (!base.daily.date || other.daily.date > base.daily.date)) base.daily = other.daily;
    else if (other.daily.date === base.daily.date) {
      other.daily.done.forEach(function (x) { if (base.daily.done.indexOf(x) < 0) base.daily.done.push(x); });
    }
    DRILLS.forEach(function (d) {
      if ((other.bests[d.id] || 0) > (base.bests[d.id] || 0)) base.bests[d.id] = other.bests[d.id];
      if ((other.stars[d.id] || 0) > (base.stars[d.id] || 0)) base.stars[d.id] = other.stars[d.id];
    });
    other.badges.forEach(function (x) { if (base.badges.indexOf(x) < 0) base.badges.push(x); });
    base.streak.best = Math.max(base.streak.best, other.streak.best);
    return base;
  }

  var api = {
    rgbToHsv: rgbToHsv, hueDiff: hueDiff, median: median,
    BallTracker: BallTracker, DribbleDetector: DribbleDetector, Calibrator: Calibrator,
    poseFromLandmarks: poseFromLandmarks, targetSpots: targetSpots, bounceInTarget: bounceInTarget,
    DRILLS: DRILLS, drill: drill, starsFor: starsFor, BADGES: BADGES, MIN_QUALITY: MIN_QUALITY, MAX_SCORE: MAX_SCORE,
    emptyData: emptyData, normalizeData: normalizeData, dayKey: dayKey, dailyDrills: dailyDrills,
    recordSession: recordSession, mergeData: mergeData
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.DQ = api;
})(typeof self !== 'undefined' ? self : this);
