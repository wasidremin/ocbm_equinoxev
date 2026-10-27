# Android / AAOS host

> **STATUS:** CURRENT · single owner for this topic. Consolidated 2026-08-31 from pre-consolidation docs 59; the originals are in git history and in the 2026-08-31 backup. Correct this file in place — do not add a sibling.

The Android projection app and the AAOS integration points.

## GM app top bar — vehicle failure diagnosed, revised emulator verification (2026-10-03)

The 2026-10-03 upload (sessions `20261003-124244` and `20261003-124326`)
confirms the adapter settle fix: rejected length 40113 recovered to 945 within the
same request. First video was 14.468 s (3.242 s USB permission), then 10.440 s;
the longer seam rendered 4,673 frames with zero dropped AUs. These logs do not
prove binary MD5 provenance or every adapter feature, but show the new settle
code executing and successful projection.

The top-bar failure was geometric: live decor was **2629×967**, while metrics
reported 2914×1134. Subtracting a hardcoded 960 produced a **7 px bar**; its icons
and text scaled down with it. Do not use that layout-time "freed space" scheme.
The revised adapter path reserves an even ~8.3% strip BEFORE SUBSCRIBE using the
laid-out window. At 2629×967 it reserves 80 px and uses an 886 px video height;
layout, advertisement and touch share the negotiated snapshot. Wired static
advertisement is unchanged. Geometry diagnostics log changes rather than every
layout. Preview is hardware-free and sends no input commands.

The sidebar now has five controls: Home, Settings, Siri, Play/Pause, and one
Answer/End button. Rail width is 128 dp, circles up to 96 dp (adapted to available
height). Reset, Previous, Next and the separate Back/phone-home buttons are gone.
The phone button consumes existing per-call metadata: ringing takes Answer
priority, active/held/outgoing calls select End, no call disables it; session
teardown clears state. Adapter config explicitly advertises telephony HID.
Actual answer/hang-up behavior still requires the owner's phone and dongle.

Verification: API-32 APK compilation; Tier-0 receiver/pairing/protocol checks;
154 hardware-free app checks passed. All ten scale/sidebar combinations passed
actual preview bounds at the **2629×967 vehicle-sized emulator window**. This
proves layout, not GM compositor behavior or live phone commands.
Published by owner request to `automotive:internal`, COMPLETED, versionCode
**91**, versionName **4.0+topbar-sidebar-fix**. Upload and Play edit commit succeeded.

### Follow-up: reclaim the former GM status-bar region (source-only, 2026-10-03)

Version 91 made the custom bar visible, but the owner reports it occupies video
space rather than the old GM bar's empty region. Latest upload at 13:34 UTC,
session `20261003-133233`, confirms version `4.0+topbar-sidebar-fix`: decor stays
2629×967 versus 2914×1134 metrics, with an 80 px strip and 2468×886 video.
Those numbers alone do not establish the window's screen origin or the cause.

The sister app's `applyDisplayMode` sets `layoutInDisplayCutoutMode=ALWAYS` for
hidden-bar modes BEFORE measuring. GM CCPA had left that at DEFAULT and only
hidden the bars/set decor-fits false. The revised shared `DisplayPrefs.applyWindow`
applies the cutout policy to BOTH launcher (pre-SUBSCRIBE measurement) and picture
activity; it retains the existing edge-to-edge layout flags. Hidden status-bar
height now comes from stable status/cutout top insets, with the fraction only a
fallback when the platform provides neither. No metrics-derived expansion,
negative offsets, privileged overlay or runtime renegotiation is used.

New diagnostics include root screen origin, cutout policy/description and stable
status insets. **Cutout avoidance is a candidate vehicle cause, not established
by the uploaded logs.** On the AAOS emulator with a synthetic top/right cutout,
DEFAULT produces a 949×678 window at (0,90); ALWAYS exposes 1024×768 at (0,0),
with the custom strip using the measured 90 px top region.

Re-verified 2026-10-03 with a synthetic **top+left** cutout (safe insets 90/90):
FULLSCREEN and STATUS_HIDDEN both latch `strip=(90,cutout)`, put the bar at y=0,
and lay the video at `[0,90,1024,672]` (bar 90, video 582, bottom inset 96 above
the nav bar); all four screen-mode previews report `preview layout PASS`.
Four surface-corner taps normalize to `(0.003,0.009)`, `(0.996,0.009)`,
`(0.003,0.993)`, `(0.996,0.993)` within the video rectangle and send no command;
bar taps emit button callbacks only. A live display resize logs one
`topbar geometry mismatch root=1180x900 latched=1024x768` and centers the block;
a transient status bar logs `GM status bar reappeared — hiding custom bar without
moving video` and leaves the latched rectangle unchanged, then restores the bar.
Overlay disabled and resolution restored to 1024×768 (density 160) afterwards.
All four screen-mode preview checks pass; 155 hardware-free self-tests pass after
preventing live launcher measurements from racing the synthetic geometry tests.
API-32 APK build, diff whitespace check and documentation budget pass.

Published by owner request on 2026-10-03 to `automotive:internal`, COMPLETED,
versionCode **92**, versionName **4.0+topbar-space**; upload and Play edit commit
succeeded (the AAB was re-checked in place: its dex carries the new
`topbar geometry mismatch` / `display_geometry` symbols). Actual GM-region
recovery on the vehicle remains unverified — the emulator proves layout, not GM
compositor behavior.

### Follow-up: non-rectangular panel fitment (2026-10-18, vehicle-evidence)

