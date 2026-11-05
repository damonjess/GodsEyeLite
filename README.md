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

Twelve keyless public catalogs are bundled, ~11,700 cameras across Europe, Asia,
North America and Australia:

| Pack | Cameras | Source | Credit |
| --- | --- | --- | --- |
| Austin | 823 | `data.austintexas.gov` | City of Austin Open Data |
| Calgary | 217 | `data.calgary.ca` | Open Government Licence – City of Calgary |
| Washington | 1,708 | `data.wsdot.wa.gov` | Washington State Department of Transportation (Travel Center) |
| London | 890 | `api.tfl.gov.uk` | Powered by TfL Open Data. Contains OS data © Crown copyright and database rights |
| Finland | 2,268 | `tie.digitraffic.fi` | Fintraffic / Digitraffic, CC BY 4.0 |
| Norway | 899 | `ogckart-sn1.atlas.vegvesen.no` | NLOD, distributed by Statens vegvesen |
| Hong Kong | 1,013 | `static.data.gov.hk` | Hong Kong Transport Department / DATA.GOV.HK |
| British Columbia | 1,048 | `www.drivebc.ca` | Open Government Licence – British Columbia |
| New South Wales | 249 | `data.livetraffic.com` | Live Traffic NSW – Transport for NSW, CC BY 4.0 |
| A22 Brenner | 13 | `tourism.api.opendatahub.com` | Autostrada del Brennero, via the Open Data Hub |
| Delaware | 364 | `tmc.deldot.gov` | DelDOT – Delaware Department of Transportation (live HLS video) |
| California | 2,200 | `cwwp2.dot.ca.gov` | Caltrans – California Department of Transportation (live HLS video) |

Only cameras reported as switched on are used (Austin publishes `DESIRED`,
`VOID` and `REMOVED` rows too, and Caltrans marks each row `inService`). Most
frames are periodic snapshots and refresh only when you press **RELOAD**. TfL
publishes short looping clips; 144 Norwegian cameras, 364 Delaware cameras and
2,200 California cameras publish live HLS video, which the panel plays directly.
DelDOT's and Caltrans' streams are public endpoints but are not a blanket
redistribution licence, so treat them as personal, local viewing.

The **LAYERS** button opens independent camera and plane switches plus an
all / stills / live camera filter. LIVE means any camera with a video URL,
including TfL's short clips; STILLS is the still-image-only set. Pack chips
still filter by region. Adding
another region means adding one entry to `PACKS` in `CctvRepository.kt`. Check
the feed's licence and add its attribution first.

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

- It compiles and packages: `./gradlew assembleDebug` succeeds and produces an
  APK (about 3.9 MB in debug, including ExoPlayer and the three web assets).
- The globe layer runs: served on a desktop browser, Cesium 1.124 boots from the
  CDN, live Esri imagery streams, and pins render, count and toggle per pack
  correctly (4 cameras in, 4 drawn, 3 drawn after hiding a pack).
- The parsers match the real feeds: mirrored against the live endpoints, they
  yield 823 / 217 / 1,708 / 890 / 2,268 / 899 / 1,013 / 1,048 / 249 / 13 / 364
  / 2,200 cameras — Austin's figure matches its `TURNED_ON` count exactly, WSDOT's Web
  Mercator coordinates resolve to correct Pacific Northwest positions, one real
  JPEG was fetched through the app's native path from each earlier still-only
  pack, and the DelDOT, Norwegian and Caltrans HLS playlists were fetched and
  parsed. One Caltrans still was also fetched directly.

**On-device checks** on the Xiaomi 2107113SG: the globe loaded the then-bundled
9,492 cameras, the JavaScript bridge opened camera panels, and both DelDOT and
Norwegian live HLS streams played in a TextureView. The California pack was
verified at the feed, parser and HLS-playlist level; it has not yet been played
on a device. The ion token and 3D buildings were not re-tested on this pass.

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
