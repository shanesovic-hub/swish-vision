// Swish Quest: SHOT CHART and PROGRESS cards on the Stats screen.
// Loaded by the website (index.html) and also injected by the Swish Quest Android app; whichever runs
// first installs it. Works everywhere (phone, desktop) because it reads the player's Swish Quest sessions.
//
//  - SHOT CHART: camera sessions carry where each shot was taken from (session.sv, saved with the session).
//    Pick one session, one day, or all time. High school half court; zones coloured hot / OK / cold.
//  - PROGRESS: shooting % per day (all shots or one shot type), the trend, and shots per day.
//
// session.sv = { v: 1, t: <camera start time, ms>, p: <3 characters per charted shot>, ft: [made, attempted] }
(function () {
  if (window.__svStatsInstalled) return;
  window.__svStatsInstalled = true;

  // ---------- court (feet from the middle of the rim: x to the shooter's right, y out toward the free-throw line) ----------
  var RIM_FT = 7.3;       // closer than this: at the rim (the app's layup range)
  var LINE = 19.75;       // high school 3-point line: 19 ft 9 in from the middle of the rim
  var BASE = -5.25;       // baseline (rim centre is 63 in from it)
  var FT_LINE = 13.75;    // free-throw line (15 ft from the backboard)

  function isThree(x, y) { return y <= 0 ? Math.abs(x) >= LINE : Math.sqrt(x * x + y * y) >= LINE; }

  function zoneOf(x, y) {
    var d = Math.sqrt(x * x + y * y), a = Math.atan2(x, y) * 180 / Math.PI, aa = Math.abs(a);
    if (d < RIM_FT) return 'rim';
    if (!isThree(x, y)) {
      if (aa < 22.5) return 'mid_center';
      if (aa < 60) return a < 0 ? 'mid_left' : 'mid_right';
      return a < 0 ? 'mid_left_baseline' : 'mid_right_baseline';
    }
    if (aa < 25) return 'three_top';
    if (aa < 78) return a < 0 ? 'three_left_wing' : 'three_right_wing';
    return a < 0 ? 'three_left_corner' : 'three_right_corner';
  }
  window.svZoneOf = zoneOf;

  // Distance to the 3-point line along a ray at angle a (degrees from straight out).
  function lineAt(a) {
    var aa = Math.abs(a);
    if (aa <= 90) return LINE;
    var s = Math.abs(Math.sin(a * Math.PI / 180));
    return s > 0.01 ? LINE / s : 200;
  }
  var ZONES = [
    // key, from angle, to angle, inner (number or 'line'), outer, label x, label y
    ['rim', -180, 180, 0, RIM_FT, 0, 3.6],
    ['mid_left_baseline', -180, -60, RIM_FT, 'line', -13.5, 0.8],
    ['mid_left', -60, -22.5, RIM_FT, 'line', -9.5, 10.5],
    ['mid_center', -22.5, 22.5, RIM_FT, 'line', 0, 10.4],
    ['mid_right', 22.5, 60, RIM_FT, 'line', 9.5, 10.5],
    ['mid_right_baseline', 60, 180, RIM_FT, 'line', 13.5, 0.8],
    ['three_left_corner', -180, -78, 'line', 80, -22.4, -0.6],
    ['three_left_wing', -78, -25, 'line', 80, -17.5, 16.5],
    ['three_top', -25, 25, 'line', 80, 0, 23.5],
    ['three_right_wing', 25, 78, 'line', 80, 17.5, 16.5],
    ['three_right_corner', 78, 180, 'line', 80, 22.4, -0.6],
  ];

  function heat(key, made, att) {
    var pct = 100 * made / att;
    var g = key === 'rim' ? [60, 45] : key.indexOf('mid') === 0 ? [45, 35] : [38, 30];
    return pct >= g[0] ? 1 : pct >= g[1] ? 0 : -1;
  }

  // ---------- compact shot codes (saved with the session, so they sync to every device) ----------
  // x to the nearest half foot from -25 to 25, y from -5.5 to 30, made or missed: 3 base-36 characters.
  function encodeShots(log) {
    var p = '', ftM = 0, ftA = 0;
    (log || []).forEach(function (s) {
      var made = s.r === 'make' ? 1 : 0;
      if (s.type === 'ft') { ftA++; ftM += made; return; }
      if (typeof s.x !== 'number' || typeof s.y !== 'number') return;
      var ix = Math.max(0, Math.min(100, Math.round((s.x + 25) * 2)));
      var iy = Math.max(0, Math.min(71, Math.round((s.y + 5.5) * 2)));
      var v = (ix * 72 + iy) * 2 + made, c = v.toString(36);
      p += ('00' + c).slice(-3);
    });
    return { p: p, ft: [ftM, ftA] };
  }
  window.svEncodeShots = encodeShots;

  function decodeShots(p) {
    var out = [];
    for (var i = 0; i + 3 <= (p || '').length; i += 3) {
      var v = parseInt(p.substr(i, 3), 36);
      if (isNaN(v)) continue;
      var m = v % 2, q = (v - m) / 2, iy = q % 72, ix = (q - iy) / 72;
      out.push({ x: ix / 2 - 25, y: iy / 2 - 5.5, m: m === 1 });
    }
    return out;
  }

  // ---------- data ----------
  function sessions() { try { return (state && Array.isArray(state.sessions)) ? state.sessions : []; } catch (e) { return []; } }
  function charted() {
    var out = [];
    sessions().forEach(function (s, i) { if (s && s.sv && (s.sv.p || (s.sv.ft && s.sv.ft[1]))) out.push({ i: i, s: s }); });
    return out;
  }

  // ---------- SVG: high school half court ----------
  var L = -25, R = 25, T = BASE, B = 28, S = 10;
  function px(x) { return ((x - L) * S).toFixed(1); }
  function py(y) { return ((y - T) * S).toFixed(1); }
  function ring(a1, a2, inner, outer) {
    var pts = [], i, a, r, n = 48;
    var rad = function (v, ang) { return v === 'line' ? lineAt(ang) : v; };
    for (i = 0; i <= n; i++) { a = a1 + (a2 - a1) * i / n; r = rad(outer, a); pts.push(px(r * Math.sin(a * Math.PI / 180)) + ',' + py(r * Math.cos(a * Math.PI / 180))); }
    for (i = n; i >= 0; i--) { a = a1 + (a2 - a1) * i / n; r = rad(inner, a); pts.push(px(r * Math.sin(a * Math.PI / 180)) + ',' + py(r * Math.cos(a * Math.PI / 180))); }
    return pts.join(' ');
  }

  var uid = 0;
  function courtSvg(dots, showDots) {
    var lines = {};
    dots.forEach(function (p) {
      var z = zoneOf(p.x, p.y);
      lines[z] = lines[z] || [0, 0];
      lines[z][1]++; if (p.m) lines[z][0]++;
    });
    var W = (R - L) * S, H = (B - T) * S, clip = 'svc' + (++uid);
    var s = '<svg viewBox="0 0 ' + W + ' ' + H + '" style="width:100%;display:block;border-radius:8px;background:#2b1f14">';
    s += '<defs><clipPath id="' + clip + '"><rect x="0" y="0" width="' + W + '" height="' + H + '"/></clipPath></defs><g clip-path="url(#' + clip + ')">';
    // hardwood
    for (var k = 0; k < W; k += 25) s += '<rect x="' + k + '" y="0" width="25" height="' + H + '" fill="' + (k / 25 % 2 ? '#3a2a1a' : '#33251a') + '"/>';
    ZONES.forEach(function (z) {
      var l = lines[z[0]], fill = 'rgba(255,255,255,0.04)';
      if (l) { var h = heat(z[0], l[0], l[1]); fill = h > 0 ? 'rgba(34,170,90,0.6)' : h === 0 ? 'rgba(235,200,80,0.6)' : 'rgba(215,60,55,0.6)'; }
      s += '<polygon points="' + ring(z[1], z[2], z[3], z[4]) + '" fill="' + fill + '" stroke="rgba(0,0,0,0.35)" stroke-width="1.2"/>';
    });
    var ln = 'fill="none" stroke="rgba(245,240,230,0.9)" stroke-width="2.2"';
    // lane (12 ft wide), free-throw line and circle (top half solid, bottom half dashed)
    s += '<rect x="' + px(-6) + '" y="' + py(T) + '" width="' + (12 * S) + '" height="' + ((FT_LINE - T) * S) + '" ' + ln + '/>';
    s += '<path d="M' + px(-6) + ' ' + py(FT_LINE) + ' A' + (6 * S) + ' ' + (6 * S) + ' 0 0 0 ' + px(6) + ' ' + py(FT_LINE) + '" ' + ln + '/>';
    s += '<path d="M' + px(-6) + ' ' + py(FT_LINE) + ' A' + (6 * S) + ' ' + (6 * S) + ' 0 0 1 ' + px(6) + ' ' + py(FT_LINE) + '" ' + ln + ' stroke-dasharray="7 7"/>';
    // lane space marks
    [2.75, 5.75, 8.75, 11.75].forEach(function (y) {
      s += '<line x1="' + px(-6) + '" y1="' + py(y) + '" x2="' + px(-6.7) + '" y2="' + py(y) + '" ' + ln + '/>';
      s += '<line x1="' + px(6) + '" y1="' + py(y) + '" x2="' + px(6.7) + '" y2="' + py(y) + '" ' + ln + '/>';
    });
    // 3-point line: straight from the baseline to level with the rim, then the 19'9" arc
    s += '<path d="M' + px(-LINE) + ' ' + py(T) + ' L' + px(-LINE) + ' ' + py(0) + ' A' + (LINE * S) + ' ' + (LINE * S) + ' 0 0 0 ' + px(LINE) + ' ' + py(0) +
      ' L' + px(LINE) + ' ' + py(T) + '" ' + ln + '/>';
    // backboard, rim
    s += '<line x1="' + px(-3) + '" y1="' + py(-1.25) + '" x2="' + px(3) + '" y2="' + py(-1.25) + '" stroke="#f5f0e6" stroke-width="4"/>';
    s += '<line x1="' + px(0) + '" y1="' + py(-1.25) + '" x2="' + px(0) + '" y2="' + py(-0.75) + '" stroke="#ff7a3c" stroke-width="2"/>';
    s += '<circle cx="' + px(0) + '" cy="' + py(0) + '" r="' + (0.75 * S) + '" fill="none" stroke="#ff7a3c" stroke-width="2.6"/>';
    s += '<rect x="1" y="1" width="' + (W - 2) + '" height="' + (H - 2) + '" ' + ln + '/>';
    s += '</g>';
    if (showDots) {
      dots.forEach(function (p) {
        var x = +px(Math.max(L + 0.5, Math.min(R - 0.5, p.x))), y = +py(Math.max(T + 0.5, Math.min(B - 0.5, p.y)));
        if (p.m) s += '<circle cx="' + x + '" cy="' + y + '" r="4.5" fill="#4ade80" stroke="#0a0a0a" stroke-width="1"/>';
        else s += '<g stroke="#ff6b6b" stroke-width="2.5"><line x1="' + (x - 4) + '" y1="' + (y - 4) + '" x2="' + (x + 4) + '" y2="' + (y + 4) + '"/><line x1="' + (x - 4) + '" y1="' + (y + 4) + '" x2="' + (x + 4) + '" y2="' + (y - 4) + '"/></g>';
      });
    }
    ZONES.forEach(function (z) {
      var l = lines[z[0]]; if (!l) return;
      var st = 'font-family:\'Bebas Neue\',sans-serif;paint-order:stroke;stroke:#000;stroke-width:3px;fill:#fff;text-anchor:middle';
      s += '<text x="' + px(z[5]) + '" y="' + py(z[6]) + '" style="' + st + ';font-size:22px">' + l[0] + '/' + l[1] + '</text>';
      s += '<text x="' + px(z[5]) + '" y="' + (+py(z[6]) + 18) + '" style="' + st + ';font-size:16px;fill:#eee">' + Math.round(100 * l[0] / l[1]) + '%</text>';
    });
    return s + '</svg>';
  }

  var MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  var DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function dayLabel(day) { var p = day.split('-'), d = new Date(+p[0], +p[1] - 1, +p[2]); return DAYS[d.getDay()] + ' ' + MONTHS[d.getMonth()] + ' ' + d.getDate(); }
  function shortDay(day) { var p = day.split('-'); return MONTHS[+p[1] - 1] + ' ' + (+p[2]); }
  function timeLabel(ms) { var d = new Date(ms), h = d.getHours(); return ((h + 11) % 12 + 1) + ':' + pad(d.getMinutes()) + (h < 12 ? ' AM' : ' PM'); }
  var font = 'font-family:Outfit,sans-serif';
  var bebas = 'font-family:\'Bebas Neue\',sans-serif;letter-spacing:1px';

  // ---------- SHOT CHART card ----------
  var pick = null; // 'all' | 'day:YYYY-MM-DD' | 's:<index>'

  function shotChartCard() {
    var list = charted();
    var card = document.createElement('div');
    card.className = 'chart-container sv-card sv-wide'; card.id = 'sv-shotchart';
    var html = '<div class="chart-title">SHOT CHART</div>';
    if (!list.length) {
      card.innerHTML = html + '<div style="color:#888;' + font + ';font-size:14px;line-height:1.5;padding:4px 2px 8px">' +
        'Track a session with the camera in the Swish Quest app, turn on 📍 and set the free-throw spot. Each shot then shows up here by where it was taken.</div>';
      return card;
    }
    var days = [];
    list.forEach(function (c) { if (days.indexOf(c.s.date) < 0) days.push(c.s.date); });
    days.sort().reverse();
    var valid = pick === 'all' || (pick && pick.indexOf('day:') === 0 && days.indexOf(pick.slice(4)) >= 0) ||
      (pick && pick.indexOf('s:') === 0 && list.some(function (c) { return 's:' + c.i === pick; }));
    if (!valid) pick = 's:' + list[list.length - 1].i;
    var opts = '<option value="all"' + (pick === 'all' ? ' selected' : '') + '>All time</option><optgroup label="By day">';
    days.forEach(function (d) { opts += '<option value="day:' + d + '"' + (pick === 'day:' + d ? ' selected' : '') + '>' + dayLabel(d) + '</option>'; });
    opts += '</optgroup><optgroup label="By session">';
    list.slice().reverse().forEach(function (c) {
      var when = c.s.sv.t ? ' · ' + timeLabel(c.s.sv.t) : '';
      opts += '<option value="s:' + c.i + '"' + (pick === 's:' + c.i ? ' selected' : '') + '>' + shortDay(c.s.date) + when + ' · ' + c.s.makes + '/' + c.s.shots + '</option>';
    });
    opts += '</optgroup>';
    var chosen = pick === 'all' ? list : pick.indexOf('day:') === 0 ? list.filter(function (c) { return c.s.date === pick.slice(4); })
      : list.filter(function (c) { return 's:' + c.i === pick; });
    var dots = [], ftM = 0, ftA = 0;
    chosen.forEach(function (c) {
      dots = dots.concat(decodeShots(c.s.sv.p));
      if (c.s.sv.ft) { ftM += c.s.sv.ft[0] || 0; ftA += c.s.sv.ft[1] || 0; }
    });
    var made = dots.filter(function (p) { return p.m; }).length;
    html += '<select class="sv-pick" style="width:100%;margin:0 0 12px;padding:10px;border-radius:8px;background:#151522;color:#fff;border:1px solid var(--teal);' + font + ';font-size:15px">' + opts + '</select>';
    html += '<div class="sv-chart-body">';
    html += dots.length ? courtSvg(dots, pick !== 'all') : '<div style="color:#888;text-align:center;padding:16px;' + font + '">No placed shots here.</div>';
    html += '<div class="sv-chart-side">' + zoneTable(dots) + '</div></div>';
    html += '<div style="display:flex;justify-content:space-between;flex-wrap:wrap;gap:6px;margin-top:10px;' + bebas + ';font-size:18px;color:#ccc">' +
      '<span>ON CHART <span style="color:var(--teal)">' + made + '/' + dots.length + (dots.length ? ' (' + Math.round(100 * made / dots.length) + '%)' : '') + '</span></span>' +
      (ftA ? '<span>FREE THROWS <span style="color:var(--teal)">' + ftM + '/' + ftA + ' (' + Math.round(100 * ftM / ftA) + '%)</span></span>' : '') + '</div>';
    html += '<div style="color:#777;font-size:12px;' + font + ';margin-top:6px">Green = hot, yellow = OK, red = cold. Hoop at the top; left/right as the shooter faces the hoop.' +
      (pick === 'all' ? '' : ' Dots: ● make, ✕ miss.') + '</div>';
    card.innerHTML = html;
    card.querySelector('.sv-pick').onchange = function () { pick = this.value; render(); };
    return card;
  }

  var ZONE_NAMES = {
    rim: 'At the rim', mid_left_baseline: 'Left baseline', mid_left: 'Left elbow', mid_center: 'Straight on',
    mid_right: 'Right elbow', mid_right_baseline: 'Right baseline', three_left_corner: 'Left corner 3',
    three_left_wing: 'Left wing 3', three_top: 'Top of the key 3', three_right_wing: 'Right wing 3', three_right_corner: 'Right corner 3',
  };
  // Zone-by-zone list (shown beside the chart on wide screens, below it on phones).
  function zoneTable(dots) {
    var lines = {};
    dots.forEach(function (p) { var z = zoneOf(p.x, p.y); lines[z] = lines[z] || [0, 0]; lines[z][1]++; if (p.m) lines[z][0]++; });
    var keys = Object.keys(ZONE_NAMES).filter(function (k) { return lines[k]; });
    if (!keys.length) return '';
    return '<div class="sv-zone-table">' + keys.map(function (k) {
      var l = lines[k], pct = Math.round(100 * l[0] / l[1]), h = heat(k, l[0], l[1]);
      var col = h > 0 ? '#4ade80' : h === 0 ? '#ffe66d' : '#ff6b6b';
      return '<div style="display:flex;justify-content:space-between;align-items:center;padding:6px 2px;border-bottom:1px solid #222;' + font + ';font-size:14px;color:#ccc">' +
        '<span><span style="display:inline-block;width:9px;height:9px;border-radius:2px;background:' + col + ';margin-right:8px"></span>' + ZONE_NAMES[k] + '</span>' +
        '<span style="' + bebas + ';font-size:17px;color:#fff">' + l[0] + '/' + l[1] + ' <span style="color:' + col + '">' + pct + '%</span></span></div>';
    }).join('') + '</div>';
  }

  // ---------- PROGRESS card ----------
  var TYPE_LABEL = { all: 'ALL', layups: 'LAYUPS', midrange: 'MID', ft: 'FT', three: '3PT' };
  var ptype = 'all', prange = 'all';

  function progressCard() {
    var card = document.createElement('div');
    card.className = 'chart-container sv-card sv-wide'; card.id = 'sv-progress';
    var byDay = {};
    sessions().forEach(function (s) {
      if (!s || !s.date) return;
      var m = 0, a = 0;
      if (ptype === 'all') { m = s.makes || 0; a = s.shots || 0; }
      else if (s.shotTypes && s.shotTypes[ptype]) { m = s.shotTypes[ptype].made || 0; a = s.shotTypes[ptype].attempted || 0; }
      if (a <= 0) return;
      byDay[s.date] = byDay[s.date] || [0, 0];
      byDay[s.date][0] += m; byDay[s.date][1] += a;
    });
    var days = Object.keys(byDay).sort();
    if (prange === '30') {
      var cut = new Date(Date.now() - 30 * 86400000), cs = cut.getFullYear() + '-' + pad(cut.getMonth() + 1) + '-' + pad(cut.getDate());
      days = days.filter(function (d) { return d >= cs; });
    }
    var btn = function (key, label, on, attr) {
      return '<button type="button" ' + attr + '="' + key + '" style="flex:1;padding:8px 2px;border-radius:6px;cursor:pointer;border:1px solid ' + (on ? 'var(--teal)' : '#333') +
        ';background:' + (on ? 'rgba(0,180,197,0.18)' : '#151522') + ';color:' + (on ? '#fff' : '#999') + ';' + bebas + ';font-size:16px">' + label + '</button>';
    };
    var html = '<div class="chart-title">PROGRESS</div><div style="display:flex;gap:6px;margin-bottom:8px">';
    Object.keys(TYPE_LABEL).forEach(function (k) { html += btn(k, TYPE_LABEL[k], ptype === k, 'data-svt'); });
    html += '</div><div style="display:flex;gap:6px;margin-bottom:12px">' + btn('all', 'ALL TIME', prange === 'all', 'data-svr') + btn('30', 'LAST 30 DAYS', prange === '30', 'data-svr') + '</div>';
    if (days.length < 2) {
      html += '<div style="color:#888;text-align:center;padding:16px;' + font + '">Shoot on a couple of different days to see the progress line.</div>';
    } else {
      html += progressSvg(days, byDay);
      var k = Math.min(5, Math.floor(days.length / 2));
      var sum = function (list) { var m = 0, a = 0; list.forEach(function (d) { m += byDay[d][0]; a += byDay[d][1]; }); return a ? Math.round(100 * m / a) : 0; };
      var first = sum(days.slice(0, k)), last = sum(days.slice(-k)), diff = last - first;
      var col = diff > 0 ? '#4ade80' : diff < 0 ? '#ff6b6b' : '#ccc';
      var stat = function (label, val, color) {
        return '<div style="text-align:center;flex:1"><div style="' + font + ';font-size:11px;color:#888;letter-spacing:1px">' + label + '</div>' +
          '<div style="' + bebas + ';font-size:26px;color:' + color + '">' + val + '</div></div>';
      };
      html += '<div style="display:flex;align-items:flex-end;margin-top:10px;border-top:1px solid #222;padding-top:8px">' +
        stat('FIRST ' + k + ' DAYS', first + '%', '#ccc') +
        stat('CHANGE', (diff > 0 ? '\u25B2 +' : diff < 0 ? '\u25BC ' : '') + diff, col) +
        stat('LAST ' + k + ' DAYS', last + '%', '#fff') + '</div>';
      html += '<div style="color:#777;font-size:12px;' + font + ';margin-top:6px">Dots: shooting % each day. Line: the trend (last 5 days). Bars: shots taken.</div>';
    }
    card.innerHTML = html;
    Array.prototype.forEach.call(card.querySelectorAll('[data-svt]'), function (b) { b.onclick = function () { ptype = b.getAttribute('data-svt'); render(); }; });
    Array.prototype.forEach.call(card.querySelectorAll('[data-svr]'), function (b) { b.onclick = function () { prange = b.getAttribute('data-svr'); render(); }; });
    return card;
  }

  function progressSvg(days, byDay) {
    var W = 520, H = 220, l = 34, r = 10, t = 10, gh = 130, bh = 42, n = days.length;
    var x = function (i) { return l + (n === 1 ? (W - l - r) / 2 : i * (W - l - r) / (n - 1)); };
    var y = function (pct) { return t + gh - pct / 100 * gh; };
    var s = '<svg viewBox="0 0 ' + W + ' ' + H + '" style="width:100%;display:block">';
    [0, 25, 50, 75, 100].forEach(function (g) {
      s += '<line x1="' + l + '" x2="' + (W - r) + '" y1="' + y(g) + '" y2="' + y(g) + '" stroke="#2a2a3e" stroke-width="1"/>';
      s += '<text x="' + (l - 5) + '" y="' + (y(g) + 4) + '" fill="#777" font-size="11" text-anchor="end" font-family="Outfit,sans-serif">' + g + '%</text>';
    });
    var maxA = 1; days.forEach(function (d) { maxA = Math.max(maxA, byDay[d][1]); });
    var bw = Math.max(2, Math.min(16, (W - l - r) / n * 0.6));
    days.forEach(function (d, i) {
      var h = byDay[d][1] / maxA * bh;
      s += '<rect x="' + (x(i) - bw / 2) + '" y="' + (t + gh + 14 + bh - h) + '" width="' + bw + '" height="' + h + '" fill="rgba(249,98,159,0.55)" rx="1"><title>' + shortDay(d) + ': ' + byDay[d][1] + ' shots</title></rect>';
    });
    var trend = days.map(function (d, i) {
      var m = 0, a = 0;
      for (var j = Math.max(0, i - 4); j <= i; j++) { m += byDay[days[j]][0]; a += byDay[days[j]][1]; }
      return 100 * m / a;
    });
    s += '<polyline fill="none" stroke="#ffe66d" stroke-width="2.5" points="' + trend.map(function (p, i) { return x(i).toFixed(1) + ',' + y(p).toFixed(1); }).join(' ') + '"/>';
    days.forEach(function (d, i) {
      var pct = 100 * byDay[d][0] / byDay[d][1];
      s += '<circle cx="' + x(i).toFixed(1) + '" cy="' + y(pct).toFixed(1) + '" r="4" fill="#00b4c5"><title>' + shortDay(d) + ': ' + byDay[d][0] + '/' + byDay[d][1] + ' (' + Math.round(pct) + '%)</title></circle>';
    });
    var lab = function (i, anchor) { return '<text x="' + x(i) + '" y="' + (H - 2) + '" fill="#888" font-size="11" text-anchor="' + anchor + '" font-family="Outfit,sans-serif">' + shortDay(days[i]) + '</text>'; };
    s += lab(0, 'start');
    if (n > 2) s += lab(Math.floor((n - 1) / 2), 'middle');
    s += lab(n - 1, 'end');
    return s + '</svg>';
  }

  // ---------- put the cards into Stats ----------
  function render() {
    var c = document.getElementById('stats-content');
    if (!c) return;
    ['sv-progress', 'sv-shotchart'].forEach(function (id) { var o = document.getElementById(id); if (o) o.remove(); });
    if (!c.children.length || c.querySelector('.empty-state')) return; // "No sessions logged yet"
    var cards = c.querySelectorAll(':scope > .chart-container');
    var after = cards.length >= 3 ? cards[2] : cards[cards.length - 1];
    var p = progressCard(), sc = shotChartCard();
    if (after && after.parentNode === c) { c.insertBefore(sc, after.nextSibling); c.insertBefore(p, after.nextSibling); }
    else { c.appendChild(p); c.appendChild(sc); }
  }
  window.svStatsRender = render;

  function watch() {
    var c = document.getElementById('stats-content');
    if (!c) return false;
    new MutationObserver(function () {
      if (c.children.length && !document.getElementById('sv-progress') && !c.querySelector('.empty-state')) render();
    }).observe(c, { childList: true });
    render();
    return true;
  }
  if (!watch()) {
    var tries = 0, iv = setInterval(function () { if (watch() || ++tries > 60) clearInterval(iv); }, 500);
  }
})();
