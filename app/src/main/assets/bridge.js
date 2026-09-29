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

  function install() {
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

  // Called by the app with the tracker's session JSON (as a string).
  window.__svApply = function (str) {
    try {
      var d = JSON.parse(str);
      var lines = d.shotTypes || {};
      if (!d.shots) return 'empty';
      showScreen('log-session'); // clears the form
      TYPES.forEach(function (t) {
        var l = lines[t];
        document.getElementById('log-' + t + '-att').value = l ? l.attempted : '';
        document.getElementById('log-' + t + '-made').value = l ? l.made : '';
      });
      document.getElementById('log-time').value = Math.max(1, d.minutes || 1);
      calcSession();
      var save = document.getElementById('save-session-btn');
      if (save && !save.disabled) {
        saveSession();
        return 'saved';
      }
      return 'filled'; // left on the form so nothing is lost
    } catch (e) {
      return 'error: ' + e.message;
    }
  };

  install();
  // Screens are static in v1.7.x, but re-check in case the page re-renders.
  setInterval(install, 2000);
})();