The 2026-10-18 drive (v203 `4.0+insights-np-logs`, FULLSCREEN, custom bar on) settled what
the emulator could not: the 17.7" panel is not a rectangle. The window reports 2914×1134
with cutout bounding rects `Rect(0,0-2629,167)` + `Rect(2629,0-2932,1134)` — safe insets
0/167/285/0, stableStatus 166. The top edge slopes down left-to-right and the right side
is cut away, so the visible glass is effectively 2629×967 at offset (0,167).

The full-window capture proved the failure: the bar's right cluster is drawn right-aligned
to the 2914 window edge, putting the clock + outside temperature (the rightmost items)
at x 2700–2850 — on missing glass. The owner saw the left icons but no temp/time. Fix:
the bar background still spans the full window, but the cluster now starts left by the live
`displayCutout.safeInsetRight` (`GmTopBarView.rightInsetPx`, re-applied every layout from
`CarPlayActivity.placeTopBar`), and the right-cluster text/icons run larger (0.33–0.34h vs
0.30h) since the slope leaves less usable height at the right end. Temperature and clock
needed no new reads — VHAL temp (44° on the drive) and the system 12/24h clock were already
wired; they were clipped, not missing.

Settings ceilings raised to match the measured 285 px cutout: the "Right margin" stops go
(0,24,48,72,96) → (0,48,96,160,240,320) with nearest-stop mapping for saved 24/72 values,
and the "Right inset (dp)" slider caps at 320 (`DisplayPrefs.RIGHT_DP_MAX`) instead of 160.
Below these ceilings CarPlay's own right-edge UI and the video surface both stayed under
the cutout. Verified hardware-free: 7 new `TopBarFitTest` checks (cluster edge math,
nearest-stop mapping incl. retired values, ceilings ≥ 285) plus the full
`:app:testPlayReleaseUnitTest` suite green. On-car confirmation of the new cluster position
is pending the next drive.

### Earlier top-bar release (2026-10-02)

`wasidremin.gmccpa` has an opt-in Display → Top bar setting (default off,
next connection). The supplied `GmTopBarView.kt` drawing class is unchanged.
Only the adapter's app-pushed YAML path reserves the strip: measured panel height
× `HEIGHT_FRACTION` rounded even, then the remaining video height is UI-scaled.
At 2400×960 this is an 80 px strip, an 880 px video view, and 704 advertised pixels
at 125%. Wired static advertisement reserves nothing. Touch uses the video view's
own origin, not the top strip. Active negotiated geometry stays fixed until reconnect.

Before SUBSCRIBE, the actual bound activity must report zero status-bar insets
following full-screen application. Unknown/visible/timed-out insets reserve nothing.
The CarPlay window checks again before showing custom chrome; if a reserved strip
cannot safely be shown there, it returns to settings rather than draw duplicate bars
or silently alter negotiated geometry. A failed guard is not a perpetual
“next connection” setting change.

Clock uses the system 12/24-hour format. Profile initial comes from the saved phone
name (blank if absent). Outside temperature is read through CarPropertyManager with
vehicle display units; the read-only, `normal` `READ_CAR_DISPLAY_UNITS` permission is
declared, not climate-control permission. Denied/unavailable/invalid values hide the
field. Cellular signal remains omitted without requesting phone-state permission.
All seven button callbacks are wired: Home uses the existing navigation command;
Maps/Phone/Music requests log pending box support and do not invent opcodes.

Verification: API-32 compile, signed x86_64 APK, and **99/99 hardware-free app
self-tests passed** initially on the AAOS 14 emulator, user 10; the expanded
scale/sidebar/safe-area and screen-mode matrix now passes **153/153**. Real emulator window 1024×768
reported zero status-bar insets; preview reserved 64 px, view 1024×704, advertisement
820×564 at 125%. All seven taps emitted the expected callbacks; outside temperature
read **77°** using the emulator's vehicle units after the permission fix. Off preview
reserved zero. At an emulator display override of **2400×960**, all ten UI-scale /
sidebar combinations passed actual view-bound assertions: video `[0,80,2400,960]`
without rail and `[108,80,2400,960]` with the 160-dpi rail. Full-panel 125% advertises
1920×704; the rail version advertises 1834×704. Top-edge and bottom-right taps normalized
to approximately `(0.333,0.001)` and `(0.999,0.998)`; bar taps only emitted button
callbacks. Repeated Back/relaunch and Home/new-preview passed; that latter path exposed
old-task geometry reuse, fixed by closing only the previous preview before relaunch.
No CarPlaySessionService was present in preview. The small size-change trial did not
prove Activity recreation, and no leak audit is claimed. Emulator resolution restored
to 1024×768, top bar/sidebar off, scale 125%, safe-right 48 px after testing.
Preview creates no session, audio, renderer, or USB claim, and refuses a live session. No real phone projection, GM-window hideability, or visual photo
comparison is claimed. **Published by owner request on 2026-10-02** to
`automotive:internal`, COMPLETED, versionCode **86**, versionName
`4.0+7532873-topbar-test`; upload and Play edit commit both succeeded.

APK: `host/gm_ccpa/apk/gmccpa-debug-7532873-dirty.apk` (versionCode 85).
Hardware-free preview: `adb -s emulator-5554 shell am start --user 10 -n
wasidremin.gmccpa/.MainActivity --es run topbar_preview --ez enabled true`.

## Temporary GM vehicle telemetry capability probe

**Real Equinox EV results: PENDING.** Nothing here establishes that a property or
permission works on the Chevrolet Equinox EV / Bosch VCUNH1 / `burmese_orange`
AAOS 14. Emulator observations are bench evidence only.

### Repository discovery and shipping identity

