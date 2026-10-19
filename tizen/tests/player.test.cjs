const assert = require('node:assert/strict');
const Player = require('../js/player.js').NikTvPlayer;
function fixture() {
  let state = 'NONE';
  let success;
  let failure;
  let plays = 0;
  const events = [];
  const api = {
    getState: () => state,
    close: () => { state = 'NONE'; },
    open: () => { state = 'IDLE'; },
    setDisplayRect() {}, setDisplayMethod() {}, setListener() {},
    prepareAsync: (ok, fail) => { success = ok; failure = fail; },
    play: () => { plays++; state = 'PLAYING'; },
    pause: () => { state = 'PAUSED'; }
  };
  return { player: new Player(api, (s, m) => events.push([s, m])), api, events,
    ready: () => success(), fail: () => failure(), state: () => state, plays: () => plays };
}
let f = fixture();
f.player.open('https://example.com/live.m3u8');
f.ready();
assert.equal(f.state(), 'PLAYING');
f.player.toggle();
assert.equal(f.state(), 'PAUSED');
f.player.toggle();
assert.equal(f.state(), 'PLAYING');
f.player.stop();
assert.equal(f.state(), 'NONE');

// A late native callback must not restart playback after Return/backgrounding.
f = fixture();
f.player.open('https://example.com/live.m3u8');
f.player.stop();
f.ready();
assert.equal(f.plays(), 0);
f.fail();
assert.equal(f.events.length, 1);

// Failed preparation releases the native player and reports a recoverable error.
f = fixture();
f.player.open('https://example.com/live.m3u8');
f.fail();
assert.equal(f.state(), 'NONE');
assert.equal(f.events[f.events.length - 1][0], 'error');
assert.throws(() => f.player.open('file:///private'), /HTTP/);
assert.throws(() => new Player(null, () => {}).open('https://example.com/live'), /unavailable/);
console.log('Player lifecycle checks passed');
