#include "FrameSourceHostObject.h"

#include <mutex>
#include <unordered_map>
#include <vector>

namespace jsi = facebook::jsi;

namespace videotexture {
namespace {

class FrameSourceState {
 public:
  explicit FrameSourceState(std::shared_ptr<FrameProvider> provider)
      : provider_(std::move(provider)) {}

  ~FrameSourceState() {
    releaseAll();
  }

  AcquiredFrame copyNewFrame() {
    AcquiredFrame frame = provider_->copyNewFrame();
    if (!frame.handle) {
      return frame;
    }
    {
      std::lock_guard<std::mutex> lock(mutex_);
      handles_.emplace(reinterpret_cast<uint64_t>(frame.handle), frame.handle);
    }
    return frame;
  }

  TransportSnapshot transportSnapshot() {
    return provider_->transportSnapshot();
  }

  void loadClip(const std::string &uri, double startSec, int64_t generation,
                const std::string &loopMode, bool autoPlay) {
    provider_->loadClip(uri, startSec, generation, loopMode, autoPlay);
  }

  void setPaused(bool paused) { provider_->setPaused(paused); }
  void setRate(double rate) { provider_->setRate(rate); }
  void rampRate(double rate, double durationMs) { provider_->rampRate(rate, durationMs); }
  void setVolume(double volume) { provider_->setVolume(volume); }

  void releaseTrackedHandle(uint64_t handle) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = handles_.find(handle);
    if (it == handles_.end()) {
      return;
    }
    provider_->releaseHandle(it->second);
    handles_.erase(it);
  }

  void releaseAll() {
    std::lock_guard<std::mutex> lock(mutex_);
    for (auto &entry : handles_) {
      provider_->releaseHandle(entry.second);
    }
    handles_.clear();
  }

  std::string pixelFormat() {
    return provider_->pixelFormat();
  }

 private:
  std::shared_ptr<FrameProvider> provider_;
  std::mutex mutex_;
  std::unordered_map<uint64_t, void *> handles_;
};

/// jsi::HostObject bridging the worklet render loop to the native frame
/// provider. Handles are +1-retained pointers tracked in a table so double
/// releases are no-ops and teardown can drain stragglers.
class FrameSourceHostObject : public jsi::HostObject {
 public:
  explicit FrameSourceHostObject(std::shared_ptr<FrameProvider> provider)
      : state_(std::make_shared<FrameSourceState>(std::move(provider))) {}

  jsi::Value get(jsi::Runtime &rt, const jsi::PropNameID &name) override {
    auto prop = name.utf8(rt);
    if (prop == "poll") {
      return makePoll(rt, prop);
    }
    if (prop == "releaseFrame") {
      auto state = state_;
      return jsi::Function::createFromHostFunction(
          rt, jsi::PropNameID::forAscii(rt, prop), 1,
          [state](jsi::Runtime &rt, const jsi::Value &, const jsi::Value *args, size_t count) {
            if (count < 1 || !args[0].isBigInt()) {
              return jsi::Value::undefined();
            }
            state->releaseTrackedHandle(args[0].asBigInt(rt).asUint64(rt));
            return jsi::Value::undefined();
          });
    }
    if (prop == "releaseAll") {
      auto state = state_;
      return jsi::Function::createFromHostFunction(
          rt, jsi::PropNameID::forAscii(rt, prop), 0,
          [state](jsi::Runtime &, const jsi::Value &, const jsi::Value *, size_t) {
            state->releaseAll();
            return jsi::Value::undefined();
          });
    }
    if (prop == "loadClip") {
      auto state = state_;
      return jsi::Function::createFromHostFunction(
          rt, jsi::PropNameID::forAscii(rt, prop), 1,
          [state](jsi::Runtime &rt, const jsi::Value &, const jsi::Value *args, size_t count) {
            if (count < 1 || !args[0].isObject()) return jsi::Value::undefined();
            auto options = args[0].asObject(rt);
            auto uriValue = options.getProperty(rt, "uri");
            if (!uriValue.isString()) return jsi::Value::undefined();
            auto number = [&](const char *name, double fallback) {
              auto value = options.getProperty(rt, name);
              return value.isNumber() ? value.asNumber() : fallback;
            };
            auto string = [&](const char *name, const std::string &fallback) {
              auto value = options.getProperty(rt, name);
              return value.isString() ? value.asString(rt).utf8(rt) : fallback;
            };
            auto autoPlayValue = options.getProperty(rt, "autoPlay");
            state->loadClip(uriValue.asString(rt).utf8(rt), number("startSec", 0.0),
                            static_cast<int64_t>(number("generation", 0.0)),
                            string("loopMode", "off"),
                            autoPlayValue.isBool() ? autoPlayValue.getBool() : true);
            return jsi::Value::undefined();
          });
    }
    if (prop == "setPaused" || prop == "setRate" || prop == "rampRate" ||
        prop == "setVolume") {
      auto state = state_;
      return jsi::Function::createFromHostFunction(
          rt, jsi::PropNameID::forAscii(rt, prop), prop == "rampRate" ? 2 : 1,
          [state, prop](jsi::Runtime &, const jsi::Value &, const jsi::Value *args, size_t count) {
            if (count < 1) return jsi::Value::undefined();
            if (prop == "setPaused" && args[0].isBool()) state->setPaused(args[0].getBool());
            else if (prop == "setRate" && args[0].isNumber()) state->setRate(args[0].asNumber());
            else if (prop == "rampRate" && count >= 2 && args[0].isNumber() && args[1].isNumber())
              state->rampRate(args[0].asNumber(), args[1].asNumber());
            else if (prop == "setVolume" && args[0].isNumber()) state->setVolume(args[0].asNumber());
            return jsi::Value::undefined();
          });
    }
    if (prop == "pixelFormat") {
      return jsi::String::createFromUtf8(rt, state_->pixelFormat());
    }
    return jsi::Value::undefined();
  }

