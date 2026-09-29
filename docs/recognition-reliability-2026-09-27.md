# Recognition reliability follow-up — 2026-09-27

## Confirmed defects and changes

- `RecognitionViewModel` discarded an NNFP failure received during Recording, and an
  immediate On Demand failure received before Recording. The collector then cancelled
  itself, leaving no remaining callback or timeout to update the displayed state.
  All terminal results now use a transition from the actual active stage.
- NowPlaying replays Recognising when a callback joins a running recognition. AMM
  previously cancelled this collector while still in StartRecognising. A direct
  loading-to-recognising transition now supports this case. Repeated progress callbacks
  no longer cancel the On Demand collector; stale Recording cannot regress Recognising.
- MotionLayout transition waits used non-cancellable `suspendCoroutine`, which can
  hold up `collectLatest` when a transition is interrupted. Waits are now cancellable,
  return immediately for a missing transition or an already completed transition,
  and flow listeners remove their own registrations instead of passing null.
- Recognition's start/total watchdog jobs are cancelled on cleanup and early service
  failures. Closing a callbackFlow channel alone does not cancel its child jobs.
  Existing 2.5-second start and 120-second total limits are unchanged.

The installed pre-change AMM APK matched the local debug APK byte-for-byte. A control
recognition completed normally, so the earlier hang was intermittent. These defects
are established from code and regression tests, but no trace proves which one caused
that particular earlier hang.

## Paired NowPlaying fix

See `stivy73/NowPlaying`, branch `android-modernization`,
`docs/recognition-reliability-2026-09-27.md`. It corrects skipped-recognition callback
filtering, reports the supported base APK's no-music exit, and makes callback iteration
safe against registration/removal from Binder threads. No audio thresholds changed.

## Validation

Commands from this repository:

```sh
bash gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --console=plain --quiet
adb -s 3B162T00UET00000 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 3B162T00UET00000 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 3B162T00UET00000 shell am instrument -w -r -e class com.kieronquinn.app.ambientmusicmod.RecognitionCompletionTest com.kieronquinn.app.ambientmusicmod.test/androidx.test.runner.AndroidJUnitRunner
```

- Unit tests: 7 passed (4 result routes, 3 existing accessibility tests).
- Device tests cover skipped recording, immediate On Demand failure, TIMEOUT delivery,
  joining an already recognising service, and repeated On Demand progress. They inject
  callback sequences into the real ViewModel; they do not prove Google's live backend
  or the elapsed 120-second watchdog interval.
- Real OPPO CPH2765 / Android 16 recognition reached No Track Matched without dismissal.
  No successful song identification is claimed from a no-music test.
- Debug/release APKs: `app/build/outputs/apk/{debug,release}/app-{debug,release}.apk`.
- Both applications use the existing personal development signing certificate.
  Updates use `install -r`, retaining application data.
- Existing build warnings: SDK XML version mismatch and unrecognised annotation
  processor options. No build/test failures in this follow-up.

## Native crash limitation

Earlier native crashes were in NowPlaying's Pine/ART bridge, not the AMM process.
The previous removal of obsolete Android-11-and-earlier hooks remains in place.
The latest crash observed before this follow-up was at 19:23, before that mitigation.
No engine replacement is included: LSPlant initialization had failed on this device,
causing fallback to Pine. A short smoke test cannot establish long-term native safety.
The upstream Android 16 report is related, but not proof of the same root cause:
https://github.com/LSPosed/LSPlant/issues/179

## Focused manual checks

1. Recognise without music: reach No Track Matched or a timeout, then Retry works.
2. Recognise while background recognition is running: eventually show a result/error.
3. Close the dialog during recording and reopen: no frozen animation or stale result.
4. Play a known song on another device: verify recognised title/history separately.
5. On Demand: either a result or an explicit error; this patch does not replace Google APIs.
6. Use normally with screen locked/unlocked; collect `adb logcat -b crash -d` if it closes,
   and `adb logcat -d -s RecognitionRepository NowPlayingHooks LSPlant` if it hangs.

These checks do not replace the broader accessibility, Shizuku, reboot, overlay and
widget regression checklist from the modernization work.

## Now Playing availability on HyperOS — 2026-09-29

On a Xiaomi HyperOS device, AMM showed "Now Playing Error" even though the
installed NowPlaying advertised a compatible API version and AMM held its signature
permission. A device test confirmed that its settings-provider query returned null.
Logcat then showed HyperOS `WakePathChecker` rejecting background startup of both
NowPlaying's settings provider and recognition service. This is an OEM startup policy,
not evidence of an APK version mismatch. Autostart for NowPlaying was observed enabled
later; the same provider query and service binding then passed, and AMM loaded the
Now Playing page. The transition itself was not instrumented, so the exact setting
change or event that lifted the restriction is not established.

AMM now reports an unavailable settings connection rather than asserting a version
mismatch, with a HyperOS/MIUI Autostart check as a device-specific suggestion.
Separately, a captured AMM crash showed `IllegalArgumentException: Service not
registered` when a rejected `bindService` attempt timed out and cancellation called
`unbindService` on that unregistered connection. Binding now tracks registration;
an unsuccessful bind is never unbound, and cancellation during an in-flight bind
unbinds exactly once if registration subsequently succeeds. Instrumentation tests
exercise both races, plus provider and real-service connectivity on the device.

The current Now Playing page no longer shows the connection error, but displays
"Downloading song database". Database completion, recognition after reboot, and
cold-start behavior under HyperOS still require separate device verification.
