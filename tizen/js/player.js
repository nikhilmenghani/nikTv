/* ES5 for the 2017 Samsung M47 engine. No Android playback dependencies. */
(function (root) {
  'use strict';
  function Player(api, notify) {
    this.api = api;
    this.notify = notify;
    this.generation = 0;
  }
  Player.prototype.stop = function () {
    this.generation += 1;
    try {
      if (this.api && this.api.getState() !== 'NONE') this.api.close();
    } catch (error) { /* close may fail when the TV has already released playback */ }
  };
  Player.prototype.open = function (url) {
    var self = this;
    if (!/^https?:\/\/[^\s]+$/i.test(url)) throw new Error('Enter an HTTP or HTTPS stream URL.');
    if (!this.api) throw new Error('Samsung AVPlay is unavailable. Run this app on the TV.');
    this.stop();
    var generation = this.generation;
    function current() { return generation === self.generation; }
    function fail() {
      if (!current()) return;
      self.stop();
      self.notify('error', 'Playback failed. Check the stream, network, and TV codec support.');
    }
    try {
      this.api.open(url);
      this.api.setDisplayRect(0, 0, 1920, 1080);
      this.api.setDisplayMethod('PLAYER_DISPLAY_MODE_LETTER_BOX');
      this.api.setListener({
        onbufferingstart: function () { if (current()) self.notify('loading', 'Buffering…'); },
        onbufferingcomplete: function () { if (current()) self.notify('playing', 'Playing'); },
        onstreamcompleted: function () {
          if (current()) { self.stop(); self.notify('ended', 'Playback finished'); }
        },
        onerror: fail
      });
      this.notify('loading', 'Opening stream…');
      this.api.prepareAsync(function () {
        if (!current()) return;
        try { self.api.play(); self.notify('playing', 'Playing'); } catch (error) { fail(); }
      }, fail);
    } catch (error) { fail(); }
  };
  Player.prototype.toggle = function () {
    if (!this.api) return;
    var state = this.api.getState();
    if (state === 'PLAYING') { this.api.pause(); this.notify('paused', 'Paused'); }
    else if (state === 'PAUSED') { this.api.play(); this.notify('playing', 'Playing'); }
  };
  root.NikTvPlayer = Player;
}(typeof window !== 'undefined' ? window : module.exports));
