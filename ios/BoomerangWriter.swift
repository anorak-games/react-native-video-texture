import AVFoundation
import CoreGraphics

/// One-shot boomerang render — iOS analogue of android/BoomerangWriter.kt.
///
/// Writes `[forward 0..N-1][reverse N-2..1]` (both duplicate endpoint frames dropped, so
/// neither the turnaround nor the loop seam holds a frame) plus the source's forward audio
/// twice, to a single self-contained file that loops seamlessly with a plain `'loop'` mode.
/// It simulates the eventual server-side pre-bake; nothing here touches playback.
///
/// Both legs go through ONE `AVAssetWriter` video input (h264 re-encode), mirroring
/// Android — where `MediaMuxer` structurally cannot mix a sample-copied forward leg with a
/// freshly encoded reverse leg in one track — so the two platforms stay honest about
/// quality. Audio is compressed passthrough, never re-encoded. The reverse leg is decoded
/// in bounded windows (`reverseWindowBudgetBytes`), so peak memory is independent of both
/// resolution and clip length; the forward leg streams.
enum BoomerangWriter {

  /// Render `sourceURL` as a boomerang to `outputURL`, overwriting any existing file.
  /// Runs on a background queue; `completion` is called on the main queue with `nil` on
  /// success or an error describing the failure (the partial output is removed).
  static func write(sourceURL: URL, outputURL: URL, completion: @escaping (Error?) -> Void) {
    DispatchQueue.global(qos: .userInitiated).async {
      do {
        try writeSync(sourceURL: sourceURL, outputURL: outputURL)
        DispatchQueue.main.async { completion(nil) }
      } catch {
        try? FileManager.default.removeItem(at: outputURL)
        DispatchQueue.main.async { completion(error) }
      }
    }
  }

  // MARK: - Internals

  private static func makeError(_ message: String, underlying: Error? = nil) -> NSError {
    var info: [String: Any] = [NSLocalizedDescriptionKey: "makeBoomerang: \(message)"]
    if let underlying {
      info[NSUnderlyingErrorKey] = underlying
      info[NSLocalizedDescriptionKey] =
        "makeBoomerang: \(message) (\(underlying.localizedDescription))"
    }
    return NSError(domain: "VideoTexture.BoomerangWriter", code: 1, userInfo: info)
  }

  /// Peak RAM the reverse pass may hold in decoded frames. The window shrinks as the frame
  /// grows, so peak memory is independent of both resolution and clip length — which is what
  /// makes rendering at source resolution safe. A whole-clip reverse would require several
  /// gigabytes for a short 2160p clip.
  private static let reverseWindowBudgetBytes = 192 * 1024 * 1024

  /// Round down to an even dimension — h264 requires even width/height.
  private static func evenDimension(_ value: CGFloat) -> Int {
    let v = Int(value.rounded(.down))
    return max(2, v - (v % 2))
  }

