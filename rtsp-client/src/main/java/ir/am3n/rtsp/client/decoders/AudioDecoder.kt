package ir.am3n.rtsp.client.decoders

import android.media.*
import android.util.Log
import ir.am3n.rtsp.client.Rtsp
import ir.am3n.rtsp.client.data.Frame
import java.nio.ByteBuffer

class AudioDecoder(
    private val mimeType: String,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val codecConfig: ByteArray?,
    private val audioFrameQueue: FrameQueue
) : Thread() {

    companion object {

        private const val TAG: String = "AudioDecoder"

        fun getAacDecoderConfigData(audioProfile: Int, sampleRate: Int, channels: Int): ByteArray {
            // AOT_LC = 2
            // 0001 0000 0000 0000
            var extraDataAac = audioProfile shl 11
            // Sample rate
            when (sampleRate) {
                7350 -> extraDataAac = extraDataAac or (0xC shl 7)
                8000 -> extraDataAac = extraDataAac or (0xB shl 7)
                11025 -> extraDataAac = extraDataAac or (0xA shl 7)
                12000 -> extraDataAac = extraDataAac or (0x9 shl 7)
                16000 -> extraDataAac = extraDataAac or (0x8 shl 7)
                22050 -> extraDataAac = extraDataAac or (0x7 shl 7)
                24000 -> extraDataAac = extraDataAac or (0x6 shl 7)
                32000 -> extraDataAac = extraDataAac or (0x5 shl 7)
                44100 -> extraDataAac = extraDataAac or (0x4 shl 7)
                48000 -> extraDataAac = extraDataAac or (0x3 shl 7)
                64000 -> extraDataAac = extraDataAac or (0x2 shl 7)
                88200 -> extraDataAac = extraDataAac or (0x1 shl 7)
                96000 -> extraDataAac = extraDataAac or (0x0 shl 7)
            }
            // Channels
            extraDataAac = extraDataAac or (channels shl 3)
            val extraData = ByteArray(2)
            extraData[0] = (extraDataAac and 0xff00 shr 8).toByte() // high byte
            extraData[1] = (extraDataAac and 0xff).toByte()         // low byte
            return extraData
        }

    }

    private var isRunning = true

    init {
        name = "RTSP audio thread"
    }

    fun stopAsync() {
        if (Rtsp.DEBUG) Log.v(TAG, "stopAsync()")
        isRunning = false
        // Wake up sleep() code
        interrupt()
    }

    override fun run() {
        if (Rtsp.DEBUG) Log.d(TAG, "$name started")

        // Creating audio decoder
        val decoder = MediaCodec.createDecoderByType(mimeType)
        val format = MediaFormat.createAudioFormat(mimeType, sampleRate, channelCount)

        val csd0 = codecConfig ?: getAacDecoderConfigData(MediaCodecInfo.CodecProfileLevel.AACObjectLC, sampleRate, channelCount)
        val bb = ByteBuffer.wrap(csd0)
        format.setByteBuffer("csd-0", bb)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)

        decoder.configure(format, null, null, 0)
        decoder.start()

        // Creating audio playback device
        val outChannel = if (channelCount > 1) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val outAudio = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, outChannel, outAudio)
        //Log.i(TAG, "sampleRate: $sampleRate, bufferSize: $bufferSize".format(sampleRate, bufferSize))
        val audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setEncoding(outAudio)
                .setChannelMask(outChannel)
                .setSampleRate(sampleRate)
                .build(),
            bufferSize,
            AudioTrack.MODE_STREAM,
            0
        )
        audioTrack.play()

        val bufferInfo = MediaCodec.BufferInfo()
        while (isRunning) {
            val inIndex: Int = decoder.dequeueInputBuffer(10000L)
            if (inIndex >= 0) {
                // fill inputBuffers[inputBufferIndex] with valid data
                var byteBuffer: ByteBuffer?
                try {
                    byteBuffer = decoder.getInputBuffer(inIndex)
                } catch (e: Exception) {
                    e.printStackTrace()
                    break
                }
                byteBuffer?.rewind()

                // Preventing BufferOverflowException
//              if (length > byteBuffer.limit()) throw DecoderFatalException("Error")

                val audioFrame: Frame?
                try {
                    audioFrame = audioFrameQueue.pop()
                    if (audioFrame == null) {
                        Log.d(TAG, "Empty audio frame")
                        // Release input buffer
                        decoder.queueInputBuffer(inIndex, 0, 0, 0L, 0)
                    } else {
                        byteBuffer?.put(audioFrame.data, audioFrame.offset, audioFrame.length)
                        decoder.queueInputBuffer(inIndex, audioFrame.offset, audioFrame.length, audioFrame.timestamp, 0)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
//            Log.i(TAG, "inIndex: ${inIndex}")

            try {
//                Log.w(TAG, "outIndex: ${outIndex}")
                if (!isRunning) break
                when (val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        Log.d(TAG, "Decoder format changed: ${decoder.outputFormat}")
                    MediaCodec.INFO_TRY_AGAIN_LATER ->
                        if (Rtsp.DEBUG) Log.d(TAG, "No output from decoder available")
                    else -> {
                        if (outIndex >= 0) {
                            val byteBuffer: ByteBuffer? = decoder.getOutputBuffer(outIndex)

                            val chunk = ByteArray(bufferInfo.size)
                            byteBuffer?.get(chunk)
                            byteBuffer?.clear()

                            if (chunk.isNotEmpty()) {
                                audioTrack.write(chunk, 0, chunk.size)
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // All decoded frames have been rendered, we can stop playing now
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                Log.d(TAG, "OutputBuffer BUFFER_FLAG_END_OF_STREAM")
                break
            }
        }
        audioTrack.flush()
        audioTrack.release()

        try {
            decoder.stop()
            decoder.release()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        audioFrameQueue.clear()

        if (Rtsp.DEBUG) Log.d(TAG, "$name stopped")

    }

}

