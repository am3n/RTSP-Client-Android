package ir.am3n.rtsp.client.decoders

import ir.am3n.rtsp.client.data.VideoFrame

class VideoFrameQueue(frameQueueCapacity: Int): FrameQueue<VideoFrame>(frameQueueCapacity)