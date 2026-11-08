import Foundation
import CoreMedia
import AVFoundation

/// Low-latency AAC-LC audio encoder for OpenDisplay.
/// Encodes raw PCM audio from ScreenCaptureKit CMSampleBuffers into AAC-LC packets.
final class AudioEncoder {
    private var converter: AVAudioConverter?
    private var inputFormat: AVAudioFormat?
    private let targetFormat: AVAudioFormat
    private let lock = NSLock()
    private var packetCount = 0
    private var loggedFirstPacket = false

    var onEncodedPacket: ((_ aacData: Data, _ ptsMs: UInt64) -> Void)?

    init() {
        var aacDesc = AudioStreamBasicDescription(
            mSampleRate: 48000.0,
            mFormatID: kAudioFormatMPEG4AAC,
            mFormatFlags: UInt32(MPEG4ObjectID.AAC_LC.rawValue),
            mBytesPerPacket: 0,
            mFramesPerPacket: 1024,
            mBytesPerFrame: 0,
            mChannelsPerFrame: 2,
            mBitsPerChannel: 0,
            mReserved: 0
        )
        self.targetFormat = AVAudioFormat(streamDescription: &aacDesc)!
    }

    private func setupConverter(input: AVAudioFormat) -> Bool {
        guard let conv = AVAudioConverter(from: input, to: targetFormat) else {
            Log.info("Could not create AVAudioConverter from \(input) to \(targetFormat)")
            return false
        }
        conv.bitRate = 128000
        self.converter = conv
        self.inputFormat = input
        Log.info("AudioConverter initialized: \(input.sampleRate)Hz \(input.channelCount)ch -> 48kHz stereo AAC (128kbps)")
        return true
    }

    func encode(sampleBuffer: CMSampleBuffer) {
        guard let formatDesc = CMSampleBufferGetFormatDescription(sampleBuffer) else { return }
        let inFormat = AVAudioFormat(cmAudioFormatDescription: formatDesc)

        let sampleCount = AVAudioFrameCount(CMSampleBufferGetNumSamples(sampleBuffer))
        guard sampleCount > 0 else { return }

        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let ptsMs = UInt64(max(0, pts.seconds * 1000.0))

        lock.lock()
        defer { lock.unlock() }

        if converter == nil || inputFormat != inFormat {
            guard setupConverter(input: inFormat) else { return }
        }
        guard let converter else { return }

        var sizeNeeded = 0
        CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sampleBuffer,
            bufferListSizeNeededOut: &sizeNeeded,
            bufferListOut: nil,
            bufferListSize: 0,
            blockBufferAllocator: nil,
            blockBufferMemoryAllocator: nil,
            flags: 0,
            blockBufferOut: nil
        )
        guard sizeNeeded > 0 else { return }

        let rawMem = UnsafeMutableRawPointer.allocate(byteCount: sizeNeeded, alignment: MemoryLayout<AudioBufferList>.alignment)
        defer { rawMem.deallocate() }
        let ablPtr = rawMem.bindMemory(to: AudioBufferList.self, capacity: 1)

        var blockBuffer: CMBlockBuffer?
        let status = CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sampleBuffer,
            bufferListSizeNeededOut: nil,
            bufferListOut: ablPtr,
            bufferListSize: sizeNeeded,
            blockBufferAllocator: nil,
            blockBufferMemoryAllocator: nil,
            flags: 0,
            blockBufferOut: &blockBuffer
        )
        guard status == noErr else { return }

        guard let pcmBuffer = AVAudioPCMBuffer(pcmFormat: inFormat, frameCapacity: sampleCount) else { return }
        pcmBuffer.frameLength = sampleCount

        let buffers = UnsafeMutableAudioBufferListPointer(ablPtr)
        for (idx, buf) in buffers.enumerated() {
            if idx < Int(inFormat.channelCount), let mData = buf.mData {
                if inFormat.isInterleaved {
                    if let dst = pcmBuffer.mutableAudioBufferList.pointee.mBuffers.mData {
                        memcpy(dst, mData, min(Int(buf.mDataByteSize), Int(pcmBuffer.mutableAudioBufferList.pointee.mBuffers.mDataByteSize)))
                    }
                } else if let channelData = pcmBuffer.floatChannelData?[idx] {
                    memcpy(channelData, mData, Int(buf.mDataByteSize))
                }
            }
        }

        let outBuffer = AVAudioCompressedBuffer(format: targetFormat, packetCapacity: 8, maximumPacketSize: 2048)
        var error: NSError?
        var consumed = false

        let convStatus = converter.convert(to: outBuffer, error: &error) { _, outStatus in
            if consumed {
                outStatus.pointee = .noDataNow
                return nil
            }
            consumed = true
            outStatus.pointee = .haveData
            return pcmBuffer
        }

        if (convStatus == .haveData || convStatus == .endOfStream || convStatus == .inputRanDry) && outBuffer.byteLength > 0 {
            if let descriptions = outBuffer.packetDescriptions, outBuffer.packetCount > 1 {
                var offset = 0
                for i in 0..<Int(outBuffer.packetCount) {
                    let packetSize = Int(descriptions[i].mDataByteSize)
                    if offset + packetSize <= Int(outBuffer.byteLength) {
                        let packetData = Data(bytes: outBuffer.data.advanced(by: offset), count: packetSize)
                        packetCount += 1
                        if !loggedFirstPacket {
                            loggedFirstPacket = true
                            Log.info("first audio packet encoded: \(packetData.count) bytes (pts: \(ptsMs)ms)")
                        }
                        onEncodedPacket?(packetData, ptsMs)
                        offset += packetSize
                    }
                }
            } else {
                let aacData = Data(bytes: outBuffer.data, count: Int(outBuffer.byteLength))
                packetCount += 1
                if !loggedFirstPacket {
                    loggedFirstPacket = true
                    Log.info("first audio packet encoded: \(aacData.count) bytes (pts: \(ptsMs)ms)")
                }
                onEncodedPacket?(aacData, ptsMs)
            }
        }
    }

    func reset() {
        lock.lock()
        defer { lock.unlock() }
        converter?.reset()
    }
}
