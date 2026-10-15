// Injected by the Swish Quest Android app into the Stats screen (after bridge.js).
// Adds two cards to Stats:
//  - SHOT CHART: where the camera saw each shot taken from, by zone (made/attempted, hot/cold),
//    for one session, one day, or all time.
//  - PROGRESS: shooting % over time (every session day), overall or by shot type, with the trend.
// Shot positions come from camera sessions (with the free-throw spot set) and are kept on this phone,
// per Swish Quest player. Progress uses the player's Swish Quest sessions (camera and typed in).
(function () {
  if (window.__svStatsInstalled) return;
  window.__svStatsInstalled = true;

  var RIM_FT = 7.3, THREE_FT = 17.5;
  var KEY = 'sv_charts';
  var ZONES = [
    // key, label, from angle, to angle, inner radius, outer radius, label angle, label radius
    ['rim', 'At the rim', -180, 180, 0, RIM_FT, 0, 4.5],
    ['mid_left_baseline', 'Left baseline', -180, -60, RIM_FT, THREE_FT, -78, 12.5],
    ['mid_left', 'Left elbow', -60, -22.5, RIM_FT, THREE_FT, -41, 12.5],
    ['mid_center', 'Middle', -22.5, 22.5, RIM_FT, THREE_FT, 0, 11],
    ['mid_right', 'Right elbow', 22.5, 60, RIM_FT, THREE_FT, 41, 12.5],
    ['mid_right_baseline', 'Right baseline', 60, 180, RIM_FT, THREE_FT, 78, 12.5],
    ['three_left_corner', 'Left corner 3', -180, -68, THREE_FT, 60, -80, 21],
    ['three_left_wing', 'Left wing 3', -68, -25, THREE_FT, 60, -46, 22],
    ['three_top', 'Top 3', -25, 25, THREE_FT, 60, 0, 22],
    ['three_right_wing', 'Right wing 3', 25, 68, THREE_FT, 60, 46, 22],
    ['three_right_corner', 'Right corner 3', 68, 180, THREE_FT, 60, 80, 21],
  ];

  function zoneOf(x, y) {
    var d = Math.sqrt(x * x + y * y), a = Math.atan2(x, y) * 180 / Math.PI, aa = Math.abs(a);
    if (d < RIM_FT) return 'rim';
    if (d < THREE_FT) {
      if (aa < 22.5) return 'mid_center';
      if (aa < 60) return a < 0 ? 'mid_left' : 'mid_right';
      return a < 0 ? 'mid_left_baseline' : 'mid_right_baseline';
    }
    if (aa < 25) return 'three_top';
    if (aa < 68) return a < 0 ? 'three_left_wing' : 'three_right_wing';
    return a < 0 ? 'three_left_corner' : 'three_right_corner';
  }

  function heat(key, made, att) {
    var pct = 100 * made / att;
    var g = key === 'rim' ? [60, 45] : key.indexOf('mid') === 0 ? [45, 35] : [38, 30];
    return pct >= g[0] ? 1 : pct >= g[1] ? 0 : -1;
  }

  // ---------- storage (this phone, per Swish Quest player) ----------
  function profileId() { try { return String(_activeProfileId || ''); } catch (e) { return ''; } }
  function loadAll() { try { return JSON.parse(localStorage.getItem(KEY) || '{}') || {}; } catch (e) { return {}; } }
  function records() { var a = loadAll()[profileId()]; return Array.isArray(a) ? a : []; }
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function dayOf(ms) { var d = new Date(ms); return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }

  // Called by bridge.js right after a camera session is saved to Swish Quest, for the same player.
  window.__svRecordChart = function (d, pid) {
    try {
      pid = pid || profileId();
      if (!pid || !d || !Array.isArray(d.log)) return;
      var dots = [], ftM = 0, ftA = 0;
      d.log.forEach(function (s) {
        var made = s.r === 'make' ? 1 : 0;
        if (s.type === 'ft') { ftA++; ftM += made; return; }
        if (typeof s.x === 'number' && typeof s.y === 'number') dots.push(s.x.toFixed(1) + ',' + s.y.toFixed(1) + ',' + made);
      });
      if (!dots.length && !ftA) return; // nothing the chart can show (no free-throw spot set)
      var all = loadAll(), list = Array.isArray(all[pid]) ? all[pid] : [];
      var id = Number(d.startedAt) || Date.now();
      list = list.filter(function (r) { return r.id !== id; });
      list.push({ id: id, day: dayOf(id), made: d.makes || 0, att: d.shots || 0, d: dots.join(';'), ft: [ftM, ftA] });
      list.sort(function (a, b) { return a.id - b.id; });
      all[pid] = list.slice(-400);
      localStorage.setItem(KEY, JSON.stringify(all));
    } catch (e) { /* the chart is extra; never break saving */ }
  };

  function dotsOf(recs) {
    var out = [];
    recs.forEach(function (r) {
      (r.d || '').split(';').forEach(function (p) {
        if (!p) return;
        var v = p.split(',');
        out.push({ x: +v[0], y: +v[1], m: v[2] === '1' });
      });
    });
    return out;
  }

  // ---------- SVG shot chart ----------
  var L = -25, R = 25, T = -5.25, B = 28, S = 10; // feet -> SVG units
  function px(x) { return ((x - L) * S).toFixed(1); }
  function py(y) { return ((y - T) * S).toFixed(1); }
  function wedge(a1, a2, r1, r2) {
    var pts = [], i, a, n = 40;
    for (i = 0; i <= n; i++) { a = (a1 + (a2 - a1) * i / n) * Math.PI / 180; pts.push(px(r2 * Math.sin(a)) + ',' + py(r2 * Math.cos(a))); }
    for (i = n; i >= 0; i--) { a = (a1 + (a2 - a1) * i / n) * Math.PI / 180; pts.push(px(r1 * Math.sin(a)) + ',' + py(r1 * Math.cos(a))); }
    return pts.join(' ');
  }

  function chartSvg(dots, showDots) {
    var lines = {};
    dots.forEach(function (p) {
      var z = zoneOf(p.x, p.y);
      lines[z] = lines[z] || [0, 0];
      lines[z][1]++; if (p.m) lines[z][0]++;
    });
    var W = (R - L) * S, H = (B - T) * S;
    var s = '<svg viewBox="0 0 ' + W + ' ' + H + '" style="width:100%;display:block;border-radius:8px;background:#1d1610">';
    s += '<defs><clipPath id="sv-court"><rect x="0" y="0" width="' + W + '" height="' + H + '"/></clipPath></defs><g clip-path="url(#sv-court)">';
    ZONES.forEach(function (z) {
      var l = lines[z[0]], fill = 'rgba(150,150,150,0.18)';
      if (l) { var h = heat(z[0], l[0], l[1]); fill = h > 0 ? 'rgba(34,170,90,0.62)' : h === 0 ? 'rgba(230,200,90,0.62)' : 'rgba(210,60,55,0.62)'; }
      s += '<polygon points="' + wedge(z[2], z[3], z[4], z[5]) + '" fill="' + fill + '" stroke="rgba(0,0,0,0.45)" stroke-width="1.5"/>';
    });
    var ln = 'fill="none" stroke="rgba(240,236,228,0.85)" stroke-width="2"';
    s += '<rect x="' + px(-6) + '" y="' + py(T) + '" width="' + (12 * S) + '" height="' + ((13.75 - T) * S) + '" ' + ln + '/>';
    s += '<circle cx="' + px(0) + '" cy="' + py(13.75) + '" r="' + (6 * S) + '" ' + ln + '/>';
    s += '<circle cx="' + px(0) + '" cy="' + py(0) + '" r="' + (THREE_FT * S) + '" ' + ln + '/>';
    s += '<line x1="' + px(-3) + '" y1="' + py(-1.25) + '" x2="' + px(3) + '" y2="' + py(-1.25) + '" ' + ln + ' stroke-width="3"/>';
    s += '<circle cx="' + px(0) + '" cy="' + py(0) + '" r="' + (0.75 * S) + '" fill="none" stroke="#ff7a3c" stroke-width="2.5"/>';
    s += '</g>';
    if (showDots) {
      dots.forEach(function (p) {
        var x = px(Math.max(L + 0.5, Math.min(R - 0.5, p.x))), y = py(Math.max(T + 0.5, Math.min(B - 0.5, p.y)));
        if (p.m) s += '<circle cx="' + x + '" cy="' + y + '" r="4.5" fill="#4ade80" stroke="#0a0a0a" stroke-width="1"/>';
        else s += '<g stroke="#f87171" stroke-width="2.5"><line x1="' + (x - 4) + '" y1="' + (y - 4) + '" x2="' + (+x + 4) + '" y2="' + (+y + 4) + '"/><line x1="' + (x - 4) + '" y1="' + (+y + 4) + '" x2="' + (+x + 4) + '" y2="' + (y - 4) + '"/></g>';
      });
    }
    ZONES.forEach(function (z) {
      var l = lines[z[0]]; if (!l) return;
      var a = z[6] * Math.PI / 180, r = z[7];
      var fx = Math.max(L + 3, Math.min(R - 3, r * Math.sin(a))), fy = Math.max(T + 1.6, Math.min(B - 2.2, r * Math.cos(a)));
      var st = 'font-family:\'Bebas Neue\',sans-serif;paint-order:stroke;stroke:#000;stroke-width:3px;fill:#fff;text-anchor:middle';
      s += '<text x="' + px(fx) + '" y="' + py(fy) + '" style="' + st + ';font-size:22px">' + l[0] + '/' + l[1] + '</text>';
      s += '<text x="' + px(fx) + '" y="' + (+py(fy) + 19) + '" style="' + st + ';font-size:17px;fill:#eee">' + Math.round(100 * l[0] / l[1]) + '%</text>';
    });
    return s + '</svg>';
  }

  var MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  var DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  function dayLabel(day) { var p = day.split('-'), d = new Date(+p[0], +p[1] - 1, +p[2]); return DAYS[d.getDay()] + ' ' + MONTHS[d.getMonth()] + ' ' + d.getDate(); }
  function shortDay(day) { var p = day.split('-'); return MONTHS[+p[1] - 1] + ' ' + (+p[2]); }
  function timeLabel(ms) { var d = new Date(ms), h = d.getHours(), m = d.getMinutes(); return ((h + 11) % 12 + 1) + ':' + pad(m) + (h < 12 ? ' AM' : ' PM'); }

  var pick = null; // 'all' | 'day:YYYY-MM-DD' | 's:<id>'

  // Camera sessions saved on this phone before charts were kept (or never added to a player).
  function importable() {
    try {
      if (!window.SwishVisionNative || !window.SwishVisionNative.savedChartSessions) return [];
      var done = JSON.parse(localStorage.getItem('sv_charts_imported') || '[]');
      var all = loadAll(), have = {};
      Object.keys(all).forEach(function (k) { (all[k] || []).forEach(function (r) { have[r.id] = 1; }); });
      return JSON.parse(window.SwishVisionNative.savedChartSessions()).filter(function (d) {
        return d.startedAt && !have[d.startedAt] && done.indexOf(d.startedAt) < 0;
      });
    } catch (e) { return []; }
  }

  function importOffer(list) {
    var name = '';
    try { name = (state && state.player && state.player.name) || ''; } catch (e) {}
    return '<div style="margin:0 0 12px;padding:10px;border:1px dashed #444;border-radius:8px;font-family:Outfit,sans-serif;font-size:13px;color:#aaa">' +
      list.length + ' earlier camera session' + (list.length > 1 ? 's' : '') + ' on this phone ha' + (list.length > 1 ? 've' : 's') + ' shot positions. ' +
      'Were they ' + (name ? name.replace(/[<>&"]/g, '') + '\'s' : 'this player\'s') + '?' +
      '<div style="display:flex;gap:8px;margin-top:8px"><button type="button" id="sv-import-yes" style="flex:1;padding:8px;border-radius:6px;border:1px solid var(--teal);background:rgba(0,180,197,0.18);color:#fff;font-family:\'Bebas Neue\',sans-serif;font-size:16px;letter-spacing:1px">YES, ADD THEM</button>' +
      '<button type="button" id="sv-import-no" style="flex:1;padding:8px;border-radius:6px;border:1px solid #333;background:#151522;color:#999;font-family:\'Bebas Neue\',sans-serif;font-size:16px;letter-spacing:1px">NO</button></div></div>';
  }

  function wireImport(card, list) {
    var mark = function () {
      try {
        var done = JSON.parse(localStorage.getItem('sv_charts_imported') || '[]');
        list.forEach(function (d) { done.push(d.startedAt); });
        localStorage.setItem('sv_charts_imported', JSON.stringify(done.slice(-500)));
      } catch (e) {}
    };
    var y = card.querySelector('#sv-import-yes'), n = card.querySelector('#sv-import-no');
    if (y) y.onclick = function () { list.forEach(function (d) { window.__svRecordChart(d); }); mark(); refresh(); };
    if (n) n.onclick = function () { mark(); refresh(); };
  }

  function shotChartCard() {
    var card = shotChartInner();
    var list = importable();
    if (list.length) {
      var box = document.createElement('div');
      box.innerHTML = importOffer(list);
      card.insertBefore(box.firstChild, card.children[1] || null);
      wireImport(card, list);
    }
    return card;
  }

  function shotChartInner() {
    var recs = records();
    var card = document.createElement('div');
    card.className = 'chart-container'; card.id = 'sv-shotchart';
    var html = '<div class="chart-title">SHOT CHART</div>';
    if (!recs.length) {
      card.innerHTML = html + '<div style="color:#888;font-family:Outfit,sans-serif;font-size:14px;line-height:1.5;padding:4px 2px 8px">' +
        'Track a session with the camera, turn on \uD83D\uDCCD and set the free-throw spot. Each shot then shows up here by where it was taken.</div>';
      return card;
    }
    var days = [];
    recs.forEach(function (r) { if (days.indexOf(r.day) < 0) days.push(r.day); });
    days.reverse();
    if (!pick || (pick.indexOf('day:') === 0 && days.indexOf(pick.slice(4)) < 0) || (pick.indexOf('s:') === 0 && !recs.some(function (r) { return 's:' + r.id === pick; }))) pick = 'day:' + days[0];
    var opts = '<option value="all"' + (pick === 'all' ? ' selected' : '') + '>All time</option><optgroup label="By day">';
    days.forEach(function (d) { opts += '<option value="day:' + d + '"' + (pick === 'day:' + d ? ' selected' : '') + '>' + dayLabel(d) + '</option>'; });
    opts += '</optgroup><optgroup label="By session">';
    recs.slice().reverse().forEach(function (r) {
      opts += '<option value="s:' + r.id + '"' + (pick === 's:' + r.id ? ' selected' : '') + '>' + shortDay(r.day) + ' \u00B7 ' + timeLabel(r.id) + ' \u00B7 ' + r.made + '/' + r.att + '</option>';
    });
    opts += '</optgroup>';
    var chosen = pick === 'all' ? recs : pick.indexOf('day:') === 0 ? recs.filter(function (r) { return r.day === pick.slice(4); })
      : recs.filter(function (r) { return 's:' + r.id === pick; });
    var dots = dotsOf(chosen), ftM = 0, ftA = 0;
    chosen.forEach(function (r) { if (r.ft) { ftM += r.ft[0]; ftA += r.ft[1]; } });
    var made = dots.filter(function (p) { return p.m; }).length;
    html += '<select id="sv-chart-pick" style="width:100%;margin:0 0 12px;padding:10px;border-radius:8px;background:#151522;color:#fff;border:1px solid var(--teal);font-family:Outfit,sans-serif;font-size:15px">' + opts + '</select>';
    html += dots.length ? chartSvg(dots, pick !== 'all') : '<div style="color:#888;text-align:center;padding:16px;font-family:Outfit,sans-serif">No placed shots here (the free-throw spot wasn\'t set).</div>';
    html += '<div style="display:flex;justify-content:space-between;margin-top:10px;font-family:\'Bebas Neue\',sans-serif;font-size:18px;letter-spacing:1px;color:#ccc">' +
      '<span>ON CHART <span style="color:var(--teal)">' + made + '/' + dots.length + (dots.length ? ' (' + Math.round(100 * made / dots.length) + '%)' : '') + '</span></span>' +
      (ftA ? '<span>FREE THROWS <span style="color:var(--teal)">' + ftM + '/' + ftA + ' (' + Math.round(100 * ftM / ftA) + '%)</span></span>' : '') + '</div>';
    html += '<div style="color:#777;font-size:12px;font-family:Outfit,sans-serif;margin-top:6px">Green = hot, yellow = OK, red = cold. Hoop at the top; left/right as the shooter faces the hoop.' +
      (pick === 'all' ? '' : ' Dots: \u25CF make, \u2715 miss.') + '</div>';
    card.innerHTML = html;
    card.querySelector('#sv-chart-pick').onchange = function () { pick = this.value; refresh(); };
    return card;
  }

  // ---------- progress over time ----------
  var TYPE_LABEL = { all: 'ALL', layups: 'LAYUPS', midrange: 'MID', ft: 'FT', three: '3PT' };
  var ptype = 'all', prange = 'all';

  function progressCard() {
    var card = document.createElement('div');
    card.className = 'chart-container'; card.id = 'sv-progress';
    var sessions = [];
    try { sessions = (state && state.sessions) || []; } catch (e) {}
    var byDay = {};
    sessions.forEach(function (s) {
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
      return '<button type="button" ' + attr + '="' + key + '" style="flex:1;padding:8px 2px;border-radius:6px;border:1px solid ' + (on ? 'var(--teal)' : '#333') +
        ';background:' + (on ? 'rgba(0,180,197,0.18)' : '#151522') + ';color:' + (on ? '#fff' : '#999') + ';font-family:\'Bebas Neue\',sans-serif;font-size:16px;letter-spacing:1px">' + label + '</button>';
    };
    var html = '<div class="chart-title">PROGRESS</div><div style="display:flex;gap:6px;margin-bottom:8px">';
    Object.keys(TYPE_LABEL).forEach(function (k) { html += btn(k, TYPE_LABEL[k], ptype === k, 'data-svt'); });
    html += '</div><div style="display:flex;gap:6px;margin-bottom:12px">' + btn('all', 'ALL TIME', prange === 'all', 'data-svr') + btn('30', 'LAST 30 DAYS', prange === '30', 'data-svr') + '</div>';
    if (days.length < 2) {
      html += '<div style="color:#888;text-align:center;padding:16px;font-family:Outfit,sans-serif">Shoot on a couple of different days to see the progress line.</div>';
    } else {
      html += progressSvg(days, byDay);
      // First days vs latest days (up to 5 each), weighted by shots.
      var k = Math.min(5, Math.floor(days.length / 2));
      var sum = function (list) { var m = 0, a = 0; list.forEach(function (d) { m += byDay[d][0]; a += byDay[d][1]; }); return a ? Math.round(100 * m / a) : 0; };
      var first = sum(days.slice(0, k)), last = sum(days.slice(-k)), diff = last - first;
      var col = diff > 0 ? '#4ade80' : diff < 0 ? '#f87171' : '#ccc';
      html += '<div style="display:flex;justify-content:space-between;align-items:center;margin-top:10px;font-family:\'Bebas Neue\',sans-serif;font-size:18px;letter-spacing:1px;color:#ccc">' +
        '<span>FIRST ' + k + ' DAYS ' + first + '%</span><span style="color:' + col + ';font-size:20px;white-space:nowrap;padding:0 6px">' + (diff > 0 ? '\u25B2 +' : diff < 0 ? '\u25BC ' : '') + diff + '</span><span>LAST ' + k + ' DAYS ' + last + '%</span></div>';
      html += '<div style="color:#777;font-size:12px;font-family:Outfit,sans-serif;margin-top:6px">Dots: shooting % each day. Line: the trend (last 5 days). Bars: shots taken.</div>';
    }
    card.innerHTML = html;
    Array.prototype.forEach.call(card.querySelectorAll('[data-svt]'), function (b) { b.onclick = function () { ptype = b.getAttribute('data-svt'); refresh(); }; });
    Array.prototype.forEach.call(card.querySelectorAll('[data-svr]'), function (b) { b.onclick = function () { prange = b.getAttribute('data-svr'); refresh(); }; });
    return card;
  }

  function progressSvg(days, byDay) {
    var W = 340, H = 210, l = 30, r = 8, t = 10, gh = 130, bh = 40, n = days.length;
    var x = function (i) { return l + (n === 1 ? (W - l - r) / 2 : i * (W - l - r) / (n - 1)); };
    var y = function (pct) { return t + gh - pct / 100 * gh; };
    var s = '<svg viewBox="0 0 ' + W + ' ' + H + '" style="width:100%;display:block">';
    [0, 25, 50, 75, 100].forEach(function (g) {
      s += '<line x1="' + l + '" x2="' + (W - r) + '" y1="' + y(g) + '" y2="' + y(g) + '" stroke="#2a2a3e" stroke-width="1"/>';
      s += '<text x="' + (l - 4) + '" y="' + (y(g) + 4) + '" fill="#777" font-size="10" text-anchor="end" font-family="Outfit,sans-serif">' + g + '%</text>';
    });
    var maxA = 1; days.forEach(function (d) { maxA = Math.max(maxA, byDay[d][1]); });
    var bw = Math.max(2, Math.min(14, (W - l - r) / n * 0.6));
    days.forEach(function (d, i) {
      var h = byDay[d][1] / maxA * bh;
      s += '<rect x="' + (x(i) - bw / 2) + '" y="' + (t + gh + 14 + bh - h) + '" width="' + bw + '" height="' + h + '" fill="rgba(249,98,159,0.55)" rx="1"/>';
    });
    // trend: shots-weighted average of the last 5 days
    var trend = days.map(function (d, i) {
      var m = 0, a = 0;
      for (var j = Math.max(0, i - 4); j <= i; j++) { m += byDay[days[j]][0]; a += byDay[days[j]][1]; }
      return 100 * m / a;
    });
    s += '<polyline fill="none" stroke="var(--gold, #ffe66d)" stroke-width="2.5" points="' + trend.map(function (p, i) { return x(i).toFixed(1) + ',' + y(p).toFixed(1); }).join(' ') + '"/>';
    days.forEach(function (d, i) {
      var pct = 100 * byDay[d][0] / byDay[d][1];
      s += '<circle cx="' + x(i).toFixed(1) + '" cy="' + y(pct).toFixed(1) + '" r="3.5" fill="#00b4c5"/>';
    });
    var lab = function (i, anchor) { return '<text x="' + x(i) + '" y="' + (H - 2) + '" fill="#888" font-size="10" text-anchor="' + anchor + '" font-family="Outfit,sans-serif">' + shortDay(days[i]) + '</text>'; };
    s += lab(0, 'start');
    if (n > 2) s += lab(Math.floor((n - 1) / 2), 'middle');
    s += lab(n - 1, 'end');
    return s + '</svg>';
  }

  // ---------- put the cards into Stats ----------
  function refresh() {
    var c = document.getElementById('stats-content');
    if (!c) return;
    var old1 = document.getElementById('sv-progress'), old2 = document.getElementById('sv-shotchart');
    if (old1) old1.remove();
    if (old2) old2.remove();
    if (!c.children.length || c.querySelector('.empty-state')) return; // Swish Quest's "no sessions yet" screen
    var cards = c.querySelectorAll(':scope > .chart-container');
    var after = cards.length >= 3 ? cards[2] : cards[cards.length - 1];
    var p = progressCard(), sc = shotChartCard();
    if (after && after.parentNode === c) { c.insertBefore(sc, after.nextSibling); c.insertBefore(p, after.nextSibling); }
    else { c.appendChild(p); c.appendChild(sc); }
  }
  window.__svStatsRefresh = refresh;

  function watch() {
    var c = document.getElementById('stats-content');
    if (!c) return false;
    new MutationObserver(function () {
      if (c.children.length && !document.getElementById('sv-progress') && !c.querySelector('.empty-state')) refresh();
    }).observe(c, { childList: true });
    refresh();
    return true;
  }
  if (!watch()) { var tries = 0, iv = setInterval(function () { if (watch() || ++tries > 30) clearInterval(iv); }, 1000); }
})();
