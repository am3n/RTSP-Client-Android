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
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import ir.am3n.rtsp.client.Rtsp
import ir.am3n.rtsp.client.data.Frame
import ir.am3n.rtsp.client.data.SdpInfo
import ir.am3n.rtsp.client.demo.databinding.FragmentLiveBinding
import ir.am3n.rtsp.client.interfaces.RtspStatusListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("SetTextI18n")
class LiveFragment : Fragment() {

    companion object {
        private const val TAG: String = "LiveFragment"
        private const val DEBUG = true
    }

    private lateinit var binding: FragmentLiveBinding
    private lateinit var liveViewModel: LiveViewModel

    private val rtsp = Rtsp()

    private var frameCounter = 0
    private var frameTimestamp = System.currentTimeMillis()

    private val rtspStatusListener = object : RtspStatusListener {

        @Volatile
        private var disconnectCount = 0

        override fun onConnecting() {
            binding.tvFrameRate.text = ""
            binding.tvStatus.text = "RTSP connecting"
            binding.pbLoading.visibility = View.VISIBLE
            binding.etRtspRequest.isEnabled = false
        }

        override fun onConnected(sdpInfo: SdpInfo) {
            disconnectCount = 0
            binding.tvFrameRate.text = ""
            binding.tvStatus.text = "RTSP connected"
            binding.bnStartStop.text = "Stop RTSP"
            binding.pbLoading.visibility = View.GONE
        }

        override fun onVideoNalUnitReceived(frame: Frame?) {
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

        override fun onVideoFrameReceived(width: Int, height: Int, mediaImage: Image?, yuv420Bytes: ByteArray?, bitmap: Bitmap?) {
            Log.d(TAG, "onVideoFrameReceived()  mediaImage: $mediaImage   yuv420Bytes: $yuv420Bytes   bitmap: $bitmap")

            /**
            val task = textRecognizer.process(InputImage.fromBitmap(mediaImage, 0))
            val text = Tasks.await(task, 2000, TimeUnit.MILLISECONDS)
            */

            /**
            if (yuv420Bytes != null) {
                Toolkit.yuvToRgbBitmap(yuv420Bytes, width, height, YuvFormat.YUV_420_888)
            }
            */

            /**
            binding.img.run {
                post { setImageBitmap(bitmap?.removeTimestamp()) }
            }
            */

        }

        override fun onAudioSampleReceived(frame: Frame?) {

        }

        override fun onDisconnected() {
            disconnectCount++
            binding.tvFrameRate.text = ""
            binding.tvStatus.text = "RTSP disconnected"
            binding.bnStartStop.text = "Start RTSP"
            binding.pbLoading.visibility = View.GONE
            binding.etRtspRequest.isEnabled = true
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

        override fun onUnauthorized() {
            binding.tvFrameRate.text = ""
            binding.tvStatus.text = "RTSP username or password invalid"
            binding.pbLoading.visibility = View.GONE
        }

        override fun onFailed(message: String?) {
            binding.tvStatus.text = "Error: $message"
            binding.pbLoading.visibility = View.GONE
        }

    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (DEBUG) Log.v(TAG, "onCreateView()")

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
            if (rtsp.isStarted()) {
                rtsp.stop()
            } else {
                rtsp.init(liveViewModel.rtspRequest.value!!)
                rtsp.start(requestVideo = true, requestAudio = true, autoPlayAudio = true)
            }
        }

        rtsp.setStatusListener(rtspStatusListener)
        rtsp.setSurfaceView(binding.svVideo)
        //rtsp.setRequestMediaImage(true)
        //rtsp.setRequestYuvBytes(true)
        //rtsp.setRequestBitmap(true)

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        if (DEBUG) Log.v(TAG, "onResume()")
        liveViewModel.loadParams(requireContext())
    }

    override fun onPause() {
        super.onPause()
        if (DEBUG) Log.v(TAG, "onPause()")
        liveViewModel.saveParams(requireContext())
    }

    private fun Bitmap.removeTimestamp(): Bitmap {
        Canvas(this).apply {
            drawRect(Rect(19, 12, 444, 40), Paint().apply { color = Color.LTGRAY })
        }
        return this
    }

}
