package ir.am3n.rtsp.client.interfaces

import ir.am3n.rtsp.client.data.Frame
import ir.am3n.rtsp.client.data.SdpInfo

interface RtspStatusListener {
    fun onConnecting()
    fun onConnected(sdpInfo: SdpInfo)
    fun onVideoNalUnitReceived(frame: Frame)
    fun onAudioSampleReceived(frame: Frame)
    fun onDisconnected()
    fun onUnauthorized()
    fun onFailed(message: String?)
}