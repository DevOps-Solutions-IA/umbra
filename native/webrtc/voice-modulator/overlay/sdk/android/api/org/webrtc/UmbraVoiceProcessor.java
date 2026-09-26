/* Copyright (c) 2026 UMBRA project contributors. SPDX-License-Identifier: MIT */
package org.webrtc;

/** Per-call local capture postprocessor. No render processing or remote controls. */
public final class UmbraVoiceProcessor implements AudioProcessingFactory, AutoCloseable {
  public static final int OFF=0, ENABLING=1, ON=2, DISABLING=3, ERROR_MUTED=4;
  private long handle;
  public UmbraVoiceProcessor(boolean modulated) {
    handle=nativeCreate(modulated);
    if(handle==0) throw new IllegalStateException("Capture processor unavailable");
  }
  private long checked() {
    if(handle==0) throw new IllegalStateException("Capture processor closed");
    return handle;
  }
  @Override public synchronized long createNative(long environment) {
    long apm=nativeApm(checked(),environment);
    if(apm==0) throw new IllegalStateException("Capture APM unavailable");
    return apm;
  }
  public synchronized boolean request(boolean modulated, boolean naturalConfirmed) {
    return nativeRequest(checked(),modulated,naturalConfirmed);
  }
  public synchronized void mute(boolean muted) {nativeMute(checked(),muted);}
  public synchronized void authorize(boolean authorized) {nativeAuthorize(checked(),authorized);}
  /** Route/format invalidation is sticky; never a request for natural voice. */
  public synchronized void invalidate() {if(handle!=0)nativeFail(handle);}
  public synchronized int status() {return handle==0?ERROR_MUTED:nativeStatus(handle);}
  public synchronized boolean requested() {return nativeRequested(checked());}
  /** Only counters/timing, never PCM. 0..8 totals; 9..33 histogram buckets. */
  public synchronized long metric(int index) {
    if(index<0 || index>=34)throw new IllegalArgumentException("Metric index");
    return nativeMetric(checked(),index);
  }
  /** Dispose after PeerConnectionFactory; in-flight APM owns its own state. */
  @Override public synchronized void close() {
    if(handle!=0){nativeDestroy(handle);handle=0;}
  }
  private static native long nativeCreate(boolean modulated);
  private static native long nativeApm(long handle,long environment);
  private static native boolean nativeRequest(long handle,boolean modulated,boolean confirmed);
  private static native void nativeMute(long handle,boolean muted);
  private static native void nativeAuthorize(long handle,boolean authorized);
  private static native void nativeFail(long handle);
  private static native int nativeStatus(long handle);
  private static native boolean nativeRequested(long handle);
  private static native long nativeMetric(long handle,int index);
  private static native void nativeDestroy(long handle);
}
