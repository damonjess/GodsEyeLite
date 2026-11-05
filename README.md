# God's Eye Lite

A small Android app with a 3D globe, buildings, and public traffic cameras. Tap a camera to view its latest image or video.

## Run the app

Open the project in Android Studio and press **Run**, or build a debug APK with:

```bash
./gradlew assembleDebug
```

The APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

**Requirements:** Android SDK platform 35 and JDK 17 or newer.

## 3D buildings

The globe and satellite imagery work without a key. To enable terrain and 3D buildings, add a Cesium ion token in the app using **ION KEY**. The token needs the `assets:read` scope and should allow `https://appassets.androidplatform.net`.

## Camera data

The app includes public camera feeds from Austin, Calgary, Washington, London, Finland, Norway, Hong Kong, British Columbia, New South Wales, A22 Brenner, Delaware, and California. Camera images and video are provided by their respective publishers; check their terms and attribution requirements before reuse.

## About

God's Eye Lite is an independent app inspired by [God's Eye View](https://github.com/bilawalsidhu/gods-eye-view). It is not affiliated with that project and does not share its code.
