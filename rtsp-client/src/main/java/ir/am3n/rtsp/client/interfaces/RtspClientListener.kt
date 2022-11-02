package ir.am3n.rtsp.client.interfaces

import android.graphics.Bitmap
import android.media.Image
import ir.am3n.rtsp.client.data.SdpInfo

internal interface RtspClientListener {
    fun onRtspConnected(sdpInfo: SdpInfo)
    fun onRtspVideoNalUnitReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long)
    fun onRtspVideoFrameReceived(width: Int, height: Int, mediaImage: Image?, yuv420Bytes: ByteArray?, bitmap: Bitmap?)
    fun onRtspAudioSampleReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long)
    fun onRtspDisconnected()
    fun onRtspFailedUnauthorized()
    fun onRtspFailed(message: String?)
}