The current internal-track product is **`host/gm_ccpa/netprobe_app/:app`**, package
**`wasidremin.gmccpa`**, **`playRelease`**, `automotive:internal` / COMPLETED.
The owner approved this location after discovery: `host/CarlinkAndroid/:app` is
`zeno.carlink.ocbm`, sideload-only (`sideloadDebug`/`sideloadRelease`), not the
current Play product. Neither identity, signing configuration nor publishing
contract is changed. The rejected `:projection`/CarProjectionManager route is
not used. Existing launcher, USB attach, OCBM, projection, media and top-bar code
is unchanged apart from the settings launch action.

Shipping build: compile/target SDK 35, min SDK 26; optional manifest
`android.car` library with API-35 compile-only platform stubs, no packaged car
classes. Main manifest owns all components; distribution flavors do not replace
the launcher. `host/CarlinkAndroid` instead uses compile SDK 37, target 36, min 32
and `useLibrary("android.car")`. The GM app uses Views/native
`requestPermissions`, not Compose activity-result launchers. Existing settings
Capture actions export logs through SAF/removable storage/Downloads; telemetry
uses the same local-first SAF/removable-storage approach, separately from logs.
No existing FileProvider is present in the GM shipping app; no backend is added.

This is a **temporary diagnostic exception** to the historical "zero car
permissions" description below, not a privileged integration. The shipping GM
app already had CAR_POWERTRAIN, CAR_EXTERIOR_ENVIRONMENT,
READ_CAR_DISPLAY_UNITS and fine/coarse location. The probe adds only read/test
car declarations: CAR_INFO, CAR_SPEED, CAR_ENERGY, CAR_ENERGY_PORTS, CAR_TIRES,
CAR_DYNAMICS_STATE, READ_CAR_STEERING, CAR_MILEAGE, CAR_DRIVING_STATE,
CAR_IDENTIFICATION (each under `android.car.permission`). No HVAC or vehicle
control permission is requested, and no vehicle property setter exists.
A separate dataSync FGS permission/type covers local diagnostic recording only.

API-35 public `Car` constants contain INFO, SPEED, ENERGY, ENERGY_PORTS,
POWERTRAIN, EXTERIOR_ENVIRONMENT, READ_DISPLAY_UNITS, READ_STEERING_STATE and
IDENTIFICATION permission strings. TIRES, DYNAMICS_STATE, MILEAGE and
DRIVING_STATE are not public `Car` constants in that jar; literal names keep
compilation independent of their existence. All 23 requested headline property
constants are present in that compile jar. PermissionManager on the vehicle,
not compile availability, establishes platform existence and protection level.

### Run on the Equinox EV (no vehicle ADB)

1. Install **94 / `4.0+vehicle-telemetry`** through the existing Play internal-test
   listing. Published by owner request on 2026-10-09 to `automotive:internal`,
   COMPLETED; upload and Play edit commit succeeded. Version 93 was rejected
   because Play had already used it, so the existing `AAB_VERSION_CODE` override
   was raised to 94 without changing telemetry source. Confirm the installed
   version in the report; future uploads require a code above the Play high-water mark.
2. Park safely. Open **Settings → Logs → Capture → Vehicle Telemetry Probe**. It is
   a separate, non-exported, non-distraction-optimized Activity; it never opens
   automatically during projection/startup. Connection and census run only here.
3. Use **Request eligible runtime permissions once**, then review/refresh the
   census. Only defined, manifest-declared, denied dangerous permissions are
   requested. Fine location is paired with coarse as Android 12+ requires.
   Denials are recorded, never retried automatically. Signature permissions are
   inspected but cannot be acquired by ordinary runtime requests. OS dialog
   presentation is not observable: reports distinguish API request submission
   from an unknown actual displayed dialog. Previously submitted requests persist
   across reopening/restart; system app permissions can be changed manually.
4. First run **Start 60-second capture** parked; it stops automatically.
   For moving observations, start **Drive Capture** while parked, leave the
   detailed screen and drive normally, then return while parked and **Stop capture**.
   No overlays, audio focus or projection listeners are added. Current/changed
   UX restrictions are recorded separately, never labeled exact driving state.
   Unknown/restricted UX disables detailed actions. A manually started dataSync
   foreground service may continue recording while the Activity is hidden.
5. GPS collection is **off by default**. To test independent GPS, explicitly enable
   the raw-location checkbox before capture. It exposes precise coordinates/route
   in JSON/CSV/NDJSON exports; Markdown omits raw location events. Verify provider
   enabled state, permission grants and actual fixes, not just registration.
6. Export while parked. **Export latest report ZIP** opens the system document
   picker; select USB/storage destination and Save. If no picker exists, insert
   a removable USB stick and retry: the fallback writes
   `Android/data/wasidremin.gmccpa/files/vehicle-telemetry-<timestamp>.zip` on it.
   Read that folder on a computer after removing the stick. If neither is
   available, the exact local path is shown with a copy button; no external
   retrieval is claimed in that case. Cancelling the picker retains local data.
   **Clear probe data** confirms and deletes only telemetry files.

### Report interpretation and limits

Local directory: app-specific external `files/telemetry/<timestamp>/`, internal
files fallback if external storage is unavailable. Each capture gets a new
folder. `report.md`, typed `report.json` (including streamed raw live events),
timestamped `events-<timestamp>.csv` and raw `events.ndjson` contain device/build provenance, permissions,
full per-area read census, headline states, subscription attempts/statistics,
exact read exceptions, confirmed facts and inconclusive items. CSV includes
wall/elapsed timestamps, property timestamp, ID, area, status and value.
Unknown vendor properties are retained. Group bits: SYSTEM `0x1`, VENDOR `0x2`,
BACKPORTED `0x3`, OTHER otherwise. HVAC census access is not proof of control.

