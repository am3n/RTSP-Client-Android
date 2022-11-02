package ir.am3n.rtsp.client.interfaces

import android.graphics.Bitmap
import android.media.Image
import ir.am3n.rtsp.client.data.Frame
import ir.am3n.rtsp.client.data.SdpInfo

interface RtspStatusListener {
    fun onConnecting()
    fun onConnected(sdpInfo: SdpInfo)
    fun onVideoNalUnitReceived(frame: Frame?)
    fun onVideoFrameReceived(width: Int, height: Int, mediaImage: Image?, yuv420Bytes: ByteArray?, bitmap: Bitmap?)
    fun onAudioSampleReceived(frame: Frame?)
    fun onDisconnected()
    fun onUnauthorized()
    fun onFailed(message: String?)
}