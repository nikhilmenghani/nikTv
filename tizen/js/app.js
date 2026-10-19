(function () {
  'use strict';
  var setup = document.getElementById('setup');
  var controls = document.getElementById('controls');
  var input = document.getElementById('stream-url');
  var status = document.getElementById('status');
  var active = false;
  var lastLaunchButton = document.getElementById('sample');
  function home(message) {
    active = false;
    setup.hidden = false;
    controls.hidden = true;
    status.textContent = message || 'Ready';
    lastLaunchButton.focus();
  }
  var player = new window.NikTvPlayer(window.webapis && window.webapis.avplay, function (state, message) {
    status.textContent = message;
    if (state === 'error' || state === 'ended') home(message);
  });
  function startPlayback(url, button) {
    lastLaunchButton = button;
    active = true;
    setup.hidden = true;
    controls.hidden = false;
    document.getElementById('pause').focus();
    try { player.open(url); }
    catch (error) { home(error.message); }
  }
  document.getElementById('sample').onclick = function () {
    startPlayback('https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8', this);
  };
  document.getElementById('play').onclick = function () {
    startPlayback(input.value.replace(/^\s+|\s+$/g, ''), this);
  };
  document.getElementById('pause').onclick = function () {
    try { player.toggle(); } catch (error) { player.stop(); home('Playback interrupted. Please reopen the stream.'); }
  };
  document.getElementById('stop').onclick = function () { player.stop(); home(); };
  document.addEventListener('keydown', function (event) {
    var code = event.keyCode;
    if (code === 10009 || code === 27) {
      event.preventDefault();
      if (active) { player.stop(); home(); }
      else if (window.tizen) window.tizen.application.getCurrentApplication().exit();
      return;
    }
    if (code === 10252 || code === 19 || code === 415) {
      event.preventDefault();
      document.getElementById('pause').click();
      return;
    }
    if (code === 413) { document.getElementById('stop').click(); return; }
    // Preserve left/right text editing and the TV's native input keyboard.
    if (document.activeElement === input && (code === 37 || code === 39)) return;
    if (code >= 37 && code <= 40) {
      var items = active ? [document.getElementById('pause'), document.getElementById('stop')] : [document.getElementById('sample'), input, document.getElementById('play')];
      var index = items.indexOf(document.activeElement);
      var step = code === 37 || code === 38 ? -1 : 1;
      items[(index + step + items.length) % items.length].focus();
      event.preventDefault();
    }
  });
  if (window.tizen && window.tizen.tvinputdevice) {
    ['MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaStop'].forEach(function (key) {
      try { window.tizen.tvinputdevice.registerKey(key); } catch (error) { /* remote may lack a key */ }
    });
  }
  document.addEventListener('visibilitychange', function () {
    if (document.hidden) { player.stop(); home('Playback stopped while NikTV is in the background.'); }
  });
  window.addEventListener('unload', function () { player.stop(); });
  document.getElementById('sample').focus();
}());
