import AVFoundation
import CoreVideo
import ExpoModulesCore

/// Options for the atomic clip-load transport call.
struct LoadClipOptions: Record {
  @Field var uri: String = ""
  @Field var startSec: Double = 0
  @Field var generation: Int = 0
  @Field var loopMode: String = "off"
  @Field var autoPlay: Bool = true
}

/// Headless video player for the WebGPU consumer: owns a VideoSource
/// (transport) and vends frames to the render worklet via the FrameSource
/// host object. Rendering happens in JS/WGSL.
public final class VideoTexturePlayer: SharedObject, VideoSourceDelegate {
  private static var nextId = 0

  let frameSourceKey: String
  private let videoSource: VideoSource
  private let provider: FrameProviderAdapter
  private var appliedGeneration = -1
  private var appliedUri: String?
  private var snapshotStatus = 0
  private var statusSeq: Int64 = 0
  private var errorSeq: Int64 = 0
  private var rateRampTimer: Timer?

  init(pixelFormat: String) {
    Self.nextId += 1
    frameSourceKey = "player\(Self.nextId)"
    videoSource = VideoSource(pixelFormat: pixelFormat)
    provider = FrameProviderAdapter(videoSource: videoSource)
    super.init()
    provider.commandTarget = self
    videoSource.delegate = self
  }

  var currentTimeSec: Double {
    videoSource.currentTime
  }

  var volume: Double {
    get { videoSource.volume }
    set { videoSource.setVolume(newValue) }
  }

  func installFrameSource(runtime: JavaScriptRuntime) {
    runtime.withUnsafePointee { runtimePointer in
      VideoTextureFrameSourceInstaller.install(
        runtimePointer: runtimePointer, provider: provider, key: frameSourceKey)
    }
  }

  func loadClip(_ options: LoadClipOptions) {
    appliedUri = options.uri
    videoSource.setLoopMode(options.loopMode)
    videoSource.setShouldAutoPlay(options.autoPlay)
    // Arm before load: loadUri carries the pending clip-start into the new item's
    // readyToPlay seek, and the reuse path relies on the armed value too.
    if options.generation != appliedGeneration {
      appliedGeneration = options.generation
      // Stamp every frame deposited from here on, so the render loop can tell a clip swap
      // from a seek without inferring it from a media-time discontinuity.
      videoSource.setClipGeneration(Int64(options.generation))
      videoSource.armClipStart(sec: options.startSec)
    }
    videoSource.loadUri(options.uri)
    provider.updateTransport(
      uri: appliedUri, status: 1, generation: Int64(appliedGeneration), source: videoSource)
  }

  func setPaused(_ paused: Bool) {
    videoSource.setShouldAutoPlay(!paused)
    if paused {
      videoSource.pause()
    } else {
      videoSource.play()
    }
    provider.updateTransport(
      uri: appliedUri, status: paused ? 3 : snapshotStatus,
      generation: Int64(appliedGeneration), source: videoSource)
  }

  func setRate(_ rate: Double) {
    rateRampTimer?.invalidate()
    videoSource.setRate(rate)
    provider.updateTransport(
      uri: appliedUri, status: snapshotStatus,
      generation: Int64(appliedGeneration), source: videoSource)
  }

  func rampRate(_ rate: Double, durationMs: Double) {
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      self.rateRampTimer?.invalidate()
      let from = self.videoSource.actualRate
      let duration = max(0.001, durationMs / 1000)
      let started = Date().timeIntervalSinceReferenceDate
      self.rateRampTimer = Timer.scheduledTimer(withTimeInterval: 1 / 60, repeats: true) {
        [weak self] timer in
        guard let self else { timer.invalidate(); return }
        let t = min(1, (Date().timeIntervalSinceReferenceDate - started) / duration)
        let eased = 1 - (1 - t) * (1 - t)
        self.videoSource.setRate(from + (rate - from) * eased)
        self.provider.updateTransport(
          uri: self.appliedUri, status: self.snapshotStatus,
          generation: Int64(self.appliedGeneration),
          source: self.videoSource)
        if t >= 1 { timer.invalidate() }
      }
    }
  }

  func seek(to sec: Double) {
    videoSource.seek(to: sec)
  }

  public override func sharedObjectWillRelease() {
    appliedUri = nil
    videoSource.loadUri(nil)
  }

  // MARK: - VideoSourceDelegate

  func videoSource(_ source: VideoSource, didChangeStatus status: String) {
    let mapped: Int
    switch status {
    case "loading": mapped = 1
    case "playing": mapped = 2
    case "ready": mapped = 3
    case "error": mapped = 5
    default: mapped = 0
    }
    if mapped != snapshotStatus { statusSeq += 1 }
    if mapped == 5 { errorSeq += 1 }
    snapshotStatus = mapped
    provider.updateTransport(
      uri: appliedUri, status: mapped, statusSeq: statusSeq, errorSeq: errorSeq,
      generation: Int64(appliedGeneration), source: source)
  }

  func videoSourceDidPlayToEnd(_ source: VideoSource) {
    if snapshotStatus != 4 { statusSeq += 1 }
    snapshotStatus = 4
    provider.updateTransport(
      uri: appliedUri, status: 4, statusSeq: statusSeq, errorSeq: errorSeq,
      generation: Int64(appliedGeneration), source: source)
  }
}

