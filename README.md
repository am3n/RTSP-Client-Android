# Rtsp client android
![MinAPI](https://img.shields.io/badge/API-21%2B-blue)
[![Release](https://jitpack.io/v/am3n/RTSP-Client-Android.svg)](https://jitpack.io/#am3n/RTSP-Client-Android)

Lightweight RTSP client library for Android.

![Screenshot](docs/images/Screenshot_20221026_182823.png?raw=true "Screenshot")

## Features

- Android min API 21.
- RTSP/RTSPS over TCP.
- Video H.264 only.
- Audio AAC LC only.
- Basic/Digest authentication.
- Supports majority of RTSP IP cameras.

## Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## Installation

To use this library in your project add this to your build.gradle:

```gradle
allprojects {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}
dependencies {
    implementation 'com.github.am3n:rtsp-client-android:NEWEST-VERSION'
}
```

## Usage

1) Easiest way is just to use `SurfaceView` class for showing video stream in UI.

```xml
<SurfaceView
    android:id="@+id/svVideo"
    android:layout_width="match_parent" 
    android:layout_height="match_parent" />
```

Then in code use:

```kotlin
val url = "rtsps://10.0.1.3/test.sdp"
val username = "admin"
val password = "secret"
val rtsp = Rtsp()
rtsp.init(uri, username, password)
rtsp.setSurfaceView(binding.svVideo)
rtsp.start()
// ...
rtsp.stop()
```

---

2) You can still use library without any decoding (just for obtaining raw frames), e.g. for writing video stream into MP4 via muxer.

```kotlin
val rtspStatusListener = object : RtspStatusListener {
    override fun onConnecting() {}
    override fun onConnected(sdpInfo: SdpInfo) {}
    override fun onVideoNalUnitReceived(frame: Frame) {
        // Send raw H264/H265 NAL unit to decoder
    }
    override fun onAudioSampleReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
        // Send raw audio to decoder
    }
    override fun onDisconnected() {}
    override fun onUnauthorized() {
        Log.e(TAG, "RTSP failed unauthorized")
    }
    override fun onFailed(message: String?) {
        Log.e(TAG, "RTSP failed with message '$message'")
    }
}
// ... build rtsp
rtsp.setStatusListener(rtspStatusListener)
// rtsp.setSurfaceView(..) don't set surface view
rtsp.start(autoPlayAudio = false) // turn off autoPlayAudio
// ...
rtsp.stop()
```

---

Also you can just check camera is online or not.

```kotlin
launch {
    val url = "rtsps://10.0.1.3/test.sdp"
    val username = "admin"
    val password = "secret"
    val isOnline = Rtsp.isOnline(url, username, password)
    Log.d(TAG, "Camera is online: $isOnline")
}
```

## Credits

* https://github.com/alexeyvasilyev/rtsp-client-android

