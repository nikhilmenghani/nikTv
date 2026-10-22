// Exercise the actual embedded script against a deterministic IFrame API adapter. No network.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync('app/src/main/java/com/nikhil/niktv/TrailerPlayerActivity.kt', 'utf8');
const html = source.slice(source.indexOf('internal fun trailerPlayerHtml')).match(/"""([\s\S]*?)"""/)[1];
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
let config, loaded = true, loads = 0, unloads = 0, plays = 0, pauses = 0, seek;
const player = {
  getOptions: () => loaded ? ['captions'] : [],
  loadModule: name => { assert.equal(name, 'captions'); loaded = true; loads++; },
  unloadModule: name => { assert.equal(name, 'captions'); loaded = false; unloads++; },
  playVideo: () => plays++, pauseVideo: () => pauses++, seekTo: value => { seek = value; }
};
const context = vm.createContext({
  YT: { Player: function (_, options) { config = options; return player; } },
  NikTvTrailer: { ready() {}, state() {}, error() {}, progress() {} }, setInterval() {}
});
vm.runInContext(script, context);
context.onYouTubeIframeAPIReady();
assert.equal(config.playerVars.controls, 0);
assert.equal(config.playerVars.cc_load_policy, 0);
config.events.onReady();
assert.equal(loaded, false, 'Initial captions must be disabled');
assert.equal(unloads, 1);
config.events.onApiChange();
assert.equal(unloads, 1, 'Unloaded module must not cause an unload loop');
loaded = true; // A delayed automatic-caption module appears after startup.
config.events.onApiChange();
assert.equal(loaded, false, 'Late captions must respect the off choice');
context.niktvCommand('captions', 1);
assert.equal(loaded, true);
config.events.onApiChange();
assert.equal(loads, 1, 'Module callback must not load captions repeatedly');
context.niktvCommand('captions', 0);
assert.equal(loaded, false);
context.niktvCommand('play', 0); context.niktvCommand('pause', 0); context.niktvCommand('seek', 42);
assert.equal(plays, 1); assert.equal(pauses, 1); assert.equal(seek, 42);
console.log('Trailer script checks passed: hidden embed controls, initial/late captions off, opt-in/out, no module loops, playback/seek preserved.');
