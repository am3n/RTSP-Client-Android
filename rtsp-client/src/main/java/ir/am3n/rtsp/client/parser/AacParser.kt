package ir.am3n.rtsp.client.parser

import android.annotation.SuppressLint
import android.util.Log
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray
import ir.am3n.rtsp.client.Rtsp

// https://tools.ietf.org/html/rfc3640
//          +---------+-----------+-----------+---------------+
//         | RTP     | AU Header | Auxiliary | Access Unit   |
//         | Header  | Section   | Section   | Data Section  |
//         +---------+-----------+-----------+---------------+
//
//                   <----------RTP Packet Payload----------->
@SuppressLint("UnsafeOptInUsageError")
class AacParser(aacMode: String) {

    companion object {

        private const val TAG: String = "AacParser"

        private const val MODE_LBR = 0
        private const val MODE_HBR = 1

        // Number of bits for AAC AU sizes, indexed by mode (LBR and HBR)
        private val NUM_BITS_AU_SIZES = intArrayOf(6, 13)

        // Number of bits for AAC AU index(-delta), indexed by mode (LBR and HBR)
        private val NUM_BITS_AU_INDEX = intArrayOf(2, 3)

        // Frame Sizes for AAC AU fragments, indexed by mode (LBR and HBR)
        private val FRAME_SIZES = intArrayOf(63, 8191)

    }

    private val headerScratchBits: ParsableBitArray = ParsableBitArray()
    private val headerScratchBytes: ParsableByteArray = ParsableByteArray()
    private val aacModeValue: Int = if (aacMode.equals("AAC-lbr", ignoreCase = true)) MODE_LBR else MODE_HBR
    private val completeFrameIndicator = true

    fun processRtpPacketAndGetSample(data: ByteArray, length: Int): ByteArray {
        if (Rtsp.DEBUG) Log.v(TAG, "processRtpPacketAndGetSample(length=$length)")
        var auHeadersCount = 1
        val numBitsAuSize = NUM_BITS_AU_SIZES[aacModeValue]
        val numBitsAuIndex = NUM_BITS_AU_INDEX[aacModeValue]

        val packet = ParsableByteArray(data, length)

        //      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+- .. -+-+-+-+-+-+-+-+-+-+
//      |AU-headers-length|AU-header|AU-header|      |AU-header|padding|
//      |                 |   (1)   |   (2)   |      |   (n)   | bits  |
//      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+- .. -+-+-+-+-+-+-+-+-+-+
        val auHeadersLength = packet.readShort().toInt() //((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        val auHeadersLengthBytes = (auHeadersLength + 7) / 8

        headerScratchBytes.reset(auHeadersLengthBytes)
        packet.readBytes(headerScratchBytes.data, 0, auHeadersLengthBytes)
        headerScratchBits.reset(headerScratchBytes.data)

        val bitsAvailable = auHeadersLength - (numBitsAuSize + numBitsAuIndex)

        if (bitsAvailable > 0) { // && (numBitsAuSize + numBitsAuSize) > 0) {
            auHeadersCount += bitsAvailable / (numBitsAuSize + numBitsAuIndex)
        }

        if (auHeadersCount == 1) {
            val auSize = headerScratchBits.readBits(numBitsAuSize)
            val auIndex = headerScratchBits.readBits(numBitsAuIndex)

            if (completeFrameIndicator) {
                if (auIndex == 0) {
                    if (packet.bytesLeft() == auSize) {
                        return handleSingleAacFrame(packet)
                    } else {
//                        handleFragmentationAacFrame(packet, auSize);
                    }
                }
            } else {
//                handleFragmentationAacFrame(packet, auSize);
            }
        } else {
            if (completeFrameIndicator) {
//                handleMultipleAacFrames(packet, auHeadersLength);
            }
        }
        //        byte[] auHeader = new byte[length-2-auHeadersLengthBytes];
//        System.arraycopy(data,2-auHeadersLengthBytes, auHeader,0, auHeader.length);
//        if (DEBUG)
//            Log.d(TAG, "AU headers size: " + auHeadersLengthBytes + ", AU headers: " + auHeadersCount + ", sample length: " + auHeader.length);
//        return auHeader;
        return ByteArray(0)
    }

    private fun handleSingleAacFrame(packet: ParsableByteArray): ByteArray {
        val length = packet.bytesLeft()
        val data = ByteArray(length)
        System.arraycopy(packet.data, packet.position, data, 0, data.size)
        return data
    } //    private static final class AUHeader {
    //        private int size;
    //        private int index;
    //
    //        public AUHeader(int size, int index) {
    //            this.size = size;
    //            this.index = index;
    //        }
    //
    //        public int size() { return size; }
    //
    //        public int index() { return index; }
    //    }
    //    /**
    //     * Stores the consecutive fragment AU to reconstruct an AAC-Frame
    //     */
    //    private static final class FragmentedAacFrame {
    //        public byte[] auData;
    //        public int auLength;
    //        public int auSize;
    //
    //        private int sequence;
    //
    //        public FragmentedAacFrame(int frameSize) {
    //            // Initialize data
    //            auData = new byte[frameSize];
    //            sequence = -1;
    //        }
    //
    //        /**
    //         * Resets the buffer, clearing any data that it holds.
    //         */
    //        public void reset() {
    //            auLength = 0;
    //            auSize = 0;
    //            sequence = -1;
    //        }
    //
    //        public void sequence(int sequence) {
    //            this.sequence = sequence;
    //        }
    //
    //        public int sequence() {
    //            return sequence;
    //        }
    //
    //        /**
    //         * Called to add a fragment unit to fragmented AU.
    //         *
    //         * @param fragment Holds the data of fragment unit being passed.
    //         * @param offset The offset of the data in {@code fragment}.
    //         * @param limit The limit (exclusive) of the data in {@code fragment}.
    //         */
    //        public void appendFragment(byte[] fragment, int offset, int limit) {
    //            if (auSize == 0) {
    //                auSize = limit;
    //            } else if (auSize != limit) {
    //                reset();
    //            }
    //
    //            if (auData.length < auLength + limit) {
    //                auData = Arrays.copyOf(auData, (auLength + limit) * 2);
    //            }
    //
    //            System.arraycopy(fragment, offset, auData, auLength, limit);
    //            auLength += limit;
    //        }
    //
    //        public boolean isCompleted() {
    //            return auSize == auLength;
    //        }
    //    }

}
