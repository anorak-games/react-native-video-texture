import AVFoundation
import CoreImage
import CoreVideo
import UIKit

protocol VideoSourceDelegate: AnyObject {
  func videoSource(_ source: VideoSource, didChangeStatus status: String)
  func videoSourceDidPlayToEnd(_ source: VideoSource)
}

final class VideoSource: NSObject {
  weak var delegate: VideoSourceDelegate?

  private(set) var player: AVPlayer?
  private var playerItem: AVPlayerItem?
  private let outputLock = NSLock()
  private var _videoOutput: AVPlayerItemVideoOutput?
  /// Thread-safe: the background frame-pull reads this while the main thread swaps it on load/teardown.
  private var videoOutput: AVPlayerItemVideoOutput? {
    get { outputLock.lock(); defer { outputLock.unlock() }; return _videoOutput }
    set { outputLock.lock(); _videoOutput = newValue; outputLock.unlock() }
  }
  private var endObserver: NSObjectProtocol?
  /// The exact item the `"status"` KVO observer is registered on. MUST be used for the
  /// matching removeObserver — never `playerItem`, which `attachOutput` reassigns to the
  /// looper-cycled COPIES (which never had a status observer). Removing from the wrong item
  /// throws `NSException: … not registered as an observer` and crashes on clip transition.
  private var statusObservedItem: AVPlayerItem?

  /// IOSurface-backed output is required by the WebGPU frame path
  /// (rn-webgpu's wrapCVPixelBuffer throws on non-IOSurface buffers) and is
  /// what CVMetalTextureCache wants anyway.
  private let outputSettings: [String: Any]
  /// "bgra8" | "nv12" — chosen at init; the simulator decode path always
  /// converts to BGRA regardless (see bgraPixelBufferForSimulator).
  let pixelFormat: String

  // MARK: - loop state
  /// Transport loop mode for the current clip: `"off"` (play once, report 'ended') or
  /// `"loop"` (seamless native loop via `AVPlayerLooper`).
  private var loopMode: String = "off"
  /// Gapless looper (`'loop'` mode). `AVPlayerLooper` cycles internal COPIES of the template
  /// item with no decode-pipeline flush, eliminating the wrap-around hitch a manual
  /// seek-to-zero would cause. Nil in `'off'` mode; torn down with the player.
  private var looper: AVPlayerLooper?
  /// KVO on the queue player's `currentItem`. The looper plays fresh COPIES of the template,
  /// and an `AVPlayerItemVideoOutput` added to the template does NOT carry to the copies, so
  /// on every item change we re-attach a fresh output to the new current item.
  private var currentItemObserver: NSKeyValueObservation?
  /// KVO on the AVPlayerLooper itself ('loop' mode). With no end observer and no status KVO
  /// on the never-played template, this is the only place a broken file can surface.
  private var looperStatusObserver: NSKeyValueObservation?
  /// The loop mode the current player was configured with. The same-URI reuse path must not
  /// keep a player whose shape (single item vs looper) no longer matches the requested mode.
  private var installedLoopMode = "off"
  /// Looper retired by a loop→'off' conversion, parked here until teardown. An
  /// `AVPlayerLooper` REMOVES its looped items from the player when it deallocates —
  /// releasing it mid-conversion yanks the still-playing current item out of the queue,
  /// the armed follow-up seek then has no item to land on, and the frame gate freezes the
  /// renderer on its last texture.
  private var retiredLooper: AVPlayerLooper?
  /// The [start, end] region the current looper was built over (whole file = (0, -1)).
  /// An `AVPlayerLooper`'s timeRange is immutable, so a loop-region change — unlike an
  /// 'off'-mode region change, which is just a forwardPlaybackEndTime write + seek — needs
  /// a fresh player.
  private var installedLoopStartSec: Double = 0
  private var installedLoopEndSec: Double = -1

  /// On-demand live time (one `player.currentTime()` query — a synchronous XPC to mediaserverd,
  /// so NEVER call this per-frame on the main thread; see `copyPixelBuffer` / the time observer).
  /// Under a looper each cycle plays a fresh copy starting at 0, so in `'loop'` mode this
  /// sawtooths 0→L→0 — which is what a loop should report.
  var currentTime: Double {
    player?.currentTime().seconds ?? 0
  }

  private(set) var volume: Double = 1

  func setVolume(_ value: Double) {
    volume = value.isFinite ? min(1, max(0, value)) : 1
    player?.volume = Float(volume)
  }

  var duration: Double {
    playerItem?.duration.seconds ?? 0
  }

  var actualRate: Double {
    Double(player?.rate ?? 0)
  }

  init(pixelFormat: String = "bgra8") {
    self.pixelFormat = pixelFormat
    let format: OSType = pixelFormat == "nv12"
      ? kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
      : kCVPixelFormatType_32BGRA
    self.outputSettings = [
      kCVPixelBufferPixelFormatTypeKey as String: format,
      kCVPixelBufferIOSurfacePropertiesKey as String: [:],
      kCVPixelBufferMetalCompatibilityKey as String: true,
    ]
    super.init()
    subscribeToLifecycle()
  }

  deinit {
    teardown()
    unsubscribeFromLifecycle()
  }

