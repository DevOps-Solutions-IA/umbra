# UI integration matrix — not evidence of Claude integration

All presentation below remains owned by Claude. No screen has been merged into the
technical branch. Domain implementation and laboratory acceptance are distinct. Inspected source:
278a572d8840f0989b21a738efe7823437cf1844; final acceptance is bound by PR16 receipt.

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
| Sensitive text entry | PrivateAndroidSurface.sensitiveInput + SensitiveBuffer | No restore/autofill/content capture; caller erases transferred buffers |
| Source import / ordinary text export | Format prepare APIs / OrdinaryTextExport | Bounded caller-owned input; ordinary consent never authorizes restricted export |
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

## Lifecycle handoff for every restricted viewer

| Event | Domain/adapter responsibility | Claude responsibility |
|---|---|---|
| Before first sensitive frame | Current Session authorizes decoder; protected output contract | Protect Activity/Window/SurfaceView before drawing, including dialogs |
| Send confirmation | Original Review binds target/mode/TTL/lease; Prepared managed by Engine | Present accurate choice; submit once; close abandoned Prepared |
| Received object | `received(peer)` / `status(id)` expose metadata | Do not invent preview, viewed receipt or auto-open |
| Open confirmation | `open(review,true)` commits consumption before handing out Session | Show consumed versus displayed/completed honestly |
| Pause/lock/emergency | Invalidate lease immediately, asynchronous native closure | Hide/clear sensitive content immediately; close handles; await closure independently |
| Recreation / permission-dialog return | Old reviews/players cannot be revived | Fresh local authentication/review when invalidated; do not replay old callbacks |
| Cleanup failure | Session closure exceptional; emergency INCOMPLETE denies overlap | Safe generic error; no unlock-to-clear or external viewer fallback |
| Audio focus/route loss | Stop strict playback; no automatic speaker fallback | Do not auto-resume; expose terminal outcome accurately |
| Process restart | Locked/private; persistent consume retained | No saved viewer/player/consent; online/Nearby explicit again |

Twenty-case acceptance references are in
[the technical matrix](validation/2026-09-29-clipboard-and-acceptance-matrix.md).
Case16's final optimized **combined graphical app** belongs to Claude's later
integration. Technical R8 decoding/playback is complementary evidence, not a
replacement. Physical constraints do not count as UI or API implementation gaps.
