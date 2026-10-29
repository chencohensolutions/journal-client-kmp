import AVFoundation

/// Sound recordings in Ogg Opus (WhatsApp's voice messages, some recorders): AVPlayer reads neither the Ogg container
/// nor Opus in it, but AudioToolbox decodes Opus packets. This takes the packets out of the Ogg pages (RFC 3533, the
/// Opus mapping of RFC 7845) and decodes them to a PCM file AVPlayer plays and seeks in.
enum OggOpus {
    enum Failure: Error { case notOggOpus, decoder }

    /// Whether `data` starts as an Ogg stream holding Opus.
    static func isOggOpus(_ data: Data) -> Bool {
        guard data.count > 36, data.starts(with: Array("OggS".utf8)) else { return false }
        return data.range(of: Data("OpusHead".utf8), in: 0..<min(data.count, 200)) != nil
    }

    /// Decodes the Ogg Opus `data` into a CAF file of 32-bit float PCM at 48 kHz at `target`.
    static func decode(_ data: Data, to target: URL) throws {
        let packets = try packetsOf(data)
        guard let head = packets.first, head.starts(with: Array("OpusHead".utf8)), head.count >= 19 else { throw Failure.notOggOpus }
        let channels = Int(head[head.startIndex + 9])
        let preSkip = Int(head[head.startIndex + 10]) | Int(head[head.startIndex + 11]) << 8
        guard channels == 1 || channels == 2 else { throw Failure.notOggOpus }
        // After the header and the tags: the sound.
        let audio = packets.dropFirst(2).filter { !$0.isEmpty }

        // The decoder is told the packets' length; an encoder keeps one for the whole stream (20 ms; some use 60).
        guard let first = audio.first, let frames = framesIn(first) else { throw Failure.notOggOpus }
        var description = AudioStreamBasicDescription(
            mSampleRate: 48_000, mFormatID: kAudioFormatOpus, mFormatFlags: 0, mBytesPerPacket: 0, mFramesPerPacket: UInt32(frames),
            mBytesPerFrame: 0, mChannelsPerFrame: UInt32(channels), mBitsPerChannel: 0, mReserved: 0
        )
        let layout = AVAudioChannelLayout(layoutTag: channels == 2 ? kAudioChannelLayoutTag_Stereo : kAudioChannelLayoutTag_Mono)!
        guard let input = AVAudioFormat(streamDescription: &description, channelLayout: layout),
              let output = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: AVAudioChannelCount(channels), interleaved: false),
              let converter = AVAudioConverter(from: input, to: output)
        else { throw Failure.decoder }
        // Apple's Opus decoder reads its settings from a cookie of its own (as afconvert writes it in a CAF), big
        // endian: 2048, the sample rate, the frame size, -1000, the channels, and two zeros. Without it, it decodes
        // mono only.
        converter.magicCookie = cookie(channels: channels, frames: frames)

