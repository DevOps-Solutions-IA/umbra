// Copyright (c) 2026 UMBRA project contributors. SPDX-License-Identifier: MIT
#ifndef UMBRA_VOICE_PROCESSOR_H_
#define UMBRA_VOICE_PROCESSOR_H_
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <ctime>
#include <limits>

namespace umbra {
struct MonotonicClock {
  static int64_t Now() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
  }
  static int64_t Cpu() {
    timespec value{};
    if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &value) != 0) return -1;
    return int64_t(value.tv_sec) * 1000000000 + value.tv_nsec;
  }
};

// No PCM storage or queue. Initialize/Process share WebRTC's capture thread;
// local Request/Mute/Authorize/Fail/Close may run concurrently. Test clocks are
// compiled only in the standalone test, never selected by JNI or application data.
template <typename Clock = MonotonicClock> class VoiceProcessor {
 public:
  enum Status : int { OFF = 0, ENABLING = 1, ON = 2, DISABLING = 3, ERROR_MUTED = 4 };
  static constexpr uint64_t kModulated=1, kMuted=2, kAuthorized=4, kError=8, kClosed=16;
  static constexpr int64_t kBudgetNanos=10000000; // One capture block, no backlog.
  static constexpr size_t kBuckets=25;
  static constexpr float kGain=0.8f, kLimit=24000.0f;
  explicit VoiceProcessor(bool modulated=false) : command_(256 | (modulated?kModulated:0)) {
    if (!command_.is_lock_free()) Fail();
  }
  VoiceProcessor(const VoiceProcessor&)=delete;
  VoiceProcessor& operator=(const VoiceProcessor&)=delete;

  // False means rejected, with no natural-voice transition. Retry ON is explicit.
  bool Request(bool modulated, bool natural_confirmed) {
    uint64_t old=command_.load();
    for (;;) {
      if ((old & kClosed) || (!modulated && !natural_confirmed && (old & (kModulated|kError)))) return false;
      if (old > std::numeric_limits<uint64_t>::max()-256) { Close();return false; }
      uint64_t next=((old+256)&~(kModulated|kError)) | (modulated?kModulated:0);
      if (command_.compare_exchange_weak(old,next)) return true;
    }
  }
  void Mute(bool value) { Change(kMuted,value); }
  void Authorize(bool value) { Change(kAuthorized,value); }
  void Close() { command_.fetch_or(kClosed); }
  void Fail() { if (!(command_.fetch_or(kError)&kError)) faults_.fetch_add(1); }
  uint64_t command() const { return command_.load(); }
  bool requested() const { return command()&kModulated; }
  Status status() const {
    uint64_t value=command();
    if (value&(kError|kClosed)) return ERROR_MUTED;
    if (applied_.load()==value) return (value&kModulated)?ON:OFF;
    return (value&kModulated)?ENABLING:DISABLING;
  }

  void Initialize(int rate,size_t channels) {
    if (busy_.test_and_set()) { Fail();return; }
    bool valid=(rate==8000 || rate==16000 || rate==32000 || rate==48000) && (channels==1 || channels==2);
    if (!valid) { Fail();rate_=0;channels_=0;busy_.clear();return; }
    // Supported bootstrap reconfiguration occurs before the first admitted PCM.
    // Once any block has been delivered, a modulated route/format change is sticky-muted.
    if (processed_.load()!=0 && (rate_!=rate || channels_!=channels) && requested()) Fail();
    rate_=rate;channels_=channels;
    for (int i=0;i<rate/100;++i) carrier_[i]=float(std::cos(2.0*3.14159265358979323846*i/(rate/100)));
    busy_.clear();
  }

  // Caller owns valid writable buffers of the stated shape. Unsupported shape
  // produces silence across ALL supplied channels, never a partial bypass.
  bool Process(float* const* audio,size_t channels,size_t frames,int rate) {
    const int64_t started=Clock::Now(),cpu_started=Clock::Cpu();
    const uint64_t admitted=command();
    if (busy_.test_and_set()) { Fail();Silence(audio,channels,frames);return false; }
    bool result=false;
    if (!audio || rate_!=rate || channels_!=channels || rate_==0 || frames!=size_t(rate_/100)) {
      Fail();
    } else if (!(admitted&(kError|kClosed|kMuted)) && (admitted&kAuthorized)) {
      bool valid=true;
      for (size_t ch=0;ch<channels;++ch) {
        if (!audio[ch]) {valid=false;break;}
        for (size_t i=0;i<frames;++i)
          if (!std::isfinite(audio[ch][i]) || std::abs(audio[ch][i])>65536.0f) {valid=false;break;}
      }
      if (!valid) { Fail(); }
      else {
        if (admitted&kModulated) {
          for (size_t ch=0;ch<channels;++ch) for (size_t i=0;i<frames;++i) {
            float sample=audio[ch][i]*carrier_[i]*kGain;
            if (sample>kLimit || sample< -kLimit) clipped_.fetch_add(1);
            audio[ch][i]=std::clamp(sample,-kLimit,kLimit);
          }
        }
        result=true;
      }
    }
    const int64_t elapsed=Clock::Now()-started,cpu=Clock::Cpu()-cpu_started;
    if (elapsed<0 || elapsed>kBudgetNanos || cpu_started<0 || cpu<0) { Fail();result=false; }
    // A control changed while this block was being processed. Discard it, even
    // when the old mode was OFF. Codec/network buffers already admitted earlier
    // are outside this boundary; this is not a promise to recall sent packets.
    if (command()!=admitted) result=false;
    if (!result) { Silence(audio,channels,frames);silent_.fetch_add(1); }
    else { applied_.store(admitted);processed_.fetch_add(1);if(admitted&kModulated)modulated_.fetch_add(1); }
    blocks_.fetch_add(1);
    if (elapsed>=0) {
      size_t bucket=0;uint64_t bound=1000;
      while(bucket+1<kBuckets && uint64_t(elapsed)>bound) {++bucket;bound*=2;}
      histogram_[bucket].fetch_add(1);wall_.fetch_add(elapsed);
      uint64_t previous=maximum_.load();
      while(uint64_t(elapsed)>previous && !maximum_.compare_exchange_weak(previous,elapsed)) {}
    }
    if (cpu>=0) cpu_.fetch_add(cpu);
    busy_.clear();return result;
  }

  struct Metrics {
    uint64_t blocks,processed,modulated,silent,faults,clipped,wall,cpu,maximum;
    std::array<uint64_t,kBuckets> histogram;
  };
  Metrics metrics() const {
    Metrics value{blocks_.load(),processed_.load(),modulated_.load(),silent_.load(),faults_.load(),clipped_.load(),wall_.load(),cpu_.load(),maximum_.load(),{}};
    for(size_t i=0;i<kBuckets;++i)value.histogram[i]=histogram_[i].load();
    return value;
  }
 private:
  void Change(uint64_t mask,bool enabled) {
    uint64_t old=command();
    for (;;) {
      if(old&kClosed)return;
      if(bool(old&mask)==enabled)return;
      if(old>std::numeric_limits<uint64_t>::max()-256) {Close();return;}
      uint64_t next=((old+256)&~mask)|(enabled?mask:0);
      if(command_.compare_exchange_weak(old,next))return;
    }
  }
  static void Silence(float* const* audio,size_t channels,size_t frames) {
    if(audio)for(size_t ch=0;ch<channels;++ch)if(audio[ch])std::fill_n(audio[ch],frames,0.0f);
  }
  std::atomic<uint64_t> command_,applied_{0};
  std::atomic_flag busy_=ATOMIC_FLAG_INIT;
  int rate_=0;size_t channels_=0;
  std::array<float,480> carrier_{}; // Oscillator only, never voice or delayed PCM.
  std::atomic<uint64_t> blocks_{0},processed_{0},modulated_{0},silent_{0},faults_{0},clipped_{0},wall_{0},cpu_{0},maximum_{0};
  std::array<std::atomic<uint64_t>,kBuckets> histogram_{};
};
} // namespace umbra
#endif
