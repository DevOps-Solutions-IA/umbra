# UI integration matrix — not evidence of Claude integration

All presentation below remains owned by Claude. No screen has been merged into the
technical branch. Domain implementation and laboratory acceptance are distinct.

| Future action | Existing technical entry point | Status/constraint |
|---|---|---|
| Unlock/create/change password | Vault password APIs | Existing security layer; do not skip platform auth |
| Admission overview | AdmissionService.status/isAdmissionAuthority | Read-only under lease |
| Issue/reject/cancel | AdmissionService review/decision/cancel APIs | Explicit owner decision; old reviews expire on lock |
| Import renewal | installRenewal | Atomic domain operation, not two UI transactions |
| Linked device admission | peerStatus | Locally observed proof and timestamps only |
| Connect/disconnect | ConnectivityService | Never inferred from unlocking |
| Nearby | startNearby/stopNearby | Independent consent; offline remains without network |
| Emergency | Engine.emergencyLock | Hide immediately, await actual closure separately |
| Generic notification | PrivateAndroidSurface.notification | No payload/preview input |
| Protected window/dialog/surface | PrivateAndroidSurface.protect | Before first frame; integrated screen test pending |
| Ordinary text clipboard | PrivateClipboard | Explicit one-use review; not restricted content |
| F01 photo once | RestrictedImages + RestrictedContentService | Native Android/HTTPS/RFCOMM and force-stop passed at7425385; final cumulative matrix is separate |
| F02 note once | RestrictedAudio + RestrictedPlayback | Native codec/capture/HTTPS/RFCOMM debug-R8 at7425385; physical capture/route removal remain unexecuted |
| F03 video once | RestrictedVideo.prepare + RestrictedPlayback with VideoOutput | Native preparation/presentation/HTTPS/RFCOMM and restart at7425385; physical synthetic silent playback/lock historical attempt11 |
| F04 PDF once | RestrictedDocuments + common Session | Static raster copy, isolated parser, HTTPS/RFCOMM and restart at7425385; no external viewer or original PDF export |
| F05 only in UMBRA | Mode.UMBRA_ONLY | Common motor; four-format policy/reopen/export-denial JVM regressions; native format acceptance remains a distinct layer |
| F06 expiry | Descriptor deadline + Session.check | Object vs active-session limits; all-format policy tamper/restart regressions; no background resume |

No title/text/icon/navigation changes are prescribed. Preserve Claude's current
Spanish simplification and C3 design. Do not use placeholder success for missing
video/PDF or a generic share intent to bypass restricted access.

References to7425385 are historical evidence for that tree, not a green claim for a
later HEAD. Consult PR16 and the dated20-case matrix. Combined Claude screens,
physical R8 and authenticated hardware Vault remain separate unexecuted work.
