# God's Eye Lite

A small Android app with a 3D globe, live aircraft, satellites, public traffic
cameras, and global hazard events. Tap a camera for its latest image or video,
or tap an aircraft, satellite, or event for details.

## Run the app

Open the project in Android Studio and press **Run**, or build a debug APK with:

```bash
./gradlew assembleDebug
```

The APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

**Requirements:** Android SDK platform 35 and JDK 17 or newer.

## 3D buildings

The globe and satellite imagery work without a key. To enable terrain and 3D
buildings, add a Cesium ion token in the app using **ION KEY**. The token needs
the `assets:read` scope and should allow
`https://appassets.androidplatform.net`.

## Camera data

The app includes public camera feeds from Austin, Calgary, Washington, London,
Finland, Norway, Hong Kong, British Columbia, New South Wales, A22 Brenner,
Delaware, and California. Camera images and video are provided by their
respective publishers; check their terms and attribution requirements before
reuse.

## Cameras

Twelve keyless public catalogs are bundled, with 12,954 active cameras at the
last count (roughly 3,600 live and 9,350 still). Counts move as publishers add,
remove, or disable feeds.

| Pack | Cameras | Source | Credit |
| --- | --- | --- | --- |
| Austin | 823 | `data.austintexas.gov` | City of Austin Open Data |
| Calgary | 219 | `data.calgary.ca` | Open Government Licence – City of Calgary |
| Washington | 1,710 | `data.wsdot.wa.gov` | Washington State Department of Transportation (Travel Center) |
| London | 889 | `api.tfl.gov.uk` | Powered by TfL Open Data. Contains OS data © Crown copyright and database rights |
| Finland | 2,269 | `tie.digitraffic.fi` | Fintraffic / Digitraffic, CC BY 4.0 |
| Norway | 900 | `ogckart-sn1.atlas.vegvesen.no` | NLOD, distributed by Statens vegvesen |
| Hong Kong | 1,013 | `static.data.gov.hk` | Hong Kong Transport Department / DATA.GOV.HK |
| British Columbia | 1,059 | `www.drivebc.ca` | Open Government Licence – British Columbia |
| New South Wales | 249 | `data.livetraffic.com` | Live Traffic NSW – Transport for NSW, CC BY 4.0 |
| A22 Brenner | 13 | `tourism.api.opendatahub.com` | Autostrada del Brennero, via the Open Data Hub |
| Delaware | 367 | `tmc.deldot.gov` | DelDOT – Delaware Department of Transportation (live HLS video) |
| California | 3,443 | `cwwp2.dot.ca.gov` | Caltrans – California Department of Transportation (live HLS video) |

Only cameras reported as switched on are used (Austin publishes `DESIRED`,
`VOID` and `REMOVED` rows too, and Caltrans marks each row `inService`). Most
frames are periodic snapshots and refresh only when you press **RELOAD**. TfL
publishes short looping clips; 144 Norwegian cameras, 367 Delaware cameras and
2,200 California cameras publish live HLS video, which the panel plays directly.
DelDOT's and Caltrans' streams are public endpoints but are not a blanket
redistribution licence, so treat them as personal, local viewing.

The **LAYERS** button opens independent camera, aircraft, satellite, and event
switches plus the all / stills / live camera filter. LIVE means any camera with
a video URL, including TfL's short clips; STILLS is the still-image-only set.
Pack chips still filter by region. Adding another region means adding one entry
to `PACKS` in `CctvRepository.kt`. Check the feed's licence and add its
attribution first.

## Aircraft, alerts, and 3D models

Flights come from `adsb.lol` with `adsb.one` as a fallback. The plane card shows
the callsign, type, altitude, speed, heading, and climb rate. The **LAYERS**
panel has these aircraft filters:

- **MIL** shows aircraft with the ADS-B military flag or a strong military
  callsign prefix.
