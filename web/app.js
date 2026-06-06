'use strict';

// ── Text preprocessing (mirrors Android TextPreprocessor.kt) ──────────────
function cleanText(raw) {
  return raw
    .replace(/-\n([a-z])/g, '$1')          // fix hyphenated line-breaks
    .replace(/^\s*\d{1,4}\s*$/gm, '')      // remove standalone page numbers
    .replace(/https?:\/\/\S+/g, '')        // remove URLs
    .replace(/[—–]/g, ', ')               // em/en dash → pause
    .replace(/…/g, '...')
    .replace(/ /g, ' ')              // non-breaking space
    .replace(/[ \t]+/g, ' ')
    .split('\n').map(l => l.trim()).join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

// ── PDF text extraction via PDF.js ────────────────────────────────────────
async function extractPdfText(file, startPage, endPage, onProgress) {
  const buf = await file.arrayBuffer();
  const pdf = await pdfjsLib.getDocument({ data: buf }).promise;
  const total = pdf.numPages;
  const from = Math.max(1, startPage || 1);
  const to   = Math.min(total, endPage || total);

  let text = '';
  for (let p = from; p <= to; p++) {
    const page = await pdf.getPage(p);
    const content = await page.getTextContent();
    let lastY = null;
    for (const item of content.items) {
      const y = item.transform ? item.transform[5] : null;
      if (lastY !== null && y !== null && Math.abs(y - lastY) > 5) text += '\n';
      text += item.str + ' ';
      lastY = y;
    }
    text += '\n';
    if (onProgress) onProgress(p - from + 1, to - from + 1);
  }
  return text;
}

// ── IndexedDB cache ───────────────────────────────────────────────────────
const DB_NAME = 'pdf-audio';
const STORE   = 'texts';

function openDb() {
  return new Promise((res, rej) => {
    const r = indexedDB.open(DB_NAME, 1);
    r.onupgradeneeded = e => e.target.result.createObjectStore(STORE);
    r.onsuccess = e => res(e.target.result);
    r.onerror   = () => rej(r.error);
  });
}

async function dbGet(db, key) {
  return new Promise((res, rej) => {
    const r = db.transaction(STORE).objectStore(STORE).get(key);
    r.onsuccess = () => res(r.result);
    r.onerror   = () => rej(r.error);
  });
}

async function dbSet(db, key, val) {
  return new Promise((res, rej) => {
    const r = db.transaction(STORE, 'readwrite').objectStore(STORE).put(val, key);
    r.onsuccess = () => res();
    r.onerror   = () => rej(r.error);
  });
}

// ── TTS Player ────────────────────────────────────────────────────────────
class Player {
  constructor() {
    this.synth    = window.speechSynthesis;
    this.chunks   = [];
    this.idx      = 0;
    this.rate     = 1.0;
    this.playing  = false;
    this.paused   = false;
    this._timer   = null;
    this.onProgress = null;
    this.onDone     = null;
  }

  load(text) {
    this.stop();
    this.chunks = this._split(text);
    this.idx    = 0;
  }

  play() {
    if (this.paused) {
      this.synth.resume();
      this.paused  = false;
      this.playing = true;
      this._keepAlive();
      return;
    }
    if (!this.chunks.length) return;
    this.playing = true;
    this.paused  = false;
    this._keepAlive();
    this._speak(this.idx);
  }

  pause() {
    this.synth.pause();
    this.paused  = true;
    this.playing = false;
    clearInterval(this._timer);
  }

  stop() {
    this.synth.cancel();
    this.playing = false;
    this.paused  = false;
    this.idx     = 0;
    clearInterval(this._timer);
  }

  setRate(r) {
    this.rate = r;
  }

  _speak(i) {
    if (i >= this.chunks.length) {
      this.playing = false;
      clearInterval(this._timer);
      this.onDone?.();
      return;
    }
    const u = new SpeechSynthesisUtterance(this.chunks[i]);
    u.rate = this.rate;
    u.lang = 'en-US';
    u.onend = () => {
      if (!this.playing) return;
      this.idx++;
      this.onProgress?.(this.idx, this.chunks.length);
      this._speak(this.idx);
    };
    u.onerror = e => {
      if (e.error === 'interrupted' || e.error === 'canceled') return;
      // skip bad chunk
      this.idx++;
      this._speak(this.idx);
    };
    this.synth.speak(u);
  }

  // Chrome Android stops synthesis after ~15s without interaction — workaround
  _keepAlive() {
    clearInterval(this._timer);
    this._timer = setInterval(() => {
      if (this.synth.speaking && !this.synth.paused) {
        this.synth.pause();
        this.synth.resume();
      }
    }, 14000);
  }

  // Split at sentence boundaries, keeping chunks under ~250 chars for smooth progress
  _split(text) {
    const sentences = text.match(/[^.!?\n]+[.!?\n]*/g) ?? [text];
    const chunks = [];
    let cur = '';
    for (const s of sentences) {
      if (cur.length + s.length > 250 && cur) {
        chunks.push(cur.trim());
        cur = s;
      } else {
        cur += s;
      }
    }
    if (cur.trim()) chunks.push(cur.trim());
    return chunks.filter(Boolean);
  }
}

// ── App ───────────────────────────────────────────────────────────────────
class App {
  constructor() {
    this.db          = null;
    this.player      = new Player();
    this.file        = null;
    this.cacheKey    = null;
    this.busy        = false;

    this.$ = id => document.getElementById(id);

    this._bindUI();
    this._initPlayer();
    openDb().then(db => { this.db = db; });
  }

  _bindUI() {
    const $ = this.$;

    // File pick / drag-drop
    $('fileInput').addEventListener('change', e => {
      if (e.target.files[0]) this._onFile(e.target.files[0]);
    });
    const dz = $('dropZone');
    dz.addEventListener('dragover', e => { e.preventDefault(); dz.classList.add('drag-over'); });
    dz.addEventListener('dragleave', () => dz.classList.remove('drag-over'));
    dz.addEventListener('drop', e => {
      e.preventDefault();
      dz.classList.remove('drag-over');
      const f = e.dataTransfer.files[0];
      if (f?.type === 'application/pdf') this._onFile(f);
    });

    $('btnPreprocess').addEventListener('click', () => this._preprocess());
    $('btnPlay').addEventListener('click',       () => this._togglePlay());
    $('btnStop').addEventListener('click',       () => this._stop());

    $('speedSlider').addEventListener('input', () => {
      const v = parseFloat($('speedSlider').value).toFixed(1);
      $('speedLabel').textContent = v + '×';
      this.player.setRate(parseFloat(v));
    });
  }

  _initPlayer() {
    this.player.onProgress = (cur, total) => {
      const pct = Math.round(cur / total * 100);
      this.$('progressBar').style.width = pct + '%';
      this.$('progressText').textContent = `chunk ${cur} / ${total}`;
      this.$('progressWrap').hidden = false;
    };
    this.player.onDone = () => {
      this._setStatus('finished.');
      this._sync();
    };
  }

  async _onFile(file) {
    this.file     = file;
    this.cacheKey = `${file.name}::${file.size}`;
    this.$('fileName').textContent = file.name;
    this.$('fileName').hidden = false;

    const cached = this.db && await dbGet(this.db, this.cacheKey);
    const cs = this.$('cacheStatus');
    if (cached) {
      const kb = Math.round(cached.length / 1024);
      cs.textContent = `✓ cached (${kb} KB) — play uses this`;
      cs.className   = 'cache-status cached';
    } else {
      cs.textContent = 'no cache — preprocess first for best results';
      cs.className   = 'cache-status uncached';
    }
    cs.hidden = false;
    this._sync();
  }

  async _preprocess() {
    if (!this.file || this.busy) return;
    this._setBusy(true);
    this._setStatus('extracting text…');

    try {
      const raw = await extractPdfText(this.file, 1, Infinity, (cur, total) => {
        this._showProgress(cur, total);
        this._setStatus(`page ${cur} / ${total}`);
      });
      this._setStatus('cleaning text…');
      const clean = cleanText(raw);
      await dbSet(this.db, this.cacheKey, clean);
      const kb = Math.round(clean.length / 1024);
      const cs = this.$('cacheStatus');
      cs.textContent = `✓ cached (${kb} KB) — play uses this`;
      cs.className   = 'cache-status cached';
      cs.hidden      = false;
      this._setStatus(`done — ${kb} KB saved.`);
    } catch (e) {
      this._setStatus('error: ' + e.message);
    } finally {
      this._setBusy(false);
      this._clearProgress();
    }
  }

  async _togglePlay() {
    if (this.player.playing) {
      this.player.pause();
      this.$('btnPlay').textContent = '▶ resume';
      return;
    }
    if (this.player.paused) {
      this.player.play();
      this.$('btnPlay').textContent = '⏸ pause';
      return;
    }

    if (!this.file || this.busy) return;
    this._setBusy(true);

    try {
      let text = this.db ? await dbGet(this.db, this.cacheKey) : null;

      if (!text) {
        const start = parseInt(this.$('startPage').value) || 1;
        const end   = parseInt(this.$('endPage').value)   || Infinity;
        this._setStatus('extracting PDF (consider preprocessing first)…');
        const raw = await extractPdfText(this.file, start, end, (c, t) => {
          this._showProgress(c, t);
          this._setStatus(`page ${c} / ${t}`);
        });
        text = cleanText(raw);
      }

      this.player.load(text);
      this.player.setRate(parseFloat(this.$('speedSlider').value));
      this._clearProgress();
      this.player.play();
      this._setStatus('playing…');
      this.$('btnPlay').textContent = '⏸ pause';
      this.$('btnStop').disabled = false;
    } catch (e) {
      this._setStatus('error: ' + e.message);
    } finally {
      this._setBusy(false);
    }
  }

  _stop() {
    this.player.stop();
    this._setStatus('stopped.');
    this._clearProgress();
    this._sync();
  }

  _sync() {
    const hasFile  = !!this.file;
    const active   = this.player.playing || this.player.paused;
    this.$('btnPreprocess').disabled = !hasFile || this.busy;
    this.$('btnPlay').disabled       = (!hasFile && !active) || this.busy;
    this.$('btnStop').disabled       = !active;
    if (!active) this.$('btnPlay').textContent = '▶ play';
  }

  _setBusy(v) {
    this.busy = v;
    this._sync();
  }

  _setStatus(msg) { this.$('status').textContent = msg; }

  _showProgress(cur, total) {
    const pct = Math.round(cur / total * 100);
    this.$('progressBar').style.width = pct + '%';
    this.$('progressText').textContent = `${cur} / ${total}`;
    this.$('progressWrap').hidden = false;
  }

  _clearProgress() {
    this.$('progressBar').style.width = '0%';
    this.$('progressText').textContent = '';
    this.$('progressWrap').hidden = true;
  }
}

document.addEventListener('DOMContentLoaded', () => {
  if (!window.speechSynthesis) {
    document.getElementById('status').textContent =
      'error: your browser does not support speech synthesis.';
    return;
  }
  new App();
});
