package ir.am3n.rtsp.client.interfaces

import android.graphics.Bitmap
import android.media.Image

interface RtspFrameListener {
    fun onVideoNalUnitReceived(frame: Frame?)
    fun onVideoFrameReceived(width: Int, height: Int, mediaImage: Image?, yuvBytes: ByteArray?, bitmap: Bitmap?)
    fun onAudioSampleReceived(frame: Frame?)
}