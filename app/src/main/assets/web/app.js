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
    finland: '#8be0ff',
    norway: '#b8a6ff',
    hongkong: '#ff9f6b',
    drivebc: '#9ee37d',
    nsw: '#ffe066',
    a22: '#ff8f8f',
    deldot: '#ff6b6b',
    california: '#ffb84d',
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
    // Cards only need to move when the camera is still; they are hidden while
    // it moves, so one layout per second is enough for refreshes.
    layoutMs: 1000,
  };

  /**
   * Motion quality profile. Cesium's FXAA is expensive on a phone GPU when the
   * globe fills the screen; keep it for globe scale and drop it at city scale.
   * While the camera is moving, also render at a lower resolution, load fewer
   * globe tiles and hide the thumbnail cards. Stills return to full quality
   * once the camera settles.
   */
  const QUALITY = {
    idleDelayMs: 220,
    fxaaMinAltitudeM: 500_000,
    movingSse: 5,
    idleSse: 3,
    movingResolutionScale: 0.8,
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
    camerasOn: true,
    cameraType: 'all', // 'all' | 'still' | 'live'
    planesOn: true,
    satellitesOn: true,
    cameraMoving: false,
    lastCullCamera: null,
    // Shared lock-on/chase state. Target objects are supplied by the flights
    // and satellites layers and expose getCartesian/getHeading/getBack/getUp.
    chase: { active: false, id: null, target: null, listener: null, pointer: null },
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

  /**
   * Whether a camera belongs in the current layer/filter. Pack chips, the
   * camera switch and the type buttons all reduce the same set, so the pins,
   * thumbnails and count cannot disagree.
   */
  function cameraMatches(camera) {
    if (!state.camerasOn) return false;
    if (state.hidden.has(camera.pack)) return false;
    if (!isFinite(camera.lat) || !isFinite(camera.lon)) return false;
    if (state.cameraType === 'live' && !camera.videoUrl) return false;
    if (state.cameraType === 'still' && camera.videoUrl) return false;
    return true;
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
    // The deep space tone shows while tiles are still streaming and through
    // any imagery gaps; the stock mid-blue reads as a loading artifact.
    viewer.scene.globe.baseColor = Cesium.Color.fromCssColorString('#08101c');
    // FXAA is kept for globe scale only; applyQuality() disables it at city
    // scale, where the post-process costs more than it adds on a phone GPU.
    viewer.scene.postProcessStages.fxaa.enabled = true;
    // Cesium's default sun light turns the 3D aircraft into black silhouettes
    // whenever the view is on the night side of the planet. A directional
    // light that follows the camera keeps the models readable at any hour.
    const modelLight = new Cesium.DirectionalLight({
      direction: new Cesium.Cartesian3(0, 0, -1),
    });
    // Replacing scene.light is global. The globe is unlit above, and pinning
    // the atmosphere to its fixed overhead light keeps both independent of the
    // camera light; only the 3D aircraft should use it.
    viewer.scene.globe.dynamicAtmosphereLighting = false;
    viewer.scene.light = modelLight;
    // Cesium's stock zoom is slow at planetary scale: measured on a phone, one
    // pinch from 12,000 km only reached ~3,900 km, so a city took a dozen
    // pinches. At 10 the same pinch reaches ~1,100 km, i.e. about three to a
    // street — still fine-grained enough to control. Scaling this down near the
    // ground was tried and made zoom feel sluggish everywhere, so the fast
    // factor is deliberate; tilting is kept usable by letting the plane card
    // pass touches through instead.
    viewer.scene.screenSpaceCameraController.zoomFactor = 10;
    viewer.camera.setView(HOME_VIEW);

    await addBaseImagery(viewer);
    wirePicking(viewer);
    wireThumbnails(viewer);
    wireHudAutoHide();
    renderPackChips();
    renderLayers();
    wireLayers();
    updateCountLabel();

    // Let the atmosphere glow a little warmer against the dark ocean while
    // keeping the default physical scattering.
    viewer.scene.skyAtmosphere.brightnessShift = 0.05;
    viewer.scene.skyAtmosphere.saturationShift = 0.08;

    const lightDirection = new Cesium.Cartesian3();
    viewer.scene.preRender.addEventListener(() => {
      // Negating the camera position aims the light from the viewer's side of
      // the globe, so the tops of the aircraft are lit from above.
      Cesium.Cartesian3.negate(viewer.camera.positionWC, lightDirection);
      Cesium.Cartesian3.normalize(lightDirection, lightDirection);
      modelLight.direction = lightDirection;
    });

    // Camera motion sets the quality profile: FXAA is skipped at city scale
    // and while moving; tiles and resolution drop only during movement.
    const motion = {
      initialized: false,
      lastPosition: new Cesium.Cartesian3(),
      lastDirection: new Cesium.Cartesian3(),
      lastMoveMs: 0,
    };

    function applyQuality() {
      const scene = viewer.scene;
      const moving = state.cameraMoving;
      const altitude = viewer.camera.positionCartographic.height;
      scene.postProcessStages.fxaa.enabled =
        !moving && altitude > QUALITY.fxaaMinAltitudeM;
      scene.globe.maximumScreenSpaceError = moving
        ? QUALITY.movingSse
        : QUALITY.idleSse;
      viewer.resolutionScale = moving ? QUALITY.movingResolutionScale : 1.0;
      const host = document.getElementById('thumbs');
      if (host) host.classList.toggle('moving', moving);
      if (state.thumbs) state.thumbs.lastLayoutMs = 0;
    }

    applyQuality();

    viewer.scene.preRender.addEventListener(() => {
      const camera = viewer.camera;
      if (!motion.initialized) {
        Cesium.Cartesian3.clone(camera.positionWC, motion.lastPosition);
        Cesium.Cartesian3.clone(camera.directionWC, motion.lastDirection);
        motion.initialized = true;
        return;
      }
      // A chase camera moves every frame by design. Treat it as a still view so
      // the tracked model and the globe around it keep full quality.
      if (state.chase.active) {
        Cesium.Cartesian3.clone(camera.positionWC, motion.lastPosition);
        Cesium.Cartesian3.clone(camera.directionWC, motion.lastDirection);
        if (state.cameraMoving) {
          state.cameraMoving = false;
          applyQuality();
        }
        return;
      }
      const moved =
        !Cesium.Cartesian3.equalsEpsilon(
          camera.positionWC,
          motion.lastPosition,
          0.0,
          0.05,
        ) ||
        !Cesium.Cartesian3.equalsEpsilon(
          camera.directionWC,
          motion.lastDirection,
          1e-7,
          0.0,
        );
      if (moved) {
        Cesium.Cartesian3.clone(camera.positionWC, motion.lastPosition);
        Cesium.Cartesian3.clone(camera.directionWC, motion.lastDirection);
        motion.lastMoveMs = performance.now();
        if (!state.cameraMoving) {
          state.cameraMoving = true;
          applyQuality();
        }
      } else if (
        state.cameraMoving &&
        performance.now() - motion.lastMoveMs >= QUALITY.idleDelayMs
      ) {
        state.cameraMoving = false;
        applyQuality();
      }
    });

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
          // Photogrammetry is single-sided: entering a roof makes the walls
          // vanish and the building look flat. Keep the camera on its surface.
          enableCollision: true,
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
    state.lastCullCamera = null;
    const collection = new Cesium.PointPrimitiveCollection();
    for (const camera of state.cameras) {
      if (!cameraMatches(camera)) continue;
      const position = Cesium.Cartesian3.fromDegrees(camera.lon, camera.lat);
      state.positions.set(camera.id, position);
      const pointColor = Cesium.Color.fromCssColorString(colorFor(camera.pack));
      const primitive = collection.add({
        position,
        pixelSize: 7,
        // At world view thousands of pins merge into dense clusters. Shrinking
        // them with distance keeps the city-scale view crisp, and a dark shade
        // of each pack's own colour as the outline stops those clusters from
        // turning into black masses.
        scaleByDistance: new Cesium.NearFarScalar(150_000, 1.0, 9_000_000, 0.42),
        color: pointColor,
        outlineColor: new Cesium.Color(
          pointColor.red * 0.32,
          pointColor.green * 0.32,
          pointColor.blue * 0.32,
          1.0,
        ),
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
      state.pointRecords.push({
        primitive,
        position,
        positionSquared: Cesium.Cartesian3.magnitudeSquared(position),
      });
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
    const eye = viewer.camera.positionWC;
    // The camera position alone decides which points are over the horizon;
    // skip the loop entirely while the camera is still. This used to rebuild
    // an EllipsoidalOccluder and touch all 12k pins every 120 ms.
    if (state.lastCullCamera && Cesium.Cartesian3.equals(eye, state.lastCullCamera)) {
      return;
    }
    if (!state.lastCullCamera) state.lastCullCamera = new Cesium.Cartesian3();
    Cesium.Cartesian3.clone(eye, state.lastCullCamera);
    for (const record of state.pointRecords) {
      const visible =
        Cesium.Cartesian3.dot(record.position, eye) >= record.positionSquared;
      if (record.primitive.show !== visible) record.primitive.show = visible;
    }
  }

  /** Tells the user how many of the loaded cameras are currently drawn. */
  function updateCountLabel() {
    const hint = document.getElementById('hint');
    if (!hint) return;
    const total = state.cameras.length;
    const shown = state.points ? state.points.length : 0;
    if (total === 0) {
      hint.textContent = 'Loading cameras…';
      return;
    }
    if (!state.camerasOn) {
      hint.textContent = 'Cameras hidden';
      return;
    }
    if (shown === 0) {
      hint.textContent = 'No cameras match this filter';
      return;
    }
    const scope =
      state.cameraType === 'live'
        ? ' live cameras'
        : state.cameraType === 'still'
          ? ' still cameras'
          : ' of ' + total + ' cameras';
    hint.textContent = shown + scope + ' shown  ·  tap a dot or thumbnail';
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
    state.thumbs = { host, byId: new Map(), lastLayoutMs: 0, lastCullMs: 0 };
    // postRender, not a timer: the cards have to move with the globe. The
    // horizon cull runs at ~8 Hz so far-side pins cannot shine through while
    // the camera moves; the much more expensive card layout only runs when the
    // camera is still, at 1 Hz.
    viewer.scene.postRender.addEventListener(() => {
      const now = performance.now();
      if (now - state.thumbs.lastCullMs >= 120) {
        state.thumbs.lastCullMs = now;
        // Driven from here rather than camera.moveEnd because flyTo with a
        // zero duration (RESET VIEW, and the test hook) never raises moveEnd,
        // and a stale cull leaves the globe oddly empty.
        refreshHorizonCulling();
      }
      if (state.cameraMoving || state.chase.active) return;
      if (now - state.thumbs.lastLayoutMs < THUMB.layoutMs) return;
      state.thumbs.lastLayoutMs = now;
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
    if (!thumbs || state.cameraMoving) return;
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
      if (!cameraMatches(camera)) continue;
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

    // DelDOT publishes live video with no still frame, so its card is a name
    // bar with a LIVE badge instead of an empty photo.
    if (camera.videoUrl) {
      card.dataset.live = '1';
      const live = document.createElement('div');
      live.className = 'thumb-live';
      live.textContent = 'LIVE';
      card.appendChild(live);
    }

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
  /** Set by wireHudAutoHide so the layers panel can keep the HUD awake. */
  let wakeHud = () => {};

  function wireHudAutoHide() {
    const hud = document.getElementById('hud');
    if (!hud) return;

    const IDLE_MS = 4000;
    let timer = 0;

    const wake = () => {
      hud.classList.remove('idle');
      clearTimeout(timer);
      timer = setTimeout(() => {
        const panel = document.getElementById('layersPanel');
        if (panel && !panel.hidden) return;
        hud.classList.add('idle');
      }, IDLE_MS);
    };

    for (const type of ['pointerdown', 'pointermove', 'wheel', 'touchstart', 'keydown']) {
      window.addEventListener(type, wake, { passive: true });
    }
    wakeHud = wake;
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

  function renderLayers() {
    const cameras = document.getElementById('layerCameras');
    const planes = document.getElementById('layerPlanes');
    const satellites = document.getElementById('layerSatellites');
    if (cameras) cameras.setAttribute('aria-pressed', String(state.camerasOn));
    if (planes) planes.setAttribute('aria-pressed', String(state.planesOn));
    if (satellites) satellites.setAttribute('aria-pressed', String(state.satellitesOn));
    for (const button of document.querySelectorAll('#layerType button')) {
      button.classList.toggle('on', button.dataset.type === state.cameraType);
    }
  }

  function setCamerasOn(on) {
    state.camerasOn = !!on;
    renderLayers();
    clearThumbs();
    rebuildPoints();
  }

  function setCameraType(type) {
    if (type !== 'all' && type !== 'still' && type !== 'live') return;
    state.cameraType = type;
    renderLayers();
    clearThumbs();
    rebuildPoints();
  }

  function wireLayers() {
    const panel = document.getElementById('layersPanel');
    const button = document.getElementById('layersBtn');
    if (!panel || !button) return;

    const setOpen = (open) => {
      panel.hidden = !open;
      button.setAttribute('aria-expanded', String(open));
      wakeHud();
    };

    button.addEventListener('click', () => setOpen(panel.hidden));
    const close = document.getElementById('layersClose');
    if (close) close.addEventListener('click', () => setOpen(false));

    const cameras = document.getElementById('layerCameras');
    if (cameras) cameras.addEventListener('click', () => setCamerasOn(!state.camerasOn));

    const planes = document.getElementById('layerPlanes');
    if (planes) {
      planes.addEventListener('click', () => {
        state.planesOn = !state.planesOn;
        renderLayers();
        const api = window.GEVLite;
        if (api && typeof api.setFlightsEnabled === 'function') {
          api.setFlightsEnabled(state.planesOn);
        }
      });
    }

    const satellites = document.getElementById('layerSatellites');
    if (satellites) {
      satellites.addEventListener('click', () => {
        state.satellitesOn = !state.satellitesOn;
        renderLayers();
        const api = window.GEVLite;
        if (api && typeof api.setSatellitesEnabled === 'function') {
          api.setSatellitesEnabled(state.satellitesOn);
        }
      });
    }

    for (const typeButton of document.querySelectorAll('#layerType button')) {
      typeButton.addEventListener('click', () => setCameraType(typeButton.dataset.type));
    }
  }

  function fail(message) {
    const boot = document.getElementById('boot');
    if (boot) boot.textContent = message;
    log(message);
  }

  // ── Lock-on / chase ────────────────────────────────────────────────────────

  const chaseScratch = {
    transform: new Cesium.Matrix4(),
    offset: new Cesium.Cartesian3(),
    camera: new Cesium.Cartesian3(),
    direction: new Cesium.Cartesian3(),
    right: new Cesium.Cartesian3(),
    up4: new Cesium.Cartesian4(),
    up: new Cesium.Cartesian3(),
  };

  /**
   * Places the camera behind and above a moving target, using the target's
   * local east/north/up frame. This avoids Camera.lookAt's locked transform and
   * the jump that follows when the transform is released: any globe touch can
   * simply stop the preRender updates and leave the camera where it is.
   */
  function chaseFrame() {
    const chase = state.chase;
    const target = chase.target;
    if (!chase.active || !target || !state.viewer) return;

    let position = null;
    try {
      position = target.getCartesian ? target.getCartesian() : null;
    } catch (error) {
      position = null;
    }
    if (!Cesium.defined(position)) return;

    const heading = (target.getHeading && target.getHeading()) || 0;
    const back = (target.getBack && target.getBack()) || 1000;
    const up = (target.getUp && target.getUp()) || 300;
    const transform = Cesium.Transforms.eastNorthUpToFixedFrame(
      position,
      Cesium.Ellipsoid.WGS84,
      chaseScratch.transform,
    );
    chaseScratch.offset.x = -Math.sin(heading) * back;
    chaseScratch.offset.y = -Math.cos(heading) * back;
    chaseScratch.offset.z = up;
    const cameraPosition = Cesium.Matrix4.multiplyByPoint(
      transform,
      chaseScratch.offset,
      chaseScratch.camera,
    );
    const direction = Cesium.Cartesian3.subtract(
      position,
      cameraPosition,
      chaseScratch.direction,
    );
    Cesium.Cartesian3.normalize(direction, direction);
    const upWorld = Cesium.Matrix4.getColumn(transform, 2, chaseScratch.up4);
    Cesium.Cartesian3.fromCartesian4(upWorld, chaseScratch.up);
    Cesium.Cartesian3.normalize(chaseScratch.up, chaseScratch.up);

    // A camera's up vector must be perpendicular to its view direction. Local
    // up is not, especially when looking down at a satellite; using it directly
    // flipped the camera away from Earth. Build a stable right/up basis.
    const right = Cesium.Cartesian3.cross(direction, chaseScratch.up, chaseScratch.right);
    Cesium.Cartesian3.normalize(right, right);
    const cameraUp = Cesium.Cartesian3.cross(right, direction, chaseScratch.up);
    Cesium.Cartesian3.normalize(cameraUp, cameraUp);
    state.viewer.camera.setView({
      destination: cameraPosition,
      orientation: { direction, up: cameraUp },
    });
  }

  function startChase(target) {
    if (!state.viewer || !target || typeof target.getCartesian !== 'function') return false;
    if (state.chase.active) stopChase();
    state.chase.active = true;
    state.chase.id = target.id || null;
    state.chase.target = target;
    state.chase.listener = chaseFrame;
    state.viewer.scene.preRender.addEventListener(chaseFrame);
    const thumbs = document.getElementById('thumbs');
    if (thumbs) thumbs.classList.add('tracking');
    state.chase.pointer = () => stopChase();
    state.viewer.scene.canvas.addEventListener('pointerdown', state.chase.pointer, {
      capture: true,
      passive: true,
    });
    chaseFrame();
    log('chase: ' + (target.name || target.id || 'target'));
    return true;
  }

  function stopChase() {
    const chase = state.chase;
    if (!chase.active) return;
    if (state.viewer && chase.listener) {
      state.viewer.scene.preRender.removeEventListener(chase.listener);
    }
    if (state.viewer && chase.pointer) {
      state.viewer.scene.canvas.removeEventListener('pointerdown', chase.pointer, {
        capture: true,
      });
    }
    const thumbs = document.getElementById('thumbs');
    if (thumbs) thumbs.classList.remove('tracking');
    chase.active = false;
    chase.id = null;
    chase.target = null;
    chase.listener = null;
    chase.pointer = null;
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
    setCameraLayer(on) {
      setCamerasOn(!!on);
    },
    setCameraFilter(type) {
      setCameraType(type);
    },
    resetView() {
      stopChase();
      if (!state.viewer) return;
      const camera = state.viewer.camera;
      const p = camera.pitch;
      const h = camera.heading;
      const tilted = Math.abs(p - (-Math.PI / 2)) > 0.01;
      const rotated = Math.abs(h) > 0.01 && Math.abs(h - 2 * Math.PI) > 0.01;
      if (tilted || rotated) {
        camera.flyTo({
          destination: camera.positionWC,
          orientation: {
            heading: 0,
            pitch: -Math.PI / 2,
            roll: 0
          },
          duration: 1.0
        });
      } else {
        camera.flyTo(HOME_VIEW);
      }
    },
    getViewer() {
      return state.viewer;
    },
    isMoving() {
      return state.cameraMoving;
    },
    /** Shared lock-on camera used by the flights and satellites layers. */
    startChase(target) {
      return startChase(target);
    },
    stopChase() {
      stopChase();
    },
    isChasing(id) {
      return !!(state.chase.active && (!id || state.chase.id === id));
    },
    chaseStats() {
      return { active: state.chase.active, id: state.chase.id };
    },
    /**
     * Fly to a point. Not used by the UI — it is the hook the device test does
     * its screenshot checks through, where dragging the globe is impractical.
     */
    flyTo(lat, lon, height) {
      stopChase();
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
        camerasOn: state.camerasOn,
        cameraType: state.cameraType,
        satellitesOn: state.satellitesOn,
        liveCameras: state.cameras.filter((camera) => !!camera.videoUrl).length,
        stillCameras: state.cameras.filter((camera) => !camera.videoUrl).length,
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
        chasing: state.chase.active,
        chaseTarget: state.chase.id,
      };
    },
  };

  const resetButton = document.getElementById('reset');
  if (resetButton) resetButton.addEventListener('click', () => window.GEVLite.resetView());

  const locateButton = document.getElementById('locate');
  if (locateButton) {
    locateButton.addEventListener('click', () => {
      if (!navigator.geolocation) {
        log('geolocation not supported by browser');
        return;
      }
      navigator.geolocation.getCurrentPosition(
        (pos) => {
          if (!state.viewer) return;
          state.viewer.camera.flyTo({
            destination: Cesium.Cartesian3.fromDegrees(
              pos.coords.longitude,
              pos.coords.latitude,
              4000 // A nice height to see the local area
            ),
            orientation: {
              heading: 0.0,
              pitch: -Math.PI / 2.0,
              roll: 0.0
            },
            duration: 1.5
          });
        },
        (err) => {
          log('geolocation error: ' + err.message);
        },
        { enableHighAccuracy: true, timeout: 5000, maximumAge: 0 }
      );
    });
  }

  window.addEventListener('error', (event) => log('js error: ' + event.message));

  boot();
})();
