// Injected by the Swish Quest Android app into swish-quest.web.app.
// Adds "Track with camera" buttons and saves camera sessions through
// Swish Quest's own Log Session form + saveSession(), so quests, badges,
// tiers, bosses, the leaderboard and friend alerts all update as usual.
(function () {
  if (window.__svInstalled || !window.SwishVisionNative) return;
  window.__svInstalled = true;

  var TYPES = ['layups', 'midrange', 'ft', 'three'];

  function cameraButton(id) {
    var b = document.createElement('button');
    b.id = id;
    b.type = 'button';
    b.className = 'save-btn';
    b.textContent = '📷 TRACK WITH CAMERA';
    b.style.background = '#101820';
    b.style.border = '2px solid #00b4c5';
    b.style.boxShadow = 'none';
    b.style.margin = '0 0 14px 0';
    b.onclick = function () { window.SwishVisionNative.startTracking('ft'); };
    return b;
  }

  // App-only layout fixes: keep all five bottom tabs on screen at any phone width.
  if (!document.getElementById('sv-app-css')) {
    var css = document.createElement('style');
    css.id = 'sv-app-css';
    css.textContent =
      '.bottom-nav{padding-left:2px!important;padding-right:2px!important}' +
      '.bottom-nav .nav-item{flex:1 1 0;min-width:0;padding:6px 2px!important}' +
      '.bottom-nav .nav-label{letter-spacing:0.5px!important;white-space:nowrap}' +
      '#sv-log-btn,#sv-dash-btn{white-space:nowrap;font-size:18px!important;letter-spacing:2px!important}' +
      // Screens with the bottom bar: always leave room for the bar's real height, so nothing hides behind it.
      '.dashboard-screen,.stats-screen,.badges-screen,.profile-screen,.board-screen' +
      '{padding-bottom:calc(var(--sv-nav-h, 90px) + 24px)!important}';
    document.head.appendChild(css);
  }

  function syncNavHeight() {
    var n = document.getElementById('bottom-nav');
    if (n && n.offsetHeight) document.documentElement.style.setProperty('--sv-nav-h', n.offsetHeight + 'px');
  }
  window.addEventListener('resize', syncNavHeight);

  function install() {
    syncNavHeight();
    var form = document.querySelector('#log-session .log-form');
    if (form && !document.getElementById('sv-log-btn')) {
      form.insertBefore(cameraButton('sv-log-btn'), form.firstChild);
    }
    var logBtn = document.querySelector('#dashboard .log-session-btn');
    if (logBtn && !document.getElementById('sv-dash-btn')) {
      var d = cameraButton('sv-dash-btn');
      d.style.margin = '10px 0 0 0';
      logBtn.parentNode.insertBefore(d, logBtn.nextSibling);
    }
  }

  // Is Swish Quest signed in, loaded and on a normal screen? (Not mid-startup or mid-sync.)
  function questReady() {
    try {
      if (typeof saveSession !== 'function' || typeof state === 'undefined') return false;
      if (typeof _firebaseReady !== 'undefined' && !_firebaseReady) return false;
      if (typeof _syncInProgress !== 'undefined' && _syncInProgress) return false;
      if (typeof _activeProfileId === 'undefined' || !_activeProfileId) return false;
      if (!state || !state.player || !state.goals) return false;
      var active = document.querySelector('.screen.active');
      var ok = ['dashboard', 'stats', 'board', 'badges', 'profile', 'log-session'];
      return !!active && ok.indexOf(active.id) >= 0;
    } catch (e) { return false; }
  }

  function appliedIds() {
    try { return JSON.parse(localStorage.getItem('sv_applied') || '[]'); } catch (e) { return []; }
  }

  // Called by the app with the tracker's session JSON (as a string).
  // Returns 'saved' | 'duplicate' | 'notready' | 'filled' | 'empty' | 'error: ...'
  // The app keeps the session and retries until it gets 'saved' or 'duplicate'.
  window.__svApply = function (str) {
    try {
      var d = JSON.parse(str);
      if (!d.shots) return 'empty';
      var id = String(d.startedAt || '');
      if (id && appliedIds().indexOf(id) >= 0) return 'duplicate'; // already in Swish Quest
      if (!questReady()) return 'notready';
      var lines = d.shotTypes || {};
      var before = state.sessions.length;
      showScreen('log-session'); // clears the form
      TYPES.forEach(function (t) {
        var l = lines[t];
        document.getElementById('log-' + t + '-att').value = l ? l.attempted : '';
        document.getElementById('log-' + t + '-made').value = l ? l.made : '';
      });
      document.getElementById('log-time').value = Math.max(1, d.minutes || 1);
      calcSession();
      var save = document.getElementById('save-session-btn');
      if (!save || save.disabled) return 'filled'; // left on the form so nothing is lost
      saveSession();
      if (state.sessions.length <= before) return 'error: save did not add a session';
      if (id) {
        var ids = appliedIds(); ids.push(id);
        localStorage.setItem('sv_applied', JSON.stringify(ids.slice(-100)));
      }
      return 'saved';
    } catch (e) {
      return 'error: ' + e.message;
    }
  };

  install();
  // Screens are static in v1.7.x, but re-check in case the page re-renders.
  setInterval(install, 2000);
})();
