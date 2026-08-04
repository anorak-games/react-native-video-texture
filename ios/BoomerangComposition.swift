import AVFoundation
import CoreGraphics
import CryptoKit

/// Builds the boomerang playback asset for a source video and provides the
/// composition-time → forward-media-time remap.
///
/// A boomerang loop plays the clip forward (start→end) then reversed (end→start),
/// repeating. We realise this as an `AVMutableComposition` whose VIDEO track is
/// `[forward][reversed]` (≈2·L) and whose AUDIO track is `[forward][forward]` — so the
/// picture boomerangs while the audio always plays forward (and loops). The reversed
/// video segment is pre-rendered with `AVAssetReader`→`AVAssetWriter` and cached in the
/// temporary directory keyed by the source URL, so it is built at most once per asset.
///
/// Because the composition's last frame ≈ its first frame (both ≈ the source's frame 0),
/// a plain seek-to-zero loop at the end is visually seamless, so callers can loop it with
/// a manual `AVPlayerItemDidPlayToEndTime` handler without a visible blink.
enum BoomerangComposition {

  /// Forward → reversed → forward triangle-wave remap.
  ///
  /// `compositionSec` is elapsed composition time (≥ 0; may exceed one period — it wraps).
  /// `clipLenSec` is the forward segment length L. `startOffsetSec` is the original clip
  /// start so the returned media time is `start + tri(compTime, L)`, matching the JS
  /// `compositionToMediaTime` helper. Returns a forward media time in `[start, start+L]`.
  static func compositionToMediaTime(
    _ compositionSec: Double,
    clipLenSec: Double,
    startOffsetSec: Double = 0
  ) -> Double {
    guard clipLenSec > 0 else { return startOffsetSec + max(0, compositionSec) }
    let period = 2 * clipLenSec
    var t = compositionSec.truncatingRemainder(dividingBy: period)
    if t < 0 { t += period }
    let forward = t <= clipLenSec ? t : period - t
    return startOffsetSec + forward
  }

  /// Result of a successful build: the looping composition plus the forward segment
  /// length L (seconds) used for the time remap.
  struct Built {
    let composition: AVComposition
    let forwardLenSec: Double
  }

  /// Build the boomerang composition for `sourceURL`, calling `completion` on the main
  /// queue with the result (or `nil` on any failure — callers must keep the plain forward
  /// loop in that case). The reversed segment is cached in tmp and reused on later builds.
  static func build(
    sourceURL: URL,
    completion: @escaping (Built?) -> Void
  ) {
    DispatchQueue.global(qos: .userInitiated).async {
      let result = buildSync(sourceURL: sourceURL)
      DispatchQueue.main.async { completion(result) }
    }
  }

  // MARK: - Internals

  private static func buildSync(sourceURL: URL) -> Built? {
    let asset = AVURLAsset(url: sourceURL)
    guard let videoTrack = asset.tracks(withMediaType: .video).first else { return nil }
    let forwardLen = asset.duration.seconds
    guard forwardLen > 0 else { return nil }

    let reversedURL = cachedReversedURL(for: sourceURL)
    if !FileManager.default.fileExists(atPath: reversedURL.path) {
      guard renderReversed(asset: asset, videoTrack: videoTrack, to: reversedURL) else {
        return nil
      }
    }

    let reversedAsset = AVURLAsset(url: reversedURL)
    guard let reversedVideoTrack = reversedAsset.tracks(withMediaType: .video).first else { return nil }

    let composition = AVMutableComposition()
    guard
      let compVideo = composition.addMutableTrack(
        withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid)
    else { return nil }

    let fullForward = CMTimeRange(start: .zero, duration: asset.duration)
    let fullReversed = CMTimeRange(start: .zero, duration: reversedAsset.duration)
    do {
      // Video: [forward][reversed]
      try compVideo.insertTimeRange(fullForward, of: videoTrack, at: .zero)
      try compVideo.insertTimeRange(fullReversed, of: reversedVideoTrack, at: asset.duration)
      compVideo.preferredTransform = videoTrack.preferredTransform

      // Audio: [forward][forward] — audio always plays forward through both visual halves.
      if let audioTrack = asset.tracks(withMediaType: .audio).first,
        let compAudio = composition.addMutableTrack(
          withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)
      {
        try compAudio.insertTimeRange(fullForward, of: audioTrack, at: .zero)
        try compAudio.insertTimeRange(fullForward, of: audioTrack, at: asset.duration)
      }
    } catch {
      return nil
    }

    return Built(composition: composition.copy() as! AVComposition, forwardLenSec: forwardLen)
  }