The public list may be **permission-filtered**: absence means not visible to this
UID, not proof GM's VHAL lacks it. Compile constant, platform permission existence,
manifest declaration, runtime grant, list exposure, initial read, registration
and real updates are separate facts. Null/non-AVAILABLE/status errors and
SecurityException are expected. SUBSCRIBABLE requires an available callback,
not merely accepted registration. Per-area stats include counts/frequency,
first/last, scalar or per-array-element extrema and distinct values. Distinct
sets cap at 10,000 and explicitly become lower bounds. Rates target 1 Hz,
clamped to supported ranges; configs demanding >5 Hz are skipped and recorded.
Every changing read-accessible returned config is considered, including unknown
vendor configs, without guessing or brute-forcing absent IDs.

Drive recording stops manually, at service teardown/disconnection/failure, OS
FGS timeout, or a protective 50 MiB raw-event / 100 MiB-free-space threshold;
resource/OS termination is not a completed drive test. Property and GPS listeners
are removed on stop; UX listener is removed at diagnostic service teardown.
Closing an active capture's screen deliberately leaves the started service alive;
closing an idle screen destroys its bound service. Process kill may leave flushed
raw files but an incomplete summary; no restart/resume is automatic.

Speed is m/s, range m, odometer km, battery level Wh (not percent), charge rate
mW, temperature Celsius, tire pressure kPa, dimensions mm. WHEEL_TICK's Android
contract is cumulative signed counts `[reset, FL, FR, RR, RL]`; config `[wheel mask,
micrometers/tick FL, FR, RR, RL]`. Changed reset counts invalidate comparisons,
and user-start reset is specified, not vehicle-proven. Observe forward/reverse,
restarts, config scales and GPS accuracy before preferring wheel distance,
speed integration or GPS. No automated distance-source recommendation is made
without a physical-vehicle report and known moving/stopped intervals.

### Bench validation (not Equinox EV evidence)

The signed `playRelease` 93 / `4.0+vehicle-telemetry` AAB was bench-tested with
 the existing upload key; the same source was rebuilt and published as **94**
 after Play rejected the already-used code 93. Eleven telemetry JVM tests pass;
existing on-emulator self-tests pass 155/155; Tier-0 receiver/pairing/JNI,
docs and protocol checks pass. Normal launcher does not create ProbeService.
Runtime location/car denials cause no crash or automatic re-prompt. AAOS 14
bench capture verifies automatic 60-second stop, background drive recording,
manual parked stop, and UX callbacks transitioning false → restricted → false.
The final-source document-picker ZIP was retrieved and parsed: typed JSON,
23 headlines, 15 permission entries, timestamped CSV matching raw-event count,
and exact `playRelease` provenance. No physical-vehicle claims follow from it.

Full `lintPlayRelease` **fails** with 29 existing errors in unchanged
CarPlayActivity/MainActivity inset APIs versus minSdk 26 and USB receiver flags.
The probe's GPS MissingPermission error was repaired with explicit checking and
SecurityException handling; final lint has no telemetry errors. Existing lint
checks were not disabled or baselined. Temporary probe warnings include literal
English UI text, resource-name lookup (needed by the existing Gradle-free build)
and conservative usable-space checking. Real-car permission grants, moving
speed/wheel behavior, GPS fixes, removable-stick fallback, missing-car-library
startup and OS timeout execution still require separate verification.

Removal: delete the `telemetry/` production/test packages, VEHICLE_PROBE enum/row
and launcher case, two manifest components, ten temporary car declarations and
FOREGROUND_SERVICE_DATA_SYNC, plus the Gradle telemetry variant resource. **Retain** pre-existing powertrain/environment/units
and fine/coarse location declarations. Remove test dependencies only if no other
tests use them; update this section with collected evidence, not a dated sibling.

## The Android app is a THIRD-PARTY app, by decision (verified 2026-09-18)

`host/CarlinkAndroid` is two Gradle modules, and today's session settled which one is the AAOS
integration route:

- **`:app`** — applicationId `zeno.carlink.ocbm`. Owns the USB/OCBM transport to the CCPA adapter
  (`ocbm/UsbBulkTransport.kt`, `ocbm/OcbmClient.kt`). **This is the product for adapter-bridged
  CarPlay on AAOS**, and integrates as an ORDINARY third-party app — it declares zero car
  permissions.
- **`:projection`** (`com.carlink.projection`) — consumes a loopback TCP seam
  (`SeamListener.kt:78`, a `ServerSocket` on `SeamContract.LOOPBACK`), not the adapter, and its AAOS
  layer is built on `android.car.CarProjectionManager`. **That route is now rejected** — see below —
  so `:projection`'s AAOS layer does not have a path to ship as-is.
- `minSdk = 32`. The real target is a GM gminfo head unit on Android 12L; the AAOS emulator (API 35)
  used for the verification below is a bench, not the target.

**Verified on a running AAOS 15 image with `pm list permissions -f`:** `android.car.permission.
CAR_PROJECTION`, `ACCESS_CAR_PROJECTION_STATUS`, `CAR_NAVIGATION_MANAGER`,
`CAR_UX_RESTRICTIONS_CONFIGURATION`, and `CAR_DRIVING_STATE` are all `signature|privileged` —
unobtainable by a third-party app on a locked unit (the same `/system` write / signature barrier
recorded below for `MANAGE_USB`). `CAR_INFO` is the one exception, `normal`. Separately,
`CarUxRestrictionsManager` listening and `CarAppFocusManager` focus claims need NO permission at
all. The public SDK jar (`~/Library/Android/sdk/platforms/android-35/optional/android.car.jar`)
exposes 55 classes and contains neither `CarProjectionManager` nor `ClusterHomeManager` — both are
`@SystemApi`, which is why `car-system-stubs/` exists as a compile-only shim for `:projection` and
was never a route to a runtime grant.

