# Rtsp client android
![MinAPI](https://img.shields.io/badge/API-23%2B-blue)
[![Release](https://jitpack.io/v/am3n/RTSP-Client-Android.svg)](https://jitpack.io/#am3n/RTSP-Client-Android)

<b>Lightweight RTSP client library for Android</b> with almost zero lag video decoding (achieved 20 msec video decoding latency on some RTSP streams). Designed for lag criticial applications (e.g. video surveillance from drones).

Unlike [AndroidX Media ExoPlayer](https://github.com/androidx/media) which also supports RTSP, this library does not make any video buffering. Video frames are shown immidiately when they arrive.

![Screenshot](docs/images/Screenshot_20221026_182823.png?raw=true "Screenshot")

## Features

- Android min API 23.
- RTSP/RTSPS over TCP.
- Video H.264 only.
- Audio AAC LC only.
- Basic/Digest authentication.
- Supports majority of RTSP IP cameras.
- Auto Decode raw frames to Media Image & YUV & Bitmap.
- Using [renderscript-intrinsics-replacement-toolkit](https://github.com/android/renderscript-intrinsics-replacement-toolkit) for YUV to Bitmap decoding.

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

### 1) RtspSurfaceView

Easiest way is just to use `RtspSurfaceView` class for showing video stream.

```xml
<ir.am3n.rtsp.client.widget.RtspSurfaceView
    android:id="@+id/rsv"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

Then in code use:

```kotlin
val url = "rtsps://10.0.1.3/test.sdp"
val username = "admin"
val password = "secret"
binding.rsv.init(url, username, password)
binding.rsv.setStatusListener(object : RtspStatusListener {
    override fun onConnecting() {}
    override fun onConnected(sdpInfo: SdpInfo) {}
    override fun onFirstFrameRendered() {}
    override fun onDisconnecting() {}
    override fun onDisconnected() {}
    override fun onUnauthorized() {}
    override fun onFailed(message: String?) {}
})
binding.rsv.start(playVideo = true, playAudio = true)
// ...
binding.rsv.stop()
```

---

### 2) Default Android SurfaceView

Next way is default `SurfaceView` class for showing video stream.

```xml
<SurfaceView
    android:id="@+id/svVideo"
    android:layout_width="match_parent" 
    android:layout_height="match_parent" />
```

Then in code use:

```kotlin
// ################# build rtsp ########################
val url = "rtsps://10.0.1.3/test.sdp"
val username = "admin"
val password = "secret"
val rtsp = Rtsp()
rtsp.init(url, username, password)
rtsp.setStatusListener(object : RtspStatusListener {
    override fun onConnecting() {}
    override fun onConnected(sdpInfo: SdpInfo) {}
    override fun onFirstFrameRendered() {}
    override fun onDisconnecting() {}
    override fun onDisconnected() {}
    override fun onUnauthorized() {}
    override fun onFailed(message: String?) {}
})
// ###################################################
rtsp.setSurfaceView(binding.svVideo)
rtsp.setRequestAudioSample(true)
rtsp.start(playVideo = true, playAudio = true)
// ...
rtsp.stop()
```

---

### 3) H264 raw frame

You can still use library without any decoding. (Just for obtaining raw H264 frames)

e.g. for writing video stream into MP4 via muxer.

```kotlin
// ... build rtsp
rtsp.setFrameListener(object : RtspFrameListener {
    override fun onVideoNalUnitReceived(frame: Frame?) {
        // Send raw H264 NAL unit to your custom decoder
    }
    override fun onVideoFrameReceived(width: Int, height: Int, mediaImage: Image?, yuv: YuvFrame?, bitmap: Bitmap?) {}
    override fun onAudioSampleReceived(frame: Frame?) {
        // Send raw audio to decoder
    }
})
rtsp.setRequestAudioSample(true)
rtsp.start(playVideo = true, playAudio = true)
// ...
rtsp.stop()
```

---

### 4) MediaImage

You can still use library with H264 to MediaImage decoding.

```kotlin
// ... build rtsp
rtsp.setFrameListener(object : RtspFrameListener {
    override fun onVideoNalUnitReceived(frame: Frame?) {}
    override fun onVideoFrameReceived(width: Int, height: Int, mediaImage: Image?, yuv: YuvFrame?, bitmap: Bitmap?) { 
        // Notice: you should use mediaImage object sync on this thread
    }
    override fun onAudioSampleReceived(frame: Frame?) {
        // Send raw audio to decoder
    }
})
rtsp.setRequestAudioSample(true)
rtsp.setRequestMediaImage(true)
rtsp.start(playVideo = true, playAudio = true)
// ...
rtsp.stop()
```

---

### 5) YUV frame

You can still use library with H264 to YUV decoding.

```kotlin
// ... build rtsp
rtsp.setFrameListener(object : RtspFrameListener {
    override fun onVideoNalUnitReceived(frame: Frame?) {}
    override fun onVideoFrameReceived(width: Int, height: Int, mediaImage: Image?, yuv: YuvFrame?, bitmap: Bitmap?) {
        // you can decode YUV to Bitmap by your custom decoder
    }
    override fun onAudioSampleReceived(frame: Frame?) {
        // Send raw audio to decoder
    }
})
rtsp.setRequestAudioSample(true)
rtsp.setRequestYuv(true)
rtsp.start(playVideo = true, playAudio = true)
// ...
rtsp.stop()
```

---

### 6) Bitmap

You can still use library with H264 to Bitmap decoding.

```kotlin
// ... build rtsp
rtsp.setFrameListener(object : RtspFrameListener {
    override fun onVideoNalUnitReceived(frame: Frame?) {}
    override fun onVideoFrameReceived(width: Int, height: Int, mediaImage: Image?, yuv: YuvFrame?, bitmap: Bitmap?) {
        // Use the bitmap
    }
    override fun onAudioSampleReceived(frame: Frame?) {
        // Send raw audio to decoder
    }
})
rtsp.setRequestAudioSample(true)
rtsp.setRequestBitmap(true)
rtsp.start(playVideo = true, playAudio = true)
// ...
rtsp.stop()
```


---


### Also you can just check camera is online or not.

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
* https://github.com/android/renderscript-intrinsics-replacement-toolkit


 
