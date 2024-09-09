package ir.am3n.rtsp.client.demo

import android.annotation.SuppressLint
import android.graphics.*
import android.media.Image
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import ir.am3n.rtsp.client.Rtsp
import ir.am3n.rtsp.client.interfaces.Frame
import ir.am3n.rtsp.client.data.SdpInfo
import ir.am3n.rtsp.client.demo.databinding.FragmentLiveBinding
import ir.am3n.rtsp.client.interfaces.RtspFrameListener
import ir.am3n.rtsp.client.interfaces.RtspStatusListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.Thread.sleep
import kotlin.concurrent.thread

@SuppressLint("SetTextI18n")
class LiveFragment : Fragment() {

    companion object {
        private const val TAG: String = "LiveFragment"
    }

    private lateinit var binding: FragmentLiveBinding
    private lateinit var liveViewModel: LiveViewModel

    private val rtsp = Rtsp()

    @Volatile
    private var disconnectCount = 0

    private var frameCounter = 0
    private var frameTimestamp = System.currentTimeMillis()


    private val rtspStatusListener = object : RtspStatusListener {

        override fun onConnecting() {
            binding.tvFrameRate.text = ""
            binding.tvStatus.text = "RTSP connecting"
            binding.etRtspRequest.isEnabled = false
        }

        override fun onConnected(sdpInfo: SdpInfo) {
            disconnectCount = 0
            binding.tvFrameRate.text = ""
            binding.tvStatus.text = "RTSP connected"
            binding.bnStartStop.text = "Stop RTSP"
        }

        override fun onFirstFrameRendered() {

        }

        override fun onDisconnecting() {

        }

        override fun onDisconnected() {
            if (Rtsp.DEBUG) Log.d(TAG, "onDisconnected()")
            disconnectCount++
            binding.tvFrameRate.text = ""
            binding.tvStatus.text = "RTSP disconnected"
            binding.bnStartStop.text = "Start RTSP"
            binding.etRtspRequest.isEnabled = true
            onError()
        }

        override fun onUnauthorized() {
            disconnectCount++
            binding.tvFrameRate.text = ""
            binding.tvStatus.text = "RTSP username or password invalid"
            onError()
        }

        override fun onFailed(message: String?) {
            disconnectCount++
            binding.tvStatus.text = "Error: $message"
            onError()
        }

        private fun onError() {
            if (disconnectCount < 3) {
                rtsp.start()
            } else {
                val timeout = when (disconnectCount) {
                    in 3..6 -> 1000L
                    in 7..10 -> 3000L
                    else -> 5000L
                }
                CoroutineScope(Dispatchers.IO).launch {
                    delay(timeout)
                    rtsp.start()
                }
            }
        }

    }


    private val rtspFrameListener = object : RtspFrameListener {

        override fun onVideoNalUnitReceived(frame: Frame?) {
            view?.post {
                disconnectCount = 0
                frameCounter++
                val now = System.currentTimeMillis()
                val diff = now - frameTimestamp
                if (diff in 1000..1500) {
                    binding.tvFrameRate.text = "$frameCounter fps"
                    frameCounter = 0
                    frameTimestamp = now
                } else if (diff > 1500) {
                    binding.tvFrameRate.text = "timeout"
                    frameCounter = 0
                    frameTimestamp = now
                }
            }
        }

        override fun onVideoFrameReceived(
            width: Int, height: Int, mediaImage: Image?,
            yuv420Bytes: ByteArray?, nv21Bytes: ByteArray?, bitmap: Bitmap?
        ) {
            Log.d(TAG, "onVideoFrameReceived()   img: $mediaImage   yuv: $yuv420Bytes   nv21: $nv21Bytes   bmp: $bitmap")
            binding.img.run {
                post { setImageBitmap(bitmap) }
            }
        }

        override fun onAudioSampleReceived(frame: Frame?) {
            Log.d(TAG, "onAudioSampleReceived()   ${frame?.data?.size}")
        }

    }


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (Rtsp.DEBUG) Log.v(TAG, "onCreateView()")

        liveViewModel = ViewModelProvider(this)[LiveViewModel::class.java]
        binding = FragmentLiveBinding.inflate(inflater, container, false)

        binding.etRtspRequest.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s.toString()
                if (text != liveViewModel.rtspRequest.value) {
                    liveViewModel.rtspRequest.value = text
                }
            }
        })

        liveViewModel.rtspRequest.observe(viewLifecycleOwner) {
            if (binding.etRtspRequest.text.toString() != it)
                binding.etRtspRequest.setText(it)
        }

        binding.bnCheckOnline.setOnClickListener {
            CoroutineScope(Dispatchers.Main).launch {
                val isOnline = Rtsp.isOnline(liveViewModel.rtspRequest.value!!)
                Toast.makeText(context, "is online: $isOnline", Toast.LENGTH_SHORT).show()
            }
        }

        binding.bnStartStop.setOnClickListener {
            liveViewModel.saveParams(requireContext())
            if (rtsp.isStarted()) {
                rtsp.stop()
            } else {

                binding.rsv.init(liveViewModel.rtspRequest.value!!.toUri())
                binding.rsv.start(playVideo = true, playAudio = true)

                thread {
                    sleep(2000)
                    rtsp.init(liveViewModel.rtspRequest.value!!, timeout = 2_000)
                    rtsp.start(playVideo = true, playAudio = true)
                }

            }
        }

        rtsp.setStatusListener(rtspStatusListener)
        rtsp.setFrameListener(rtspFrameListener)

        rtsp.setSurfaceView(binding.svVideo)

        //rtsp.setRequestMediaImage(true)
        //rtsp.setRequestYuvBytes(true)
        //rtsp.setRequestNv21Bytes(true)
        //rtsp.setRequestBitmap(true)
        //rtsp.setRequestAudioSample(true)

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        if (Rtsp.DEBUG) Log.v(TAG, "onResume()")
        liveViewModel.loadParams(requireContext())
    }

    override fun onPause() {
        super.onPause()
        if (Rtsp.DEBUG) Log.v(TAG, "onPause()")
        liveViewModel.saveParams(requireContext())
    }

}