/// NSObject adapter implementing the ObjC frame-provider protocol for the JSI
/// host object (SharedObject itself is not an NSObject).
final class FrameProviderAdapter: NSObject, VideoTextureFrameProviding {
  private weak var videoSource: VideoSource?
  weak var commandTarget: VideoTexturePlayer?
  private let sourcePixelFormat: String

  init(videoSource: VideoSource) {
    self.videoSource = videoSource
    sourcePixelFormat = videoSource.pixelFormat
    super.init()
  }

  // MARK: - VideoTextureFrameProviding (called on the render worklet thread)

  /// Metadata for the frame the last `copyNewFrame` handed over. Only read immediately after
  /// a non-nil result, from the same thread, so no lock is needed here.
  private var lastPtsSec: Double = -1
  private var lastGeneration: Int64 = 0
  private let transportLock = NSLock()
  private var transport = VideoTextureTransportSnapshot(
    uri: nil, status: 0, statusSeq: 0, errorSeq: 0, errorMessage: nil,
    durationSec: 0, actualRate: 0, generation: 0)

  func copyNewFrame() -> CVPixelBuffer? {
    // CF_RETURNS_RETAINED on the protocol: the compiler transfers +1 to the caller.
    guard let frame = videoSource?.copyPixelBuffer() else {
      lastPtsSec = -1
      return nil
    }
    lastPtsSec = frame.ptsSec
    lastGeneration = frame.generation
    return frame.buffer
  }

  func lastFramePtsSec() -> Double { lastPtsSec }

  func lastFrameGeneration() -> Int64 { lastGeneration }

  func updateTransport(
    uri: String?, status: Int, statusSeq: Int64? = nil, errorSeq: Int64? = nil,
    generation: Int64, source: VideoSource
  ) {
    transportLock.lock(); defer { transportLock.unlock() }
    let nextStatusSeq = statusSeq ?? transport.statusSeq + (status == transport.status ? 0 : 1)
    transport = VideoTextureTransportSnapshot(
      uri: uri, status: Int32(status), statusSeq: nextStatusSeq,
      errorSeq: errorSeq ?? transport.errorSeq, errorMessage: nil,
      durationSec: source.duration, actualRate: source.actualRate, generation: generation)
  }

  func transportSnapshot() -> VideoTextureTransportSnapshot {
    transportLock.lock(); defer { transportLock.unlock() }
    return transport
  }

  func loadClip(
    withUri uri: String, startSec: Double, generation: Int64, loopMode: String, autoPlay: Bool
  ) {
    DispatchQueue.main.async { [weak commandTarget] in
      guard let commandTarget else { return }
      let options = LoadClipOptions()
      options.uri = uri
      options.startSec = startSec
      options.generation = Int(generation)
      options.loopMode = loopMode
      options.autoPlay = autoPlay
      commandTarget.loadClip(options)
    }
  }

  func setPausedFromRuntime(_ paused: Bool) {
    DispatchQueue.main.async { [weak commandTarget] in commandTarget?.setPaused(paused) }
  }

  func setRateFromRuntime(_ rate: Double) {
    DispatchQueue.main.async { [weak commandTarget] in commandTarget?.setRate(rate) }
  }

  func rampRate(fromRuntime rate: Double, durationMs: Double) {
    DispatchQueue.main.async { [weak commandTarget] in
      commandTarget?.rampRate(rate, durationMs: durationMs)
    }
  }

  func setVolumeFromRuntime(_ volume: Double) {
    DispatchQueue.main.async { [weak commandTarget] in commandTarget?.volume = volume }
  }

  func pixelFormat() -> String {
    #if targetEnvironment(simulator)
    return "bgra8"
    #else
    return sourcePixelFormat
    #endif
  }
}
