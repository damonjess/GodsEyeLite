/**
 * God's Eye Lite — global events layer.
 *
 * Kotlin owns the two keyless feeds (USGS earthquakes for the last 24 hours and
 * NASA EONET open wildfires, storms/floods and volcanoes). This layer draws
 * them as tinted billboards, filters them by kind, and opens a small card with
 * the time, magnitude/depth and a FLY TO action.
 *
 * The layer starts off: adding it must not cost a network request or hundreds
 * of billboards until the user asks for it.
 */
(() => {
  'use strict';

  const CFG = {
    refreshMs: 5 * 60 * 1000,
    minRequestGapMs: 30 * 1000,
    iconSize: 128,
    scaleByDistance: new Cesium.NearFarScalar(1e5, 1.15, 2e7, 0.55),
  };

  const KIND = {
    quake: { label: 'EARTHQUAKE', color: '#ffc24b' },
    fire: { label: 'WILDFIRE', color: '#ff7a3d' },
    storm: { label: 'STORM / FLOOD', color: '#61c8ff' },
    volcano: { label: 'VOLCANO', color: '#ff5b45' },
  };

  const state = {
    viewer: null,
    enabled: false,
    collection: null,
    labels: null,
    items: [],
    byId: new Map(),
    selectedId: null,
    filter: 'all',
    loaded: false,
    note: '',
    lastRequestMs: 0,
    refreshTimer: 0,
    selectionLabel: null,
    ui: {},
    iconUrls: new Map(),
  };

  function bridge() {
    return typeof window.GEVAndroid === 'object' ? window.GEVAndroid : null;
  }

  function log(message) {
    const api = bridge();
    if (api && typeof api.log === 'function') api.log('events: ' + message);
  }

  function kindInfo(kind) {
    return KIND[kind] || KIND.quake;
  }

  function colorFor(rec) {
    if (rec.kind !== 'quake') return Cesium.Color.fromCssColorString(kindInfo(rec.kind).color);
    if (rec.magnitude >= 6.5) return Cesium.Color.fromCssColorString('#ff4b4b');
    if (rec.magnitude >= 5.5) return Cesium.Color.fromCssColorString('#ff8a4c');
    if (rec.magnitude >= 4.5) return Cesium.Color.fromCssColorString('#ffc24b');
    return Cesium.Color.fromCssColorString('#ffe08a');
  }

  function matches(rec) {
    return state.filter === 'all' || rec.kind === state.filter;
  }

  function renderToggle() {
    const note = document.getElementById('eventsStatus');
    if (!note) return;
    const visible = state.items.filter((rec) => matches(rec)).length;
    note.textContent = !state.enabled
      ? 'OFF'
      : state.note
        ? state.note
        : state.items.length
          ? state.filter === 'all'
            ? String(visible)
            : `${visible}/${state.items.length}`
          : '';
  }

  function setNote(note) {
    if (state.note === note) return;
    state.note = note;
    renderToggle();
  }

  // ── Icons ──────────────────────────────────────────────────────────────────

  function iconFor(kind) {
    if (state.iconUrls.has(kind)) return state.iconUrls.get(kind);
    const size = CFG.iconSize;
    const canvas = document.createElement('canvas');
    canvas.width = size;
    canvas.height = size;
    const g = canvas.getContext('2d');
    g.translate(size / 2, size / 2);
    g.lineJoin = 'round';
    g.lineCap = 'round';
    g.shadowColor = 'rgba(0, 0, 0, 0.85)';
    g.shadowBlur = 7;
    g.strokeStyle = '#ffffff';
    g.fillStyle = '#ffffff';

    if (kind === 'fire') {
      g.beginPath();
      g.moveTo(0, 38);
      g.bezierCurveTo(-38, 15, -23, -12, -3, -38);
      g.bezierCurveTo(-2, -14, 10, -21, 12, -34);
      g.bezierCurveTo(35, -2, 29, 27, 0, 38);
      g.fill();
    } else if (kind === 'storm') {
      g.lineWidth = 6;
      for (let i = 0; i < 3; i++) {
        g.beginPath();
        g.arc(0, 0, 11 + i * 9, -0.35, 2.25);
        g.stroke();
      }
      g.beginPath();
      g.arc(0, 0, 5, 0, Math.PI * 2);
      g.fill();
    } else if (kind === 'volcano') {
      g.beginPath();
      g.moveTo(0, -34);
      g.lineTo(35, 32);
      g.lineTo(-35, 32);
      g.closePath();
      g.fill();
      g.fillStyle = 'rgba(8, 10, 15, 0.68)';
      g.beginPath();
      g.moveTo(0, -8);
      g.lineTo(12, 15);
      g.lineTo(-12, 15);
      g.closePath();
      g.fill();
    } else {
      // Earthquake: a seismic ring with a centre dot and four ticks.
      g.lineWidth = 6;
      g.beginPath();
      g.arc(0, 0, 32, 0, Math.PI * 2);
      g.stroke();
      g.beginPath();
      g.arc(0, 0, 8, 0, Math.PI * 2);
      g.fill();
      g.lineWidth = 4;
      for (const [x1, y1, x2, y2] of [
        [0, -45, 0, -29],
        [0, 29, 0, 45],
        [-45, 0, -29, 0],
        [29, 0, 45, 0],
      ]) {
        g.beginPath();
        g.moveTo(x1, y1);
        g.lineTo(x2, y2);
        g.stroke();
      }
    }

    const url = canvas.toDataURL('image/png');
    state.iconUrls.set(kind, url);
    return url;
  }

  function scaleFor(rec) {
    if (rec.kind !== 'quake') return 34 / CFG.iconSize;
    const magnitude = Math.max(0, Math.min(rec.magnitude || 0, 7));
    return (40 * (0.62 + (magnitude / 7) * 0.88)) / CFG.iconSize;
  }

  // ── Card UI ────────────────────────────────────────────────────────────────

  function injectUi() {
    const style = document.createElement('style');
    style.textContent = `
      #eventCard {
        position: absolute; left: 12px; right: 12px; bottom: 34px; max-width: 440px;
        display: none; z-index: 7; pointer-events: none;
        background: rgba(10, 12, 18, 0.94); border: 1px solid #35404f;
        border-left: 4px solid #ffc24b; border-radius: 10px;
        padding: 10px 12px; color: #e8e8f0; font-family: monospace; font-size: 11px;
      }
      #eventCard .ec-head { display: flex; align-items: flex-start; gap: 8px; }
      #eventCard .ec-kind { flex: 1; color: #ffc24b; font-size: 9px; letter-spacing: 1.2px; }
      #eventCard .ec-close {
        pointer-events: auto; background: none; border: 0; color: #9a9ab0;
        font-size: 22px; line-height: 1; padding: 0 2px;
      }
      #eventCard .ec-title { font-size: 13px; font-weight: 700; line-height: 1.3; margin-top: 3px; }
      #eventCard .ec-meta { color: #9a9ab0; font-size: 9px; margin-top: 4px; }
      #eventCard .ec-stats { display: flex; gap: 16px; margin-top: 9px; }
      #eventCard .ec-stats b { display: block; font-size: 13px; }
      #eventCard .ec-stats span { color: #9a9ab0; font-size: 8px; }
      #eventCard .ec-foot { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 9px; }
      #eventCard .ec-source { color: #8a8aa0; font-size: 8px; }
      #eventCard .ec-go {
        pointer-events: auto; background: rgba(17, 24, 32, 0.94); border: 1px solid #ffc24b;
        color: #ffc24b; border-radius: 5px; padding: 5px 10px;
        font: 700 10px monospace; letter-spacing: 1px;
      }
    `;
    document.head.appendChild(style);

    const card = document.createElement('div');
    card.id = 'eventCard';
    card.innerHTML = `
      <div class="ec-head">
        <div class="ec-kind" id="evKind"></div>
        <button class="ec-close" id="evClose" aria-label="Close">&times;</button>
      </div>
      <div class="ec-title" id="evTitle"></div>
      <div class="ec-meta" id="evMeta"></div>
      <div class="ec-stats">
        <div><b id="evMag"></b><span>MAGNITUDE</span></div>
        <div><b id="evDepth"></b><span>DEPTH</span></div>
        <div><b id="evAge"></b><span>AGE</span></div>
      </div>
      <div class="ec-foot">
        <span class="ec-source" id="evSource"></span>
        <button class="ec-go" id="evGo" type="button">FLY TO</button>
      </div>`;
    document.body.appendChild(card);
    state.ui.card = card;
    document.getElementById('evClose').addEventListener('click', clearSelection);
    document.getElementById('evGo').addEventListener('click', flyToSelected);
  }

  function wireUi() {
    const row = document.getElementById('layerEvents');
    if (row) row.addEventListener('click', () => setEnabled(!state.enabled));
    const group = document.getElementById('eventFilter');
    if (group) {
      for (const button of group.querySelectorAll('button')) {
        button.addEventListener('click', () => setFilter(button.dataset.event));
      }
    }
    renderFilterUi();
    renderToggle();
  }

  function renderFilterUi() {
    for (const button of document.querySelectorAll('#eventFilter button')) {
      const active = button.dataset.event === state.filter;
      button.classList.toggle('on', active);
      button.setAttribute('aria-pressed', String(active));
    }
  }

  function setFilter(filter) {
    if (!['all', 'quake', 'fire', 'storm', 'volcano'].includes(filter)) return;
    state.filter = filter;
    for (const rec of state.items) rec.bb.show = state.enabled && matches(rec);
    if (state.selectedId) {
      const selected = state.byId.get(state.selectedId);
      if (!selected || !matches(selected)) clearSelection();
    }
    renderFilterUi();
    renderToggle();
  }

  function ageText(timeMs) {
    if (!timeMs) return '—';
    const diff = Math.max(0, Date.now() - timeMs);
    if (diff < 60_000) return 'now';
    if (diff < 3_600_000) return Math.round(diff / 60_000) + 'm';
    if (diff < 86_400_000) return Math.round(diff / 3_600_000) + 'h';
    return Math.round(diff / 86_400_000) + 'd';
  }

  function updateCard() {
    const rec = state.selectedId ? state.byId.get(state.selectedId) : null;
    if (!rec || !state.ui.card) return;
    const $ = (id) => document.getElementById(id);
    const set = (id, text) => {
      const el = $(id);
      if (el && el.textContent !== text) el.textContent = text;
    };
    const info = kindInfo(rec.kind);
    set('evKind', info.label);
    set('evTitle', rec.title);
    set(
      'evMeta',
      `${rec.lat.toFixed(2)}°, ${rec.lon.toFixed(2)}° · ${rec.source}`,
    );
    set(
      'evMag',
      rec.kind === 'quake' && Number.isFinite(rec.magnitude)
        ? 'M' + rec.magnitude.toFixed(1)
        : '—',
    );
    set('evDepth', rec.kind === 'quake' ? Math.round(rec.depthKm) + ' km' : '—');
    set('evAge', ageText(rec.timeMs));
    set('evSource', rec.source);
    state.ui.card.style.borderLeftColor = colorFor(rec).toCssColorString();
    state.ui.card.querySelector('.ec-kind').style.color = colorFor(rec).toCssColorString();
  }

  function select(id) {
    const rec = state.byId.get(id);
    if (!rec || !state.enabled) return;
    window.dispatchEvent(new CustomEvent('gev:dismissCards', { detail: 'event' }));
    clearSelection();
    state.selectedId = id;
    rec.bb.scale = rec.baseScale * 1.25;
    state.selectionLabel = state.labels.add({
      text: rec.title,
      font: '600 10px monospace',
      fillColor: Cesium.Color.WHITE,
      outlineColor: Cesium.Color.BLACK.withAlpha(0.9),
      outlineWidth: 3,
      style: Cesium.LabelStyle.FILL_AND_OUTLINE,
      verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
      pixelOffset: new Cesium.Cartesian2(0, -26),
    });
    state.selectionLabel.position = Cesium.Cartesian3.fromDegrees(rec.lon, rec.lat, 2500);
    state.ui.card.style.display = 'block';
    updateCard();
  }

  function clearSelection() {
    const rec = state.selectedId ? state.byId.get(state.selectedId) : null;
    if (rec) rec.bb.scale = rec.baseScale;
    if (state.selectionLabel && state.labels) {
      state.labels.remove(state.selectionLabel);
      state.selectionLabel = null;
    }
    state.selectedId = null;
    if (state.ui.card) state.ui.card.style.display = 'none';
  }

  function flyToSelected() {
    const rec = state.selectedId ? state.byId.get(state.selectedId) : null;
    if (!rec || !state.viewer) return;
    const api = window.GEVLite;
    if (api && typeof api.isChasing === 'function' && api.isChasing()) {
      if (typeof api.stopChase === 'function') api.stopChase();
    }
    const height = rec.kind === 'quake' ? 350_000 : 650_000;
    state.viewer.camera.flyTo({
      destination: Cesium.Cartesian3.fromDegrees(rec.lon, rec.lat, height),
      orientation: { heading: 0, pitch: -Cesium.Math.PI_OVER_TWO, roll: 0 },
      duration: 1.4,
    });
  }

  // ── Data ───────────────────────────────────────────────────────────────────

  function clearMarkers() {
    clearSelection();
    if (state.collection) state.collection.removeAll();
    if (state.labels) state.labels.removeAll();
    state.items = [];
    state.byId.clear();
    state.selectionLabel = null;
  }

  function setEvents(list) {
    if (!state.viewer || !state.collection || !Array.isArray(list)) return;
    clearMarkers();
    const now = Date.now();
    for (const entry of list) {
      if (!entry || typeof entry.i !== 'string' || !KIND[entry.k]) continue;
      const lat = Number(entry.la);
      const lon = Number(entry.lo);
      if (!Number.isFinite(lat) || !Number.isFinite(lon) || state.byId.has(entry.i)) continue;
      const rec = {
        id: entry.i,
        kind: entry.k,
        title: String(entry.t || kindInfo(entry.k).label),
        lat,
        lon,
        timeMs: Number(entry.ts) || 0,
        magnitude: Number(entry.m) || 0,
        depthKm: Number(entry.d) || 0,
        source: String(entry.s || ''),
      };
      rec.baseScale = scaleFor(rec);
      rec.bb = state.collection.add({
        // Keep this at an explicit altitude. CLAMP_TO_GROUND made Cesium's
        // billboard update path touch globe terrain here, which crashed the
        // render loop on the test phone when the layer was first populated.
        position: Cesium.Cartesian3.fromDegrees(rec.lon, rec.lat, 1500),
        image: iconFor(rec.kind),
        color: colorFor(rec),
        scale: rec.baseScale,
        verticalOrigin: Cesium.VerticalOrigin.CENTER,
        horizontalOrigin: Cesium.HorizontalOrigin.CENTER,
        scaleByDistance: CFG.scaleByDistance,
        id: 'event:' + rec.id,
      });
      rec.bb.show = state.enabled && matches(rec);
      state.items.push(rec);
      state.byId.set(rec.id, rec);
    }
    state.loaded = true;
    setNote('');
    renderFilterUi();
    renderToggle();
    log(`loaded ${state.items.length} events (${now})`);
  }

  function eventsFailed() {
    setNote('OFFLINE');
  }

  function request() {
    if (!state.enabled || document.hidden) return;
    const api = bridge();
    if (!api || typeof api.requestEvents !== 'function') {
      setNote('OFFLINE');
      return;
    }
    const now = Date.now();
    if (now - state.lastRequestMs < CFG.minRequestGapMs) return;
    state.lastRequestMs = now;
    setNote('LOADING');
    api.requestEvents();
  }

  function setEnabled(on) {
    const wanted = !!on;
    if (state.enabled === wanted) {
      if (wanted) request();
      return;
    }
    state.enabled = wanted;
    const row = document.getElementById('layerEvents');
    if (row) row.setAttribute('aria-pressed', String(wanted));
    const filters = document.getElementById('eventsFilters');
    if (filters) filters.hidden = !wanted;

    if (!wanted) {
      for (const rec of state.items) rec.bb.show = false;
      clearSelection();
      setNote('');
    } else {
      for (const rec of state.items) rec.bb.show = matches(rec);
      if (!state.loaded || Date.now() - state.lastRequestMs > CFG.refreshMs) request();
    }
    renderFilterUi();
    renderToggle();
  }

  // ── Start ──────────────────────────────────────────────────────────────────

  function start(viewer) {
    state.viewer = viewer;
    const row = document.getElementById('layerEvents');
    state.enabled = !!row && row.getAttribute('aria-pressed') === 'true';
    state.collection = viewer.scene.primitives.add(new Cesium.BillboardCollection());
    state.labels = viewer.scene.primitives.add(new Cesium.LabelCollection());
    window.addEventListener('gev:dismissCards', (event) => {
      if (event.detail !== 'event') clearSelection();
    });
    injectUi();
    wireUi();

    const filters = document.getElementById('eventsFilters');
    if (filters) filters.hidden = !state.enabled;

    const handler = new Cesium.ScreenSpaceEventHandler(viewer.scene.canvas);
    handler.setInputAction((movement) => {
      const picked = viewer.scene.pick(movement.position);
      if (!Cesium.defined(picked)) return;
      const id =
        typeof picked.id === 'string'
          ? picked.id
          : picked.primitive && typeof picked.primitive.id === 'string'
            ? picked.primitive.id
            : null;
      if (id && id.startsWith('event:')) select(id.slice(6));
    }, Cesium.ScreenSpaceEventType.LEFT_CLICK);

    state.refreshTimer = setInterval(() => {
      if (
        state.enabled &&
        !document.hidden &&
        Date.now() - state.lastRequestMs > CFG.refreshMs
      ) {
        request();
      }
    }, 60_000);

    document.addEventListener('visibilitychange', () => {
      if (!document.hidden && state.enabled) request();
    });

    log('layer ready');
    if (state.enabled) request();
  }

  function whenViewerReady(callback) {
    let tries = 0;
    const tick = () => {
      const api = window.GEVLite;
      const viewer = api && typeof api.getViewer === 'function' ? api.getViewer() : null;
      if (viewer && viewer.scene) {
        try {
          callback(viewer);
        } catch (error) {
          log('start failed: ' + (error && error.message));
        }
        return;
      }
      if (++tries > 150) {
        log('gave up waiting for the globe');
        return;
      }
      setTimeout(tick, 400);
    };
    tick();
  }

  window.GEVLite = window.GEVLite || {};
  window.GEVLite.setEvents = setEvents;
  window.GEVLite.eventsFailed = eventsFailed;
  window.GEVLite.setEventsEnabled = setEnabled;
  window.GEVLite.selectEvent = (id) => {
    if (!state.enabled) setEnabled(true);
    select(id);
  };
  window.GEVLite.eventStats = () => ({
    enabled: state.enabled,
    loaded: state.loaded,
    total: state.items.length,
    visible: state.items.filter(matches).length,
    filter: state.filter,
    selected: state.selectedId,
    note: state.note,
    counts: state.items.reduce((out, rec) => {
      out[rec.kind] = (out[rec.kind] || 0) + 1;
      return out;
    }, {}),
    sample: state.items[0]
      ? { id: state.items[0].id, title: state.items[0].title, lat: state.items[0].lat, lon: state.items[0].lon }
      : null,
  });

  whenViewerReady(start);
})();
