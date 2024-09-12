package ir.am3n.rtsp.client.interfaces

import android.graphics.Bitmap
import android.media.Image
import ir.am3n.rtsp.client.data.YuvFrame

interface RtspFrameListener {
    fun onVideoNalUnitReceived(frame: Frame?)
    fun onVideoFrameReceived(width: Int, height: Int, mediaImage: Image?, yuv: YuvFrame?, bitmap: Bitmap?)
    fun onAudioSampleReceived(frame: Frame?)
}