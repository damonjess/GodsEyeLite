/**
 * God's Eye Lite — current weather layer.
 *
 * Kotlin fetches one keyless Open-Meteo response for the camera-city centres.
 * This layer draws a compact temperature badge at each city, opens a card with
 * the condition, wind and coordinates, and can fly the camera to that place.
 *
 * The layer starts off: no weather request is made until the user asks for it.
 */
(() => {
  'use strict';

  const CFG = {
    refreshMs: 15 * 60 * 1000,
    minRequestGapMs: 30 * 1000,
    labelAltitudeM: 2500,
  };

  const state = {
    viewer: null,
    enabled: false,
    labels: null,
    items: [],
    byId: new Map(),
    selectedId: null,
    loaded: false,
    note: '',
    lastRequestMs: 0,
    timer: 0,
    ui: { card: null },
  };

  function bridge() {
    return typeof window.GEVAndroid === 'object' ? window.GEVAndroid : null;
  }

  function log(message) {
    const api = bridge();
    if (api && typeof api.log === 'function') api.log('weather: ' + message);
  }

  function colorFor(temp) {
    if (!Number.isFinite(temp)) return Cesium.Color.WHITE;
    if (temp <= 0) return Cesium.Color.fromCssColorString('#8be0ff');
    if (temp <= 10) return Cesium.Color.fromCssColorString('#7ab8ff');
    if (temp <= 20) return Cesium.Color.fromCssColorString('#35e0c8');
    if (temp <= 30) return Cesium.Color.fromCssColorString('#ffc24b');
    return Cesium.Color.fromCssColorString('#ff7a7a');
  }

  function temperatureText(value) {
    return Number.isFinite(value) ? Math.round(value) + '\u00b0' : '\u2014';
  }

  function renderToggle() {
    const note = document.getElementById('weatherStatus');
    if (!note) return;
    note.textContent = !state.enabled
      ? 'OFF'
      : state.note
        ? state.note
        : state.items.length
          ? String(state.items.length)
          : '';
  }

  function setNote(note) {
    if (state.note === note) return;
    state.note = note;
    renderToggle();
  }

  // ── Card UI ────────────────────────────────────────────────────────────────

  function injectUi() {
    const style = document.createElement('style');
    style.textContent = `
      #weatherCard {
        position: absolute; left: 12px; right: 12px; bottom: 34px; max-width: 440px;
        display: none; z-index: 8; pointer-events: none;
        background: rgba(10, 12, 18, 0.95); border: 1px solid #35404f;
        border-left: 4px solid #ffc24b; border-radius: 10px;
        padding: 10px 12px; color: #e8e8f0; font-family: monospace; font-size: 11px;
      }
      #weatherCard .wx-head { display: flex; align-items: flex-start; gap: 8px; }
      #weatherCard .wx-city { flex: 1; font-size: 14px; font-weight: 700; line-height: 1.25; }
      #weatherCard .wx-close {
        pointer-events: auto; background: none; border: 0; color: #9a9ab0;
        font-size: 22px; line-height: 1; padding: 0 2px;
      }
      #weatherCard .wx-cond { color: #ffc24b; font-size: 9px; letter-spacing: 1px; margin-top: 4px; }
      #weatherCard .wx-temp { font-size: 28px; font-weight: 700; line-height: 1; margin-top: 9px; }
      #weatherCard .wx-meta { color: #9a9ab0; font-size: 9px; margin-top: 4px; }
      #weatherCard .wx-stats { display: flex; gap: 18px; margin-top: 9px; }
      #weatherCard .wx-stats b { display: block; font-size: 13px; }
      #weatherCard .wx-stats span { color: #9a9ab0; font-size: 8px; }
      #weatherCard .wx-foot {
        display: flex; align-items: center; justify-content: space-between; gap: 10px;
        margin-top: 9px;
      }
      #weatherCard .wx-credit { color: #8a8aa0; font-size: 8px; }
      #weatherCard .wx-credit a { color: #c8c8d8; pointer-events: auto; }
      #weatherCard .wx-go {
        pointer-events: auto; background: rgba(17, 24, 32, 0.94); border: 1px solid #ffc24b;
        color: #ffc24b; border-radius: 5px; padding: 5px 10px;
        font: 700 10px monospace; letter-spacing: 1px;
      }
    `;
    document.head.appendChild(style);

    const card = document.createElement('div');
    card.id = 'weatherCard';
    card.innerHTML = `
      <div class="wx-head">
        <div class="wx-city" id="wxCity"></div>
        <button class="wx-close" id="wxClose" aria-label="Close">&times;</button>
      </div>
      <div class="wx-cond" id="wxCond"></div>
      <div class="wx-temp" id="wxTemp"></div>
      <div class="wx-meta" id="wxMeta"></div>
      <div class="wx-stats">
        <div><b id="wxWind"></b><span>WIND</span></div>
        <div><b id="wxCoords"></b><span>LOCATION</span></div>
      </div>
      <div class="wx-foot">
        <span class="wx-credit">
          <a id="wxCredit" data-weather-credit href="https://open-meteo.com/" target="_blank" rel="noopener">
            Weather data by Open-Meteo.com
          </a>
        </span>
        <button class="wx-go" id="wxGo" type="button">FLY TO</button>
      </div>`;
    document.body.appendChild(card);
    state.ui.card = card;
    document.getElementById('wxClose').addEventListener('click', clearSelection);
    document.getElementById('wxGo').addEventListener('click', flyToSelected);

    // The credits link is inside the non-interactive overlay. Route it through
    // the native bridge instead of letting the WebView navigate away from the app.
    document.addEventListener('click', (event) => {
      const link = event.target && event.target.closest
        ? event.target.closest('a[data-weather-credit]')
        : null;
      if (!link) return;
      event.preventDefault();
      const api = bridge();
      if (api && typeof api.openLink === 'function') api.openLink(link.href);
    });
  }

  function wireUi() {
    const row = document.getElementById('layerWeather');
    if (row) row.addEventListener('click', () => setEnabled(!state.enabled));
    renderToggle();
  }

  // ── Data / lifecycle ───────────────────────────────────────────────────────

  function request() {
    if (!state.enabled || document.hidden) return;
    const api = bridge();
    if (!api || typeof api.requestWeather !== 'function') {
      setNote('OFFLINE');
      return;
    }
    const now = Date.now();
    if (now - state.lastRequestMs < CFG.minRequestGapMs) return;
    state.lastRequestMs = now;
    setNote('LOADING');
    api.requestWeather();
  }

  function setEnabled(on) {
    const wanted = !!on;
    if (state.enabled === wanted) {
      if (wanted) request();
      return;
    }
    state.enabled = wanted;
    const row = document.getElementById('layerWeather');
    if (row) row.setAttribute('aria-pressed', String(wanted));

    if (!wanted) {
      for (const rec of state.items) rec.label.show = false;
      clearSelection();
      setNote('');
    } else {
      for (const rec of state.items) rec.label.show = true;
      if (!state.loaded || Date.now() - state.lastRequestMs > CFG.refreshMs) request();
    }
    renderToggle();
  }

  function clearMarkers() {
    clearSelection();
    if (state.labels) state.labels.removeAll();
    state.items = [];
    state.byId.clear();
    state.loaded = false;
  }

  function setWeather(list) {
    if (!state.viewer || !state.labels || !Array.isArray(list)) return;
    clearMarkers();
    for (const entry of list) {
      if (!entry || typeof entry.k !== 'string' || typeof entry.c !== 'string') continue;
      const lat = Number(entry.la);
      const lon = Number(entry.lo);
      const temperature = Number(entry.t);
      if (!Number.isFinite(lat) || !Number.isFinite(lon) || !Number.isFinite(temperature)) continue;
      if (state.byId.has(entry.k)) continue;

      const rec = {
        id: entry.k,
        city: entry.c,
        lat,
        lon,
        temperatureC: temperature,
        weatherCode: Number(entry.code) || 0,
        condition: String(entry.cond || 'Unknown'),
        windKmh: Number(entry.w) || 0,
        label: null,
        baseScale: 0.95,
      };
      rec.label = state.labels.add({
        position: Cesium.Cartesian3.fromDegrees(lon, lat, CFG.labelAltitudeM),
        text: rec.city + ' ' + temperatureText(rec.temperatureC),
        font: '600 10px monospace',
        fillColor: colorFor(rec.temperatureC),
        outlineColor: Cesium.Color.BLACK.withAlpha(0.92),
        outlineWidth: 3,
        style: Cesium.LabelStyle.FILL_AND_OUTLINE,
        showBackground: true,
        backgroundColor: Cesium.Color.fromCssColorString('#0a0c12').withAlpha(0.84),
        backgroundPadding: new Cesium.Cartesian2(6, 3),
        verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
        horizontalOrigin: Cesium.HorizontalOrigin.CENTER,
        scale: rec.baseScale,
        scaleByDistance: new Cesium.NearFarScalar(5e5, 1.08, 2e7, 0.72),
        id: 'wx:' + rec.id,
      });
      rec.label.show = state.enabled;
      state.items.push(rec);
      state.byId.set(rec.id, rec);
    }
    state.loaded = true;
    setNote('');
    renderToggle();
    log('loaded ' + state.items.length + ' city readings');
  }

  function weatherFailed() {
    setNote('OFFLINE');
  }

  // ── Selection ──────────────────────────────────────────────────────────────

  function updateCard() {
    const rec = state.selectedId ? state.byId.get(state.selectedId) : null;
    if (!rec || !state.ui.card) return;
    const set = (id, text) => {
      const element = document.getElementById(id);
      if (element && element.textContent !== text) element.textContent = text;
    };
    set('wxCity', rec.city);
    set('wxCond', rec.condition.toUpperCase());
    set('wxTemp', temperatureText(rec.temperatureC) + 'C');
    set('wxMeta', rec.condition + ' · current conditions');
    set('wxWind', Math.round(rec.windKmh) + ' km/h');
    set('wxCoords', rec.lat.toFixed(1) + '\u00b0, ' + rec.lon.toFixed(1) + '\u00b0');
    const border = colorFor(rec.temperatureC).toCssColorString();
    state.ui.card.style.borderLeftColor = border;
    const condition = document.getElementById('wxCond');
    if (condition) condition.style.color = border;
  }

  function select(id) {
    const rec = state.byId.get(id);
    if (!rec || !state.enabled) return;
    window.dispatchEvent(new CustomEvent('gev:dismissCards', { detail: 'weather' }));
    clearSelection();
    state.selectedId = id;
    rec.label.scale = rec.baseScale * 1.18;
    if (state.ui.card) state.ui.card.style.display = 'block';
    updateCard();
  }

  function clearSelection() {
    const rec = state.selectedId ? state.byId.get(state.selectedId) : null;
    if (rec) rec.label.scale = rec.baseScale;
    state.selectedId = null;
    if (state.ui.card) state.ui.card.style.display = 'none';
  }

  function flyToSelected() {
    const rec = state.selectedId ? state.byId.get(state.selectedId) : null;
    if (!rec || !state.viewer) return;
    state.viewer.camera.flyTo({
      destination: Cesium.Cartesian3.fromDegrees(rec.lon, rec.lat, 650_000),
      orientation: { heading: 0, pitch: -Cesium.Math.PI_OVER_TWO, roll: 0 },
      duration: 1.4,
    });
  }

  // ── Start ──────────────────────────────────────────────────────────────────

  function start(viewer) {
    state.viewer = viewer;
    state.labels = viewer.scene.primitives.add(new Cesium.LabelCollection());

    window.addEventListener('gev:dismissCards', (event) => {
      if (event.detail !== 'weather') clearSelection();
    });
    injectUi();
    wireUi();

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
      if (id && id.startsWith('wx:')) select(id.slice(3));
    }, Cesium.ScreenSpaceEventType.LEFT_CLICK);

    state.timer = setInterval(() => {
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
  window.GEVLite.setWeather = setWeather;
  window.GEVLite.weatherFailed = weatherFailed;
  window.GEVLite.setWeatherEnabled = setEnabled;
  window.GEVLite.selectWeather = (id) => {
    if (!state.enabled) setEnabled(true);
    select(id);
  };
  window.GEVLite.weatherStats = () => ({
    enabled: state.enabled,
    loaded: state.loaded,
    total: state.items.length,
    selected: state.selectedId,
    note: state.note,
    cities: state.items.map((rec) => ({
      id: rec.id,
      city: rec.city,
      temperatureC: Math.round(rec.temperatureC * 10) / 10,
      condition: rec.condition,
      windKmh: Math.round(rec.windKmh * 10) / 10,
    })),
  });

  whenViewerReady(start);
})();
