/**
 * God's Eye Lite — satellites layer.
 *
 * Kotlin owns the CelesTrak TLE fetch and hands the catalog over the bridge.
 * The browser propagates each orbit locally with satellite.js, then converts
 * the TEME position to a Cesium fixed-frame position. This keeps the layer
 * live without polling a tracking API for every satellite every second.
 *
 * The layer has its own switch and category filters. Military satellites and
 * the space stations are deliberately first-class categories, not an
 * afterthought hidden inside an “all” list.
 */
(() => {
  'use strict';

  const CFG = {
    refreshMs: 1000,
    iconSize: 64,
    iconScale: 0.62,
    selectedScale: 0.9,
    // Satellites are spread from low Earth orbit to geostationary orbit. The
    // near/far scale keeps a geostationary satellite readable at world view
    // without turning an overhead ISS pass into a screen-filling icon.
    scaleByDistance: new Cesium.NearFarScalar(5e5, 1.0, 5e7, 0.38),
  };

  const CATEGORY = {
    station: { label: 'Space station', color: '#35e0c8' },
    nav: { label: 'Navigation', color: '#7ab8ff' },
    weather: { label: 'Weather', color: '#ffc24b' },
    military: { label: 'Military', color: '#ff7a7a' },
    science: { label: 'Science', color: '#b8a6ff' },
  };

  const state = {
    viewer: null,
    enabled: true,
    collection: null,
    labels: null,
    items: [],
    byId: new Map(),
    selectedId: null,
    filter: 'all',
    loaded: false,
    note: '',
    selectionLabel: null,
    timer: 0,
    ui: {},
  };

  let iconUrl = '';

  function bridge() {
    return typeof window.GEVAndroid === 'object' ? window.GEVAndroid : null;
  }

  function log(message) {
    const api = bridge();
    if (api && typeof api.log === 'function') api.log('satellites: ' + message);
  }

  function categoryFor(key) {
    return CATEGORY[key] || CATEGORY.science;
  }

  function colorFor(key) {
    return Cesium.Color.fromCssColorString(categoryFor(key).color);
  }

  function renderToggle() {
    const note = document.getElementById('satellitesStatus');
    if (!note) return;
    const visible = state.items.filter((rec) => matches(rec)).length;
    note.textContent = !state.enabled
      ? 'OFF'
      : state.note
        ? state.note
        : state.items.length
          ? String(visible)
          : '';
  }

  function setNote(note) {
    if (state.note === note) return;
    state.note = note;
    renderToggle();
  }

  // ── Icon ───────────────────────────────────────────────────────────────────

  function satelliteIcon() {
    if (iconUrl) return iconUrl;
    const size = CFG.iconSize;
    const canvas = document.createElement('canvas');
    canvas.width = size;
    canvas.height = size;
    const g = canvas.getContext('2d');
    g.translate(size / 2, size / 2);
    g.lineJoin = 'round';
    g.lineCap = 'round';

    // Soft halo: the billboard's color tints the whole image, so each category
    // gets a faint glow in its own colour against the dark ground.
    const halo = g.createRadialGradient(0, 0, 6, 0, 0, 30);
    halo.addColorStop(0, 'rgba(255, 255, 255, 0.30)');
    halo.addColorStop(0.55, 'rgba(255, 255, 255, 0.10)');
    halo.addColorStop(1, 'rgba(255, 255, 255, 0)');
    g.fillStyle = halo;
    g.beginPath();
    g.arc(0, 0, 30, 0, Math.PI * 2);
    g.fill();

    // A tiny white/cream spacecraft glyph. The billboard's color tints it by
    // category, so one image serves every filter.
    g.fillStyle = '#ffffff';
    g.strokeStyle = 'rgba(10, 18, 28, 0.85)';
    g.lineWidth = 2;

    // Bus
    g.beginPath();
    g.roundRect ? g.roundRect(-8, -9, 16, 18, 3) : g.rect(-8, -9, 16, 18);
    g.fill();
    g.stroke();

    // Solar panels
    for (const side of [-1, 1]) {
      g.fillStyle = '#d8e8f5';
      g.beginPath();
      g.rect(side === -1 ? -29 : 11, -5, 18, 10);
      g.fill();
      g.stroke();
      g.strokeStyle = 'rgba(15, 30, 48, 0.5)';
      g.lineWidth = 1;
      for (let x = 1; x < 18; x += 6) {
        const left = side === -1 ? -29 : 11;
        g.beginPath();
        g.moveTo(left + x, -5);
        g.lineTo(left + x, 5);
        g.stroke();
      }
      g.strokeStyle = 'rgba(10, 18, 28, 0.85)';
      g.lineWidth = 2;
    }

    // Dish / antenna
    g.beginPath();
    g.arc(0, -17, 5, 0, Math.PI * 2);
    g.fill();
    g.stroke();
    g.beginPath();
    g.moveTo(0, -12);
    g.lineTo(0, -7);
    g.stroke();

    iconUrl = canvas.toDataURL('image/png');
    return iconUrl;
  }

  // ── UI ─────────────────────────────────────────────────────────────────────

  function injectUi() {
    const style = document.createElement('style');
    style.textContent = `
      #satCard {
        position: absolute; left: 12px; right: 12px; bottom: 34px; max-width: 440px;
        display: none; z-index: 6; pointer-events: none;
        background: rgba(10, 12, 18, 0.94); border: 1px solid #35404f;
        border-left: 4px solid #35e0c8; border-radius: 10px;
        padding: 10px 12px; color: #e8e8f0; font-family: monospace; font-size: 11px;
      }
      #satCard .sc-head { display: flex; align-items: flex-start; gap: 8px; }
      #satCard .sc-name { flex: 1; font-size: 14px; font-weight: 700; line-height: 1.25; }
      #satCard .sc-close { pointer-events: auto; background: none; border: 0; color: #9a9ab0;
        font-size: 22px; line-height: 1; padding: 0 2px; }
      #satCard .sc-cat { color: #35e0c8; font-size: 9px; letter-spacing: 1px; margin-top: 4px; }
      #satCard .sc-id { color: #8a8aa0; font-size: 9px; margin-top: 2px; }
      #satCard .sc-stats { display: flex; gap: 16px; margin-top: 10px; }
      #satCard .sc-stats b { display: block; font-size: 13px; }
      #satCard .sc-stats span { color: #9a9ab0; font-size: 8px; }
    `;
    document.head.appendChild(style);

    const card = document.createElement('div');
    card.id = 'satCard';
    card.innerHTML = `
      <div class="sc-head">
        <div class="sc-name" id="scName"></div>
        <button class="sc-close" id="scClose" aria-label="Close">&times;</button>
      </div>
      <div class="sc-cat" id="scCategory"></div>
      <div class="sc-id" id="scId"></div>
      <div class="sc-stats">
        <div><b id="scAlt"></b><span>ALTITUDE</span></div>
        <div><b id="scSpeed"></b><span>SPEED</span></div>
        <div><b id="scPeriod"></b><span>PERIOD</span></div>
      </div>`;
    document.body.appendChild(card);
    state.ui.card = card;
    document.getElementById('scClose').addEventListener('click', clearSelection);
  }

  function wireFilterUi() {
    const group = document.getElementById('satelliteFilter');
    if (group) {
      for (const button of group.querySelectorAll('button')) {
        button.addEventListener('click', () => setFilter(button.dataset.sat));
      }
    }
    renderFilterUi();
  }

  function renderFilterUi() {
    for (const button of document.querySelectorAll('#satelliteFilter button')) {
      const active = button.dataset.sat === state.filter;
      button.classList.toggle('on', active);
      button.setAttribute('aria-pressed', String(active));
    }
  }

  function setFilter(filter) {
    if (!['all', 'station', 'nav', 'weather', 'military', 'science'].includes(filter)) return;
    state.filter = filter;
    if (state.selectedId) {
      const selected = state.byId.get(state.selectedId);
      if (!selected || !matches(selected)) clearSelection();
    }
    renderFilterUi();
    update(new Date());
    renderToggle();
  }

  // ── Data / propagation ─────────────────────────────────────────────────────

  function request() {
    if (!state.enabled) return;
    const api = bridge();
    if (!api || typeof api.requestSatellites !== 'function') {
      setNote('OFFLINE');
      return;
    }
    setNote('LOADING');
    api.requestSatellites();
  }

  function clearAll() {
    if (state.collection) state.collection.removeAll();
    if (state.labels) state.labels.removeAll();
    state.items = [];
    state.byId.clear();
    state.selectionLabel = null;
    state.selectedId = null;
    state.loaded = false;
    if (state.ui.card) state.ui.card.style.display = 'none';
  }

  function setSatellites(list) {
    if (!state.viewer || !state.collection || !Array.isArray(list)) return;
    if (typeof satellite === 'undefined') {
      setNote('UNAVAILABLE');
      return;
    }

    clearAll();
    const icon = satelliteIcon();
    for (const entry of list) {
      if (!entry || typeof entry.id !== 'string' || !entry.l1 || !entry.l2) continue;
      if (state.byId.has(entry.id)) continue;
      let satrec;
      try {
        satrec = satellite.twoline2satrec(entry.l1, entry.l2);
      } catch (error) {
        continue;
      }
      const rec = {
        id: entry.id,
        name: entry.n || entry.id,
        category: CATEGORY[entry.cat] ? entry.cat : 'science',
        satrec,
        visible: true,
        bb: state.collection.add({
          image: icon,
          color: Cesium.Color.WHITE,
          scale: CFG.iconScale,
          verticalOrigin: Cesium.VerticalOrigin.CENTER,
          horizontalOrigin: Cesium.HorizontalOrigin.CENTER,
          scaleByDistance: CFG.scaleByDistance,
          id: 'sat:' + entry.id,
        }),
      };
      rec.bb.color = colorFor(rec.category);
      if (rec.category === 'station') {
        rec.stationLabel = state.labels.add({
          text: rec.name,
          font: '600 10px monospace',
          fillColor: Cesium.Color.WHITE,
          outlineColor: Cesium.Color.BLACK.withAlpha(0.9),
          outlineWidth: 3,
          style: Cesium.LabelStyle.FILL_AND_OUTLINE,
          verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
          pixelOffset: new Cesium.Cartesian2(0, -16),
          scaleByDistance: new Cesium.NearFarScalar(5e5, 1.0, 4e7, 0.65),
        });
      }
      state.items.push(rec);
      state.byId.set(rec.id, rec);
    }

    state.loaded = true;
    setNote('');
    if (!state.enabled) {
      for (const rec of state.items) {
        rec.bb.show = false;
        if (rec.stationLabel) rec.stationLabel.show = false;
      }
    } else {
      update(new Date());
    }
    log('loaded ' + state.items.length + ' satellites');
  }

  function satellitesFailed() {
    setNote('OFFLINE');
  }

  function matches(rec) {
    return state.filter === 'all' || rec.category === state.filter;
  }

  function update(date) {
    if (!state.viewer || !state.collection || typeof satellite === 'undefined') return;
    if (!state.enabled) return;

    const gmst = satellite.gstime(date);
    const occluder = new Cesium.EllipsoidalOccluder(
      Cesium.Ellipsoid.WGS84,
      state.viewer.camera.positionWC,
    );

    for (const rec of state.items) {
      // Set this before propagation: one bad TLE must not leave the previous
      // filter's visible flag behind on that satellite.
      rec.visible = matches(rec);
      let posVel;
      try {
        posVel = satellite.propagate(rec.satrec, date);
      } catch (error) {
        posVel = null;
      }
      if (!posVel || !posVel.position || typeof posVel.position === 'boolean') {
        rec.bb.show = false;
        if (rec.stationLabel) rec.stationLabel.show = false;
        continue;
      }

      const geo = satellite.eciToGeodetic(posVel.position, gmst);
      rec.lat = satellite.degreesLat(geo.latitude);
      rec.lon = satellite.degreesLong(geo.longitude);
      rec.altM = geo.height * 1000;
      rec.cart = Cesium.Cartesian3.fromDegrees(rec.lon, rec.lat, rec.altM);
      rec.speedKmS = posVel.velocity
        ? Math.sqrt(
            posVel.velocity.x ** 2 +
              posVel.velocity.y ** 2 +
              posVel.velocity.z ** 2,
          )
        : 0;

      const onNearSide = occluder.isPointVisible(rec.cart);
      rec.bb.show = rec.visible && onNearSide;
      if (rec.stationLabel) {
        rec.stationLabel.position = rec.cart;
        rec.stationLabel.show = rec.visible && onNearSide;
      }
      if (rec.bb.show) rec.bb.position = rec.cart;
    }

    if (state.selectedId) updateCard();
  }

  // ── Selection ──────────────────────────────────────────────────────────────

  function select(id) {
    const rec = state.byId.get(id);
    if (!rec || !rec.visible) return;
    clearSelection();
    state.selectedId = id;
    rec.bb.scale = CFG.selectedScale;
    rec.bb.color = Cesium.Color.WHITE;
    state.selectionLabel = state.labels.add({
      text: rec.name,
      font: '600 11px monospace',
      fillColor: Cesium.Color.WHITE,
      outlineColor: Cesium.Color.BLACK.withAlpha(0.9),
      outlineWidth: 3,
      style: Cesium.LabelStyle.FILL_AND_OUTLINE,
      verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
      pixelOffset: new Cesium.Cartesian2(0, -18),
    });
    if (rec.cart) state.selectionLabel.position = rec.cart;
    state.ui.card.style.display = 'block';
    updateCard();
  }

  function clearSelection() {
    const rec = state.selectedId ? state.byId.get(state.selectedId) : null;
    if (rec) {
      rec.bb.scale = CFG.iconScale;
      rec.bb.color = colorFor(rec.category);
    }
    state.selectedId = null;
    if (state.selectionLabel) {
      state.labels.remove(state.selectionLabel);
      state.selectionLabel = null;
    }
    if (state.ui.card) state.ui.card.style.display = 'none';
  }

  function updateCard() {
    const rec = state.selectedId ? state.byId.get(state.selectedId) : null;
    if (!rec || !state.ui.card) return;
    const set = (id, text) => {
      const element = document.getElementById(id);
      if (element && element.textContent !== text) element.textContent = text;
    };
    set('scName', rec.name);
    set('scCategory', categoryFor(rec.category).label.toUpperCase());
    set('scId', 'NORAD ' + rec.id);
    set('scAlt', Math.round((rec.altM || 0) / 1000).toLocaleString() + ' km');
    set('scSpeed', (rec.speedKmS || 0).toFixed(2) + ' km/s');
    // The TLE's mean motion is rev/day; 86400 / rev/day gives minutes/orbit.
    const revsPerDay = rec.satrec && rec.satrec.no ? rec.satrec.no * 229.183118 : 0;
    set('scPeriod', revsPerDay ? Math.round(1440 / revsPerDay) + ' min' : '—');
  }

  // ── Start ──────────────────────────────────────────────────────────────────

  function setEnabled(on) {
    state.enabled = !!on;
    if (!state.enabled) {
      for (const rec of state.items) {
        rec.bb.show = false;
        if (rec.stationLabel) rec.stationLabel.show = false;
      }
      clearSelection();
    } else if (state.viewer) {
      if (!state.loaded) request();
      update(new Date());
    }
    renderToggle();
  }

  function start(viewer) {
    if (typeof satellite === 'undefined') {
      setNote('UNAVAILABLE');
      log('satellite.js failed to load');
      return;
    }
    state.viewer = viewer;
    const row = document.getElementById('layerSatellites');
    if (row && row.getAttribute('aria-pressed') === 'false') state.enabled = false;
    state.collection = viewer.scene.primitives.add(new Cesium.BillboardCollection());
    state.labels = viewer.scene.primitives.add(new Cesium.LabelCollection());
    injectUi();
    wireFilterUi();

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
      if (id && id.startsWith('sat:')) select(id.slice(4));
    }, Cesium.ScreenSpaceEventType.LEFT_CLICK);

    state.timer = setInterval(() => update(new Date()), CFG.refreshMs);
    request();
    log('layer ready');
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
  window.GEVLite.setSatellites = setSatellites;
  window.GEVLite.satellitesFailed = satellitesFailed;
  window.GEVLite.setSatellitesEnabled = setEnabled;
  window.GEVLite.satelliteStats = () => ({
    enabled: state.enabled,
    loaded: state.items.length,
    visible: state.enabled ? state.items.filter((rec) => rec.visible).length : 0,
    positioned: state.items.filter((rec) => !!rec.cart).length,
    filter: state.filter,
    selected: state.selectedId,
    note: state.note,
    sample: state.items[0] && state.items[0].cart
      ? {
          name: state.items[0].name,
          lat: state.items[0].lat,
          lon: state.items[0].lon,
          altKm: Math.round(state.items[0].altM / 10) / 100,
        }
      : null,
    categories: state.items.reduce((out, rec) => {
      out[rec.category] = (out[rec.category] || 0) + 1;
      return out;
    }, {}),
  });

  whenViewerReady(start);
})();