  private var shouldAutoPlay = false
  /// True from didEnterBackground until didBecomeActive: gates the pause-observer's
  /// end-detect/auto-resume so the system background pause is neither fought nor
  /// misread as clip end. Foreground resume happens in `appDidBecomeActive`.
  private var isInBackground = false
  private var seekInProgress = false
  private var deferredSeekSec: Double = -1
  /// Authoritative start position for the current clip. Persists across loadUri/teardown so
  /// the clip-start seek is applied exactly once when the item is ready.
  private var clipStartSec: Double = -1
  /// Set by `armClipStart` (once per clip generation), consumed by `loadUri`. Non-nil means
  /// "a new clip start was armed and has not been applied yet", distinguishing a genuine
  /// re-arm from redundant same-URI `loadUri` calls that must not move the playhead.
  private var pendingReuseStartSec: Double?
  private var lastPlaybackStartTime: CFTimeInterval = 0
  /// Absolute-file-time bound of the playable region; <= 0 = none. In `'off'` mode it is
  /// applied as `forwardPlaybackEndTime` (so `AVPlayerItemDidPlayToEndTime` fires there);
  /// in `'loop'` mode it becomes the looper's timeRange end.
  private var clipEndSec: Double = -1

  /// Armed together with `armClipStart` (once per clip generation), before `loadUri`.
  func armClipEnd(sec: Double) {
    clipEndSec = sec > 0 ? sec : -1
  }

  /// `forwardPlaybackEndTime` value for the armed region (`.invalid` clears the bound).
  private var clipEndTime: CMTime {
    clipEndSec > 0 ? CMTime(seconds: clipEndSec, preferredTimescale: 600) : .invalid
  }

  /// The effective end of the playable region (item duration, or the armed region bound).
  private func effectiveEndSec(of item: AVPlayerItem?) -> Double {
    let duration = item?.duration.seconds ?? 0
    guard duration.isFinite, duration > 0 else { return clipEndSec > 0 ? clipEndSec : 0 }
    return clipEndSec > 0 ? min(duration, clipEndSec) : duration
  }

  func setShouldAutoPlay(_ value: Bool) {
    shouldAutoPlay = value
  }

  /// Select the transport loop mode: `"loop"` or anything else = `"off"`. Takes effect on
  /// the next `loadUri` — `loadClip` always sets the mode before loading, and the reuse
  /// guard on `installedLoopMode` forces a fresh player when the mode changed.
  func setLoopMode(_ mode: String) {
    loopMode = (mode == "loop") ? "loop" : "off"
  }

  /// Resolve a uri string to a URL the same way `loadUri` does (file path vs remote).
  private static func resolvedURL(for uri: String) -> URL? {
    if uri.hasPrefix("file://") || uri.hasPrefix("/") {
      return URL(fileURLWithPath: uri.hasPrefix("file://") ? String(uri.dropFirst(7)) : uri)
    }
    return URL(string: uri)
  }

  private var currentUri: String?
  private var timeControlObserver: NSKeyValueObservation?