**Consequence: `android.car.CarProjectionManager` / `ProjectionStatus` is REJECTED as the
integration route for `:app`.** The 3P substitutes actually shipped, all hardware- or
box-verified (see `host/CarlinkAndroid/OCBMANDROID.md` "3P native shell" for the wire-level detail):
an active `MediaSession` (drives the AAOS media card, cluster now-playing, and steering-wheel media
keys) in place of `updateProjectionStatus`; `Notification.CallStyle` for calls; `CarAppFocusManager`
for nav focus; `CarUxRestrictionsManager` listening for drive state (already this doc's own
"Drive state" pattern in `OCBMANDROID.md`, of reading a UX-restrictions signal instead of a gated
one). **Cluster turn-by-turn video and `CH_ALT_VIDEO` on a cluster display are out of reach for a
third-party app** — do not re-plan either against `ClusterHomeManager`.

This finding is orthogonal to, and does not upgrade, the USB-handler squat below: squatting as
`android.car.usb.handler` buys silent USB device-permission arbitration only (confirmed narrow in
the "workaround" section), never a signature|privileged `android.car` grant. The two mechanisms
guard different resources and neither substitutes for the other.

## GM AAOS USB permission handler

<!-- absorbed: ../host/01_ANDROID_AND_AAOS.md -->

### The symptom

On a GM AAOS head unit (2024 Silverado ICE, `gminfo37`, build `W231E-Y181.3.2`, Android 12L / SDK 32)
the USB permission dialog for the OCBM adapter (`0x1314:0x2d00`) **reappears on every connect and after
every power cycle**; the "always allow / remember" checkbox never sticks, and the device is treated as
new each time. This is a **head-unit-side** problem, not an adapter-firmware one — but it gates the
whole seamless-connect experience for any third-party OCBM host app, so it lives here.

Evidence base: two independent static analyses of the Y181 `86331654` partition images — all six USB
framework classes decompiled and confirmed **byte-equivalent to stock AOSP 12** (GM changed no USB
Java). Full write-up and file/line citations are in the resources repo,
`gminfo_resources/analysis/platform_faq.md` §2 (+ the 2026-08-17 corrections). This doc is the
CCPA-project-facing summary and the action item.

### Root cause — a dangling resource, not a missing app

`framework-res.apk` `res/values/strings.xml:497`:

```xml
<string name="config_UsbDeviceConnectionHandling_component">android.car.usb.handler/android.car.usb.handler.UsbHostManagementActivity</string>
```

`UsbHostManager.usbDeviceAdded()` branches on that:

```java
if (getUsbDeviceConnectionHandler() == null) getCurrentUserSettings().deviceAttached(newDevice);          // stock path — consults the "always" map, issues implicit grants
else                                         getCurrentUserSettings().deviceAttachedForFixedHandler(...);  // ALWAYS taken here
```

The component is set but the package `android.car.usb.handler` is **stripped from the image** (verified
four ways incl. a raw dex-descriptor grep across all three partitions). So `deviceAttached()` — the path
that reads the default-app map the "always" checkbox writes and issues the implicit grant — is **never
reached for any USB host device.** `deviceAttachedForFixedHandler` instead throws `NameNotFoundException`
and returns. Separately, the genuinely-persistent store `usb_permissions.xml` (written only by
`setDevicePersistentPermission`) has **zero callers image-wide**, so it is never populated either. The
in-memory grant map is keyed on `/dev/bus/usb/BBB/DDD`, which changes on every enumeration — hence
"prompt every time," including on a hot replug within one boot.

**Precise scope — the dead-end is NARROW (this corrects an earlier overstatement).** It kills only the
*implicit-grant + resolver-launch for an untrusted third-party app, for a host-mode device*. It does NOT
break native projection or storage, because:

- The `UsbDevice` is still created (`mDevices.put`, line 292) and the `USB_DEVICE_ATTACHED` broadcast
  still fires (line 660) before the throw — `getDeviceList()` still returns the device.
- **System / privileged apps skip the resolver:** `hasPermission` line 122 short-circuits
  `uid == 1000 → true`, and any `MANAGE_USB` holder opens devices directly. GM's own
  `com.gm.lcm` / `com.gm.hmi.connection` / `com.gm.hmi.settings`, Google's apps, SystemUI and
  CarSettings all hold `MANAGE_USB`.
- **Wired CarPlay** = `/system/app/GMCarPlaySrc/GMCarPlay.apk` (system app, Cinemo receiver).
  **Wired Android Auto** = AOA **accessory** mode → `accessoryAttached()`, a separate ungated path;
  `/system/app/GMGALSrc/GMGAL.apk` is a system app too.
- **USB storage** never uses `UsbManager` permissions — kernel `usb-storage` → vold →
  StorageManager/DocumentsProvider.

So the only thing that dead-ends is **exactly our case**: a sideloaded third-party app claiming a
host-mode vendor device (`0x1314:0x2d00`) with no kernel driver and no privileged system claimant. It is
also why GM never noticed — everything GM ships is privileged and routes around the broken handler.

**Equinox EV (`burmese_orange`, Android 14 / SDK 34) is not this unit.** Corrected 2026-09-15 from
Play-app logs (`wasidremin.gmccpa` v=4.0+usb-inventory, user 12): `FEATURE_USB_HOST=true` but
`UsbManager.getDeviceList()` contains **only** the SoC xHCI roots and a Microchip USB24915P /
USB249XX NCM/IAP bridge (`0424:4915`, `0424:49a0`, `0424:4911`). `0x1314:0x2d00` never appears, so
`USB_DEVICE_ATTACHED` never fires and there is no Allow dialog. The Silverado claim that
`mDevices.put` still exposes the adapter to third-party apps **does not hold here** — that data port
is GM’s iPhone CarPlay jack, not a generic USB host. Full account:
`host/gm_ccpa/docs/14_LESSONS_LEARNED.md`. Do not apply the squat, the “always allow” persistence
work, or a new APK to this vehicle until `deviceList` actually lists `1314:2d00`.

