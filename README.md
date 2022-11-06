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
- Auto Decode H.264 to Media Image & YUV ByteArray & Bitmap.

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

### 1) Easiest way is just to use `SurfaceView` class for showing video stream in UI.

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
rtsp.setStatusListener(object : RtspStatusListener {
    override fun onConnecting() {}
    override fun onConnected(sdpInfo: SdpInfo) {}
    override fun onDisconnected() {}
    override fun onUnauthorized() {}
    override fun onFailed(message: String?) {}
})
rtsp.setSurfaceView(binding.svVideo)
rtsp.start()
// ...
rtsp.stop()
```

---

### 2) You can still use library without any decoding (just for obtaining raw H264/H265 frames) 
e.g. for writing video stream into MP4 via muxer.

```kotlin
// ... build rtsp
rtsp.setFrameListener(object : RtspFrameListener {
    override fun onVideoNalUnitReceived(frame: Frame?) {
        // Send raw H264/H265 NAL unit to decoder
    }
    override fun onVideoFrameReceived(width: Int, height: Int, mediaImage: Image?, yuv420Bytes: ByteArray?, bitmap: Bitmap?) {}
    override fun onAudioSampleReceived(frame: Frame?) {
        // Send raw audio to decoder
    }
})
rtsp.start(autoPlayAudio = false) // turn off autoPlayAudio
// ...
rtsp.stop()
```

---

### 3) You can still use library with H264/H265 to YUV MediaImage decoding

```kotlin
// ... build rtsp
rtsp.setFrameListener(object : RtspFrameListener {
    override fun onVideoNalUnitReceived(frame: Frame?) {}
    override fun onVideoFrameReceived(width: Int, height: Int, mediaImage: Image?, yuv420Bytes: ByteArray?, bitmap: Bitmap?) {
        if (mediaImage != null) {
            // Just use it!
            // Notice that you should use that sync on this thread
            /**
            val task = textRecognizer.process(InputImage.fromBitmap(mediaImage, 0))
            val text = Tasks.await(task, 2000, TimeUnit.MILLISECONDS)
             */
        }
    }
    override fun onAudioSampleReceived(frame: Frame?) {
        // Send raw audio to decoder
    }
})
rtsp.setRequestMediaImage(true)
rtsp.start(autoPlayAudio = false) // turn off autoPlayAudio
// ...
rtsp.stop()
```

---

### 4) You can still use library with H264/H265 to YUV ByteArray decoding

```kotlin
// ... build rtsp
rtsp.setFrameListener(object : RtspFrameListener {
    override fun onVideoNalUnitReceived(frame: Frame?) {}
    override fun onVideoFrameReceived(width: Int, height: Int, mediaImage: Image?, yuv420Bytes: ByteArray?, bitmap: Bitmap?) {
        // you can decode YUV to Bitmap by Android New RenderScript Toolkit that integrated in the library 
        // or your custom decoder
        /**
        if (yuv420Bytes != null) {
        Toolkit.yuvToRgbBitmap(yuv420Bytes, width, height, YuvFormat.YUV_420_888)
        }
         */
    }
    override fun onAudioSampleReceived(frame: Frame?) {
        // Send raw audio to decoder
    }
})
rtsp.setRequestYuvBytes(true)
rtsp.start(autoPlayAudio = false) // turn off autoPlayAudio
// ...
rtsp.stop()
```

---


### 5) You can still use library with H264/H265 to Bitmap decoding

```kotlin
// ... build rtsp
rtsp.setFrameListener(object : RtspFrameListener {
    override fun onVideoNalUnitReceived(frame: Frame?) {}
    override fun onVideoFrameReceived(width: Int, height: Int, mediaImage: Image?, yuv420Bytes: ByteArray?, bitmap: Bitmap?) {
        if (bitmap != null) {
            // Just use it!
            /**
            binding.img.run {
            post { setImageBitmap(bitmap?.removeTimestamp()) }
            }
             */
        }
    }
    override fun onAudioSampleReceived(frame: Frame?) {
        // Send raw audio to decoder
    }
})
rtsp.setRequestBitmap(true)
rtsp.start(autoPlayAudio = false) // turn off autoPlayAudio
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


 
