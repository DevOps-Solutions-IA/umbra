// Copyright (c) 2026 UMBRA project contributors. SPDX-License-Identifier: MIT
#include "processor.h"
#include <cstdlib>
#include <iostream>
#include <thread>
#include <vector>

#define CHECK(x) do { if (!(x)) { std::cerr << __LINE__ << ": " #x "\n"; std::abort(); } } while (0)
struct Clock {
  static inline int64_t tick=0, step=100, cpu=0;
  static inline void (*hook)()=nullptr;
  static int64_t Now() { tick+=step; if(hook) { auto f=hook;hook=nullptr;f(); }return tick; }
  static int64_t Cpu() {return cpu;}
};
using P=umbra::VoiceProcessor<Clock>;
struct Block {
  std::array<float,480> a{},b{};
  float* data[2]={a.data(),b.data()};
  void tone(int rate=48000) {
    for(int i=0;i<rate/100;++i)a[i]=b[i]=10000*std::sin(2*3.141592653589793*1000*i/rate);
  }
  bool zero(int channels=2,int frames=480) {
    for(int ch=0;ch<channels;++ch)for(int i=0;i<frames;++i)if(data[ch][i]!=0)return false;
    return true;
  }
  double energy(int frequency,int rate=48000) {
    double re=0,im=0;
    for(int i=0;i<rate/100;++i) {
      re+=a[i]*std::cos(2*3.141592653589793*frequency*i/rate);
      im+=a[i]*std::sin(2*3.141592653589793*frequency*i/rate);
    }
    return std::hypot(re,im)/(rate/100);
  }
};
static P* callback_processor;
static void Toggle() { callback_processor->Request(true,false); }
static void Reenter() {Block nested;nested.tone();CHECK(!callback_processor->Process(nested.data,2,480,48000));CHECK(nested.zero());}
void modes() {
  P p;Block b;p.Initialize(48000,2);b.tone();
  CHECK(!p.Process(b.data,2,480,48000));CHECK(b.zero());
  p.Authorize(true);b.tone();auto original=b.a;
  CHECK(p.Process(b.data,2,480,48000));CHECK(b.a==original);CHECK(p.status()==P::OFF);
  CHECK(p.Request(true,false));CHECK(p.status()==P::ENABLING);b.tone();
  CHECK(p.Process(b.data,2,480,48000));CHECK(p.status()==P::ON);
  CHECK(b.energy(900)>1999 && b.energy(1100)>1999);CHECK(b.energy(1000)<0.01);
  CHECK(!p.Request(false,false));CHECK(p.requested());
  p.Mute(true);CHECK(p.Request(false,true));b.tone();CHECK(!p.Process(b.data,2,480,48000));CHECK(b.zero());
  CHECK(p.status()==P::DISABLING);p.Mute(false);b.tone();CHECK(p.Process(b.data,2,480,48000));CHECK(b.a==original);
  p.Close();CHECK(!p.Request(true,false));b.tone();CHECK(!p.Process(b.data,2,480,48000));CHECK(b.zero());
}
void formats() {
  // APM negotiates supported internal formats before admitting its first frame.
  // Initial MODULATED selection must survive that bootstrap without a dry frame.
  {
    P initial(true);Block first;initial.Initialize(16000,1);initial.Initialize(48000,2);
    initial.Authorize(true);first.tone();CHECK(initial.Process(first.data,2,480,48000));
    CHECK(initial.status()==P::ON);CHECK(first.energy(1000)<0.01);
  }
  for(int rate:{8000,16000,32000,48000})for(int channels:{1,2}) {
    P p(true);Block b;p.Initialize(rate,channels);p.Authorize(true);b.tone(rate);
    CHECK(p.status()==P::ENABLING);CHECK(p.Process(b.data,channels,rate/100,rate));
    CHECK(b.energy(900,rate)>1999);CHECK(b.energy(1000,rate)<0.01);
    b.a.fill(0);b.b.fill(0);CHECK(p.Process(b.data,channels,rate/100,rate));CHECK(b.zero());
    b.a.fill(65536);b.b.fill(-65536);CHECK(p.Process(b.data,channels,rate/100,rate));
    for(int i=0;i<rate/100;++i)CHECK(std::abs(b.a[i])<=P::kLimit);
    CHECK(p.metrics().clipped>0);
  }
  P p(true);Block b;p.Initialize(48000,2);p.Authorize(true);b.tone();CHECK(p.Process(b.data,2,480,48000));p.Initialize(16000,1);
  CHECK(p.status()==P::ERROR_MUTED);b.tone();CHECK(!p.Process(b.data,1,160,16000));
  CHECK(p.Request(true,false));b.tone(16000);CHECK(p.Process(b.data,1,160,16000));
  p.Initialize(44100,2);CHECK(p.status()==P::ERROR_MUTED);
}
void faults() {
  // Finite low/high amplitudes and frequencies retain block duration and never
  // bypass the carrier. This is signal behavior, not intelligibility evidence.
  for(int frequency:{200,700,2100})for(float amplitude:{1.f,50.f,12000.f,40000.f}) {
    P p(true);Block b;p.Initialize(48000,2);p.Authorize(true);
    for(int i=0;i<480;++i)b.a[i]=b.b[i]=amplitude*std::sin(2*3.141592653589793*frequency*i/48000);
    auto original=b.a;CHECK(p.Process(b.data,2,480,48000));
    for(int i=0;i<480;++i) {
      float expected=std::clamp(original[i]*float(std::cos(2*3.141592653589793*i/480))*P::kGain,-P::kLimit,P::kLimit);
      CHECK(std::abs(b.a[i]-expected)<0.01f);CHECK(std::isfinite(b.a[i]));
    }
  }

  for(float bad:{std::numeric_limits<float>::quiet_NaN(),std::numeric_limits<float>::infinity(),65537.f}) {
    P p(true);Block b;p.Initialize(48000,2);p.Authorize(true);b.tone();b.b[479]=bad;
    CHECK(!p.Process(b.data,2,480,48000));CHECK(b.zero());CHECK(p.status()==P::ERROR_MUTED);
    b.tone();CHECK(!p.Process(b.data,2,480,48000));CHECK(b.zero());CHECK(!p.Request(false,false));
    CHECK(p.Request(true,false));b.tone();CHECK(p.Process(b.data,2,480,48000));
  }
  P p(true);Block b;p.Initialize(48000,2);p.Authorize(true);b.tone();
  Clock::step=P::kBudgetNanos+1;CHECK(!p.Process(b.data,2,480,48000));CHECK(b.zero());CHECK(p.status()==P::ERROR_MUTED);
  Clock::step=100;CHECK(p.Request(true,false));Clock::cpu=-1;b.tone();
  CHECK(!p.Process(b.data,2,480,48000));CHECK(b.zero());Clock::cpu=0;
  CHECK(p.Request(true,false));b.tone();CHECK(!p.Process(b.data,2,479,48000));CHECK(b.zero(2,479));
}
void stale_and_concurrent() {
  P p;Block b;p.Initialize(48000,2);p.Authorize(true);b.tone();
  // Cpu is queried before admission; trigger a request after admission via a
  // second Now call (not by hoping for a scheduler race).
  struct Hooks {
    static void Arm() {Clock::hook=Toggle;}
  };
  callback_processor=&p;Clock::hook=Hooks::Arm;
  CHECK(!p.Process(b.data,2,480,48000));CHECK(b.zero());CHECK(p.status()==P::ENABLING);
  p.Mute(true);
  std::thread a([&]{for(int i=0;i<10000;++i)CHECK(p.Request(true,false));});
  std::thread c([&]{for(int i=0;i<10000;++i)CHECK(p.Request(false,true));});
  for(int i=0;i<10000;++i) {b.tone();CHECK(!p.Process(b.data,2,480,48000));CHECK(b.zero());}
  a.join();c.join();CHECK(p.command()&P::kMuted);CHECK(p.command()&P::kAuthorized);
  CHECK(p.Request(true,false));p.Mute(false);b.tone();
  struct Reentrant {static void Arm(){Clock::hook=Reenter;}};
  Clock::hook=Reentrant::Arm;CHECK(!p.Process(b.data,2,480,48000));CHECK(b.zero());CHECK(p.status()==P::ERROR_MUTED);
  p.Authorize(false);p.Mute(false);CHECK(p.Request(true,false));b.tone();CHECK(!p.Process(b.data,2,480,48000));CHECK(b.zero());
}
void benchmark() {
  umbra::VoiceProcessor<> p(true);Block b;p.Initialize(48000,2);p.Authorize(true);
  for(int i=0;i<10000;++i) {b.tone();CHECK(p.Process(b.data,2,480,48000));}
  auto m=p.metrics();CHECK(m.processed==10000);CHECK(m.faults==0);
  std::cout<<"DSP host benchmark: blocks="<<m.blocks<<" mean_wall_ns="<<m.wall/m.blocks
    <<" mean_cpu_ns="<<m.cpu/m.blocks<<" max_wall_ns="<<m.maximum<<" object_bytes="<<sizeof(p)<<"\n";
  for(int percentile:{50,95,99}) {
    uint64_t sum=0,upper=1000;
    for(auto count:m.histogram){sum+=count;if(sum*100>=m.blocks*percentile)break;upper*=2;}
    std::cout<<"wall_p"<<percentile<<"_upper_ns="<<upper<<"\n";
  }
  // An impulse stays at its original sample index: zero algorithmic lookahead.
  b.a.fill(0);b.b.fill(0);b.a[17]=10000;CHECK(p.Process(b.data,2,480,48000));
  CHECK(b.a[17]!=0);for(int i=0;i<480;++i)if(i!=17)CHECK(b.a[i]==0);
}
int main() {modes();formats();faults();stale_and_concurrent();benchmark();std::cout<<"PASS five DSP test groups; not Android/Opus/TURN acceptance\n";}