  func loadUri(_ uri: String?) {
    guard let uri, !uri.isEmpty else {
      currentUri = nil
      teardown()
      delegate?.videoSource(self, didChangeStatus: "idle")
      return
    }
    // Same URI + player alive + same mode: skip teardown+reload. Mode changes: loop→'off'
    // is converted in place by the branch below; 'off'→loop and loop-region changes (a
    // looper's timeRange is immutable) still fall through to a fresh install.
    let loopRegionUnchanged =
      loopMode != "loop"
      || (abs(installedLoopStartSec - max(0, clipStartSec)) < 0.001
        && abs(installedLoopEndSec - clipEndSec) < 0.001)
    if uri == currentUri, player != nil, loopMode == installedLoopMode, loopRegionUnchanged {
      // A new playback may reuse the same file; reset end-of-clip heuristics before seeking.
      lastPlaybackStartTime = CACurrentMediaTime()
      // A region change in 'off' mode is just a bound rewrite on the live item — THE hot
      // path for a baked-file segment swap (main → follow-up is a seek, not a teardown).
      if loopMode != "loop" {
        player?.currentItem?.forwardPlaybackEndTime = clipEndTime
      }
      // Apply a freshly-armed clip start HERE, as a real seek — 0 included. `armClipStart` only
      // records; for start==0 it also clears the pending position, which is right for a fresh
      // item (it begins at 0) but wrong on reuse: the player is parked wherever the previous
      // playthrough ended, and after a plain-forward loop that is the FINAL frame
      // with `actionAtItemEnd = .pause`. Without this the re-arm issued neither a position nor
      // a play and the clip soft-locked on its last frame — no new decoded frames, so the
      // render loop froze too. `applyDeferredSeekIfReady` resumes via `startPlaybackPrerolled`.
      // Guarded so redundant same-URI loads do not move the playhead.
      if let start = pendingReuseStartSec {
        pendingReuseStartSec = nil
        armDeferredSeek(start)
        applyDeferredSeekIfReady()
      }
      return
    }
    // Same URI, looper installed, 'off' requested: convert the live looper into a bounded
    // single playback instead of tearing down — THE shot-time swap on a looping level
    // (main loop → follow-up region). The looper's cycled item is a copy of the FULL asset
    // (its timeRange only bounds playback), so dropping the loop machinery, rewriting the
    // region bound, and seeking reaches any part of the file with no decoder respin.
    // A false return is NOT a degraded retry: it means there is no live item to convert
    // (the looper fills its queue asynchronously, so a shot in a round's first frames can
    // beat it) — the fresh-install path below is the primary path for that state.
    if uri == currentUri, installedLoopMode == "loop", loopMode == "off",
      convertLooperToBoundedSinglePlayback()
    {
      installedLoopMode = "off"
      installedLoopStartSec = 0
      installedLoopEndSec = -1
      lastPlaybackStartTime = CACurrentMediaTime()
      if let start = pendingReuseStartSec {
        pendingReuseStartSec = nil
        armDeferredSeek(start)
        applyDeferredSeekIfReady()
      }
      return
    }
    currentUri = uri
    // Consumed by the fresh-load path below (via `clipStartSec`), not by a later reuse.
    pendingReuseStartSec = nil
    teardown()
    pendingPlay = shouldAutoPlay
    // Re-arm the authoritative clip start so teardown() above cannot drop it. The seek is
    // applied (and playback held) once the new item reaches readyToPlay.
    // Skip the carry-over for clipStartSec ≈ 0: position 0 is AVPlayer's default, so seeking
    // to it only introduces a rate=0 freeze + async round-trip before playback resumes.
    if clipStartSec > 0.001 {
      armDeferredSeek(clipStartSec)
    }

    let url: URL
    if let resolved = Self.resolvedURL(for: uri) {
      url = resolved
    } else {
      currentUri = nil
      delegate?.videoSource(self, didChangeStatus: "error")
      return
    }

    installedLoopMode = loopMode
    if loopMode == "loop" {
      installedLoopStartSec = max(0, clipStartSec)
      installedLoopEndSec = clipEndSec
      installLooper(url: url)
      return
    }

    let asset = AVURLAsset(url: url)
    let item = AVPlayerItem(asset: asset)
    // Bound the playable region: AVPlayerItemDidPlayToEndTime fires at this time natively,
    // so the existing end observer covers region ends with no extra machinery.
    item.forwardPlaybackEndTime = clipEndTime
    #if targetEnvironment(simulator)
    let output = AVPlayerItemVideoOutput(outputSettings: nil)
    #else
    let output = AVPlayerItemVideoOutput(outputSettings: outputSettings)
    #endif
    output.requestNotificationOfMediaDataChange(withAdvanceInterval: 1.0 / 30.0)
    item.add(output)

    // AVQueuePlayer (an AVPlayer subclass — drop-in everywhere `player` is used) so 'off'
    // and 'loop' mode share one player type. With a single item it behaves exactly like
    // AVPlayer.
    let avPlayer = AVQueuePlayer(playerItem: item)
    avPlayer.volume = Float(volume)
    // Keep the player clock advancing through brief decode stalls; the renderer holds its last frame.
    avPlayer.automaticallyWaitsToMinimizeStalling = false
    avPlayer.actionAtItemEnd = .pause

    self.playerItem = item
    self.videoOutput = output
    self.player = avPlayer
    startFramePull()

    installOffModeTimeControlObserver(on: avPlayer)

    delegate?.videoSource(self, didChangeStatus: "loading")

    item.addObserver(self, forKeyPath: "status", options: [.new], context: nil)
    statusObservedItem = item

    endObserver = NotificationCenter.default.addObserver(
      forName: .AVPlayerItemDidPlayToEndTime,
      object: item,
      queue: .main
    ) { [weak self] note in
      self?.handleItemDidPlayToEnd(note)
    }
  }