- **EMERG** shows emergency and known service aircraft.
- **HELI** shows rotorcraft, using ADS-B category `A7` or a known type code.
- **LOW** shows aircraft below 5,000 ft.
- The search box matches callsign, registration, type, hex code, and emergency
  reason.

Far away, fixed-wing aircraft and helicopters use distinct 2D silhouettes. Within
100 km of the camera, the nearest aircraft switch to the bundled `plane.glb` and
`helicopter.glb` models; the helicopter's main and tail rotors are animated.
Tapping a flight opens its card and trail. **CHASE** follows the aircraft from
behind and above, and **RELEASE** returns to the normal camera.

Emergency/service aircraft are identified from the ADS-B emergency status,
7500/7600/7700 squawks, known air-ambulance, rescue, police, and fire callsigns,
and for helicopters with registration-style callsigns, the registered owner from
[ADSBdb](https://api.adsbdb.com/) (cached for a day). Recognized HEMS callsigns
include the UK CAA `HLE##` HELIMED series, and contractor-owned air ambulances
such as **Babcock MCS Onshore** are matched from owner metadata. Known
police/fire/rescue callsigns take precedence; `Babcock MCS Offshore` is not
treated as a medical operator.

Service aircraft get a colored dashed marker ring and a short badge:
**MEDEVAC** red, **POLICE** blue, **FIRE** orange, **RESCUE** amber, and other
emergency reasons red with their reason shown. Military aircraft get an olive
marker and `MIL` tag. Some aircraft remain unidentifiable because their feed has
neither a service callsign nor a matching registered owner (or ADSBdb has no
record). Contractor ownership is a heuristic: Babcock MCS Onshore also operates
some non-HEMS aircraft, so an owner-only match can over-tag one of those flights.

The **SERVICE** button opens Service Watch: a nearest-first list of emergency
and service aircraft currently loaded within 10, 25, or 50 km of either the map
centre or your own location. A **MAP / ME** toggle switches the distance origin;
**ME** takes one location fix using the same Android/WebView location permission
as **LOCATE ME**, then falls back to the map centre if location is denied or
unavailable. Each row shows its role, callsign/registration, type, distance and
bearing, altitude, and last-seen age. Tap a row to reveal the aircraft (clearing
a conflicting filter or search), open its card, and use **CHASE**. It reads the
same flight poll as the map.

**BACKGROUND ALERTS** is an explicit opt-in. It needs a **ME** fix, requests
Android notification permission, and then locks that location, radius, and role
filters while active. The **ALERT ROLES** chips select **MEDEVAC**, **POLICE**,
**FIRE**, **RESCUE**, and **OTHER** (other emergency squawks); all start enabled.
At least one role is required. An ongoing foreground-service notification keeps
the watch alive; while the app is visible the existing Service Watch poll is
used, and only when the app is backgrounded does the service poll on its own
(about every 30 seconds). New emergency/service aircraft matching the selected
roles inside the radius produce one notification; aircraft already there when
watching starts are not announced. Stop alerts from the panel or the
notification's **STOP** action. No continuous location tracking is used: the
service only reuses the one fix taken when alerts were enabled.

Each posted alert is appended to **RECENT ALERTS** in the panel, with its role,
callsign/registration, distance, and time. The local history keeps the latest 30
entries across app restarts and Service Watch sessions, and **CLEAR** removes it.
The live Service Watch list above still shows every emergency/service aircraft in
the radius regardless of the alert-role filters.

## Satellites

Satellites are fetched from CelesTrak's public GP element sets (space stations,
weather, GPS/GLONASS/Galileo/BeiDou navigation, military and science groups). The
TLEs are propagated locally in the WebView with
[`satellite.js`](https://github.com/shashwatak/satellite-js) (MIT), so positions
update every second without a per-satellite tracking API. The satellite layer
has its own switch and **STN / NAV / WX / MIL / SCI** filters; tapping a
satellite opens its name, NORAD ID, altitude, speed and orbital period, and
**ORBIT** tracks it with an orbit ring. The catalog is deliberately a useful
subset rather than every object in orbit.

## Events

The **EVENTS** layer combines keyless feeds from USGS earthquakes and NASA EONET
wildfires, storms, floods, and volcanoes. Markers are filterable by
**ALL / QUAKES / FIRES / STORMS / VOLCANO**; tapping one opens its source,
magnitude or category, and a **FLY TO** action. The feeds refresh every five
minutes while the layer is on.

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

## About

God's Eye Lite is an independent app inspired by
[God's Eye View](https://github.com/bilawalsidhu/gods-eye-view). It is not
affiliated with that project and does not share its code.

## What is verified, and what is not

Verified while building this:

- It compiles and packages: `./gradlew assembleDebug` succeeds and produces an
  APK (roughly 4 MB in debug, including ExoPlayer and the bundled web assets).
- The globe layer runs: served on a desktop browser, Cesium boots from the CDN,
  live Esri imagery streams, and pins render, count, and toggle per pack
  correctly.
- The camera parsers match the live feeds and currently load 12,954 active
  cameras. The app splits them into roughly 3,600 live and 9,350 still cameras.
- On the Xiaomi 2107113SG, the globe loaded the full camera catalog, the
  JavaScript bridge opened camera panels, and both DelDOT and Norwegian live HLS
  streams played in a TextureView. Aircraft rendered as 2D icons and as 3D
  models; live chase, satellite orbit, and the USGS/EONET events layer were
  exercised. A live UK police helicopter (`G-TVHB`/`UKP042`) was promoted from
  its ADSBdb owner to a `POLICE` badge. The California live stream itself was not
  played on the device; its feed, parser, and HLS playlist were checked.
- Background Service Watch was exercised on the Xiaomi: a ME fix enabled the
  foreground service and ongoing STOP notification, polls continued while the
  app was backgrounded (about 30 seconds apart), and reopening or reloading the
  WebView restored `ON` with the location/radius locked. Selecting only
  `POLICE` round-tripped into the running service and stayed locked after the
  reload; clearing every role showed `PICK ROLE` and did not start the service.
  The RECENT ALERTS list and CLEAR action rendered and cleared injected records
  through the same JavaScript callback Kotlin uses. Stopping from the panel
  removed both the service and its notification. Radius, role filtering, seeding,
  new-aircraft detection, and history serialization/merging are covered by
  `ServiceWatchMonitorTest` and `ServiceWatchAlertStoreTest`. No live new service
  aircraft appeared during the device check, so the real alert notification and
  the service's end-to-end history write were not triggered on-device;
  notification permission was granted with adb rather than through the system
  prompt.
- The ion token and Cesium 3D buildings were not re-tested on this pass.

## Limitations

- **No microphone or voice control.** In a WebView, `getUserMedia` needs a
  secure context; `https://appassets.androidplatform.net` is one on paper, but
  audio capture would also need explicit `onPermissionRequest` handling that this
  app does not implement.
- **Service detection is best-effort.** A missing or contractor-style owner, a
  missing callsign, or an ambiguous operator can leave a real service aircraft
  unidentified, and owner-only contractor matches can occasionally over-tag a
  non-HEMS flight.
- **Background alerts are best-effort.** They need notification permission and
  the Android foreground service to remain running; aggressive battery settings
  can stop them. The watched area and role filters are fixed when enabled, and an
  alert only fires when a later poll sees an aircraft that was absent from an
  earlier successful poll. **RECENT ALERTS** records posted background alerts,
  not ordinary foreground sightings, and keeps only the latest 30.
- **Not affiliated with God's Eye View.** Separate app, separate code.
- Building height coverage follows OpenStreetMap, and photorealistic tiles do not
  cover every city, which is why the OSM Buildings fallback exists.
- Camera positions come from the publishers; this app does not estimate or
  correct them.
- Imagery is Esri World Imagery (OSM as fallback) — check each provider's terms
  before publishing anything built on this.