**That "never appears / no Allow dialog" sentence was the pre-composite state and is now superseded.**
With the `accessory,mass_storage` composite (uDisk, §E1 / `14_LESSONS_LEARNED.md` §5), `1314:2d00`
*does* enumerate on the Equinox, `USB_DEVICE_ATTACHED` *does* reach `UsbAttachActivity`, and the
app's own `requestPermission()` *does* raise an Allow dialog. Device-confirmed in the 2026-10-22 car
capture: `deviceList` lists `0x1314:0x2d06 Auto Box (announce)` → `0x2d00`, `[usbdiag] ATTACH is the
OCBM adapter, permission held=false`, then `usb permission granted +38912ms after attach (via
broadcast)` — the 39 s is the driver physically tapping, not a system delay.

### VERDICT (2026-10-23): the per-drive Allow popup CANNOT be made persistent by a Play app on a stock Equinox EV

This is the definitive answer to the recurring "why must I tap Allow every startup" question, so it
is not re-investigated. The popup is a **GM VCU platform defect**, not an app fault. Android persists
a USB device grant by exactly two mechanisms, and VCUUM-274.4 has broken both:

1. **"Always" → default-handler map.** The implicit-grant path (`UsbHostManager.usbDeviceAdded` →
   `deviceAttached()` → `grantDevicePermission`) only runs when
   `config_UsbDeviceConnectionHandling_component` resolves. On the Equinox the pointed-to package
   `android.car.usb.handler` is **absent** (log: `Unknown package: android.car.usb.handler`), so the
   branch that reads the "Always" choice is never reached. There is nothing for an "Always" checkbox
   to write to.
2. **`setDevicePersistentPermission` → `usb_permissions.xml`.** That store has **zero callers
   image-wide**, and a `/data` app writing it directly is a SELinux `neverallow`
   (`plat_sepolicy.cil:6707`).

So the dialog the driver taps is SystemUI's **one-shot** permission prompt. Its grant is keyed to the
device's `/dev/bus/usb/BBB/DDD` node, which is re-assigned on every enumeration — new node each boot
→ fresh prompt each boot, by construction.

**Closed, do not retry (all device- or decompile-confirmed):**
- Package-name squat as `android.car.usb.handler` — gives a silent grant on the *Silverado*, but is
  dead on the EV (handler component absent) and is not Play-shippable (reserved `android.*` id).
- Pre-seeding / writing `usb_permissions.xml` from the app — SELinux `neverallow`.
- `pm grant … MANAGE_USB` over adb — `MANAGE_USB` is `signature|privileged` with no `development`
  flag; `pm grant` returns "not a changeable permission type". No `appops` or `cmd usb` grant verb
  exists either.

**The only path to a true zero-tap grant** is to stop being a `/data` app: a `/system/priv-app`
install holding `MANAGE_USB`, or a platform-signed app. Both require writing the system partition,
which the locked VCU does not permit without an unlocked unit / root route that has not been shown to
exist on this vehicle. Short of that privilege level, **one Allow tap per drive is the floor on a
stock car.**

What the app already does to minimise it (so there is no further in-app win here): on a cold attach it
skips the futile 8 s system-dispatch wait and self-requests immediately; it defers the dialog while
the display is off (warm-up) and re-raises it on the next 250 ms claim-loop poll once the screen wakes
(`UsbBulkTransport.dialogDeferredForDisplay`); and
`MainActivity.maybeRelinkOnResume` re-claims when the app is opened with a failed claim and the
adapter on the bus. The tap is surfaced as early as the platform allows; it cannot be eliminated or
auto-dismissed.

Until 2026-10-27 the "re-raises once the screen wakes" step did not exist. Nothing re-checked the
display after a deferral, so the dialog waited for the claim loop's 30 s re-raise
(`REQUEST_INTERVAL_MS`). The car wakes with the adapter enumerating about 8 s later and the screen still
dark, so every such start lost roughly 30 s. The startup lines (`[startup] startup: permission +N`)
show it: +31.5 s on 2026-10-27 21:25, +38.9 s on 2026-10-22, +34.9 s and +31.8 s on 2026-10-23. Starts
with the screen already lit took 1.7-2.1 s. In the 2026-10-27 start the dialog appeared at +30 s and
was accepted 1.4 s later.

**Log-confirmed unique (POTATO capture, 2026-08-17).** A sweep of ~4.9 GB of real `adb logcat` from this
unit (43 boots, `/Volumes/POTATO/logcat`) found `Default USB handling package (android.car.usb.handler)
not found` **1,176 times and it is the ONLY variant** of the "handling package (X) not found" grant
signature — no sibling handler is broken this way. 148 packages log as "allowlisted but not present," but
that set is inert for squatting (test apps, RRO theme overlays, stripped stock apps — nothing consumes
them via a grant-by-name path); the two config pointers whose targets are also absent
(`config_carrierAppInstallDialogComponent`→`com.android.simappdialog`,
`config_overrideComponentUiPackage`→`com.android.stk`) show **0 live launch/resolve refs** (dead
SIM/carrier flows that never fire on this vehicle). A separate, murkier surface exists — service-bind
failures for GM/Google components (`com.gm.permissionproxy/.PlatformPermissionService`,
`com.gm.vmsplugin/.VMSClusterService`, …) — but those are mostly present-but-failing, not absent, and are
not the same ungated grant primitive. A rigorous per-`config_*`-pointer exploitability sweep (present vs
absent × consumer signature-gate) is in progress to confirm the USB handler is the sole EXPLOITABLE
instance; update this line with its verdict.