        try? FileManager.default.removeItem(at: target)
        let file = try AVAudioFile(forWriting: target, settings: output.settings, commonFormat: .pcmFormatFloat32, interleaved: false)
        var next = audio.startIndex
        var skip = preSkip
        // An Opus packet holds at most 120 ms.
        guard let pcm = AVAudioPCMBuffer(pcmFormat: output, frameCapacity: 5_760) else { throw Failure.decoder }
        while true {
            pcm.frameLength = 0
            var error: NSError?
            let status = converter.convert(to: pcm, error: &error) { _, inputStatus in
                guard next < audio.endIndex else {
                    inputStatus.pointee = .endOfStream
                    return nil
                }
                let packet = audio[next]
                next += 1
                let buffer = AVAudioCompressedBuffer(format: input, packetCapacity: 1, maximumPacketSize: packet.count)
                packet.withUnsafeBytes { buffer.data.copyMemory(from: $0.baseAddress!, byteCount: packet.count) }
                buffer.packetDescriptions?.pointee = AudioStreamPacketDescription(
                    mStartOffset: 0, mVariableFramesInPacket: 0, mDataByteSize: UInt32(packet.count)
                )
                buffer.packetCount = 1
                buffer.byteLength = UInt32(packet.count)
                inputStatus.pointee = .haveData
                return buffer
            }
            if status == .error { throw error ?? Failure.decoder }
            if pcm.frameLength > 0 {
                // The encoder's priming samples at the start aren't sound (OpusHead's pre-skip).
                if skip > 0 {
                    let dropped = min(skip, Int(pcm.frameLength))
                    skip -= dropped
                    if dropped < Int(pcm.frameLength), let shifted = tail(of: pcm, from: dropped) { try file.write(from: shifted) }
                } else {
                    try file.write(from: pcm)
                }
            }
            if status == .endOfStream || (status == .inputRanDry && next >= audio.endIndex) { break }
        }
    }

    private static func cookie(channels: Int, frames: Int) -> Data {
        var data = Data()
        for value in [Int32(2048), 48_000, Int32(frames), -1000, Int32(channels), 0, 0] {
            withUnsafeBytes(of: value.bigEndian) { data.append(contentsOf: $0) }
        }
        return data
    }

    /// The samples at 48 kHz an Opus packet holds, from its TOC byte (RFC 6716, 3.1); nil for an empty one.
    static func framesIn(_ packet: Data) -> Int? {
        guard let toc = packet.first else { return nil }
        let config = Int(toc >> 3)
        // Tenths of a millisecond: SILK 10/20/40/60, hybrid 10/20, CELT 2.5/5/10/20.
        let tenths: Int
        switch config {
        case 0..<12: tenths = [100, 200, 400, 600][config % 4]
        case 12..<16: tenths = [100, 200][config % 2]
        default: tenths = [25, 50, 100, 200][config % 4]
        }
        let count: Int
        switch toc & 3 {
        case 0: count = 1
        case 1, 2: count = 2
        default: count = packet.count > 1 ? Int(packet[packet.startIndex + 1] & 0x3F) : 1
        }
        return tenths * 48 / 10 * count
    }

    /// The frames of `buffer` from `start` on, as a buffer of their own.
    private static func tail(of buffer: AVAudioPCMBuffer, from start: Int) -> AVAudioPCMBuffer? {
        let count = Int(buffer.frameLength) - start
        guard count > 0, let copy = AVAudioPCMBuffer(pcmFormat: buffer.format, frameCapacity: AVAudioFrameCount(count)),
              let from = buffer.floatChannelData, let to = copy.floatChannelData else { return nil }
        for channel in 0..<Int(buffer.format.channelCount) {
            to[channel].update(from: from[channel] + start, count: count)
        }
        copy.frameLength = AVAudioFrameCount(count)
        return copy
    }

    /// The packets of the first logical stream in the Ogg pages of `data`, joined across pages as the lacing says.
    static func packetsOf(_ data: Data) throws -> [Data] {
        let bytes = [UInt8](data)
        var packets: [Data] = []
        var partial = Data()
        var serial: UInt32?
        var at = 0
        while at + 27 <= bytes.count {
            guard bytes[at] == 0x4F, bytes[at + 1] == 0x67, bytes[at + 2] == 0x67, bytes[at + 3] == 0x53 else { throw Failure.notOggOpus }
            let pageSerial = UInt32(bytes[at + 14]) | UInt32(bytes[at + 15]) << 8 | UInt32(bytes[at + 16]) << 16 | UInt32(bytes[at + 17]) << 24
            let segments = Int(bytes[at + 26])
            let table = at + 27
            guard table + segments <= bytes.count else { break }
            var body = table + segments
            let mine = serial == nil || serial == pageSerial
            if serial == nil { serial = pageSerial }
            for i in 0..<segments {
                let length = Int(bytes[table + i])
                guard body + length <= bytes.count else { break }
                if mine {
                    partial.append(contentsOf: bytes[body..<body + length])
                    // A lacing value under 255 ends a packet.
                    if length < 255 {
                        packets.append(partial)
                        partial = Data()
                    }
                }
                body += length
            }
            at = body
        }
        guard !packets.isEmpty else { throw Failure.notOggOpus }
        return packets
    }
}
