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
| F01 photo once | RestrictedImages + RestrictedContentService | PNG Android/HTTPS and historical RFCOMM acceptance; final cumulative repeat pending |
| F02 note once | RestrictedAudio + RestrictedPlayback | Native sanitized codec/capture/HTTPS debug-R8 at c808418; RFCOMM PNG/AAC at 126ac2f; cumulative repeat/lifecycle gaps pending |
| F03 video once | None yet | BLOCKING technical gap, not videoconferencing |
| F04 PDF once | RestrictedDocuments + common Session | Static raster copy; c7584ee debug acceptance, R8 and expanded lifecycle/transport pending |
| F05 only in UMBRA | Mode.UMBRA_ONLY | Common motor implemented; format-specific acceptance pending |
| F06 expiry | Descriptor deadline + Session.check | Object vs active-session limits; no background resume |

No title/text/icon/navigation changes are prescribed. Preserve Claude's current
Spanish simplification and C3 design. Do not use placeholder success for missing
video/PDF or a generic share intent to bypass restricted access.