  private static func writeSync(sourceURL: URL, outputURL: URL) throws {
    let asset = AVURLAsset(url: sourceURL)
    guard let videoTrack = asset.tracks(withMediaType: .video).first else {
      throw makeError("no video track in \(sourceURL.lastPathComponent)")
    }

    guard let sampleTimes = videoSampleTimes(asset: asset, videoTrack: videoTrack) else {
      throw makeError("could not index video samples")
    }
    let frameCount = sampleTimes.count
    guard frameCount >= 2 else {
      throw makeError("source has fewer than 2 video frames")
    }

    let natural = videoTrack.naturalSize
    let outW = evenDimension(abs(natural.width))
    let outH = evenDimension(abs(natural.height))

    let nominalFrameRate = videoTrack.nominalFrameRate > 0 ? videoTrack.nominalFrameRate : 30
    let timescale = CMTimeScale(nominalFrameRate.rounded())
    let frameDuration = CMTime(value: 1, timescale: max(1, timescale))

    try? FileManager.default.removeItem(at: outputURL)
    try? FileManager.default.createDirectory(
      at: outputURL.deletingLastPathComponent(), withIntermediateDirectories: true)

    // Container from the output extension — callers pass .mp4; .mov kept for parity.
    let fileType: AVFileType = outputURL.pathExtension.lowercased() == "mov" ? .mov : .mp4
    let writer = try AVAssetWriter(outputURL: outputURL, fileType: fileType)

    // Match the source data rate. The writer's default for a 2160p target is conservative, and
    // a soft render would defeat encoding at source resolution in the first place.
    let sourceRate = videoTrack.estimatedDataRate
    let bitRate =
      sourceRate > 0
      ? Int(sourceRate)
      : min(60_000_000, max(2_000_000, outW * outH * Int(nominalFrameRate) * 15 / 100))
    let writerSettings: [String: Any] = [
      AVVideoCodecKey: AVVideoCodecType.h264,
      AVVideoWidthKey: outW,
      AVVideoHeightKey: outH,
      AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: bitRate],
    ]
    let videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: writerSettings)
    videoInput.transform = videoTrack.preferredTransform
    videoInput.expectsMediaDataInRealTime = false
    let pixelAttrs: [String: Any] = [
      kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
      kCVPixelBufferWidthKey as String: outW,
      kCVPixelBufferHeightKey as String: outH,
    ]
    let adaptor = AVAssetWriterInputPixelBufferAdaptor(
      assetWriterInput: videoInput, sourcePixelBufferAttributes: pixelAttrs)
    guard writer.canAdd(videoInput) else { throw makeError("writer rejected the video input") }
    writer.add(videoInput)

    // Audio: the source's forward audio, twice, second copy offset by the forward video
    // leg's OUTPUT duration (N frames), trimmed to the total video duration. Built as an
    // audio-only composition so `insertTimeRange` does the retiming and trimming — no
    // CMSampleBuffer timing surgery — then read back with a passthrough output and appended
    // to a passthrough (outputSettings: nil) writer input: compressed samples copied as-is.
    var audioReader: AVAssetReader?
    var audioOutput: AVAssetReaderTrackOutput?
    var audioInput: AVAssetWriterInput?
    if let audioTrack = asset.tracks(withMediaType: .audio).first,
      let audioFormat = audioTrack.formatDescriptions.first
    {
      let forwardLegDur = CMTime(value: CMTimeValue(frameCount), timescale: frameDuration.timescale)
      let totalVideoDur = CMTime(
        value: CMTimeValue(2 * frameCount - 2), timescale: frameDuration.timescale)
      let audioDur = audioTrack.timeRange.duration

      let composition = AVMutableComposition()
      guard
        let compAudio = composition.addMutableTrack(
          withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)
      else { throw makeError("could not create the audio composition track") }
      do {
        let copy1Dur = CMTimeMinimum(audioDur, forwardLegDur)
        try compAudio.insertTimeRange(
          CMTimeRange(start: .zero, duration: copy1Dur), of: audioTrack, at: .zero)
        if CMTimeCompare(copy1Dur, forwardLegDur) < 0 {
          // Audio shorter than the forward leg: pad explicitly so copy 2 still lands at the
          // leg boundary instead of relying on implicit-gap insertion behaviour.
          compAudio.insertEmptyTimeRange(CMTimeRange(start: copy1Dur, end: forwardLegDur))
        }
        let copy2Dur = CMTimeMinimum(audioDur, CMTimeSubtract(totalVideoDur, forwardLegDur))
        if CMTimeCompare(copy2Dur, .zero) > 0 {
          try compAudio.insertTimeRange(
            CMTimeRange(start: .zero, duration: copy2Dur), of: audioTrack, at: forwardLegDur)
        }
      } catch {
        throw makeError("could not assemble the audio timeline", underlying: error)
      }

      guard let reader = try? AVAssetReader(asset: composition),
        let compTrack = composition.tracks(withMediaType: .audio).first
      else { throw makeError("could not read the audio composition") }
      let output = AVAssetReaderTrackOutput(track: compTrack, outputSettings: nil)
      output.alwaysCopiesSampleData = false
      guard reader.canAdd(output) else { throw makeError("reader rejected the audio output") }
      reader.add(output)

      let input = AVAssetWriterInput(
        mediaType: .audio, outputSettings: nil,
        sourceFormatHint: (audioFormat as! CMFormatDescription))
      input.expectsMediaDataInRealTime = false
      guard writer.canAdd(input) else { throw makeError("writer rejected the audio input") }
      writer.add(input)

      audioReader = reader
      audioOutput = output
      audioInput = input
    }

    guard writer.startWriting() else {
      throw makeError("could not start writing", underlying: writer.error)
    }
    writer.startSession(atSourceTime: .zero)

    // Drive the audio input on its own queue, concurrently with the video passes below —
    // AVAssetWriter interleaves its inputs, so feeding both serially from one thread can
    // park video appends behind an audio input that is never serviced (deadlock).
    let audioGroup = DispatchGroup()
    var audioFailure: Error?
    if let audioReader, let audioOutput, let audioInput {
      guard audioReader.startReading() else {
        throw makeError("could not start the audio read", underlying: audioReader.error)
      }
      audioGroup.enter()
      let audioQueue = DispatchQueue(label: "react-native-video-texture.boomerang-audio")
      var finished = false
      audioInput.requestMediaDataWhenReady(on: audioQueue) {
        guard !finished else { return }
        while audioInput.isReadyForMoreMediaData {
          if let sample = audioOutput.copyNextSampleBuffer() {
            if !audioInput.append(sample) {
              audioFailure = makeError("audio append failed", underlying: writer.error)
              audioReader.cancelReading()
              finished = true
              audioInput.markAsFinished()
              audioGroup.leave()
              return
            }
          } else {
            if audioReader.status == .failed {
              audioFailure = makeError("audio read failed", underlying: audioReader.error)
            }
            finished = true
            audioInput.markAsFinished()
            audioGroup.leave()
            return
          }
        }
      }
    }

    var writeTime = CMTime.zero
    func appendFrame(_ pixelBuffer: CVPixelBuffer) throws {
      // Busy-wait briefly for the input to be ready (offline render, not real-time).
      var spins = 0
      while !videoInput.isReadyForMoreMediaData && spins < 2000 {
        usleep(1000)
        spins += 1
      }
      guard videoInput.isReadyForMoreMediaData,
        adaptor.append(pixelBuffer, withPresentationTime: writeTime)
      else {
        throw makeError("video append failed", underlying: writer.error)
      }
      writeTime = CMTimeAdd(writeTime, frameDuration)
    }

    do {
      // Pass 1 (forward): one streaming reader over the whole track — decoded output
      // arrives in presentation order, so no windowing is needed reading forward.
      guard let reader = try? AVAssetReader(asset: asset) else {
        throw makeError("could not read the source video")
      }
      let settings: [String: Any] = [
        kCVPixelBufferPixelFormatTypeKey as String:
          kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
        kCVPixelBufferWidthKey as String: outW,
        kCVPixelBufferHeightKey as String: outH,
      ]
      let output = AVAssetReaderTrackOutput(track: videoTrack, outputSettings: settings)
      output.alwaysCopiesSampleData = false
      guard reader.canAdd(output) else { throw makeError("reader rejected the video output") }
      reader.add(output)
      guard reader.startReading() else {
        throw makeError("could not start the forward read", underlying: reader.error)
      }
      while let sample = output.copyNextSampleBuffer() {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sample) else { continue }
        try appendFrame(pixelBuffer)
      }
      if reader.status == .failed {
        throw makeError("forward decode failed", underlying: reader.error)
      }

      // Pass 2 (reverse): windows of the frame list walked from the end, each decoded
      // forward and appended newest→oldest. The two duplicate endpoint frames are dropped
      // by a GLOBAL presentation-index guard (emit only 1..N-2): the turnaround never
      // repeats frame N-1 and the loop seam never repeats frame 0. Guarding per-window
      // would silently drop a frame at every window boundary.
      let frameBytes = max(1, outW * outH * 3 / 2)
      let framesPerWindow = max(1, reverseWindowBudgetBytes / frameBytes)
      var upper = frameCount  // exclusive
      while upper > 0 {
        let lower = max(0, upper - framesPerWindow)
        let range = CMTimeRange(
          start: sampleTimes[lower],
          end: CMTimeAdd(sampleTimes[upper - 1], frameDuration))
        guard
          let frames = decodeWindow(
            asset: asset, videoTrack: videoTrack, range: range, width: outW, height: outH),
          !frames.isEmpty
        else {
          throw makeError("reverse decode failed")
        }
        for (indexInWindow, frame) in frames.enumerated().reversed() {
          let sourceIndex = lower + indexInWindow
          guard sourceIndex >= 1 && sourceIndex <= frameCount - 2 else { continue }
          try appendFrame(frame.pixelBuffer)
        }
        upper = lower
      }
    } catch {
      // Unwind in an order that cannot trip AVFoundation exceptions: stop the audio pump
      // (cancelled reader → nil sample → markAsFinished + leave) BEFORE cancelling the
      // writer, then surface the original error. The partial file is removed by `write`.
      audioReader?.cancelReading()
      audioGroup.wait()
      if writer.status == .writing { writer.cancelWriting() }
      throw error
    }

    videoInput.markAsFinished()
    audioGroup.wait()
    if let audioFailure {
      if writer.status == .writing { writer.cancelWriting() }
      throw audioFailure
    }

    let done = DispatchSemaphore(value: 0)
    writer.finishWriting { done.signal() }
    done.wait()
    guard writer.status == .completed else {
      throw makeError("could not finish writing", underlying: writer.error)
    }
  }

  /// Presentation timestamps of every video sample, in presentation order, obtained *without
  /// decoding* (passthrough output — compressed samples carry their PTS). The analogue of
  /// Android's MediaExtractor sample-time pass: it lets the reverse be chunked on exact frame
  /// boundaries rather than guessed time ranges, so no frame is dropped or duplicated at a seam.
  private static func videoSampleTimes(asset: AVAsset, videoTrack: AVAssetTrack) -> [CMTime]? {
    guard let reader = try? AVAssetReader(asset: asset) else { return nil }
    let output = AVAssetReaderTrackOutput(track: videoTrack, outputSettings: nil)
    output.alwaysCopiesSampleData = false
    guard reader.canAdd(output) else { return nil }
    reader.add(output)
    guard reader.startReading() else { return nil }
    var times: [CMTime] = []
    while let sample = output.copyNextSampleBuffer() {
      let pts = CMSampleBufferGetPresentationTimeStamp(sample)
      if pts.isValid { times.append(pts) }
    }
    if reader.status == .failed { return nil }
    // Decode order is not presentation order once B-frames are involved.
    return times.sorted { CMTimeCompare($0, $1) < 0 }
  }

  /// Decode one window forward, returning its frames in presentation order. NV12 rather than
  /// BGRA: it is 2.7× smaller per frame and is what the h264 encoder wants anyway.
  private static func decodeWindow(
    asset: AVAsset,
    videoTrack: AVAssetTrack,
    range: CMTimeRange,
    width: Int,
    height: Int
  ) -> [(time: CMTime, pixelBuffer: CVPixelBuffer)]? {
    guard let reader = try? AVAssetReader(asset: asset) else { return nil }
    reader.timeRange = range
    let settings: [String: Any] = [
      kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
      kCVPixelBufferWidthKey as String: width,
      kCVPixelBufferHeightKey as String: height,
    ]
    let output = AVAssetReaderTrackOutput(track: videoTrack, outputSettings: settings)
    output.alwaysCopiesSampleData = false
    guard reader.canAdd(output) else { return nil }
    reader.add(output)
    guard reader.startReading() else { return nil }
    var frames: [(time: CMTime, pixelBuffer: CVPixelBuffer)] = []
    while let sample = output.copyNextSampleBuffer() {
      let pts = CMSampleBufferGetPresentationTimeStamp(sample)
      guard let pb = CMSampleBufferGetImageBuffer(sample) else { continue }
      // Decoding starts at the keyframe preceding the window, so frames from before it can be
      // delivered. Keep only the ones this window owns, or seams would duplicate frames.
      if CMTimeCompare(pts, range.start) >= 0 && CMTimeCompare(pts, range.end) < 0 {
        frames.append((pts, pb))
      }
    }
    if reader.status == .failed { return nil }
    return frames.sorted { CMTimeCompare($0.time, $1.time) < 0 }
  }
}
