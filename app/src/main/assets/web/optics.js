/**
 * God's Eye Lite — tactical optics (NVG / FLIR / CRT), live reticle telemetry,
 * and real-time solar day/night terminator. Zero external APIs.
 */
(() => {
  'use strict';

  const FLIR_SHADER = `
    uniform sampler2D colorTexture;
    uniform float u_time;
    in vec2 v_textureCoordinates;

    vec3 ironbow(float t) {
      vec3 c0 = vec3(0.02, 0.02, 0.10);
      vec3 c1 = vec3(0.38, 0.04, 0.48);
      vec3 c2 = vec3(0.86, 0.22, 0.14);
      vec3 c3 = vec3(0.99, 0.72, 0.10);
      vec3 c4 = vec3(1.00, 0.99, 0.92);
      if (t < 0.25) return mix(c0, c1, t * 4.0);
      if (t < 0.50) return mix(c1, c2, (t - 0.25) * 4.0);
      if (t < 0.75) return mix(c2, c3, (t - 0.50) * 4.0);
      return mix(c3, c4, (t - 0.75) * 4.0);
    }

    void main() {
      vec4 tex = texture(colorTexture, v_textureCoordinates);
      float lum = dot(tex.rgb, vec3(0.299, 0.587, 0.114));
      float contrast = clamp((lum - 0.08) * 1.28, 0.0, 1.0);
      vec3 col = ironbow(contrast);
      float scan = 0.96 + 0.04 * sin(v_textureCoordinates.y * 900.0 + u_time * 4.0);
      vec2 uv = v_textureCoordinates * 2.0 - 1.0;
      float vig = 1.0 - dot(uv, uv) * 0.22;
      out_FragColor = vec4(col * scan * vig, tex.a);
    }
  `;

  const CRT_SHADER = `
    uniform sampler2D colorTexture;
    uniform float u_time;
    in vec2 v_textureCoordinates;

    void main() {
      vec2 uv = v_textureCoordinates;
      vec4 tex = texture(colorTexture, uv);
      float lum = dot(tex.rgb, vec3(0.25, 0.65, 0.10));
      float scan = 0.84 + 0.16 * sin(uv.y * 1100.0);
      float sweep = smoothstep(0.0, 0.08, abs(fract(uv.y - u_time * 0.12) - 0.5));
      vec3 phosphor = vec3(0.12, 0.95, 0.58) * pow(clamp(lum * 1.25, 0.0, 1.0), 0.92);
      phosphor *= scan * (1.08 - 0.08 * sweep);
      vec2 c = uv * 2.0 - 1.0;
      float vig = clamp(1.0 - dot(c, c) * 0.35, 0.0, 1.0);
      out_FragColor = vec4(phosphor * vig, tex.a);
    }
  `;

  const state = {
    viewer: null,
    mode: 'std', // 'std' | 'nvg' | 'flir' | 'crt'
    reticleOn: false,
    solarOn: false,
    stages: {},
    originalLight: null,
    sunLight: null,
    t0: performance.now(),
    reticleEl: null,
    readoutEl: null,
  };

  function injectUi() {
    const style = document.createElement('style');
    style.textContent = `
      #opticsReticle {
        position: absolute; inset: 0; pointer-events: none; z-index: 3; display: none;
      }
      #opticsReticle.on { display: block; }
      #opticsReticle .or-cross {
        position: absolute; left: 50%; top: 50%; width: 76px; height: 76px;
        transform: translate(-50%, -50%);
        border: 1px solid rgba(53, 224, 200, 0.35); border-radius: 50%;
      }
      #opticsReticle .or-cross::before, #opticsReticle .or-cross::after {
        content: ""; position: absolute; background: rgba(53, 224, 200, 0.65);
      }
      #opticsReticle .or-cross::before { left: 50%; top: -12px; width: 1px; height: 100px; }
      #opticsReticle .or-cross::after { top: 50%; left: -12px; height: 1px; width: 100px; }
      #opticsReadout {
        position: absolute; right: 12px; bottom: 28px;
        background: rgba(6, 10, 14, 0.78); border: 1px solid rgba(53, 224, 200, 0.4);
        border-radius: 5px; padding: 4px 8px;
        color: #35e0c8; font-family: monospace; font-size: 9px; letter-spacing: 0.6px;
        text-shadow: 0 1px 2px rgba(0,0,0,0.9);
      }
    `;
    document.head.appendChild(style);

    const reticle = document.createElement('div');
    reticle.id = 'opticsReticle';
    reticle.innerHTML = `<div class="or-cross"></div><div id="opticsReadout"></div>`;
    document.body.appendChild(reticle);
    state.reticleEl = reticle;
    state.readoutEl = document.getElementById('opticsReadout');
  }

  function setMode(mode) {
    if (!['std', 'nvg', 'flir', 'crt'].includes(mode)) return;
    state.mode = mode;
    for (const [key, stage] of Object.entries(state.stages)) {
      if (stage) stage.enabled = key === mode;
    }
    for (const btn of document.querySelectorAll('#opticsMode button')) {
      const on = btn.dataset.optics === mode;
      btn.classList.toggle('on', on);
      btn.setAttribute('aria-pressed', String(on));
    }
  }

  function setReticle(on) {
    state.reticleOn = !!on;
    if (state.reticleEl) state.reticleEl.classList.toggle('on', state.reticleOn);
    const btn = document.getElementById('layerReticle');
    if (btn) btn.setAttribute('aria-pressed', String(state.reticleOn));
  }

  function setSolar(on) {
    if (!state.viewer) return;
    state.solarOn = !!on;
    const scene = state.viewer.scene;
    if (state.solarOn) {
      if (!state.sunLight) state.sunLight = new Cesium.SunLight();
      scene.light = state.sunLight;
      scene.globe.enableLighting = true;
      state.viewer.clock.currentTime = Cesium.JulianDate.now();
    } else {
      scene.globe.enableLighting = false;
      if (state.originalLight) scene.light = state.originalLight;
    }
    const btn = document.getElementById('layerSolar');
    if (btn) btn.setAttribute('aria-pressed', String(state.solarOn));
  }

  function updateReadout() {
    if (!state.reticleOn || !state.viewer || !state.readoutEl) return;
    const cam = state.viewer.camera;
    const c = cam.positionCartographic;
    const lat = Cesium.Math.toDegrees(c.latitude).toFixed(4);
    const lon = Cesium.Math.toDegrees(c.longitude).toFixed(4);
    const altKm = (c.height / 1000).toFixed(c.height < 10_000 ? 2 : 0);
    const hdg = String(Math.round(Cesium.Math.toDegrees(cam.heading)) % 360).padStart(3, '0');
    state.readoutEl.textContent = `${lat}°, ${lon}°  ·  ALT ${altKm} km  ·  HDG ${hdg}°  ·  ${state.mode.toUpperCase()}`;
  }

  function start(viewer) {
    state.viewer = viewer;
    state.originalLight = viewer.scene.light;
    injectUi();

    try {
      const stages = viewer.scene.postProcessStages;
      state.stages.nvg = stages.add(Cesium.PostProcessStageLibrary.createNightVisionStage());
      state.stages.nvg.enabled = false;

      state.stages.flir = stages.add(new Cesium.PostProcessStage({
        fragmentShader: FLIR_SHADER,
        uniforms: { u_time: () => (performance.now() - state.t0) * 0.001 },
      }));
      state.stages.flir.enabled = false;

      state.stages.crt = stages.add(new Cesium.PostProcessStage({
        fragmentShader: CRT_SHADER,
        uniforms: { u_time: () => (performance.now() - state.t0) * 0.001 },
      }));
      state.stages.crt.enabled = false;
    } catch (e) {
      // Older WebView GPUs stay on standard rendering without crashing.
    }

    for (const btn of document.querySelectorAll('#opticsMode button')) {
      btn.addEventListener('click', () => setMode(btn.dataset.optics));
    }
    const reticleBtn = document.getElementById('layerReticle');
    if (reticleBtn) reticleBtn.addEventListener('click', () => setReticle(!state.reticleOn));
    const solarBtn = document.getElementById('layerSolar');
    if (solarBtn) solarBtn.addEventListener('click', () => setSolar(!state.solarOn));

    viewer.scene.postRender.addEventListener(updateReadout);
  }

  const wait = () => {
    const v = window.GEVLite && window.GEVLite.getViewer && window.GEVLite.getViewer();
    if (v && v.scene) start(v);
    else setTimeout(wait, 400);
  };

  window.GEVLite = window.GEVLite || {};
  window.GEVLite.setOpticsMode = setMode;
  window.GEVLite.setReticleEnabled = setReticle;
  window.GEVLite.setSolarEnabled = setSolar;
  wait();
})();