  std::vector<jsi::PropNameID> getPropertyNames(jsi::Runtime &rt) override {
    return jsi::PropNameID::names(rt, "poll", "loadClip", "setPaused", "setRate", "rampRate",
                                  "setVolume", "releaseFrame", "releaseAll", "pixelFormat");
  }

 private:
  /// Returns `{handle, ptsSec, generation}` for a newly decoded frame, or null when nothing
  /// new has landed. Never blocks. Allocating an object is fine here: it only happens on an
  /// actual new frame (<=60/s), not on every render tick.
  jsi::Value makePoll(jsi::Runtime &rt, const std::string &prop) {
    auto state = state_;
    return jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, prop), 0,
        [state](jsi::Runtime &rt, const jsi::Value &, const jsi::Value *, size_t) {
          jsi::Object out(rt);
          AcquiredFrame frame = state->copyNewFrame();
          if (frame.handle) {
            jsi::Object value(rt);
            value.setProperty(
                rt, "handle",
                jsi::Value(jsi::BigInt::fromUint64(rt, reinterpret_cast<uint64_t>(frame.handle))));
            value.setProperty(rt, "ptsSec", jsi::Value(frame.ptsSec));
            value.setProperty(rt, "generation", jsi::Value(static_cast<double>(frame.generation)));
            out.setProperty(rt, "frame", std::move(value));
          } else {
            out.setProperty(rt, "frame", jsi::Value::null());
          }
          TransportSnapshot snapshot = state->transportSnapshot();
          static const char *statuses[] = {"idle", "loading", "playing", "paused", "ended", "error"};
          int status = snapshot.status >= 0 && snapshot.status <= 5 ? snapshot.status : 5;
          if (snapshot.uri.empty()) out.setProperty(rt, "uri", jsi::Value::null());
          else out.setProperty(rt, "uri", jsi::String::createFromUtf8(rt, snapshot.uri));
          out.setProperty(rt, "generation", static_cast<double>(snapshot.generation));
          out.setProperty(rt, "status", jsi::String::createFromAscii(rt, statuses[status]));
          out.setProperty(rt, "statusSeq", static_cast<double>(snapshot.statusSeq));
          out.setProperty(rt, "errorSeq", static_cast<double>(snapshot.errorSeq));
          if (snapshot.errorMessage.empty()) out.setProperty(rt, "errorMessage", jsi::Value::null());
          else out.setProperty(rt, "errorMessage", jsi::String::createFromUtf8(rt, snapshot.errorMessage));
          out.setProperty(rt, "durationSec", snapshot.durationSec);
          out.setProperty(rt, "actualRate", snapshot.actualRate);
          return jsi::Value(std::move(out));
        });
  }

  std::shared_ptr<FrameSourceState> state_;
};

constexpr const char *kRegistryName = "__videoTextureFrameSources";

}  // namespace

void installFrameSource(jsi::Runtime &rt, std::shared_ptr<FrameProvider> provider,
                        const std::string &key) {
  auto hostObject = std::make_shared<FrameSourceHostObject>(std::move(provider));

  jsi::Object registry = [&]() -> jsi::Object {
    jsi::Value existing = rt.global().getProperty(rt, kRegistryName);
    if (existing.isObject()) {
      return existing.asObject(rt);
    }
    jsi::Object fresh(rt);
    rt.global().setProperty(rt, kRegistryName, fresh);
    return fresh;
  }();

  registry.setProperty(rt, key.c_str(), jsi::Object::createFromHostObject(rt, hostObject));
}

}  // namespace videotexture