  /// Stable temporary URL for the reversed copy, keyed by a deterministic source URL digest.
  /// The filename version identifies the render format and must change when cached output
  /// compatibility changes.
  private static func cachedReversedURL(for sourceURL: URL) -> URL {
    let digest = SHA256.hash(data: Data(sourceURL.absoluteString.utf8))
    let key = digest.map { String(format: "%02x", $0) }.joined().prefix(20)
    return FileManager.default.temporaryDirectory
      .appendingPathComponent("boomerang-rev-v2-\(key).mov")
  }

  /// Peak RAM the reverse render may hold in decoded frames. The window shrinks as the frame
  /// grows, so peak memory is independent of both resolution and clip length — which is what
  /// makes rendering at source resolution safe. A whole-clip reverse would require several
  /// gigabytes for a short 2160p clip.
  private static let reverseWindowBudgetBytes = 192 * 1024 * 1024

  /// Round down to an even dimension — h264 requires even width/height.
  private static func evenDimension(_ value: CGFloat) -> Int {
    let v = Int(value.rounded(.down))
    return max(2, v - (v % 2))
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

  /// Write the source back to `outputURL` reversed, at source resolution.
  ///
  /// Windowed to bound memory: the frame list is walked from the end in chunks of
  /// `reverseWindowBudgetBytes`, each chunk decoded forward and then appended newest→oldest onto
  /// a forward timeline. Mirrors the Android implementation's GOP-at-a-time reverse; the cost is
  /// re-decoding from each window's preceding keyframe. Synchronous; false on any failure.
  private static func renderReversed(
    asset: AVAsset,
    videoTrack: AVAssetTrack,
    to outputURL: URL
  ) -> Bool {
    try? FileManager.default.removeItem(at: outputURL)

    let natural = videoTrack.naturalSize
    let outW = evenDimension(abs(natural.width))
    let outH = evenDimension(abs(natural.height))

    guard let sampleTimes = videoSampleTimes(asset: asset, videoTrack: videoTrack),
      !sampleTimes.isEmpty
    else { return false }

    let nominalFrameRate = videoTrack.nominalFrameRate > 0 ? videoTrack.nominalFrameRate : 30
    let frameDuration = CMTime(value: 1, timescale: CMTimeScale(nominalFrameRate))

    guard let writer = try? AVAssetWriter(outputURL: outputURL, fileType: .mov) else { return false }
    // Match the source data rate. The writer's default for a 2160p target is conservative, and
    // a soft reverse leg would defeat rendering it at source resolution in the first place.
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
    let writerInput = AVAssetWriterInput(mediaType: .video, outputSettings: writerSettings)
    writerInput.transform = videoTrack.preferredTransform
    writerInput.expectsMediaDataInRealTime = false
    let pixelAttrs: [String: Any] = [
      kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
      kCVPixelBufferWidthKey as String: outW,
      kCVPixelBufferHeightKey as String: outH,
    ]
    let adaptor = AVAssetWriterInputPixelBufferAdaptor(
      assetWriterInput: writerInput, sourcePixelBufferAttributes: pixelAttrs)
    guard writer.canAdd(writerInput) else { return false }
    writer.add(writerInput)
    guard writer.startWriting() else { return false }
    writer.startSession(atSourceTime: .zero)

    let frameBytes = max(1, outW * outH * 3 / 2)
    let framesPerWindow = max(1, reverseWindowBudgetBytes / frameBytes)

    var writeTime = CMTime.zero
    var upper = sampleTimes.count  // exclusive
    var ok = true

    while upper > 0 && ok {
      let lower = max(0, upper - framesPerWindow)
      let range = CMTimeRange(
        start: sampleTimes[lower],
        end: CMTimeAdd(sampleTimes[upper - 1], frameDuration))
      guard
        let frames = decodeWindow(
          asset: asset, videoTrack: videoTrack, range: range, width: outW, height: outH),
        !frames.isEmpty
      else {
        ok = false
        break
      }
      for frame in frames.reversed() {
        // Busy-wait briefly for the input to be ready (offline render, not real-time).
        var spins = 0
        while !writerInput.isReadyForMoreMediaData && spins < 2000 {
          usleep(1000)
          spins += 1
        }
        if !writerInput.isReadyForMoreMediaData || !adaptor.append(
          frame.pixelBuffer, withPresentationTime: writeTime)
        {
          ok = false
          break
        }
        writeTime = CMTimeAdd(writeTime, frameDuration)
      }
      upper = lower
    }

    writerInput.markAsFinished()
    let done = DispatchSemaphore(value: 0)
    writer.finishWriting { done.signal() }
    done.wait()

    if writer.status != .completed || !ok {
      try? FileManager.default.removeItem(at: outputURL)
      return false
    }
    return true
  }
}
