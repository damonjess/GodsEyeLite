/**
 * God's Eye Lite — live flights layer.
 *
 * Loaded after app.js. It needs one thing from it: GEVLite.getViewer().
 *
 *  - Kotlin owns the network (FlightRepository.kt). This file asks for a fetch
 *    around whatever the camera is looking at via GEVAndroid.requestFlights(),
 *    and receives the result in GEVLite.setFlights().
 *  - Planes are drawn at their real altitude (Cesium positions take metres).
 *    Far away they are airliner-shaped icons (a BillboardCollection) tinted by
 *    height: green low -> blue -> violet high. The nearest few, within about
 *    30 km of the camera, swap to a real 3D airliner model (plane.glb).
 *  - Between polls each plane is dead-reckoned along its heading, so it glides
 *    instead of jumping every ten seconds.
 *  - The nearest planes get tags: an airline chip plus callsign and altitude.
 *  - Tapping a plane shows an info card (airline, aircraft photo, route), a drop
 *    line to the ground, and a ribbon trail built from positions seen while
 *    you were watching. Photo and route are fetched by Kotlin on demand.
 *
 * Everything here is wrapped so that a failure logs and disables the layer
 * rather than taking the camera pins down with it.
 */
(() => {
  'use strict';

  const CFG = {
    pollMs: 10_000,
    minGapMs: 4_000,
    moveDebounceMs: 800,
    // Above this camera height a query would cover most of a continent and the
    // aggregator caps radius at 400 nm anyway, so ask the user to zoom in.
    maxViewHeightM: 6_000_000,
    minRadiusNm: 30,
    maxRadiusNm: 400,
    maxDeadReckonS: 30,
    stepMs: 200,
    trailMax: 60,
    iconPx: 36,
    iconCanvasPx: 128,
    // Tags (airline chip + callsign/altitude label) go on the nearest planes
    // only, and only when the camera is low enough for them to be readable.
    tagMax: 30,
    tagMaxViewM: 700_000,
    // 3D model level of detail. A small pool of models is re-assigned to
    // whichever planes are nearest the camera, so cost stays flat however many
    // aircraft are in range.
    modelUrl: 'plane.glb',
    modelMax: 50,
    modelRangeM: 100_000,
    modelMinPixels: 40,
    // plane.glb faces +Z (glTF's front). Cesium renders that along the local
    // east axis at heading 0, hence -90 degrees. If planes fly sideways or
    // backwards on a device, this is the one number to change (try 0, PI/2, PI).
    modelHeadingOffset: -Math.PI / 2,
  };

  const EARTH_R = 6_371_000;
  const FT = 0.3048;
  const KT = 0.514444;

  const state = {
    viewer: null,
    enabled: true,
    billboards: null,
    byHex: new Map(),
    selectedHex: null,
    trailPrimitive: null,
    wallPrimitive: null,
    dropLines: null,
    dropLine: null,
    pool: [],
    chips: null,
    labels: null,
    details: new Map(),
    lastReqMs: 0,
    lastStepMs: 0,
    pollTimer: null,
    moveTimer: null,
    statusNote: '',
    ui: {},
  };

  const scratch = new Cesium.Cartesian3();

  function bridge() {
    return typeof window.GEVAndroid === 'object' ? window.GEVAndroid : null;
  }

  function log(message) {
    const api = bridge();
    if (api && typeof api.log === 'function') api.log('flights: ' + message);
  }

  // ── Airlines, aircraft types, formatting ───────────────────────────────────

  // ICAO airline code (first three letters of the callsign) -> name, short chip
  // text, brand-ish colour. Plain text chips: no logos are fetched or bundled.
  const AIRLINES = {
    BAW: ['British Airways', 'British', '#1d3f8f'], CFE: ['BA CityFlyer', 'CityFlyer', '#1d3f8f'],
    EZY: ['easyJet', 'easyJet', '#ff6600'], EJU: ['easyJet Europe', 'easyJet', '#ff6600'],
    EZS: ['easyJet Switzerland', 'easyJet', '#ff6600'], RYR: ['Ryanair', 'Ryanair', '#073590'],
    RUK: ['Ryanair UK', 'Ryanair', '#073590'], VIR: ['Virgin Atlantic', 'Virgin', '#c8102e'],
    EXS: ['Jet2', 'Jet2', '#d5192a'], TOM: ['TUI Airways', 'TUI', '#d40e14'],
    LOG: ['Loganair', 'Loganair', '#1b5faa'], EIN: ['Aer Lingus', 'Aer Lingus', '#008a5e'],
    UAL: ['United Airlines', 'United', '#1f4aa8'], AAL: ['American Airlines', 'American', '#0078d2'],
    DAL: ['Delta Air Lines', 'Delta', '#c01933'], SWA: ['Southwest Airlines', 'Southwest', '#304cb2'],
    JBU: ['JetBlue', 'JetBlue', '#0a4aa0'], ASA: ['Alaska Airlines', 'Alaska', '#01426a'],
    ACA: ['Air Canada', 'Air Canada', '#d2232a'], WJA: ['WestJet', 'WestJet', '#00a3ad'],
    DLH: ['Lufthansa', 'Lufthansa', '#0c1e5b'], AFR: ['Air France', 'Air France', '#1a2f66'],
    KLM: ['KLM', 'KLM', '#00a1de'], SWR: ['SWISS', 'Swiss', '#d52b1e'],
    AUA: ['Austrian', 'Austrian', '#d6001c'], BEL: ['Brussels Airlines', 'Brussels', '#0a2f6a'],
    IBE: ['Iberia', 'Iberia', '#d7192d'], VLG: ['Vueling', 'Vueling', '#ffcc00'],
    TAP: ['TAP Air Portugal', 'TAP', '#d4003a'], ITY: ['ITA Airways', 'ITA', '#0066b3'],
    WZZ: ['Wizz Air', 'Wizz Air', '#c6007e'], NAX: ['Norwegian', 'Norwegian', '#d81939'],
    SAS: ['SAS', 'SAS', '#1d2b6e'], FIN: ['Finnair', 'Finnair', '#0b1560'],
    LOT: ['LOT Polish', 'LOT', '#0b2a6f'], AEE: ['Aegean', 'Aegean', '#0a2a5e'],
    ICE: ['Icelandair', 'Icelandair', '#0f4c81'], CSA: ['Czech Airlines', 'Czech', '#d6001c'],
    THY: ['Turkish Airlines', 'Turkish', '#c8102e'], UAE: ['Emirates', 'Emirates', '#d71920'],
    QTR: ['Qatar Airways', 'Qatar', '#5c0632'], ETD: ['Etihad', 'Etihad', '#b08d57'],
    SVA: ['Saudia', 'Saudia', '#006c35'], ELY: ['El Al', 'El Al', '#0c5faa'],
    MSR: ['EgyptAir', 'EgyptAir', '#1b3a8a'], ETH: ['Ethiopian', 'Ethiopian', '#0b7a3b'],
    AIC: ['Air India', 'Air India', '#d71920'], SIA: ['Singapore Airlines', 'Singapore', '#f2a900'],
    CPA: ['Cathay Pacific', 'Cathay', '#00685a'], QFA: ['Qantas', 'Qantas', '#e0001b'],
    ANA: ['ANA', 'ANA', '#1b3a9a'], JAL: ['Japan Airlines', 'JAL', '#c8102e'],
    KAL: ['Korean Air', 'Korean Air', '#0079c1'], CCA: ['Air China', 'Air China', '#d4001a'],
    CES: ['China Eastern', 'China Eastern', '#1c3f94'], CSN: ['China Southern', 'China Southern', '#0f6db3'],
    FDX: ['FedEx', 'FedEx', '#4d148c'], UPS: ['UPS', 'UPS', '#351c15'],
    DHK: ['DHL', 'DHL', '#ffcc00'], BCS: ['DHL (EAT)', 'DHL', '#ffcc00'],
    CLX: ['Cargolux', 'Cargolux', '#0a3a7a'], GTI: ['Atlas Air', 'Atlas', '#1f3b6b'],
    RRR: ['Royal Air Force', 'RAF', '#1b3a6b'],
  };
  const MIL_CHIP = ['Military', 'MIL', '#b3202a'];

  /** Airline entry {n, s, c} for a callsign like "BAW123", or null. */
  function airlineOf(callsign, military) {
    const m = /^([A-Z]{3})\d/.exec(callsign || '');
    const hit = m && AIRLINES[m[1]];
    const entry = hit || (military ? MIL_CHIP : null);
    return entry ? { n: entry[0], s: entry[1], c: entry[2] } : null;
  }

  const TYPES = {
    A318: 'Airbus A318', A319: 'Airbus A319', A320: 'Airbus A320', A321: 'Airbus A321',
    A20N: 'Airbus A320neo', A21N: 'Airbus A321neo', A19N: 'Airbus A319neo',
    A332: 'Airbus A330-200', A333: 'Airbus A330-300', A338: 'Airbus A330-800', A339: 'Airbus A330-900',
    A343: 'Airbus A340-300', A346: 'Airbus A340-600', A359: 'Airbus A350-900', A35K: 'Airbus A350-1000',
    A388: 'Airbus A380-800', B712: 'Boeing 717', B737: 'Boeing 737', B738: 'Boeing 737-800',
    B739: 'Boeing 737-900', B37M: 'Boeing 737 MAX 7', B38M: 'Boeing 737 MAX 8', B39M: 'Boeing 737 MAX 9',
    B744: 'Boeing 747-400', B748: 'Boeing 747-8', B752: 'Boeing 757-200', B753: 'Boeing 757-300',
    B763: 'Boeing 767-300', B764: 'Boeing 767-400', B772: 'Boeing 777-200', B77L: 'Boeing 777-200LR',
    B77W: 'Boeing 777-300ER', B788: 'Boeing 787-8', B789: 'Boeing 787-9', B78X: 'Boeing 787-10',
    E170: 'Embraer 170', E75L: 'Embraer 175', E75S: 'Embraer 175', E190: 'Embraer 190', E195: 'Embraer 195',
    E290: 'Embraer E190-E2', E295: 'Embraer E195-E2', CRJ7: 'CRJ-700', CRJ9: 'CRJ-900', CRJX: 'CRJ-1000',
    DH8D: 'Dash 8 Q400', AT72: 'ATR 72', AT76: 'ATR 72-600', AT45: 'ATR 42', SF34: 'Saab 340',
    BCS1: 'Airbus A220-100', BCS3: 'Airbus A220-300', C172: 'Cessna 172', C25A: 'Citation CJ2',
    PC12: 'Pilatus PC-12', GLF6: 'Gulfstream G650', H25B: 'Hawker 800', A400: 'Airbus A400M',
    C130: 'C-130 Hercules', C17: 'C-17 Globemaster', EUFI: 'Eurofighter Typhoon', F35: 'F-35 Lightning II',
  };
  const typeName = (code) => TYPES[code] || code || '';

  /** FL for high altitudes, feet below that. */
  function altText(meters) {
    const ft = meters / FT;
    return ft >= 10000
      ? 'FL' + String(Math.round(ft / 100)).padStart(3, '0')
      : (Math.round(ft / 100) * 100).toLocaleString() + ' ft';
  }

  function greatCircleM(lat1, lon1, lat2, lon2) {
    const rad = Math.PI / 180;
    const a =
      Math.sin(((lat2 - lat1) * rad) / 2) ** 2 +
      Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(((lon2 - lon1) * rad) / 2) ** 2;
    return 2 * EARTH_R * Math.asin(Math.min(1, Math.sqrt(a)));
  }

  function textColorOn(hex) {
    const n = parseInt(hex.slice(1), 16);
    const lum = (0.299 * (n >> 16) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) / 255;
    return lum > 0.62 ? '#111111' : '#ffffff';
  }

  // ── Altitude colour ramp ───────────────────────────────────────────────────

  const ALT_STOPS = [
    { m: 0, c: Cesium.Color.fromCssColorString('#3ddc84') },
    { m: 3000, c: Cesium.Color.fromCssColorString('#27d3c3') },
    { m: 7000, c: Cesium.Color.fromCssColorString('#3d8bff') },
    { m: 11000, c: Cesium.Color.fromCssColorString('#a64dff') },
  ];

  function altColor(meters) {
    if (meters <= ALT_STOPS[0].m) return ALT_STOPS[0].c.clone();
    for (let i = 1; i < ALT_STOPS.length; i++) {
      if (meters <= ALT_STOPS[i].m) {
        const a = ALT_STOPS[i - 1];
        const b = ALT_STOPS[i];
        return Cesium.Color.lerp(a.c, b.c, (meters - a.m) / (b.m - a.m), new Cesium.Color());
      }
    }
    return ALT_STOPS[ALT_STOPS.length - 1].c.clone();
  }

  // ── Detailed top-down aircraft icons (Plane Finder style) ─────────────────

  const iconCache = new Map();
  const QUAD_TYPES = new Set(['A388', 'B744', 'B748', 'A343', 'A346', 'C17', 'A400', 'C130']);
  const PROP_TYPES = new Set(['DH8D', 'AT72', 'AT76', 'AT45', 'SF34', 'C172', 'PC12']);
  const FIGHTER_TYPES = new Set(['EUFI', 'F35']);

  function shapeKindOf(type) {
    if (FIGHTER_TYPES.has(type)) return 'fighter';
    if (QUAD_TYPES.has(type)) return 'quad';
    if (PROP_TYPES.has(type)) return 'prop';
    return 'twin';
  }

  function traceSymmetric(g, rightPts) {
    const pts = rightPts.concat(rightPts.slice(1, -1).reverse().map(([x, y]) => [-x, y]));
    g.beginPath();
    pts.forEach(([x, y], i) => (i ? g.lineTo(x, y) : g.moveTo(x, y)));
    g.closePath();
  }

  function drawEngineNacelle(g, x, y, w, h) {
    for (const s of [-1, 1]) {
      const cx = s * x;
      // Engine pylon shadow/connector
      g.fillStyle = '#5c6675';
      g.fillRect(cx - w * 0.28, y + h * 0.2, w * 0.56, h * 0.65);

      // Metallic nacelle cowling
      const grad = g.createLinearGradient(cx - w / 2, y, cx + w / 2, y);
      grad.addColorStop(0, '#6a7484');
      grad.addColorStop(0.42, '#eef2f7');
      grad.addColorStop(0.65, '#c4cdd9');
      grad.addColorStop(1, '#596373');
      g.fillStyle = grad;
      g.strokeStyle = 'rgba(15, 20, 28, 0.85)';
      g.lineWidth = 1.4;
      g.beginPath();
      if (g.roundRect) {
        g.roundRect(cx - w / 2, y - h / 2, w, h, w * 0.38);
      } else {
        g.rect(cx - w / 2, y - h / 2, w, h);
      }
      g.fill();
      g.stroke();

      // Dark turbofan intake lip at the leading edge
      g.fillStyle = '#1b222c';
      g.fillRect(cx - w * 0.36, y - h / 2 + 0.8, w * 0.72, 2.6);
    }
  }

  function makePlaneIcon(kind, accent, selected) {
    const key = `${kind}:${accent}:${selected ? 1 : 0}`;
    if (iconCache.has(key)) return iconCache.get(key);

    const size = CFG.iconCanvasPx;
    const canvas = document.createElement('canvas');
    canvas.width = size;
    canvas.height = size;
    const g = canvas.getContext('2d');
    g.translate(size / 2, size / 2);
    g.lineJoin = 'round';
    g.lineCap = 'round';

    // Subtle selection halo so a tapped aircraft stands out without recolouring it.
    if (selected) {
      const halo = g.createRadialGradient(0, 0, 14, 0, 0, 58);
      halo.addColorStop(0, 'rgba(53, 224, 200, 0.38)');
      halo.addColorStop(0.75, 'rgba(53, 224, 200, 0.14)');
      halo.addColorStop(1, 'rgba(53, 224, 200, 0)');
      g.fillStyle = halo;
      g.beginPath();
      g.arc(0, 0, 58, 0, Math.PI * 2);
      g.fill();
    }

    // Geometry profiles by aircraft family
    const isQuad = kind === 'quad';
    const isProp = kind === 'prop';
    const isFighter = kind === 'fighter';

    const wingRight = isFighter
      ? [
          [0, -18], [6, -12], [40, 16], [40, 25], [10, 22], [0, 24],
        ]
      : isProp
        ? [
            [0, -10], [48, -6], [50, -3], [50, 3], [48, 5], [14, 7], [0, 9],
          ]
        : isQuad
          ? [
              [0, -18], [16, -9], [56, 17], [58, 25], [55, 26], [22, 11], [7, 11], [0, 14],
            ]
          : [
              [0, -14], [14, -6], [52, 15], [54, 22], [51, 23], [20, 9], [6, 9], [0, 12],
            ];

    const tailRight = isFighter
      ? [
          [0, 24], [8, 28], [18, 42], [18, 47], [6, 44], [0, 45],
        ]
      : [
          [0, 31], [5, 34], [21, 45], [21, 50], [5, 46], [0, 47],
        ];

    const bodyRight = isFighter
      ? [
          [0, -50], [2.2, -42], [4.5, -26], [6.2, -6], [6.5, 22], [5.2, 42], [2.8, 48], [0, 50],
        ]
      : isQuad
        ? [
            [0, -54], [3.2, -51], [6.2, -42], [7.2, -26], [7.2, 16], [5.8, 36], [3.0, 49], [0, 53],
          ]
        : [
            [0, -52], [2.8, -49], [5.4, -40], [6.0, -24], [6.0, 16], [4.8, 35], [2.4, 48], [0, 52],
          ];

    // 1. Soft drop shadow underneath the whole aircraft so it separates from satellite imagery
    g.save();
    g.shadowColor = 'rgba(0, 0, 0, 0.65)';
    g.shadowBlur = 6;
    g.shadowOffsetY = 2;
    g.fillStyle = 'rgba(0, 0, 0, 0.35)';
    traceSymmetric(g, wingRight);
    g.fill();
    traceSymmetric(g, tailRight);
    g.fill();
    traceSymmetric(g, bodyRight);
    g.fill();
    g.restore();

    // 2. Under-wing turbofan engine nacelles
    if (!isFighter) {
      if (isQuad) {
        drawEngineNacelle(g, 19, -4, 7.2, 15);
        drawEngineNacelle(g, 35, 6, 6.6, 13.5);
      } else if (isProp) {
        drawEngineNacelle(g, 18, -4, 6.0, 14);
        // Subtle propeller disc blur at the nose of each turboprop nacelle
        g.strokeStyle = 'rgba(220, 230, 242, 0.55)';
        g.lineWidth = 1.6;
        for (const s of [-1, 1]) {
          g.beginPath();
          g.moveTo(s * 18 - 6, -12);
          g.lineTo(s * 18 + 6, -12);
          g.stroke();
        }
      } else {
        drawEngineNacelle(g, 19, -2, 7.4, 15);
      }
    }

    // 3. Swept main wings + control surface / flap panel lines
    const wingGrad = g.createLinearGradient(0, -18, 0, 26);
    wingGrad.addColorStop(0, '#eef2f7');
    wingGrad.addColorStop(0.45, '#d5dde8');
    wingGrad.addColorStop(1, '#aeb8c7');
    traceSymmetric(g, wingRight);
    g.fillStyle = wingGrad;
    g.fill();
    g.lineWidth = 1.8;
    g.strokeStyle = 'rgba(15, 22, 32, 0.88)';
    g.stroke();

    // Wing interior panel lines (leading-edge slats + trailing-edge flaps + spoiler boxes)
    if (!isFighter) {
      g.strokeStyle = 'rgba(55, 70, 92, 0.45)';
      g.lineWidth = 1.1;
      for (const s of [-1, 1]) {
        // Leading-edge slat seam
        g.beginPath();
        if (isProp) {
          g.moveTo(s * 8, -7);
          g.lineTo(s * 46, -3.5);
        } else {
          g.moveTo(s * 9, -6);
          g.lineTo(s * (isQuad ? 52 : 48), isQuad ? 17.5 : 15.5);
        }
        g.stroke();

        // Trailing-edge flap seam & flap track fairings
        g.beginPath();
        if (isProp) {
          g.moveTo(s * 8, 4);
          g.lineTo(s * 45, 2);
        } else {
          g.moveTo(s * 8, 6.5);
          g.lineTo(s * 21, 6.8);
          g.lineTo(s * (isQuad ? 51 : 47), isQuad ? 21 : 19);
        }
        g.stroke();

        // Flap track fairing ticks along trailing edge
        if (!isProp) {
          for (const [fx, fy] of [[14, 8.5], [25, 11.5], [36, 16]]) {
            g.beginPath();
            g.moveTo(s * fx, fy - 2.5);
            g.lineTo(s * fx, fy + 2.2);
            g.stroke();
          }
        }
      }

      // Livery-coloured winglets / sharklets at the wingtips
      g.fillStyle = accent;
      for (const s of [-1, 1]) {
        const tipX = isProp ? 48 : isQuad ? 55 : 51;
        const tipY = isProp ? -5 : isQuad ? 16.5 : 14.5;
        const tipH = isProp ? 8 : 8.5;
        g.beginPath();
        g.moveTo(s * tipX, tipY);
        g.lineTo(s * (tipX + 3), tipY + 2);
        g.lineTo(s * (tipX + 2), tipY + tipH);
        g.lineTo(s * (tipX - 1.5), tipY + tipH - 1.5);
        g.closePath();
        g.fill();
      }
    }

    // 4. Horizontal tailplanes + elevator seam line
    traceSymmetric(g, tailRight);
    g.fillStyle = wingGrad;
    g.fill();
    g.lineWidth = 1.6;
    g.strokeStyle = 'rgba(15, 22, 32, 0.88)';
    g.stroke();

    g.strokeStyle = 'rgba(55, 70, 92, 0.45)';
    g.lineWidth = 1.0;
    for (const s of [-1, 1]) {
      g.beginPath();
      g.moveTo(s * 4, 42);
      g.lineTo(s * 18, 46.5);
      g.stroke();
    }

    // 5. Cylindrical 3D-shaded fuselage
    const bodyW = isQuad ? 7.5 : 6.2;
    const bodyGrad = g.createLinearGradient(-bodyW, 0, bodyW, 0);
    bodyGrad.addColorStop(0, '#aeb9c8');
    bodyGrad.addColorStop(0.22, '#eef2f8');
    bodyGrad.addColorStop(0.45, '#ffffff');
    bodyGrad.addColorStop(0.72, '#e3e9f2');
    bodyGrad.addColorStop(1, '#9aa6b8');

    traceSymmetric(g, bodyRight);
    g.fillStyle = bodyGrad;
    g.fill();
    g.lineWidth = 1.8;
    g.strokeStyle = 'rgba(15, 22, 32, 0.9)';
    g.stroke();

    // Subtle wing-root fairing seams along the fuselage sides
    g.strokeStyle = 'rgba(120, 135, 155, 0.45)';
    g.lineWidth = 0.9;
    for (const s of [-1, 1]) {
      g.beginPath();
      g.moveTo(s * (bodyW - 1.2), -14);
      g.lineTo(s * (bodyW - 0.8), 12);
      g.stroke();
    }

    // Nose radome tip cap
    g.strokeStyle = 'rgba(80, 95, 115, 0.5)';
    g.lineWidth = 1.0;
    g.beginPath();
    g.arc(0, -47, 2.8, 0.15 * Math.PI, 0.85 * Math.PI);
    g.stroke();

    // 6. Multi-pane dark cockpit windscreen + glass highlight
    g.fillStyle = '#141e2b';
    g.beginPath();
    if (isFighter) {
      g.ellipse(0, -28, 2.3, 7.5, 0, 0, Math.PI * 2);
    } else {
      const cw = isQuad ? 4.6 : 4.0;
      const cy = isQuad ? -43 : -41;
      g.moveTo(0, cy - 3.2);
      g.quadraticCurveTo(cw, cy - 2.2, cw + 0.5, cy + 2.2);
      g.quadraticCurveTo(cw * 0.5, cy + 1.0, 0, cy + 1.3);
      g.quadraticCurveTo(-cw * 0.5, cy + 1.0, -(cw + 0.5), cy + 2.2);
      g.quadraticCurveTo(-cw, cy - 2.2, 0, cy - 3.2);
    }
    g.closePath();
    g.fill();

    // Cockpit center post & glass glint
    g.strokeStyle = 'rgba(210, 228, 248, 0.65)';
    g.lineWidth = 0.9;
    g.beginPath();
    g.moveTo(0, isQuad ? -46.2 : -44.2);
    g.lineTo(0, isQuad ? -41.5 : -39.5);
    g.stroke();

    // 7. Vertical tail fin spine (dorsal fairing) in airline livery colour
    const finGrad = g.createLinearGradient(-2.5, 28, 2.5, 28);
    finGrad.addColorStop(0, accent);
    finGrad.addColorStop(0.5, '#ffffff');
    finGrad.addColorStop(1, accent);

    g.fillStyle = accent;
    g.strokeStyle = 'rgba(15, 20, 28, 0.8)';
    g.lineWidth = 1.1;
    g.beginPath();
    g.moveTo(0, 26);
    g.lineTo(2.1, 33);
    g.lineTo(1.7, 50);
    g.lineTo(0, 52);
    g.lineTo(-1.7, 50);
    g.lineTo(-2.1, 33);
    g.closePath();
    g.fill();
    g.stroke();

    // Bright dorsal ridge highlight along the top of the tail fin
    g.strokeStyle = 'rgba(255, 255, 255, 0.65)';
    g.lineWidth = 0.9;
    g.beginPath();
    g.moveTo(0, 28);
    g.lineTo(0, 49);
    g.stroke();

    const url = canvas.toDataURL('image/png');
    iconCache.set(key, url);
    return url;
  }

  function iconFor(rec, selected) {
    const kind = rec.military && FIGHTER_TYPES.has(rec.type) ? 'fighter' : shapeKindOf(rec.type);
    const accent = rec.airline ? rec.airline.c : rec.military ? '#6e7b68' : '#d62828';
    return makePlaneIcon(kind, accent, selected);
  }

  // ── UI (injected, so index.html only needs the script tag) ─────────────────

  function injectUi() {
    const style = document.createElement('style');
    style.textContent = `
      #hud.idle #flightsToggle { opacity: 0; pointer-events: none; }
      #flightsToggle { pointer-events: auto; margin-top: 8px; margin-right: 6px; }
      #flightCard {
        position: absolute; left: 12px; right: 12px; bottom: 34px; max-width: 440px;
        display: none; z-index: 5; pointer-events: auto;
        background: rgba(10, 12, 18, 0.92); border: 1px solid #35404f; border-left: 4px solid #35404f;
        border-radius: 10px; padding: 10px 12px; color: #e8e8f0;
        font-family: monospace; font-size: 11px;
      }
      #flightCard .fc-main { display: flex; gap: 10px; align-items: flex-start; }
      #flightCard .fc-text { flex: 1; min-width: 0; }
      #flightCard .fc-head { display: flex; align-items: baseline; gap: 8px; }
      #flightCard .fc-call { font-size: 17px; font-weight: 700; letter-spacing: 1px; }
      #flightCard .fc-mil { color: #ff7a7a; font-weight: 700; }
      #flightCard .fc-airline { font-size: 12px; margin-top: 1px; }
      #flightCard .fc-sub { color: #9a9ab0; margin-top: 2px; }
      #flightCard .fc-photo { display: none; flex: none; text-align: right; cursor: pointer; }
      #flightCard .fc-photo img {
        display: block; height: 70px; max-width: 150px; border-radius: 8px; object-fit: cover;
      }
      #flightCard .fc-credit { color: #8a8aa0; font-size: 8px; margin-top: 2px; max-width: 150px; }
      #flightCard .fc-close {
        background: none; border: 0; color: #9a9ab0; font-size: 22px; line-height: 1; padding: 0 2px;
      }
      #flightCard .fc-route { display: none; margin-top: 9px; }
      #flightCard .fc-codes { display: flex; justify-content: space-between; align-items: baseline; }
      #flightCard .fc-codes b { font-size: 16px; }
      #flightCard .fc-city { color: #9a9ab0; font-size: 9px; }
      #flightCard .fc-bar { height: 4px; background: #2b3340; border-radius: 2px; margin: 4px 0; overflow: hidden; }
      #flightCard .fc-bar i { display: block; height: 100%; width: 0; background: #ff6a4d; }
      #flightCard .fc-stats { display: flex; justify-content: space-between; margin-top: 9px; }
      #flightCard .fc-stats b { display: block; font-size: 13px; }
      #flightCard .fc-stats span { color: #9a9ab0; font-size: 9px; }
    `;
    document.head.appendChild(style);

    const hud = document.getElementById('hud');
    const toggle = document.createElement('button');
    toggle.id = 'flightsToggle';
    toggle.className = 'chip on';
    toggle.addEventListener('click', () => setEnabled(!state.enabled));
    if (hud) {
      const reset = document.getElementById('reset');
      hud.insertBefore(toggle, reset || null);
    } else {
      document.body.appendChild(toggle);
    }
    state.ui.toggle = toggle;

    const card = document.createElement('div');
    card.id = 'flightCard';
    card.innerHTML = `
      <div class="fc-main">
        <div class="fc-text">
          <div class="fc-head"><span class="fc-call" id="fcCall"></span><span class="fc-mil" id="fcMil"></span></div>
          <div class="fc-airline" id="fcAirline"></div>
          <div class="fc-sub" id="fcSub"></div>
        </div>
        <div class="fc-photo" id="fcPhotoBox">
          <img id="fcPhoto" alt="">
          <div class="fc-credit" id="fcCredit"></div>
        </div>
        <button class="fc-close" id="fcClose" aria-label="Close">&times;</button>
      </div>
      <div class="fc-route" id="fcRoute">
        <div class="fc-codes">
          <div><b id="fcFrom"></b><div class="fc-city" id="fcFromCity"></div></div>
          <div style="text-align:right"><b id="fcTo"></b><div class="fc-city" id="fcToCity"></div></div>
        </div>
        <div class="fc-bar"><i id="fcBar"></i></div>
        <div class="fc-city" id="fcLeft"></div>
      </div>
      <div class="fc-stats">
        <div><b id="fcAlt"></b><span>ALTITUDE</span></div>
        <div><b id="fcSpd"></b><span>SPEED</span></div>
        <div><b id="fcHdg"></b><span>HEADING</span></div>
        <div><b id="fcVs"></b><span>CLIMB</span></div>
      </div>`;
    document.body.appendChild(card);
    state.ui.card = card;
    document.getElementById('fcClose').addEventListener('click', clearSelection);
    // Planespotters asks that the photo links to its page. The link opens in
    // the phone's browser (Kotlin checks the host), never inside the globe.
    document.getElementById('fcPhotoBox').addEventListener('click', (event) => {
      const link = event.currentTarget.dataset.link;
      const api = bridge();
      if (link && api && typeof api.openLink === 'function') api.openLink(link);
    });

    const credits = document.getElementById('credits');
    if (credits) credits.appendChild(document.createTextNode(' \u00b7 Flights: adsb.lol / adsb.one'));

    renderToggle();
  }

  function renderToggle() {
    const t = state.ui.toggle;
    if (!t) return;
    t.classList.toggle('on', state.enabled);
    const count = state.byHex.size;
    const suffix = !state.enabled ? '' : state.statusNote ? ' \u00b7 ' + state.statusNote : count ? ' ' + count : '';
    t.textContent = '\u2708 FLIGHTS' + suffix;
  }

  function setNote(note) {
    if (state.statusNote === note) return;
    state.statusNote = note;
    renderToggle();
  }

  // ── Requesting data ────────────────────────────────────────────────────────

  function viewCenter() {
    const viewer = state.viewer;
    const camera = viewer.camera;
    const canvas = viewer.scene.canvas;
    const centre = new Cesium.Cartesian2(canvas.clientWidth / 2, canvas.clientHeight / 2);
    const hit = camera.pickEllipsoid(centre, viewer.scene.globe.ellipsoid);
    const carto = hit ? Cesium.Cartographic.fromCartesian(hit) : camera.positionCartographic;
    return {
      lat: Cesium.Math.toDegrees(carto.latitude),
      lon: Cesium.Math.toDegrees(carto.longitude),
      heightM: camera.positionCartographic.height,
    };
  }

  function request(force) {
    if (!state.viewer || !state.enabled || document.hidden) return;
    const api = bridge();
    if (!api || typeof api.requestFlights !== 'function') return;

    const now = performance.now();
    if (now - state.lastReqMs < CFG.minGapMs) {
      if (force) {
        clearTimeout(state.moveTimer);
        state.moveTimer = setTimeout(() => request(true), CFG.minGapMs - (now - state.lastReqMs));
      }
      return;
    }

    const view = viewCenter();
    if (view.heightM > CFG.maxViewHeightM) {
      clearAll();
      setNote('ZOOM IN');
      return;
    }
    setNote('');
    state.lastReqMs = now;
    const radius = Math.min(
      CFG.maxRadiusNm,
      Math.max(CFG.minRadiusNm, Math.round((view.heightM / 1852) * 1.2)),
    );
    api.requestFlights(view.lat, view.lon, radius);
  }

  // ── Receiving data ─────────────────────────────────────────────────────────

  function setFlights(list) {
    if (!state.viewer || !state.billboards || !Array.isArray(list)) return;
    if (!state.enabled) return;
    setNote('');

    const now = performance.now();
    const seen = new Set();

    for (const f of list) {
      if (!f || typeof f.h !== 'string' || !isFinite(f.la) || !isFinite(f.lo)) continue;
      seen.add(f.h);

      let rec = state.byHex.get(f.h);
      if (!rec) {
        rec = {
          hex: f.h,
          trail: [],
          bb: state.billboards.add({
            position: Cesium.Cartesian3.fromDegrees(f.lo, f.la, f.a || 0),
            scale: CFG.iconPx / CFG.iconCanvasPx,
            verticalOrigin: Cesium.VerticalOrigin.CENTER,
            scaleByDistance: new Cesium.NearFarScalar(1.5e5, 1.0, 2e6, 0.6),
            id: 'flight:' + f.h,
          }),
        };
        state.byHex.set(f.h, rec);
      }

      rec.callsign = f.c || f.h.toUpperCase();
      rec.reg = f.r || '';
      rec.type = f.t || '';
      rec.military = !!f.m;
      rec.airline = airlineOf(rec.callsign, rec.military);
      rec.lat = f.la;
      rec.lon = f.lo;
      rec.altM = f.a || 0;
      rec.speedMs = f.s || 0;
      rec.track = f.k || 0;
      rec.climb = f.v || 0;
      rec.t0 = now;
      rec.bb.image = iconFor(rec, state.selectedHex === rec.hex);
      rec.bb.color = Cesium.Color.WHITE;

      const last = rec.trail[rec.trail.length - 1];
      if (!last || last.lon !== rec.lon || last.lat !== rec.lat || last.alt !== rec.altM) {
        rec.trail.push({ lon: rec.lon, lat: rec.lat, alt: rec.altM });
        if (rec.trail.length > CFG.trailMax) rec.trail.shift();
      }
    }

    for (const [hex, rec] of state.byHex) {
      if (seen.has(hex)) continue;
      releaseModel(hex);
      dropTags(rec);
      state.billboards.remove(rec.bb);
      state.byHex.delete(hex);
      if (state.selectedHex === hex) clearSelection();
    }

    stepPositions(now);
    if (state.selectedHex) rebuildTrail();
    renderToggle();
  }

  function flightsFailed() {
    setNote('OFFLINE');
  }

  // ── Motion ─────────────────────────────────────────────────────────────────

  /** Dead-reckons one plane to `now`; fills rec.now and rec.cart. */
  function advance(rec, now) {
    const dt = Math.min(Math.max((now - rec.t0) / 1000, 0), CFG.maxDeadReckonS);
    const dist = rec.speedMs * dt;
    const trk = (rec.track * Math.PI) / 180;
    const cosLat = Math.max(Math.cos((rec.lat * Math.PI) / 180), 0.01);
    const lat = rec.lat + ((dist * Math.cos(trk)) / EARTH_R) * (180 / Math.PI);
    const lon = rec.lon + ((dist * Math.sin(trk)) / (EARTH_R * cosLat)) * (180 / Math.PI);
    const alt = Math.max(rec.altM + rec.climb * dt, 0);
    rec.now = { lat, lon, alt };
    rec.cart = Cesium.Cartesian3.fromDegrees(lon, lat, alt, Cesium.Ellipsoid.WGS84, rec.cart);
    return trk;
  }

  function stepPositions(now) {
    const heading = state.viewer.camera.heading;
    for (const rec of state.byHex.values()) {
      const trk = advance(rec, now);
      rec.bb.position = rec.cart;
      // Billboard rotation is counter-clockwise on screen. A plane with track
      // T on a map whose top points along camera heading H appears turned
      // (T - H) clockwise, so the rotation is H - T.
      rec.bb.rotation = heading - trk;
    }
    const ranked = rankByDistance();
    assignModels(ranked);
    assignTags(ranked);
    updateDropLine();
    updateCard();
  }

  // ── 3D model level of detail ───────────────────────────────────────────────

  const hpr = new Cesium.HeadingPitchRoll();
  const modelMatrix = new Cesium.Matrix4();

  function initModelPool() {
    for (let i = 0; i < CFG.modelMax; i++) {
      Cesium.Model.fromGltfAsync({
        url: CFG.modelUrl,
        show: false,
        scale: 3,
        minimumPixelSize: 64,
        // Keep the model's own livery (white fuselage, red tail, grey engines, dark cockpit)
        // without tinting it by altitude colour.
        colorBlendMode: Cesium.ColorBlendMode.MIX,
        colorBlendAmount: 0.0,
        // Lit like a daytime photo rather than by the globe's sun position.
        // Increased from 2.2 to 7.0 so the model is not underexposed/black.
        lightColor: new Cesium.Cartesian3(7.0, 7.0, 7.0),
      })
        .then((model) => {
          if (!state.viewer) return;
          state.viewer.scene.primitives.add(model);
          state.pool.push({ model, hex: null });
        })
        .catch((error) => {
          if (i === 0) log('3D model unavailable, using icons only: ' + (error && error.message));
        });
    }
  }

  function rankByDistance() {
    const camera = state.viewer.camera.positionWC;
    const ranked = [];
    for (const rec of state.byHex.values()) {
      if (!rec.cart) continue;
      ranked.push({ rec, d: Cesium.Cartesian3.distance(camera, rec.cart) });
    }
    ranked.sort((a, b) => a.d - b.d);
    return ranked;
  }

  /** Gives the pool's models to the planes nearest the camera. */
  function assignModels(ranked) {
    if (!state.pool.length) return;
    const near = ranked.filter((n) => n.d <= CFG.modelRangeM);
    const want = new Set(near.slice(0, state.pool.length).map((n) => n.rec.hex));

    // Free models whose plane is no longer among the nearest.
    for (const entry of state.pool) {
      if (entry.hex && !want.has(entry.hex)) releaseEntry(entry);
    }
    // Hand free models to planes that do not have one yet.
    for (const hex of want) {
      if (state.pool.some((e) => e.hex === hex)) continue;
      const entry = state.pool.find((e) => !e.hex);
      if (!entry) break;
      const rec = state.byHex.get(hex);
      entry.hex = hex;
      entry.model.show = true;
      entry.model.color = Cesium.Color.WHITE;
      try {
        entry.model.id = 'flight:' + hex; // so a tap on the model selects it
      } catch (error) {
        /* picking falls back to the icon */
      }
      rec.bb.show = false;
    }
  }

  function releaseEntry(entry) {
    const rec = entry.hex ? state.byHex.get(entry.hex) : null;
    if (rec) rec.bb.show = true;
    entry.hex = null;
    entry.model.show = false;
  }

  function releaseModel(hex) {
    for (const entry of state.pool) if (entry.hex === hex) releaseEntry(entry);
  }

  /** Every frame: the few model planes move smoothly, not in 200 ms hops. */
  function updateModels(now) {
    for (const entry of state.pool) {
      if (!entry.hex) continue;
      const rec = state.byHex.get(entry.hex);
      if (!rec) {
        releaseEntry(entry);
        continue;
      }
      const trk = advance(rec, now);
      hpr.heading = trk + CFG.modelHeadingOffset;
      hpr.pitch = Math.max(-0.26, Math.min(0.26, Math.atan2(rec.climb, Math.max(rec.speedMs, 50))));
      hpr.roll = 0;
      Cesium.Transforms.headingPitchRollToFixedFrame(
        rec.cart,
        hpr,
        Cesium.Ellipsoid.WGS84,
        Cesium.Transforms.eastNorthUpToFixedFrame,
        modelMatrix,
      );
      entry.model.modelMatrix = Cesium.Matrix4.clone(modelMatrix);
    }
  }

  // ── Tags: airline chip + callsign/altitude label on the nearest planes ──────

  const chipCache = new Map();

  /** Rounded pill in the airline's colour. Drawn once per airline, at 2x. */
  function chipImage(airline) {
    const key = airline.s + airline.c;
    if (chipCache.has(key)) return chipCache.get(key);
    const canvas = document.createElement('canvas');
    const g = canvas.getContext('2d');
    const font = '700 20px sans-serif';
    g.font = font;
    const text = airline.s.toUpperCase();
    const w = Math.ceil(g.measureText(text).width) + 22;
    canvas.width = w;
    canvas.height = 32;
    g.font = font;
    g.fillStyle = airline.c;
    g.beginPath();
    g.roundRect ? g.roundRect(0, 0, w, 32, 16) : g.rect(0, 0, w, 32);
    g.fill();
    g.lineWidth = 2;
    g.strokeStyle = 'rgba(255,255,255,0.55)';
    g.stroke();
    g.fillStyle = textColorOn(airline.c);
    g.textBaseline = 'middle';
    g.textAlign = 'center';
    g.fillText(text, w / 2, 17);
    const url = canvas.toDataURL('image/png');
    chipCache.set(key, url);
    return url;
  }

  function assignTags(ranked) {
    const low = state.viewer.camera.positionCartographic.height <= CFG.tagMaxViewM;
    const want = new Set();
    if (low) for (const n of ranked.slice(0, CFG.tagMax)) want.add(n.rec.hex);
    if (state.selectedHex) want.add(state.selectedHex);
    for (const rec of state.byHex.values()) {
      if (want.has(rec.hex)) tagPlane(rec);
      else dropTags(rec);
    }
  }

  function tagPlane(rec) {
    if (!rec.cart || !rec.now) return;
    // Planes drawn as 3D models are bigger on screen, so the tags sit further out.
    const big = !rec.bb.show;
    const chipOffset = new Cesium.Cartesian2(0, big ? -30 : -15);
    const labelOffset = new Cesium.Cartesian2(0, big ? 30 : 15);
    const fade = new Cesium.NearFarScalar(1.5e5, 1.0, 1.5e6, 0.7);

    if (rec.airline) {
      if (!rec.chip) {
        rec.chip = state.chips.add({
          image: chipImage(rec.airline),
          scale: 0.5,
          verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
          scaleByDistance: fade,
          id: 'flight:' + rec.hex,
        });
      }
      rec.chip.position = rec.cart;
      rec.chip.pixelOffset = chipOffset;
    }

    const text = rec.callsign + '\n' + altText(rec.now.alt) + ' \u00b7 ' + Math.round(rec.speedMs / KT) + ' kt';
    if (!rec.label) {
      rec.label = state.labels.add({
        text,
        font: '600 11px monospace',
        fillColor: Cesium.Color.WHITE,
        outlineColor: Cesium.Color.BLACK.withAlpha(0.9),
        outlineWidth: 3,
        style: Cesium.LabelStyle.FILL_AND_OUTLINE,
        horizontalOrigin: Cesium.HorizontalOrigin.CENTER,
        verticalOrigin: Cesium.VerticalOrigin.TOP,
        scaleByDistance: fade,
        id: 'flight:' + rec.hex,
      });
    }
    rec.label.text = text;
    rec.label.position = rec.cart;
    rec.label.pixelOffset = labelOffset;
  }

  function dropTags(rec) {
    if (rec.chip) {
      state.chips.remove(rec.chip);
      rec.chip = null;
    }
    if (rec.label) {
      state.labels.remove(rec.label);
      rec.label = null;
    }
  }

  // ── Selection: card, drop line, trail ──────────────────────────────────────

  function select(hex) {
    const rec = state.byHex.get(hex);
    if (!rec) return;
    clearSelection();
    state.selectedHex = hex;
    rec.bb.scale = (CFG.iconPx * 1.45) / CFG.iconCanvasPx;
    rec.bb.image = iconFor(rec, true);
    state.ui.card.style.display = 'block';

    if (!state.dropLines) {
      state.dropLines = new Cesium.PolylineCollection();
      state.viewer.scene.primitives.add(state.dropLines);
    }
    state.dropLine = state.dropLines.add({
      positions: [Cesium.Cartesian3.ZERO, Cesium.Cartesian3.ZERO],
      width: 1.5,
      material: Cesium.Material.fromType('Color', {
        uniforms: { color: Cesium.Color.WHITE.withAlpha(0.7) },
      }),
    });
    updateDropLine();
    rebuildTrail();
    updateCard();
    requestDetails(rec);
  }

  function requestDetails(rec) {
    const seen = state.details.get(rec.hex);
    if (seen && seen.requested) return;
    state.details.set(rec.hex, Object.assign(seen || {}, { requested: true }));
    if (state.details.size > 200) state.details.delete(state.details.keys().next().value);
    const api = bridge();
    if (api && typeof api.requestFlightDetails === 'function') {
      api.requestFlightDetails(rec.hex, rec.callsign || '', rec.lat, rec.lon);
    }
  }

  /** Called by Kotlin with {photo} and/or {route}; merges and redraws the card. */
  function setFlightDetails(hex, part) {
    if (typeof hex !== 'string' || !part) return;
    const merged = Object.assign(state.details.get(hex) || {}, part);
    state.details.set(hex, merged);
    if (hex === state.selectedHex) updateCard();
  }

  function clearSelection() {
    const rec = state.selectedHex ? state.byHex.get(state.selectedHex) : null;
    if (rec) {
      rec.bb.scale = CFG.iconPx / CFG.iconCanvasPx;
      rec.bb.image = iconFor(rec, false);
    }
    state.selectedHex = null;
    if (state.ui.card) state.ui.card.style.display = 'none';
    if (state.dropLines) state.dropLines.removeAll();
    state.dropLine = null;
    removeTrail();
  }

  function updateDropLine() {
    if (!state.dropLine || !state.selectedHex) return;
    const rec = state.byHex.get(state.selectedHex);
    if (!rec || !rec.now) return;
    state.dropLine.positions = [
      Cesium.Cartesian3.fromDegrees(rec.now.lon, rec.now.lat, rec.now.alt),
      Cesium.Cartesian3.fromDegrees(rec.now.lon, rec.now.lat, 0),
    ];
  }

  function removeTrail() {
    const prims = state.viewer ? state.viewer.scene.primitives : null;
    if (prims && state.trailPrimitive) prims.remove(state.trailPrimitive);
    if (prims && state.wallPrimitive) prims.remove(state.wallPrimitive);
    state.trailPrimitive = null;
    state.wallPrimitive = null;
  }

  /**
   * Trail = the positions reported while you have been watching, coloured by
   * the altitude at each point, plus a translucent curtain down to the ground.
   * It starts short and grows; nothing is invented for the time before.
   */
  function rebuildTrail() {
    removeTrail();
    const rec = state.selectedHex ? state.byHex.get(state.selectedHex) : null;
    if (!rec) return;

    // Drop consecutive duplicates: Cesium rejects zero-length segments.
    const pts = [];
    for (const p of rec.trail) {
      const prev = pts[pts.length - 1];
      if (!prev || prev.lon !== p.lon || prev.lat !== p.lat || prev.alt !== p.alt) pts.push(p);
    }
    if (pts.length < 2) return;

    try {
      const positions = pts.map((p) => Cesium.Cartesian3.fromDegrees(p.lon, p.lat, p.alt));
      const colors = pts.map((p) => altColor(p.alt).withAlpha(0.95));
      const lastAlt = pts[pts.length - 1].alt;

      state.trailPrimitive = state.viewer.scene.primitives.add(
        new Cesium.Primitive({
          geometryInstances: new Cesium.GeometryInstance({
            geometry: new Cesium.PolylineGeometry({
              positions,
              width: 4,
              vertexFormat: Cesium.PolylineColorAppearance.VERTEX_FORMAT,
              colors,
              colorsPerVertex: true,
            }),
          }),
          appearance: new Cesium.PolylineColorAppearance({ translucent: true }),
          asynchronous: false,
        }),
      );

      state.wallPrimitive = state.viewer.scene.primitives.add(
        new Cesium.Primitive({
          geometryInstances: new Cesium.GeometryInstance({
            geometry: new Cesium.WallGeometry({
              positions,
              vertexFormat: Cesium.PerInstanceColorAppearance.VERTEX_FORMAT,
            }),
            attributes: {
              color: Cesium.ColorGeometryInstanceAttribute.fromColor(
                altColor(lastAlt).withAlpha(0.28),
              ),
            },
          }),
          appearance: new Cesium.PerInstanceColorAppearance({ translucent: true, closed: false }),
          asynchronous: false,
        }),
      );
    } catch (error) {
      log('trail failed: ' + (error && error.message));
      removeTrail();
    }
  }

  function updateCard() {
    const rec = state.selectedHex ? state.byHex.get(state.selectedHex) : null;
    if (!rec || !rec.now) return;
    const $ = (id) => document.getElementById(id);
    const set = (id, text) => {
      const el = $(id);
      if (el && el.textContent !== text) el.textContent = text;
    };
    const details = state.details.get(rec.hex) || {};

    set('fcCall', rec.callsign);
    set('fcMil', rec.military ? 'MIL' : '');
    set('fcAirline', rec.airline ? rec.airline.n : '');
    set('fcSub', [rec.reg, typeName(rec.type)].filter(Boolean).join(' \u00b7 ') || rec.hex.toUpperCase());
    state.ui.card.style.borderLeftColor = rec.airline ? rec.airline.c : '#35404f';

    set('fcAlt', altText(rec.now.alt));
    set('fcSpd', Math.round(rec.speedMs / KT) + ' kts');
    set('fcHdg', Math.round(rec.track) + '\u00b0');
    const fpm = Math.round(rec.climb / 0.00508 / 50) * 50;
    set('fcVs', (fpm > 0 ? '+' : '') + fpm + ' fpm');

    // Aircraft photo (Planespotters). Credit and link are required by its terms.
    const box = $('fcPhotoBox');
    const photo = details.photo;
    if (photo && photo.src) {
      const img = $('fcPhoto');
      if (img.dataset.src !== photo.src) {
        img.dataset.src = photo.src;
        img.src = photo.src;
      }
      box.dataset.link = photo.link || '';
      set('fcCredit', 'Photo: ' + (photo.by || 'unknown') + ' \u00b7 Planespotters.net');
      box.style.display = 'block';
    } else {
      box.style.display = 'none';
    }

    // Route, when the callsign resolves to one.
    const routeEl = $('fcRoute');
    const route = details.route;
    if (route && route.from && route.to) {
      set('fcFrom', route.from.code);
      set('fcTo', route.to.code);
      set('fcFromCity', route.from.city || '');
      set('fcToCity', route.to.city || '');
      let progress = null;
      if ([route.from.lat, route.from.lon, route.to.lat, route.to.lon].every(Number.isFinite)) {
        const done = greatCircleM(route.from.lat, route.from.lon, rec.now.lat, rec.now.lon);
        const left = greatCircleM(rec.now.lat, rec.now.lon, route.to.lat, route.to.lon);
        if (done + left > 0) progress = done / (done + left);
        set('fcLeft', Math.round(left / 1609.34).toLocaleString() + ' mi to go');
      } else {
        set('fcLeft', '');
      }
      $('fcBar').style.width = progress == null ? '0%' : Math.round(progress * 100) + '%';
      routeEl.style.display = 'block';
    } else {
      routeEl.style.display = 'none';
    }
  }

  // ── Clearing / toggling ────────────────────────────────────────────────────

  function clearAll() {
    clearSelection();
    for (const entry of state.pool) {
      entry.hex = null;
      entry.model.show = false;
    }
    if (state.billboards) state.billboards.removeAll();
    if (state.chips) state.chips.removeAll();
    if (state.labels) state.labels.removeAll();
    for (const rec of state.byHex.values()) {
      rec.chip = null;
      rec.label = null;
    }
    state.byHex.clear();
    renderToggle();
  }

  function setEnabled(on) {
    state.enabled = !!on;
    if (!state.enabled) {
      clearAll();
      setNote('');
    } else {
      request(true);
    }
    renderToggle();
  }

  // ── Wiring ─────────────────────────────────────────────────────────────────

  function start(viewer) {
    state.viewer = viewer;
    state.billboards = viewer.scene.primitives.add(new Cesium.BillboardCollection());
    state.chips = viewer.scene.primitives.add(new Cesium.BillboardCollection());
    state.labels = viewer.scene.primitives.add(new Cesium.LabelCollection());
    injectUi();

    initModelPool();

    viewer.scene.preRender.addEventListener(() => {
      if (!state.enabled || state.byHex.size === 0) return;
      const now = performance.now();
      updateModels(now);
      stepPositions(now);
    });

    // Re-query after the camera settles somewhere new.
    viewer.camera.moveEnd.addEventListener(() => {
      clearTimeout(state.moveTimer);
      state.moveTimer = setTimeout(() => request(true), CFG.moveDebounceMs);
    });

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
      if (id && id.startsWith('flight:')) select(id.slice(7));
    }, Cesium.ScreenSpaceEventType.LEFT_CLICK);

    state.pollTimer = setInterval(() => request(false), CFG.pollMs);
    document.addEventListener('visibilitychange', () => {
      if (!document.hidden) request(true);
    });

    request(true);
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
        log('gave up waiting for the globe (is GEVLite.getViewer added to app.js?)');
        return;
      }
      setTimeout(tick, 400);
    };
    tick();
  }

  // ── API used by Kotlin ─────────────────────────────────────────────────────

  window.GEVLite = window.GEVLite || {};
  window.GEVLite.setFlights = setFlights;
  window.GEVLite.flightsFailed = flightsFailed;
  window.GEVLite.setFlightDetails = setFlightDetails;
  window.GEVLite.flightStats = () => ({
    enabled: state.enabled,
    drawn: state.byHex.size,
    selected: state.selectedHex,
    note: state.statusNote,
    models: state.pool.length,
    modelsInUse: state.pool.filter((e) => e.hex).length,
    tagged: [...state.byHex.values()].filter((r) => r.label).length,
  });

  whenViewerReady(start);
})();
