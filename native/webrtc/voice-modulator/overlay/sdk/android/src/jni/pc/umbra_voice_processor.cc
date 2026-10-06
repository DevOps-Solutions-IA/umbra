// Copyright (c) 2026 UMBRA project contributors. SPDX-License-Identifier: MIT
#include <memory>
#include <string>
#include "api/audio/builtin_audio_processing_builder.h"
#include "api/environment/environment.h"
#include "modules/audio_processing/audio_buffer.h"
#include "sdk/android/src/jni/pc/umbra_voice_processor_core.h"
#include "sdk/android/generated_peerconnection_jni/UmbraVoiceProcessor_jni.h"

namespace webrtc {
namespace jni {
namespace {
using Processor = umbra::VoiceProcessor<>;
class Capture final : public CustomProcessing {
 public:
  explicit Capture(std::shared_ptr<Processor> state) : state_(std::move(state)) {}
  void Initialize(int rate, int channels) override {
    rate_=rate;state_->Initialize(rate, channels);
  }
  void Process(AudioBuffer* audio) override {
    state_->Process(audio->channels(),audio->num_channels(),audio->num_frames(),rate_);
  }
  std::string ToString() const override {return "UmbraLocalVoiceV1";}
 private:
  std::shared_ptr<Processor> state_;
  int rate_=0;
};
struct Handle {
  explicit Handle(bool modulated) : state(std::make_shared<Processor>(modulated)) {}
  std::shared_ptr<Processor> state;
  scoped_refptr<AudioProcessing> apm;
};
Handle* Get(jlong handle) {return reinterpret_cast<Handle*>(handle);}
} // namespace
static jlong JNI_UmbraVoiceProcessor_Create(JNIEnv*, jboolean modulated) {
  return reinterpret_cast<jlong>(new Handle(modulated));
}
static jlong JNI_UmbraVoiceProcessor_Apm(JNIEnv*, jlong handle,jlong environment) {
  auto* h=Get(handle);
  if(!h->apm)h->apm=BuiltinAudioProcessingBuilder()
      .SetCapturePostProcessing(std::make_unique<Capture>(h->state))
      .Build(*reinterpret_cast<Environment*>(environment));
  if(!h->apm)h->state->Fail();
  return reinterpret_cast<jlong>(h->apm.get());
}
static jboolean JNI_UmbraVoiceProcessor_Request(JNIEnv*, jlong h,jboolean mode,jboolean confirmed) {
  return Get(h)->state->Request(mode,confirmed);
}
static void JNI_UmbraVoiceProcessor_Mute(JNIEnv*,jlong h,jboolean mute) {Get(h)->state->Mute(mute);}
static void JNI_UmbraVoiceProcessor_Authorize(JNIEnv*,jlong h,jboolean allowed) {Get(h)->state->Authorize(allowed);}
static void JNI_UmbraVoiceProcessor_Fail(JNIEnv*,jlong h) {Get(h)->state->Fail();}
static jint JNI_UmbraVoiceProcessor_Status(JNIEnv*,jlong h) {return Get(h)->state->status();}
static jboolean JNI_UmbraVoiceProcessor_Requested(JNIEnv*,jlong h) {return Get(h)->state->requested();}
static jlong JNI_UmbraVoiceProcessor_Metric(JNIEnv*,jlong h,jint index) {
  auto m=Get(h)->state->metrics();
  const uint64_t totals[]={m.blocks,m.processed,m.modulated,m.silent,m.faults,m.clipped,m.wall,m.cpu,m.maximum};
  if(index>=0 && index<9)return totals[index];
  if(index>=9 && index<34)return m.histogram[index-9];
  return -1;
}
static void JNI_UmbraVoiceProcessor_Destroy(JNIEnv*,jlong h) {
  Get(h)->state->Close();delete Get(h);
}
} // namespace jni
} // namespace webrtc