### The workaround — "package-name squat" (CONFIRMED by decompile, NOT device-tested)

`deviceAttachedForFixedHandler` (`UsbProfileGroupSettingsManager.java:658`):

```java
Intent intent = createDeviceAttachedIntent(device);
mContext.sendBroadcastAsUser(intent, UserHandle.of(ActivityManager.getCurrentUser()));
try {
    ApplicationInfo appInfo = mPackageManager.getApplicationInfoAsUser(component.getPackageName(), 0, mParentUser /* =10 */);
    mUsbService.getPermissionsForUser(UserHandle.getUserId(appInfo.uid)).grantDevicePermission(device, appInfo.uid);  // ← SILENT, per attach
    Intent activityIntent = new Intent(intent).setComponent(component);
    try { mContext.startActivityAsUser(activityIntent, mParentUser); }
    catch (ActivityNotFoundException e) { /* logged; grant already happened */ }
} catch (PackageManager.NameNotFoundException e2) { /* the current dead-end */ }
```

If a third-party app is installed **under the package name `android.car.usb.handler`, in the foreground
user (user 10)**, the framework resolves *that app* and grants it device permission **silently on every
USB attach** — no dialog, no root, no persistence needed (re-granted each attach, which is behaviourally
"always allow").

**What the name buys, and what it does NOT.** It grants exactly one power: **USB device-permission
arbitration for host-mode devices.** It is not a privilege escalation. A `/data` install is
`untrusted_app` regardless of name — no platform signature, no system UID, no `MANAGE_USB`, no access to
`/data/system/users/*` (that write is a SELinux `neverallow`, `plat_sepolicy.cil:6707`, so the
"pre-seed `usb_permissions.xml`" idea is **refuted** for any third-party app). The tradeoff: the
squatting app becomes the fixed handler for **every** USB device on the vehicle — filter hard on
`0x1314:0x2d00`, ignore everything else, and provide a no-display `UsbHostManagementActivity` that
`finish()`es immediately.