  /// `'off'`-mode watchdog: monitor for unexpected stalls / pauses. Installed by a fresh
  /// 'off' load AND by the looper→'off' conversion in `loadUri` (replacing the loop
  /// watchdog, whose blind rate-nudge would fight the region-end pause). Handles two cases:
  /// 1. AVPlayer fails to fire AVPlayerItemDidPlayToEndTime after certain seek patterns
  ///    → detect a near-end pause and report the end
  /// 2. iOS pauses the player mid-clip (audio session, decoder scheduling)
  ///    → resume after brief delay
  private func installOffModeTimeControlObserver(on avPlayer: AVPlayer) {
    timeControlObserver?.invalidate()
    timeControlObserver = avPlayer.observe(\.timeControlStatus, options: [.new, .old]) { [weak self] player, _ in
      guard let self, player === self.player else { return }
      let status = player.timeControlStatus

      if status == .paused && self.shouldAutoPlay && !self.pendingPlay && !self.seekInProgress {
        if self.isInBackground {
          return
        }
        let playElapsed = CACurrentMediaTime() - self.lastPlaybackStartTime
        let currentSec = player.currentTime().seconds
        // The region bound (forwardPlaybackEndTime) is the real end when set: measuring
        // against the full item duration would misread the region-end pause as a mid-clip
        // stall and resume playback PAST the region.
        let durationSec = self.effectiveEndSec(of: player.currentItem)
        let nearEnd = durationSec > 0.2 && (durationSec - currentSec) < 0.5

        if nearEnd && playElapsed >= 1.5 {
          self.delegate?.videoSourceDidPlayToEnd(self)
        } else if currentSec < durationSec - 0.5 {
          DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in
            guard let self, let p = self.player else { return }
            if p.timeControlStatus == .paused && self.shouldAutoPlay && !self.pendingPlay {
              p.rate = self.desiredRate
            }
          }
        }
      }
    }
  }

  /// Convert the live looper into a bounded single playback on the SAME player — the
  /// teardown-free path for a same-URI loop→'off' clip swap. Returns false when there is
  /// nothing usable to convert (no queue player, no current item, or the manipulation lost
  /// the current item); the caller then falls back to a full teardown + fresh install.
  private func convertLooperToBoundedSinglePlayback() -> Bool {
    guard let queue = player as? AVQueuePlayer, let current = queue.currentItem else {
      return false
    }
    looper?.disableLooping()
    // Park the looper (see `retiredLooper`): releasing it here would dealloc it and rip
    // `current` out of the queue while it is still the item being played and pulled from.
    retiredLooper = looper
    looper = nil
    looperStatusObserver?.invalidate()
    looperStatusObserver = nil
    // No more looper-driven item advances: stop re-attaching outputs per cycle, and drop
    // the queued copies so the region end pauses here instead of advancing into another
    // cycle of the old loop.
    currentItemObserver?.invalidate()
    currentItemObserver = nil
    for item in queue.items() where item !== current {
      queue.remove(item)
    }
    queue.actionAtItemEnd = .pause
    current.forwardPlaybackEndTime = clipEndTime
    // 'off' semantics from here on: report 'ended' at the region bound (loop mode installs
    // no end observer), and swap the loop watchdog for the off-mode observer — the loop
    // variant has no near-end guard, so it would misread the region-end pause as a stall
    // and kick the rate back up against the bound.
    if endObserver == nil {
      endObserver = NotificationCenter.default.addObserver(
        forName: .AVPlayerItemDidPlayToEndTime,
        object: current,
        queue: .main
      ) { [weak self] note in
        self?.handleItemDidPlayToEnd(note)
      }
    }
    installOffModeTimeControlObserver(on: queue)
    // Documented AVFoundation behavior guarantees the current item survives all of the
    // above (disableLooping lets the current pass play out; removing OTHER queue items
    // cannot change currentItem). If that contract ever breaks, fail LOUDLY in debug —
    // a silent fall-through here would mean quietly tearing down on every shot, hiding
    // the exact regression this path exists to prevent.
    assert(
      queue.currentItem === current,
      "loop→off conversion lost the current item — AVPlayerLooper broke its contract")
    return queue.currentItem === current
  }

  /// Install an `AVQueuePlayer` + `AVPlayerLooper` for seamless looping of `url`, at load
  /// time — no async build, no mid-playback swap. The looper cycles internal COPIES of the
  /// template item with no decode-pipeline flush, so the wrap is gapless. The template
  /// itself is NEVER played (its status stays `.unknown` forever), which shapes everything
  /// here:
  /// - the video output is attached per cycled item (outputs do not carry to the copies),
  /// - "ready"/"error", the armed clip-start seek, and the pendingPlay resume are driven by
  ///   a status KVO on the FIRST cycled item — the same `observeValue` flow as a plain load,
  /// - no end observer is installed: a looped clip never "plays to end".
  ///
  /// `startSec` applies to the FIRST cycle only (as a deferred seek on that first item): the
  /// loop must wrap to 0, because a pre-baked loop file's seam is frame(last)→frame(0) —
  /// wrapping anywhere else would jump.
  private func installLooper(url: URL) {
    let asset = AVURLAsset(url: url)
    let template = AVPlayerItem(asset: asset)
    let queue = AVQueuePlayer()
    queue.volume = Float(volume)
    // Keep the player clock advancing through brief decode stalls; the renderer holds its last frame.
    queue.automaticallyWaitsToMinimizeStalling = false

    self.playerItem = template
    self.videoOutput = nil
    self.player = queue
    startFramePull()

    delegate?.videoSource(self, didChangeStatus: "loading")

    // Watchdog: iOS can pause the player mid-clip (audio session, decoder scheduling). The
    // looper owns position and looping, so never seek from here — just nudge the rate back.
    timeControlObserver = queue.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
      guard let self, player === self.player else { return }
      if player.timeControlStatus == .paused && self.shouldAutoPlay && !self.pendingPlay
        && !self.seekInProgress && !self.isInBackground && player.rate == 0
      {
        player.rate = self.desiredRate
      }
    }

    // Re-attach a fresh video output whenever the looper advances to a new current item, and
    // (re)apply the rate there too. The looper populates the queue ASYNCHRONOUSLY, so a rate
    // write issued right after its init lands on an empty queue and is silently dropped —
    // leaving the player parked at rate 0 with a stopped timebase, which stalls
    // `hasNewPixelBuffer` and freezes the render loop on its last decoded frame.
    currentItemObserver = queue.observe(\.currentItem, options: [.initial, .new]) {
      [weak self] q, _ in
      guard let self, let current = q.currentItem else { return }
      self.attachOutput(to: current)
      if self.statusObservedItem == nil {
        // First cycled item: its status KVO drives the plain-load readyToPlay flow
        // (deferred clip-start seek, pendingPlay resume, "ready"/"error" reporting).
        // `.initial` because the item may already be readyToPlay when it becomes current.
        current.addObserver(self, forKeyPath: "status", options: [.new, .initial], context: nil)
        self.statusObservedItem = current
      } else if self.shouldAutoPlay && !self.pendingPlay && !self.seekInProgress && q.rate == 0 {
        // Later cycles reuse already-primed copies: no status dance, just make sure the
        // rate survives the item transition.
        q.rate = self.desiredRate
        self.lastPlaybackStartTime = CACurrentMediaTime()
      }
    }

    // Installing the looper populates the queue (copies of the template) and loops them
    // gaplessly; actionAtItemEnd is managed by the looper from here on. With an armed
    // region the looper cycles ONLY [startSec, endSec] of the asset — cycled copies report
    // asset-absolute item time, so ptsSec sawtooths startSec→endSec with no translation.
    let looper: AVPlayerLooper
    if clipEndSec > 0 {
      let start = max(0, clipStartSec)
      let range = CMTimeRange(
        start: CMTime(seconds: start, preferredTimescale: 600),
        duration: CMTime(seconds: max(0, clipEndSec - start), preferredTimescale: 600))
      looper = AVPlayerLooper(player: queue, templateItem: template, timeRange: range)
    } else {
      looper = AVPlayerLooper(player: queue, templateItem: template)
    }
    self.looper = looper
    looperStatusObserver = looper.observe(\.status, options: [.new]) { [weak self] looper, _ in
      guard let self else { return }
      if looper.status == .failed {
        self.delegate?.videoSource(self, didChangeStatus: "error")
      }
    }
  }

  /// End-of-clip handler for `'off'` mode (loop mode installs no end observer — a looped
  /// clip never "plays to end"). The terminal state is exposed through the transport
  /// snapshot.
  private func handleItemDidPlayToEnd(_ note: Notification) {
    guard let endedItem = note.object as? AVPlayerItem, endedItem === player?.currentItem else {
      return
    }
    delegate?.videoSourceDidPlayToEnd(self)
  }

  /// Attach a fresh `AVPlayerItemVideoOutput` to `item` and point `self.videoOutput` at it, so
  /// `copyPixelBuffer` always reads from the CURRENT looper-cycled item. The looper alternates
  /// between a small set of item copies; we attach our output once per distinct item and reuse
  /// it on revisits (re-adding the same output to an item it already has is invalid).
  private func attachOutput(to item: AVPlayerItem?) {
    guard let item else { return }
    if let existing = outputsByLoopedItem.object(forKey: item) {
      videoOutput = existing
      playerItem = item
      return
    }
    #if targetEnvironment(simulator)
    let output = AVPlayerItemVideoOutput(outputSettings: nil)
    #else
    let output = AVPlayerItemVideoOutput(outputSettings: outputSettings)
    #endif
    output.requestNotificationOfMediaDataChange(withAdvanceInterval: 1.0 / 30.0)
    item.add(output)
    outputsByLoopedItem.setObject(output, forKey: item)
    videoOutput = output
    playerItem = item
  }

  /// Tracks the video output attached to each looper-cycled item (weak keys so recycled items
  /// release naturally) — prevents re-adding an output to an item that already has one.
  private let outputsByLoopedItem = NSMapTable<AVPlayerItem, AVPlayerItemVideoOutput>(
    keyOptions: .weakMemory, valueOptions: .strongMemory)

  private var pendingPlay = false
  private var desiredRate: Float = 1.0
  private var lastSeekWallTime: CFTimeInterval = 0

  override func observeValue(forKeyPath keyPath: String?, of object: Any?, change: [NSKeyValueChangeKey: Any]?, context: UnsafeMutableRawPointer?) {
    if keyPath == "status", let item = object as? AVPlayerItem,
       item === statusObservedItem, item === player?.currentItem {
      switch item.status {
      case .readyToPlay:
        delegate?.videoSource(self, didChangeStatus: "ready")
        applyDeferredSeekIfReady()
        if pendingPlay {
          pendingPlay = false
          if !seekInProgress {
            player?.rate = desiredRate
            lastPlaybackStartTime = CACurrentMediaTime()
            delegate?.videoSource(self, didChangeStatus: "playing")
          }
        }
      case .failed:
        delegate?.videoSource(self, didChangeStatus: "error")
      default:
        break
      }
    }
  }

  func play() {
    guard let p = player else { return }
    if seekInProgress { return }
    if p.currentItem?.status == .readyToPlay {
      p.rate = desiredRate
      lastPlaybackStartTime = CACurrentMediaTime()
      delegate?.videoSource(self, didChangeStatus: "playing")
    } else {
      pendingPlay = true
    }
  }

  func pause() {
    player?.pause()
  }

  func setRate(_ rate: Double) {
    desiredRate = Float(rate)
    if !seekInProgress, player?.currentItem?.status == .readyToPlay, let p = player {
      p.rate = desiredRate
    }
  }

  /// Prepare an already-loaded URI for another playback without clearing its pending seek.
  /// The clip start is owned by `armClipStart` and cleared by `applyDeferredSeekIfReady`.
  func prepareForReuseSeek() {
    lastPlaybackStartTime = CACurrentMediaTime()
  }

  /// Authoritative clip-start position bound to a clip generation. Records the arm ONLY — the
  /// seek is issued by `loadUri`, which always follows (the sole caller is
  /// `VideoTexturePlayer.loadClip`) and is the first point that knows which player it belongs to:
  /// the reuse path applies it directly, the fresh path lets the new item's `readyToPlay` drive
  /// it. Applying it here instead would seek whichever player is still mounted — on a URI change
  /// that is the OUTGOING one, about to be torn down, and its late completion would then conclude
  /// an arm the incoming player has not performed.
  func armClipStart(sec: Double) {
    clipStartSec = max(0, sec)
    // Record the arm for `loadUri`'s reuse path, which must apply it as a real seek even
    // when it is 0 (the player is wherever the last playthrough left it, not at 0).
    pendingReuseStartSec = clipStartSec
    // On a FRESH load, position 0 is AVPlayer's default start — no seek needed. Clear any
    // stale carry-over from loadUri so readyToPlay goes straight to the pendingPlay path
    // (no async round-trip).
    if clipStartSec < 0.001 {
      deferredSeekSec = -1
      // Nothing to skip past: a fresh item's own frame at 0 IS the wanted frame, so the pull must
      // not be gated. (On reuse `loadUri` re-arms below — there 0 is a real seek.)
      releaseDeferredSeekHold()
      return
    }
    armDeferredSeek(clipStartSec)
  }

  func seek(to sec: Double) {
    let duration = effectiveEndSec(of: player?.currentItem)
    let clampedSec = (duration > 0.2) ? min(sec, duration - 0.1) : sec
    armDeferredSeek(max(0, clampedSec))
    applyDeferredSeekIfReady()
  }

  /// The single funnel for arming a pending position. Holding frames from HERE — not from the
  /// moment the seek is issued — is the point: on a fresh load the seek cannot even be attempted
  /// until `readyToPlay`, and the ~120 Hz pull would otherwise publish the item's frame at 0
  /// during that window. AVFoundation has no start-position-at-load equivalent to ExoPlayer's
  /// `setMediaItem(uri, startPositionMs)`, so the gate is what makes the two platforms behave the
  /// same.
  private func armDeferredSeek(_ sec: Double) {
    deferredSeekSec = sec
    bufferLock.lock()
    framesHeldForSeek = true
    bufferLock.unlock()
  }

  /// Release the gate: the playhead is at the armed position, so what the output hands us next is
  /// the frame the caller asked for. Also called from `teardown` — with no player there is nothing
  /// to wait for, and leaving it set would gate the next clip's frames forever.
  private func releaseDeferredSeekHold() {
    bufferLock.lock()
    framesHeldForSeek = false
    bufferLock.unlock()
  }

  private func applyDeferredSeekIfReady() {
    guard deferredSeekSec >= 0 else { return }
    guard let p = player else { return }
    guard p.currentItem?.status == .readyToPlay else { return }
    guard !seekInProgress else { return }

    let clampedSec = deferredSeekSec
    let time = CMTime(seconds: clampedSec, preferredTimescale: 600)
    seekInProgress = true
    p.rate = 0
    p.seek(to: time, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self, weak p] finished in
      guard let self else { return }
      DispatchQueue.main.async {
        // Stale completion from a player that has since been swapped out. Clip-start seeks are
        // issued from `loadUri` onward, so they always belong to the mounted player; the exposed
        // `seek(to:)` is the one that can still be in flight when a clip swap tears its player
        // down. Such a completion says nothing about the incoming item — acting on it would clear
        // the new clip's armed position and release its frame gate, re-opening the very hole the
        // gate closes. `teardown` already reset `seekInProgress`.
        guard let p, p === self.player else { return }
        self.seekInProgress = false
        guard finished else {
          // Interrupted by a newer target, whose own `applyDeferredSeekIfReady` bailed on
          // `seekInProgress`. Re-drive from whatever is armed now: dropping it here used to just
          // leave the playhead wrong, but with the frame gate in place it would also hold the
          // renderer on its last texture indefinitely.
          self.applyDeferredSeekIfReady()
          return
        }
        // A newer target was requested while this seek was in flight; honor the latest target.
        if self.deferredSeekSec >= 0 && abs(self.deferredSeekSec - clampedSec) > 0.0005 {
          self.applyDeferredSeekIfReady()
          return
        }
        self.deferredSeekSec = -1
        // The playhead is where it was asked to be; the next pulled frame is the right one.
        self.releaseDeferredSeekHold()
        if self.shouldAutoPlay {
          // Preroll a decoded runway from the seek point before resuming, so the playhead
          // does not catch the decode head during playback.
          self.startPlaybackPrerolled()
        }
      }
    }
  }

  /// Resume playback only after `preroll` has decoded a runway from the current position.
  /// Preroll fills the decoded runway before playback resumes. Rate is restored unless a new
  /// seek takes ownership of the resume.
  private func startPlaybackPrerolled() {
    guard let p = player else { return }
    p.rate = 0
    p.preroll(atRate: desiredRate) { [weak self] _ in
      guard let self else { return }
      DispatchQueue.main.async {
        // A seek started during preroll → let that path own the resume.
        guard self.shouldAutoPlay, !self.seekInProgress else { return }
        self.player?.rate = self.desiredRate
        self.lastPlaybackStartTime = CACurrentMediaTime()
        self.delegate?.videoSource(self, didChangeStatus: "playing")
      }
    }
  }

  // MARK: - Off-main frame pull
  // The decoded frame lives in mediaserverd; `copyPixelBuffer` ships it over XPC/IOSurface, which
  // can block when mediaserverd is busy. A background timer pulls frames and deposits the latest
  // here under `bufferLock`; the renderer's `copyPixelBuffer()` just reads the deposited frame.
  private let pullQueue = DispatchQueue(
    label: "react-native-video-texture.frame-pull", qos: .userInteractive)
  private var pullTimer: DispatchSourceTimer?
  private let bufferLock = NSLock()
  private var latestBuffer: CVPixelBuffer?
  private var latestBufferIsNew = false
  /// Media time of `latestBuffer`, captured at deposit from the same item time the
  /// pull used to fetch it. Guarded by `bufferLock` with the buffer it describes.
  private var latestPtsSec: Double = -1
  private var clipGeneration: Int64 = 0
  /// True from the moment a position is armed until that seek lands. While set, the player is
  /// still parked at the OLD position, so every frame the output can hand us is the wrong one —
  /// see `pullLatestFrame`. Written on the main thread, read on `pullQueue`, so it shares
  /// `bufferLock` with the buffer it guards.
  private var framesHeldForSeek = false
  /// A deposited frame together with the metadata describing it. The timestamp is carried
  /// with the pixels so a consumer can never simulate against a different frame than the one
  /// it draws — the previous split path (pixels here, media time via a separate native->JS
  /// hop) raced, and which frame won depended on thread scheduling.
  struct DecodedFrame {
    let buffer: CVPixelBuffer
    /// Media time of this frame on the item's own timeline. In `'loop'` mode each looper
    /// cycle plays a fresh copy starting at 0, so this sawtooths 0→L→0.
    let ptsSec: Double
    let generation: Int64
  }

  /// Reader for the renderer: returns the latest frame the background pull deposited since
  /// the last read, or nil (the renderer then holds its last texture). Calls no AVFoundation,
  /// so the render loop cannot block on mediaserverd.
  func copyPixelBuffer() -> DecodedFrame? {
    bufferLock.lock(); defer { bufferLock.unlock() }
    guard latestBufferIsNew, let buffer = latestBuffer else { return nil }
    latestBufferIsNew = false
    return DecodedFrame(buffer: buffer, ptsSec: latestPtsSec, generation: clipGeneration)
  }

  /// Bumped by the player on each armed clip generation, and stamped onto every frame
  /// deposited afterwards, so a consumer can tell a clip swap from a seek without having to
  /// infer it from a jump in media time.
  func setClipGeneration(_ generation: Int64) {
    bufferLock.lock(); defer { bufferLock.unlock() }
    clipGeneration = generation
  }

  /// Background pull (on `pullQueue`): fetch the current frame and deposit it. The
  /// `copyPixelBuffer(forItemTime:)` here is the remote XPC that used to block the main thread —
  /// now it blocks only this background queue, so a busy mediaserverd just delays a frame.
  private func pullLatestFrame() {
    guard let output = videoOutput else { return }
    // An armed position has not landed yet: the item is still at wherever it was (0 on a fresh
    // load, the previous playthrough's end on reuse), so depositing now publishes a frame the
    // caller explicitly asked to skip past — on a follow-up clip that is the pre-shot lead-in
    // `startSec` exists to hide. Deposit nothing; the renderer holds its last texture, which is
    // the cover the clip swap already relies on.
    bufferLock.lock()
    let held = framesHeldForSeek
    bufferLock.unlock()
    if held { return }
    let time = output.itemTime(forHostTime: CACurrentMediaTime())
    let pb: CVPixelBuffer?
    #if targetEnvironment(simulator)
    guard let raw = output.copyPixelBuffer(forItemTime: time, itemTimeForDisplay: nil) else { return }
    pb = Self.bgraPixelBufferForSimulator(from: raw) ?? raw
    #else
    guard output.hasNewPixelBuffer(forItemTime: time) else { return }
    pb = output.copyPixelBuffer(forItemTime: time, itemTimeForDisplay: nil)
    #endif
    guard let pb else { return }
    bufferLock.lock()
    latestBuffer = pb
    latestBufferIsNew = true
    // `time` is the item time this very buffer was fetched for, so it is the frame's own
    // presentation time rather than a later sample of the player clock.
    latestPtsSec = time.seconds
    bufferLock.unlock()
  }

  /// Start the background pull timer (idempotent, runs on `pullQueue`). ~120 Hz so a 24–60 fps
  /// source never waits more than a tick for its next frame.
  private func startFramePull() {
    pullQueue.async { [weak self] in
      guard let self, self.pullTimer == nil else { return }
      let timer = DispatchSource.makeTimerSource(queue: self.pullQueue)
      timer.schedule(deadline: .now(), repeating: .milliseconds(8), leeway: .milliseconds(2))
      timer.setEventHandler { [weak self] in self?.pullLatestFrame() }
      self.pullTimer = timer
      timer.resume()
    }
  }

  /// Drop the last deposited frame so a new clip never briefly shows the previous one.
  private func clearLatestBuffer() {
    bufferLock.lock()
    latestBuffer = nil
    latestBufferIsNew = false
    latestPtsSec = -1
    bufferLock.unlock()
  }

  #if targetEnvironment(simulator)
  private static let ciContext = CIContext(options: [.cacheIntermediates: false])

  /// Converts NV12/other decode formats to BGRA for CVMetalTextureCache (simulator only).
  private static func bgraPixelBufferForSimulator(from source: CVPixelBuffer) -> CVPixelBuffer? {
    let format = CVPixelBufferGetPixelFormatType(source)
    if format == kCVPixelFormatType_32BGRA { return source }

    let width = CVPixelBufferGetWidth(source)
    let height = CVPixelBufferGetHeight(source)
    var dest: CVPixelBuffer?
    let attrs: [String: Any] = [
      kCVPixelBufferCGImageCompatibilityKey as String: true,
      kCVPixelBufferCGBitmapContextCompatibilityKey as String: true,
      kCVPixelBufferMetalCompatibilityKey as String: true,
      kCVPixelBufferIOSurfacePropertiesKey as String: [:],
      kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
    ]
    let status = CVPixelBufferCreate(
      kCFAllocatorDefault,
      width,
      height,
      kCVPixelFormatType_32BGRA,
      attrs as CFDictionary,
      &dest
    )
    guard status == kCVReturnSuccess, let dest else { return nil }

    ciContext.render(CIImage(cvPixelBuffer: source), to: dest)
    return dest
  }
  #endif

  private func teardown() {
    seekInProgress = false
    deferredSeekSec = -1
    // No player, so no seek is coming to release it. `loadUri` re-arms after this returns.
    releaseDeferredSeekHold()
    lastPlaybackStartTime = 0
    clearLatestBuffer()
    // Tear down the gapless looper + its current-item KVO before dropping the player.
    looper?.disableLooping()
    looper = nil
    // Safe to release now: its dealloc-time item removal hits a player we are discarding.
    retiredLooper = nil
    looperStatusObserver?.invalidate()
    looperStatusObserver = nil
    currentItemObserver?.invalidate()
    currentItemObserver = nil
    outputsByLoopedItem.removeAllObjects()
    if let obs = endObserver {
      NotificationCenter.default.removeObserver(obs)
      endObserver = nil
    }
    timeControlObserver?.invalidate()
    timeControlObserver = nil
    // Remove the status observer from the EXACT item it's registered on (the forward item or
    // the first looper-cycled item) — NOT `playerItem`, which `attachOutput` may have reassigned
    // to a looper-cycled copy that was never observed (removing from it would throw and crash).
    if let observed = statusObservedItem {
      observed.removeObserver(self, forKeyPath: "status")
      statusObservedItem = nil
    }
    player?.pause()

    // Detach the output, cancel the pull, and release player/item/asset — all ON THE PULL QUEUE.
    // Being serial, this runs AFTER any in-flight `pullLatestFrame`, so `remove(output)` can never
    // race the background `copyPixelBuffer` (AVPlayerItemVideoOutput is not thread-safe → crash on
    // swap). Capture STRONG LOCALS, never `self`: teardown is also called from `deinit`, and forming
    // a weak/strong ref to a deallocating object aborts (`objc_initWeak` fatal — the actual crash).
    // Off-main also keeps the Fig teardown XPC (FigRemotePropertyCacheTeardown) off the main thread.
    let timer = pullTimer
    pullTimer = nil
    let oldPlayer = player
    let oldItem = playerItem
    let oldOutput = videoOutput
    videoOutput = nil
    player = nil
    playerItem = nil
    pullQueue.async {
      timer?.cancel()
      if let oldItem, let oldOutput { oldItem.remove(oldOutput) }
      withExtendedLifetime((oldPlayer, oldItem, oldOutput)) {}
    }
  }

  private func subscribeToLifecycle() {
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(appDidEnterBackground),
      name: UIApplication.didEnterBackgroundNotification,
      object: nil
    )
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(appWillEnterForeground),
      name: UIApplication.willEnterForegroundNotification,
      object: nil
    )
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(appDidBecomeActive),
      name: UIApplication.didBecomeActiveNotification,
      object: nil
    )
  }

  private func unsubscribeFromLifecycle() {
    NotificationCenter.default.removeObserver(self)
  }

  @objc private func appDidEnterBackground() {
    isInBackground = true
    player?.pause()
  }

  @objc private func appWillEnterForeground() {
    // Resume happens in appDidBecomeActive — the audio session can't be reactivated
    // reliably until the app is actually active.
  }

  @objc private func appDidBecomeActive() {
    isInBackground = false
    // Backgrounding does not change the requested autoplay state, so resume here.
    guard shouldAutoPlay, let p = player, p.timeControlStatus == .paused else { return }
    play()
  }
}
