/**
 * God's Eye Lite — globe logic.
 *
 * Rendering strategy:
 *  - Base imagery is keyless (Esri satellite, OSM fallback), so the app shows a
 *    usable globe before any token exists.
 *  - Cesium World Terrain and the 3D buildings come from Cesium ion and need a
 *    token. Photorealistic 3D Tiles (2275207) is tried first because it shows
 *    real buildings; OSM Buildings (96188) is the global fallback.
 *  - Cameras arrive from Kotlin over the bridge and are drawn as a
 *    PointPrimitiveCollection, which is the cheap path for thousands of pins.
 */
(() => {
  'use strict';

  const HOME_VIEW = {
    destination: Cesium.Cartesian3.fromDegrees(-98.0, 39.0, 12_000_000),
    duration: 0,
  };

  const PHOTOREAL_TILESET = 2275207; // Google Photorealistic 3D Tiles (via ion)
  const OSM_BUILDINGS_TILESET = 96188; // Cesium OSM Buildings
  const WORLD_TERRAIN = 1;

  /**
   * Keyless imagery, in preference order.
   *
   * These are plain UrlTemplate providers rather than
   * `ArcGisMapServerImageryProvider.fromBasemapType()`: that call resolves to
   * `ibasemaps-api.arcgis.com`, which now requires an ArcGIS API key and
   * answers 401/404 without one. Worse, it still *succeeds* as a constructor
   * when the service refuses the requests, so a try/catch around it never
   * fires, no fallback runs, and the globe ends up with no imagery at all —
   * which the user sees as a black globe.
   */
  const IMAGERY_SOURCES = [
    {
      label: 'Esri World Imagery',
      credit: 'Imagery \u00a9 Esri',
      tileUrl:
        'https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
      probeUrl:
        'https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/0/0/0',
      maximumLevel: 19,
    },
    {
      label: 'OpenStreetMap',
      credit: '\u00a9 OpenStreetMap contributors',
      tileUrl: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
      probeUrl: 'https://tile.openstreetmap.org/0/0/0.png',
      maximumLevel: 19,
    },
  ];

  const PACK_COLORS = {
    austin: '#35e0c8',
    calgary: '#ffc24b',
    wsdot: '#7ab8ff',
    tfl: '#ff7ab8',
  };
  const FALLBACK_COLOR = '#c8c8d8';

  /**
   * On-globe camera thumbnails.
   *
   * They are HTML rather than Cesium entities because the still is a plain
   * <img> from the publisher's CDN — verified to load in the WebView without
   * CORS — and DOM text stays crisp at any device density. Only a handful are
   * ever on screen: every card is a full-resolution decode (Austin serves
   * 1920x1080), so the cap is about memory as much as clutter.
   */
  const THUMB = {
    // Above this altitude a city block is a few pixels wide and the cards would
    // cover the planet instead of describing it.
    maxAltitudeM: 45_000,
    maxCards: 6,
    cardWidthPx: 134,
    cardHeightPx: 130, // image + name bar + stem, for the overlap test
    refreshMs: 10_000,
    layoutMs: 120,
  };

  const state = {
    viewer: null,
    points: null,
    // id -> Cartesian3, filled with the pins so the card layout does not
    // recompute trigonometry for thousands of cameras every frame.
    positions: new Map(),
    // The drawn pins, kept so horizon culling can flip their visibility
    // without rebuilding the whole collection.
    pointRecords: [],
    thumbs: null,
    cameras: [],
    packs: [],
    hidden: new Set(),
    ionToken: '',
    tokenApplied: false,
  };

  // ── Android bridge ─────────────────────────────────────────────────────────

  function bridge() {
    return typeof window.GEVAndroid === 'object' ? window.GEVAndroid : null;
  }

  function log(message) {
    const api = bridge();
    if (api && typeof api.log === 'function') api.log(String(message));
  }

  function notifyGlobeReady() {
    const api = bridge();
    if (api && typeof api.onGlobeReady === 'function') api.onGlobeReady();
  }

  function colorFor(pack) {
    return PACK_COLORS[pack] || FALLBACK_COLOR;
  }

  // ── Boot ───────────────────────────────────────────────────────────────────

  async function boot() {
    if (typeof Cesium === 'undefined') {
      fail('Cesium failed to load — is this device online?');
      return;
    }

    try {
      state.viewer = new Cesium.Viewer('globe', {
        // A token-free default layer: we add imagery ourselves below.
        baseLayer: false,
        baseLayerPicker: false,
        geocoder: false,
        homeButton: false,
        sceneModePicker: false,
        navigationHelpButton: false,
        animation: false,
        timeline: false,
        fullscreenButton: false,
        infoBox: false,
        selectionIndicator: false,
        shouldAnimate: false,
      });
    } catch (error) {
      fail('Could not start the globe: ' + (error && error.message));
      return;
    }

    const viewer = state.viewer;
    viewer.scene.globe.enableLighting = false;
    viewer.scene.skyAtmosphere.show = true;
    // Cesium's ground-atmosphere pass writes gl_FragDepth, and on some Android
    // WebView GPU stacks that makes the whole globe surface fail its depth test:
    // the sphere renders black while stars, pins and the sky atmosphere still
    // draw. Verified causally on an Adreno 660 (Chrome 156 WebView) — with this
    // off the globe draws, with it on the globe disappears. The cost is a little
    // haze near the horizon; the rim seen from space is skyAtmosphere, which stays.
    viewer.scene.globe.showGroundAtmosphere = false;
    // Keep the default imagery resolution reasonable on a phone GPU.
    viewer.scene.globe.maximumScreenSpaceError = 3;
    // Cesium's stock zoom is slow at planetary scale: measured on a phone, one
    // pinch from 12,000 km only reached ~3,900 km, so a city took a dozen
    // pinches. At 10 the same pinch reaches ~1,100 km, i.e. about three to a
    // street — still fine-grained enough to control.
    viewer.scene.screenSpaceCameraController.zoomFactor = 10;
    viewer.camera.setView(HOME_VIEW);

    await addBaseImagery(viewer);
    wirePicking(viewer);
    wireThumbnails(viewer);
    renderPackChips();
    updateCountLabel();
    wireHudAutoHide();

    const boot = document.getElementById('boot');
    if (boot) boot.remove();

    notifyGlobeReady();
    log('globe ready');
  }

  /**
   * Adds the first imagery source that can actually serve a tile.
   *
   * Probing first is the whole point: a provider that merely *constructs* can
   * still fail every tile request, and the failure is invisible to JavaScript
   * — the globe simply stays blank, with no exception to catch. Fetching one
   * real tile is the only reliable signal that imagery will work.
   */
  async function addBaseImagery(viewer) {
    for (const source of IMAGERY_SOURCES) {
      try {
        const response = await fetch(source.probeUrl, {
          mode: 'cors',
          cache: 'no-store',
        });
        if (!response.ok) throw new Error('HTTP ' + response.status);
      } catch (error) {
        log('imagery ' + source.label + ' unavailable: ' + (error && error.message));
        continue;
      }

      viewer.imageryLayers.addImageryProvider(
        new Cesium.UrlTemplateImageryProvider({
          url: source.tileUrl,
          credit: source.credit,
          maximumLevel: source.maximumLevel,
        }),
      );
      log('imagery: ' + source.label);
      return source.label;
    }

    log('no imagery source reachable \u2014 the globe will be bare');
    return null;
  }

  /**
   * Applied whenever Kotlin sends a token. Everything here is optional: with no
   * token the globe still works, just without terrain or 3D buildings.
   */
  async function applyIonToken(token) {
    state.ionToken = token || '';
    if (!state.ionToken || state.tokenApplied) return;
    const viewer = state.viewer;
    if (!viewer) return;

    Cesium.Ion.defaultAccessToken = state.ionToken;
    state.tokenApplied = true;

    // Terrain first: it changes how everything else sits on the globe.
    try {
      const terrain = await Cesium.Terrain.fromWorldTerrain();
      viewer.scene.setTerrain(terrain);
      log('world terrain on');
    } catch (error) {
      log('terrain unavailable: ' + (error && error.message));
    }

    // Then the buildings: real photorealistic tiles, else global OSM extrusions.
    for (const assetId of [PHOTOREAL_TILESET, OSM_BUILDINGS_TILESET]) {
      try {
        const resource = await Cesium.IonResource.fromAssetId(assetId);
        const tileset = await Cesium.Cesium3DTileset.fromUrl(resource, {
          maximumScreenSpaceError: 16,
        });
        viewer.scene.primitives.add(tileset);
        log('3D buildings on (asset ' + assetId + ')');
        return;
      } catch (error) {
        log('asset ' + assetId + ' failed: ' + (error && error.message));
      }
    }
    log('no 3D buildings available for this token');
  }

  // ── Camera pins ────────────────────────────────────────────────────────────

  function rebuildPoints() {
    const viewer = state.viewer;
    if (!viewer) return;

    if (state.points) {
      viewer.scene.primitives.remove(state.points);
      state.points = null;
    }

    state.positions.clear();
    state.pointRecords = [];
    const collection = new Cesium.PointPrimitiveCollection();
    for (const camera of state.cameras) {
      if (state.hidden.has(camera.pack)) continue;
      if (!isFinite(camera.lat) || !isFinite(camera.lon)) continue;
      const position = Cesium.Cartesian3.fromDegrees(camera.lon, camera.lat);
      state.positions.set(camera.id, position);
      const primitive = collection.add({
        position,
        pixelSize: 7,
        color: Cesium.Color.fromCssColorString(colorFor(camera.pack)),
        outlineColor: Cesium.Color.fromCssColorString('#07070b'),
        outlineWidth: 1,
        // Road-level cameras sit under terrain and, once you are close enough
        // for the depth buffer to resolve metres, under photoreal buildings
        // too — which is exactly where the pins used to vanish. Drawing them
        // regardless of depth is the fix; refreshHorizonCulling() then puts the
        // horizon back, since nothing else hides the far hemisphere.
        disableDepthTestDistance: Number.POSITIVE_INFINITY,
        // Picking reads this back to identify which camera was tapped.
        id: camera.id,
      });
      state.pointRecords.push({ primitive, position });
    }
    viewer.scene.primitives.add(collection);
    state.points = collection;
    refreshHorizonCulling();
    updateCountLabel();
  }

  /**
   * Hides the pins that are behind the planet.
   *
   * Only needed because the pins are always-on-top: with the depth test off,
   * London's whole cluster would otherwise shine through the globe from a US
   * viewpoint. Pure math over a few thousand points, on the same ~8Hz throttle
   * as the thumbnails and after every rebuild.
   */
  function refreshHorizonCulling() {
    const viewer = state.viewer;
    if (!viewer || !state.pointRecords.length) return;
    const occluder = new Cesium.EllipsoidalOccluder(
      Cesium.Ellipsoid.WGS84,
      viewer.camera.positionWC,
    );
    for (const record of state.pointRecords) {
      record.primitive.show = occluder.isPointVisible(record.position);
    }
  }

  /** Tells the user how many of the loaded cameras are currently drawn. */
  function updateCountLabel() {
    const hint = document.getElementById('hint');
    if (!hint) return;
    const shown = state.points ? state.points.length : 0;
    hint.textContent =
      state.cameras.length === 0
        ? 'Loading cameras…'
        : shown + ' of ' + state.cameras.length + ' cameras shown  ·  tap a dot or thumbnail';
  }

  function wirePicking(viewer) {
    const handler = new Cesium.ScreenSpaceEventHandler(viewer.scene.canvas);
    handler.setInputAction((movement) => {
      // Thumbnail cards first: they sit above their pin, so a tap meant for the
      // card would otherwise pick whatever dot happens to be underneath it.
      const cardId = thumbAt(movement.position);
      if (cardId) {
        const api = bridge();
        if (api && typeof api.openCamera === 'function') api.openCamera(cardId);
        return;
      }

      const picked = viewer.scene.pick(movement.position);
      if (!Cesium.defined(picked)) return;
      // Accept the id whether Cesium surfaces it on the picked object or on the
      // underlying primitive.
      const id =
        typeof picked.id === 'string'
          ? picked.id
          : picked.primitive && typeof picked.primitive.id === 'string'
            ? picked.primitive.id
            : null;
      if (!id) return;
      const api = bridge();
      if (api && typeof api.openCamera === 'function') api.openCamera(id);
    }, Cesium.ScreenSpaceEventType.LEFT_CLICK);
  }

  // ── Camera thumbnails ──────────────────────────────────────────────────────

  function wireThumbnails(viewer) {
    const host = document.getElementById('thumbs');
    if (!host) return;
    state.thumbs = { host, byId: new Map(), lastLayoutMs: 0 };
    // postRender, not a timer: the cards have to move with the globe, and one
    // layout per frame would be wasted work on a phone GPU, hence the throttle.
    viewer.scene.postRender.addEventListener(() => {
      const now = performance.now();
      if (now - state.thumbs.lastLayoutMs < THUMB.layoutMs) return;
      state.thumbs.lastLayoutMs = now;
      // Driven from here rather than camera.moveEnd because flyTo with a
      // zero duration (RESET VIEW, and the test hook) never raises moveEnd, and
      // a stale cull leaves the globe oddly empty.
      refreshHorizonCulling();
      layoutThumbnails(viewer, now);
    });
  }

  function clearThumbs() {
    const thumbs = state.thumbs;
    if (!thumbs || thumbs.byId.size === 0) return;
    for (const element of thumbs.byId.values()) element.remove();
    thumbs.byId.clear();
  }

  /**
   * Places a card for each camera worth showing: on screen, on the near side of
   * the planet, and not hidden by a pack toggle. Nearest to the centre of the
   * view wins, and cards that would overlap are dropped rather than stacked.
   */
  function layoutThumbnails(viewer, now) {
    const thumbs = state.thumbs;
    if (!thumbs) return;
    const scene = viewer.scene;

    if (
      state.positions.size === 0 ||
      viewer.camera.positionCartographic.height > THUMB.maxAltitudeM
    ) {
      clearThumbs();
      return;
    }

    const view = viewer.camera.computeViewRectangle();
    if (!view) {
      clearThumbs();
      return;
    }

    const canvas = scene.canvas;
    const width = canvas.clientWidth;
    const height = canvas.clientHeight;
    const centreX = width / 2;
    const centreY = height / 2;
    const occluder = new Cesium.EllipsoidalOccluder(
      Cesium.Ellipsoid.WGS84,
      scene.camera.positionWC,
    );
    // wgs84ToWindowCoordinates is the pre-1.121 name and logs a deprecation.
    const project =
      Cesium.SceneTransforms.worldToWindowCoordinates ||
      Cesium.SceneTransforms.wgs84ToWindowCoordinates;

    const candidates = [];
    for (const camera of state.cameras) {
      if (state.hidden.has(camera.pack)) continue;
      const position = state.positions.get(camera.id);
      if (!position) continue;

      const north = Cesium.Math.toRadians(camera.lat);
      const east = Cesium.Math.toRadians(camera.lon);
      if (north > view.north || north < view.south) continue;
      // A view rectangle that crosses the antimeridian has west > east.
      if (view.west <= view.east) {
        if (east < view.west || east > view.east) continue;
      } else if (east < view.west && east > view.east) {
        continue;
      }

      // Otherwise cameras on the far side of the planet would get cards drawn
      // over the near hemisphere.
      if (!occluder.isPointVisible(position)) continue;

      const point = project(scene, position);
      if (!point) continue;
      // The top band belongs to the HUD, however faint it currently is.
      if (point.x < 0 || point.x > width || point.y < 150 || point.y > height) continue;

      // A camera near the edge would otherwise get a card sliced in half by the
      // screen. Only the card body slides inwards; the stem stays on the point
      // so the card is still visibly attached to it.
      const halfCard = THUMB.cardWidthPx / 2;
      const cardX = Cesium.Math.clamp(point.x, halfCard + 4, width - halfCard - 4);

      const dx = point.x - centreX;
      const dy = point.y - centreY;
      candidates.push({ camera, x: point.x, y: point.y, cardX, distance: dx * dx + dy * dy });
    }

    // Cards already up keep their slot: ranking purely by distance makes them
    // flicker between neighbours as the camera drifts.
    candidates.sort((a, b) => {
      const aKept = thumbs.byId.has(a.camera.id) ? 0 : 1;
      const bKept = thumbs.byId.has(b.camera.id) ? 0 : 1;
      return aKept - bKept || a.distance - b.distance;
    });

    const placed = [];
    const keep = new Set();
    for (const candidate of candidates) {
      if (keep.size >= THUMB.maxCards) break;
      const left = candidate.cardX - THUMB.cardWidthPx / 2;
      const box = {
        left,
        right: left + THUMB.cardWidthPx,
        top: candidate.y - THUMB.cardHeightPx,
        bottom: candidate.y,
      };
      if (placed.some((other) => boxesOverlap(box, other))) continue;
      placed.push(box);
      keep.add(candidate.camera.id);

      const element = thumbElement(candidate.camera);
      element.style.transform =
        'translate3d(' + Math.round(candidate.x) + 'px,' + Math.round(candidate.y) + 'px,0)';
      element.style.setProperty(
        '--thumb-dx',
        Math.round(candidate.cardX - candidate.x) + 'px',
      );
      refreshThumb(element, candidate.camera, now);
    }

    for (const [id, element] of thumbs.byId) {
      if (keep.has(id)) continue;
      element.remove();
      thumbs.byId.delete(id);
    }
  }

  /** Id of the card under a canvas-space point, or null. */
  function thumbAt(position) {
    const thumbs = state.thumbs;
    if (!thumbs) return null;
    for (const [id, element] of thumbs.byId) {
      const card = element.firstElementChild;
      if (!card) continue;
      const rect = card.getBoundingClientRect();
      if (
        position.x >= rect.left &&
        position.x <= rect.right &&
        position.y >= rect.top &&
        position.y <= rect.bottom
      ) {
        return id;
      }
    }
    return null;
  }

  function boxesOverlap(a, b) {
    const gap = 6;
    return (
      a.left < b.right + gap &&
      b.left < a.right + gap &&
      a.top < b.bottom + gap &&
      b.top < a.bottom + gap
    );
  }

  function thumbElement(camera) {
    const thumbs = state.thumbs;
    const existing = thumbs.byId.get(camera.id);
    if (existing) return existing;

    const element = document.createElement('div');
    element.className = 'thumb';
    // Parked off-screen until the first layout positions it, so a new card
    // never flashes in the top-left corner.
    element.style.transform = 'translate3d(-9999px,-9999px,0)';

    const card = document.createElement('div');
    card.className = 'thumb-card';

    const image = document.createElement('img');
    image.className = 'thumb-img';
    image.alt = '';
    image.decoding = 'async';
    image.addEventListener('load', () => {
      element.classList.add('loaded');
      element.classList.remove('missing');
    });
    // A camera that is powered off still gets a card: its name is useful, and
    // silently dropping it would look like a bug.
    image.addEventListener('error', () => element.classList.add('missing'));

    const name = document.createElement('div');
    name.className = 'thumb-name';
    name.textContent = camera.name;

    const cornerLeft = document.createElement('span');
    cornerLeft.className = 'thumb-tl';
    const cornerRight = document.createElement('span');
    cornerRight.className = 'thumb-tr';

    card.appendChild(image);
    card.appendChild(name);
    card.appendChild(cornerLeft);
    card.appendChild(cornerRight);

    const stem = document.createElement('div');
    stem.className = 'thumb-stem';

    element.appendChild(card);
    element.appendChild(stem);

    thumbs.host.appendChild(element);
    thumbs.byId.set(camera.id, element);
    return element;
  }

  /** Re-fetches a still once it is due, with a cache-buster so it is not stale. */
  function refreshThumb(element, camera, now) {
    if (!camera.imageUrl) return;
    const loadedAt = Number(element.dataset.loadedAt || 0);
    if (loadedAt && now - loadedAt < THUMB.refreshMs) return;
    element.dataset.loadedAt = String(now);

    const image = element.querySelector('.thumb-img');
    if (!image) return;
    image.src =
      camera.imageUrl +
      (camera.imageUrl.indexOf('?') >= 0 ? '&' : '?') +
      'r=' +
      Math.round(now);
  }

  // ── Overlay ────────────────────────────────────────────────────────────────

  /**
   * Fades the top controls out once the globe sits untouched.
   *
   * Only #hud fades: the camera-data credit lives outside it because those
   * open-data licences require it to stay visible. Any touch, drag or wheel
   * brings the controls straight back.
   */
  function wireHudAutoHide() {
    const hud = document.getElementById('hud');
    if (!hud) return;

    const IDLE_MS = 4000;
    let timer = 0;

    const wake = () => {
      hud.classList.remove('idle');
      clearTimeout(timer);
      timer = setTimeout(() => hud.classList.add('idle'), IDLE_MS);
    };

    for (const type of ['pointerdown', 'pointermove', 'wheel', 'touchstart', 'keydown']) {
      window.addEventListener(type, wake, { passive: true });
    }
    wake();
  }

  function renderPackChips() {
    const host = document.getElementById('packs');
    if (!host) return;
    host.textContent = '';
    for (const pack of state.packs) {
      const chip = document.createElement('button');
      chip.className = 'chip' + (state.hidden.has(pack.key) ? '' : ' on');
      const dot = document.createElement('span');
      dot.className = 'dot';
      dot.style.background = colorFor(pack.key);
      chip.appendChild(dot);
      chip.appendChild(document.createTextNode(pack.label));
      chip.addEventListener('click', () => {
        if (state.hidden.has(pack.key)) state.hidden.delete(pack.key);
        else state.hidden.add(pack.key);
        renderPackChips();
        rebuildPoints();
      });
      host.appendChild(chip);
    }
  }

  function fail(message) {
    const boot = document.getElementById('boot');
    if (boot) boot.textContent = message;
    log(message);
  }

  // ── API used by Kotlin ─────────────────────────────────────────────────────

  window.GEVLite = {
    setIonToken(token) {
      applyIonToken(token);
    },
    setPacks(packs) {
      state.packs = Array.isArray(packs) ? packs : [];
      renderPackChips();
    },
    setCameras(cameras) {
      state.cameras = Array.isArray(cameras) ? cameras : [];
      rebuildPoints();
      log('cameras: ' + state.cameras.length);
    },
    resetView() {
      if (state.viewer) state.viewer.camera.flyTo(HOME_VIEW);
    },
    /**
     * Fly to a point. Not used by the UI — it is the hook the device test does
     * its screenshot checks through, where dragging the globe is impractical.
     */
    flyTo(lat, lon, height) {
      if (!state.viewer) return false;
      state.viewer.camera.flyTo({
        destination: Cesium.Cartesian3.fromDegrees(lon, lat, height),
        duration: 0,
      });
      return true;
    },
    /** Counts and imagery state, for the host app's logs and for device debugging. */
    stats() {
      const viewer = state.viewer;
      const layers = viewer ? viewer.imageryLayers : null;
      const base = layers && layers.length ? layers.get(0) : null;
      return {
        loaded: state.cameras.length,
        drawn: state.points ? state.points.length : 0,
        packs: state.packs.length,
        hidden: Array.from(state.hidden),
        tokenApplied: state.tokenApplied,
        // Imagery diagnostics: a layer that exists but is not attached to the
        // globe is the difference between "tiles load" and "the globe shows them".
        imageryLayers: layers ? layers.length : 0,
        imageryUrl: base && base.imageryProvider ? base.imageryProvider.url : null,
        imageryShow: base ? base.show : null,
        imageryReady: base ? base.ready : null,
        globeTilesLoaded: viewer ? viewer.scene.globe.tilesLoaded : null,
        cameraHeightKm: viewer
          ? Math.round(viewer.camera.positionCartographic.height / 1000)
          : null,
        thumbnails: state.thumbs ? state.thumbs.byId.size : 0,
      };
    },
  };

  const resetButton = document.getElementById('reset');
  if (resetButton) resetButton.addEventListener('click', () => window.GEVLite.resetView());

  window.addEventListener('error', (event) => log('js error: ' + event.message));

  boot();
})();