**REFUTED — `adb pm grant MANAGE_USB` cannot upgrade the squat into a third-party arbiter.** The
tempting shortcut ("grant the handler `MANAGE_USB` over adb, then it can `grantPermission(device,
zeno.carlink.ocbm)` for a SEPARATE app, exactly like the real car-usb-handler") does not work on this
image. `MANAGE_USB` is `signature|privileged` with **no `development` flag** (Y181,
`gminfo_resources/analysis/platform_faq.md:157`), and `pm grant` only mutates runtime/`dangerous` perms
or signature perms carrying `development` (that is why `WRITE_SECURE_SETTINGS` is adb-grantable and this
is not). `pm grant android.car.usb.handler android.permission.MANAGE_USB` returns **"not a changeable
permission type"** — the SAME result GM's own research got testing signature-level
`gm.permission.ACCESS_ONSTAR` / `ACCESS_IPC_HUD` (`gminfo_resources/research/security/
SHELL_ACCESS_ESCALATION_Jun2026.md:38`). Even if it were changeable, `privileged` means it is only
grantable on the `/system/priv-app` path, which needs `/system` write (root / verified-boot off) the
locked GM does not give. There is no `appops` for USB management either. So on a `/data` sideload the
squat's grant lands on **its own UID only** — the "system app blesses our separate app" mechanism
requires a PRIV-APP install (fine on the Pi, unavailable on the locked truck). This is what forces
integration option (A) below: the claiming app must itself carry the `android.car.usb.handler` name.

**Verified installability (this build):** `ParsingPackageUtils.validateName` has no `android.*` prefix
reservation; PMS reserves only the literal `"android"` and `android.uid.*`; the `privapp-permissions` /
`install-in-user-type` config entries for the name do not reject a `/data` install. **Must be user 10,
not user 0** — a user-0 install resolves in the wrong `UsbUserPermissionManager` and does nothing; the
`UserHandle{0}`/`UserHandle{10}` log pair is the "app absent in that user" signature.

**Install route caveats (unverified on this build):** `adb install` accepts the name, but getting adb on
the locked unit is its own problem (ADB is OTG-only on the center-console Type-C, CAN-gated). A 3P
installer / AltStore-style route calls the same `PackageInstaller` API — the name is not the blocker,
the unknown-sources / `REQUEST_INSTALL_PACKAGES` policy is, and that is untested. Not Play-shippable
(reserved `android.*` namespace). A future GM OTA shipping a real `CarUsbHandler.apk` would collide with
the `/data` package.

### PENDING TASK — build + on-vehicle test of a handler-squat proof app

**Goal:** confirm on the actual head unit that the silent grant fires, before committing a real app to
the name.

> **STEP 1 DONE (2026-08-17): the proof app is built.** Module `host/CarlinkAndroid/usbhandler`
> (`:usbhandler` in the Gradle build), package `android.car.usb.handler`, `assembleDebug` green. It is a
> SEPARATE module so the daily-driver `:app` (`zeno.carlink.ocbm`) is untouched. The merged manifest was
> verified to emit package `android.car.usb.handler` + activity
> `android.car.usb.handler.UsbHostManagementActivity` — byte-identical to GM's
> `config_UsbDeviceConnectionHandling_component` string. Structure mirrors AOSP
> `packages/services/Car/car-usb-handler` (same package + `.UsbHostManagementActivity`, the
> `USB_DEVICE_ATTACHED` intent-filter + `usb_device_filter` meta-data); two deliberate divergences:
> **NoDisplay** instead of the Dialog picker, and **no `MANAGE_USB`** (signature|privileged; a `/data`
> install can never hold it — SELinux — and does not need it: the framework grants *to* us). The device
> filter is a catch-all `<usb-device/>` on purpose — the fixed handler sees every device, so this is also
> how an unknown adapter (a C2Air) gets its VID/PID logged the first time it is plugged in;
> `UsbGrantProbe` does the CCPA-vs-other classification in code and only OPENS the CCPA-OCBM
> (`0x1314:0x2d00`), releasing and closing immediately so it never blocks the real host app's claim.
> **STEPS 2–4 DONE (2026-08-17, on-vehicle) — PASS.** Log `~/Downloads/log.txt` (2024 Silverado
> `gminfo37` Y181), one boot, A/B in ~2 min:
> - **Squat present, user 10:** two CCPA-OCBM attaches (`/dev/bus/usb/001/009`, then `…/010` on replug),
>   framework launched `android.car.usb.handler/.UsbHostManagementActivity` **from uid 1000** (the
>   fixed-handler path), and both times `hasPermission=TRUE … no dialog`, `open OK: claimIf0=true`. The
>   `Default USB handling package (android.car.usb.handler) not found` line stops once we are present.
> - **Control (squat uninstalled, OCBM app alone):** on the next attach the OCBM app
>   (`zeno.carlink.ocbm`) triggered `com.android.systemui/.usb.UsbPermissionActivity` — the ordinary
>   prompt reappears. Same unit, same adapter, ~1 min later. This is the causal proof: the squat, and
>   only the squat, removes the prompt.
> - **Endpoints, measured live:** interface 0 class `0xFF`, **bulk IN `0x81` / OUT `0x01`**, mps 512 —
>   read identically by the proof app AND the OCBM app's own transport. This differs from docs/carplay/00_ARCHITECTURE.md's
>   `0x83`/`0x02`; both host apps enumerate endpoints dynamically so it is cosmetic, but docs/carplay/00_ARCHITECTURE.md is
>   stale for this unit (corrected there).
> Build/install/watch harness: `usbhandler/test_squat.sh` (installs to **user 10**, tails the probe
> lines + the framework's dead-end line).

1. **Minimal proof APK** — nothing but:
   - `applicationId = "android.car.usb.handler"`, installed into **user 10**.
   - `<activity android:name="android.car.usb.handler.UsbHostManagementActivity">` with a no-display
     theme (`@android:style/Theme.NoDisplay`) that `finish()`es in `onCreate`.
   - a `USB_DEVICE_ATTACHED` receiver (+ `res/xml/device_filter.xml` on `0x1314:0x2d00`) that logs the
     device and calls `UsbManager.hasPermission()` / `openDevice()` **without** calling
     `requestPermission()` — the whole point is that permission is already held.
2. **Instrument the grant.** On attach, log `hasPermission(device)` immediately; expect `true` with **no
   dialog**. Cross-check `adb shell dumpsys usb` (if adb is reachable) — `default_usb_host_connection_handler`
   and the in-memory permission map. Watch for the `NameNotFoundException` line to **disappear** once the
   app is present in user 10.
3. **Install-route reality check.** Determine what actually installs on this unit: `adb install` (if adb
   is obtainable), a 3P installer, or AltStore-equivalent — and whether user-10 targeting is possible via
   `pm install --user 10`. This is the real risk, not the framework behaviour.
4. **Coexistence + collision checks.** Confirm the squat app does not break native CarPlay/AA/storage
   (it shouldn't — those route around the handler), and note the OTA-collision hazard in the app's own
   docs.
5. **Decide integration — and mind the per-UID grant.** The grant is
   `grantDevicePermission(device, appInfo.uid)` where `appInfo` is **the `android.car.usb.handler`
   package** (§ "The workaround", `:88`). USB device-permission is **per-UID**, so the grant helps
   *only the app that IS `android.car.usb.handler`* — a separate claiming app (different package →
   different UID) still gets the dialog. There is no `/data`-legal way to re-grant to another package
   (`UsbManager.grantPermission` is signature-gated; `sharedUserId` is deprecated and cannot pair a
   fresh `/data` app with an arbitrary sibling). So "our apps claim without a prompt" resolves ONE of
   two ways, and this is the real integration fork:
     - **(A) Unified — recommended.** The app that opens the CCPA-OCBM over USB IS
       `android.car.usb.handler`. For the softAP/wireless CarPlay path that is the OCBM host currently
       named `zeno.carlink.ocbm` (`:app`). Adopting the handler identity there is the clean single-UID
       answer; cost is losing the side-by-side-with-`zeno.carlink` A/B property (CLAUDE.md build note)
       and an OTA-collision hazard if GM ever ships a real `CarUsbHandler.apk`. Because it is one app
       serving both adapters, "our apps" (plural) collapses to one — the CCPA-OCBM today, the C2Air-OCBM
       (another session's in-flight conversion) once its VID/PID is known.
     - **(B) Handler + FD hand-off.** Keep a thin `android.car.usb.handler` that holds the grant, opens
       the device, and passes the raw usbfs fd (`UsbDeviceConnection.getFileDescriptor()`, dup'd into a
       `ParcelFileDescriptor`) to the real app over a bound service. Heavier: the receiver must drive the
       pipe via libusb/usbfs on the fd because there is **no public API to rebuild a `UsbDeviceConnection`
       from a bare fd**. Only worth it if the claiming app genuinely cannot adopt the handler name.
   The adapter side needs no change either way — entirely head-unit-app work. Cross-ref
   `../carplay/00_ARCHITECTURE.md` (the `0x2d00` PID is what the `device_filter.xml` must match).

**Adapter-side note:** none of this requires a firmware change. The `0x1314:0x2d00` identity and its
stable placeholder `iSerial` are already correct; iSerial is **not** a lever here (the build's
`DeviceFilter.hashCode()` omits the serial). See `../carplay/00_ARCHITECTURE.md`.
