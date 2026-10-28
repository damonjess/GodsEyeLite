# God's Eye Lite

A small native Android app: a 3D globe with 3D buildings, plus live public traffic
cameras. Tap a camera dot to see its current frame.

It is inspired by [God's Eye View](https://github.com/bilawalsidhu/gods-eye-view) but
is an independent, deliberately tiny app — it implements a fraction of that
project's features and shares none of its code.

## Running it

Open this folder in Android Studio (**File → Open**), let it sync, connect your
phone over USB with USB debugging on, and press **Run**. Or build from a terminal:

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires an Android SDK with platform 35 (this project was built and verified
with platforms 34–37 installed) and JDK 17+.

## The Cesium ion token (needed for 3D buildings)

The globe works with **no token at all** — it loads Esri satellite imagery
keylessly. Terrain and the 3D buildings come from Cesium ion, so they need a
token. Tap **ION KEY** in the app and paste one.

Use a token with:

- scope **`assets:read`**
- allowed URL **`https://appassets.androidplatform.net`**

That origin is not arbitrary: the app serves its bundled page from
`WebViewAssetLoader` under exactly that host, deliberately, instead of `file://`.
A real HTTPS origin is what lets Cesium's blob-backed web workers start *and*
gives the token a stable origin to be restricted to. If you would rather not
restrict the token to it, the keyless imagery still works and you simply get no
3D buildings.

Verified ion asset ids used here: `2275207` (Google Photorealistic 3D Tiles —
real buildings) with an automatic fallback to `96188` (Cesium OSM Buildings,
global but blocky), plus `1` (Cesium World Terrain).

## Cameras

Four keyless public catalogs are bundled, ~3,600 cameras:

| Pack | Cameras | Source | Credit |
| --- | --- | --- | --- |
| Austin | 823 | `data.austintexas.gov` | City of Austin Open Data |
| Calgary | 214 | `data.calgary.ca` | Open Government Licence – City of Calgary |
| Washington | 1,706 | `data.wsdot.wa.gov` | Washington State Department of Transportation (Travel Center) |
| London | 890 | `api.tfl.gov.uk` | Powered by TfL Open Data. Contains OS data © Crown copyright and database rights |

Only cameras reported as switched on are used (Austin publishes `DESIRED`,
`VOID` and `REMOVED` rows too). Frames are periodic snapshots, not video, and
they refresh only when you press **RELOAD**.

Adding another region means adding one entry to `PACKS` in
`CctvRepository.kt`. Check the feed's licence and add its attribution first.

## Why there is no server

God's Eye View needs a Node server, and the single biggest reason is CORS: a
browser cannot call these open-data catalogs or their image hosts directly. A
native Android HTTP client has no such restriction, so Kotlin fetches the
catalogs and frames itself and no proxy is needed. That one difference is what
makes this a self-contained app rather than a client for a running server.

Rendering still happens in a WebView, because Cesium is the practical way to get
a true 3D globe with real 3D buildings on Android — MapLibre's stable Android
release has no globe projection at all (it exists only in an unreleased
pre-release).

## What is verified, and what is not

Verified while building this:

- It compiles and packages: `./gradlew assembleDebug` succeeds and produces a
  ~1.3 MB APK containing `classes.dex`, the three web assets and the launcher icon.
- The globe layer runs: served on a desktop browser, Cesium 1.124 boots from the
  CDN, live Esri imagery streams, and pins render, count and toggle per pack
  correctly (4 cameras in, 4 drawn, 3 drawn after hiding a pack).
- The parsers match the real feeds: mirrored against the live endpoints, they
  yield 823 / 214 / 1,706 / 890 cameras — Austin's figure matches its
  `TURNED_ON` count exactly, and WSDOT's Web Mercator coordinates resolve to
  correct Pacific Northwest positions.

**Not verified**, because it needs your phone: on-device WebView behaviour, the
JavaScript bridge, the ion token and 3D buildings, and live frame loading.

## Limitations

- **No microphone or voice control.** In a WebView, `getUserMedia` needs a
  secure context; `https://appassets.androidplatform.net` is one on paper, but
  audio capture would also need explicit `onPermissionRequest` handling that this
  app does not implement.
- **Not affiliated with God's Eye View.** Separate app, separate code.
- Building height coverage follows OpenStreetMap, and photorealistic tiles do not
  cover every city, which is why the OSM Buildings fallback exists.
- Camera positions come from the publishers; this app does not estimate or
  correct them.
- Imagery is Esri World Imagery (OSM as fallback) — check each provider's terms
  before publishing anything built on this.
