# Open items and roadmap

> **STATUS:** CURRENT · single owner for this topic. Consolidated 2026-08-31 from pre-consolidation docs OPEN, 06; the originals are in git history and in the 2026-08-31 backup. Re-verified against the tree and the car logs 2026-11-02. Correct this file in place — do not add a sibling.
>
> **CarPlay SDK plan (2026-09-22)** lives in [`08_FUTURE_TASKS.md`](08_FUTURE_TASKS.md) as T6–T11
> (location, EV status, MainBuffered audio, UI context, focus transfer, Enhanced Siri). This file
> stays the defect index.

Every known-open item in one place, plus the ordered roadmap. An item leaves this file only when it is closed with evidence.


## Settled — do not re-raise

- **A bench `wpa_passphrase` in the tree is accepted.** gm_ccpa's head-unit credential (and, until
  the Pi port was removed on 2026-10-27, `pi/evidence/hostapd_5g.conf`) holds deliberate
  placeholders for a bench AP; they protect nothing. Assessed and accepted by the owner 2026-08-31,
  explicitly including the case where the repo gains a remote. No rotation, no history scrub. Three
  separate audits have flagged the key *name* without reading the value — pattern-matching on
  `wpa_passphrase` is not a finding.
- **Assistant-session trailers. Corrected 2026-11-02.** This said the history carries none (true of
  upstream's mirror on 2026-08-31, whose pre-push hook scanned for them). This fork's commits carry
  them by the owner's instruction, the `equinox` remote takes them, and `.git/hooks` here has no
  pre-push scan. Still write neither pattern out literally in a doc, in case a mirror with that hook
  ever carries this tree.

## Open items

<!-- absorbed: ../ops/04_OPEN_ITEMS.md -->

Open work is spread across the docs, most of it inside documents whose own status line reads
`HISTORICAL` or `CURRENT-WITH-CORRECTIONS`. A reader cannot tell what is still outstanding without
reading all of them. **This is that index — one line per item plus a back-pointer. It is not a spec
and not a plan**: the detail, the reasoning and the wire evidence stay where they already are.

**Re-checked 2026-11-26:** the car items against the uploads through that day, the design items
against `main` at `1ff89cc` (each section says what changed); the 2026-09 view-area resize handoff moved
to `docs/carplay/06_AV_PIPELINE.md` §5. **Re-verified 2026-11-02** against `main` at `d4b7bbf` and the car logs through the 2026-10-30
drive. Every `Verified open` command below was re-run; unless an entry says otherwise, it still
returns what its line records. Items found closed that day moved to
[Found closed](#found-closed--documents-still-call-these-open) with their evidence. The 2026-10-27
removals (the macOS app, the Silverado app, Android Auto, the Raspberry Pi/C2Air ports) took
several entries with them; those were dropped or reworded so that every remaining item applies to
this fork, whose only host is the GM app (`host/gm_ccpa`) in a Chevrolet Equinox EV. The first
check was on 2026-08-16; dates in `Verified open` lines are that first check.

Anchors are **symbols and greppable strings, not line numbers** — line-number rot is this corpus's
single largest source of false claims. Grouping is by subsystem.

**Scope note.** Owner-decided WONTFIX items are recorded under
[Closed by decision](#closed-by-decision--do-not-re-plan-these) so they are not re-planned as work.
Superseded *design positions* (box-owned `/info` policy, the `/tmp/carplay_metadata` lever as a
canonical control, box-autonomous page-on-boot) are **not** open items and are not listed;
[../ops/06_CORRECTIONS_LEDGER.md](../ops/06_CORRECTIONS_LEDGER.md) carries those.

---

### GM app and the car (Equinox EV), re-checked 2026-11-26

Car-side items. The owning document holds the detail; this list only says what is open. Play
high-water mark: versionCode 272 (`4.0+sound`, 2026-11-26; `host/gm_ccpa/docs/14_LESSONS_LEARNED.md` §6).
Re-checked 2026-11-26 against the car's uploads through 2026-11-26 00:49 UTC (app 269): each item's
own log line counted; the closed ones are listed at the end of this section with that evidence.

**Waiting for a drive** (in Play 270, published 2026-11-26, unless the item says otherwise)

- **The Wi-Fi link to the car's hotspot (T15): removed 2026-11-25, in Play 270.** A day in the car with
  it on: about 30 s to the picture when it linked, against about 13 s over USB with the tap
  (`08_FUTURE_TASKS.md` T15). The car's adapter is already clean: on the bench (2026-11-26) the app turned
  its join off and cleared its key, its update removed the files, and its `wpa_supplicant` and `wpa_cli`
  were removed by hand. To see in the car: no start waits for the hotspot (`[sup] waited N s for the
  access point` gone) and `MGMT_INFO` carries no `"sta_uplink"`.
- **The boot preload on the car's adapter (T25 step 2a; bench-installed 2026-11-26, its other files in Play 270).**
  The adapter carries `start_main_service.sh`, `radio_hal.sh`, `session_supervisor.sh`, `btd` and
  `iap2d` from the bench. To check in the car: a `[radio-boot] v1 … rc=0` line on every start, with `bt=`
  about 9,000; no start that never reaches the app (a hang shows as the adapter missing until the car
  cuts its power; `/script/.radio_preload.log` keeps the step it stopped in, and two in a row switch the
  preload off); the iPhone sees `CarLink-13d0` every time; the timeline's `page_start` within about 2 s
  of the app's arrival. App 270 carries the same `radio_hal.sh`, supervisor, `btd` and `iap2d` (firmware 9f91fb58dc51), so
  its Settings ▸ Advanced ▸ Adapter firmware leaves them as they are; `start_main_service.sh` is the boot
  path and stays as the bench left it. It also starts the hardware watchdog (2026-11-26,
  `docs/ops/01_RECOVERY.md`): a frozen adapter now reboots itself about a minute later. In the car: the
  `[start_main_service] hardware watchdog on` line at every start; a start that follows a freeze shows as
  a second boot within a drive.
- **After-drive summary (T28, built 2026-11-24, in Play 270).** After a drive of a mile or more,
  shift into Park: the card should show over CarPlay for 30 s, close at a tap or on leaving Park, and
  the log should say `drive summary: <m> m, <Wh> Wh, …` with its numbers matching the drive's record in
  Insights ▸ Trips once it closes (4 min later). A short hop logs `not shown (<m> m)`. The tap has not
  been checked anywhere yet.

- **Wi-Fi only and the redaction fix (2026-11-26, Play 271).** Settings ▸ Advanced ▸ Wi-Fi only
  (`host/gm_ccpa/docs/14_LESSONS_LEARNED.md` §13.5): with it on, on the car's modem the log shows
  `request held: Wi-Fi only` and `background telemetry uploads paused: Wi-Fi only`, Car data used this
  month stops growing, and on home Wi-Fi or the hotspot the queue and any held log upload go out. The
  adapter's `MGMT_INFO` is now wrapped at commas (`logging/LogWrap.kt`): the next upload's `"devices"`
  lines should carry only `<REDACTED:MAC_ADDRESS:…>`.

- **Loudness by speed (T36, 2026-11-26, Play 272).** On by default (Gentle). In the car: on a
  highway stretch the log shows `loudness by speed (gentle): … bass +4.0 dB, treble +2.5 dB`, rising from
  40 km/h and falling as the car slows, and the owner hears the music hold up without the volume knob.
  Listen for distortion on loud tracks at Strong, and watch for `framesDropped`/underrun lines rising (the
  shaping runs on the audio thread).

- **The equalizer (T37, 2026-11-26, Play 272).** Settings ▸ Screen & Sound ▸ Sound: presets any
  time, the bands dragged in Park. In the car: a preset logs `equalizer: <preset> [+x …]` and changes the
  sound; a drag out of Park leaves the bands and says "set in Park"; a drag in Park moves a band and logs
  the Custom curve when the finger lifts. Listen for distortion at large boosts (the pre-gain and soft
  knee should stop clipping).

**Still open**

- **Which iPhone first, by schedule (T35, Play 268): a changed order not yet seen.** The weekday-morning
  side works (2026-11-23: the owner's phone first, answering 0.6 to 0.8 s after `page_start`). Every
  start since logged `phones: list phone 1 first, as the adapter has it` (19 to 2026-11-26), with 0 or 1
  of the 2 schedules covering it, so the app has never sent a changed order and no start has had only
  the wife's phone. A phone has one window, so "weekends and evenings" for the wife's phone is the
  list's top place, not a window of its own; on an evening with only her phone the first page costs the
  3.2 s miss. To see: `the adapter took it` in the app's log and `phone order kept for Bluetooth's next
  start` in the box log.
- **The voice button ends a CarPlay call (263, issue #4): half seen.** A press while a call rang answered
  it (2026-11-21, `while a call rings -> answer sent=true`). Not yet pressed during a live call (`while a
  call is up -> end sent=true`), nor during a waiting call. Owner: `docs/host/01_ANDROID_AND_AAOS.md`
  "The steering-wheel call key".
- **The same host re-attaching re-keys the A/V seams (Play 262): never happened in the car.** No
  `HELLO … reattached=true` in any upload; every re-attach has been a replacement. Benched only
  (`docs/carplay/02_SESSION_LIFECYCLE.md`).
- **Video from the iPhone while parked (T34) and route A: blocked.** The phone keeps video on itself
  ("CarPlay Video Overlay NOT supported on Bonjour discovery based accessories", its log on the bench);
  route A waits on the simplified connection flow (address over iAP2), not yet planned.
  `08_FUTURE_TASKS.md` T34.
- **Log redaction lets a second Bluetooth address through (found 2026-11-12): fixed 2026-11-26, Play
  271.** 30 of the 64 `"devices"` lines from 2026-11-21 to 11-26 carried part of an address: the
  app cut the adapter's `MGMT_INFO` every 110 characters, sometimes inside an address, and the redactor
  masks whole addresses only. Now cut after a comma (`logging/LogWrap.kt`, `LogWrapTest`). Closed by the
  first upload from the next publish.
- **EV Insights server (`host/evinsights`): the worker runs, the real car's trips are not usable
  yet.** Fixed 2026-11-12 (`1fc20f3`): the worker loaded a vehicle's whole history into memory, and
  at 3.3 million samples the kernel killed it on every pass (160 restarts in a day, the database at
  100% CPU). It now streams the history and rebuilds a vehicle at most once an hour
  (`host/evinsights/README.md` "Derivation and evidence rules"). Still open: its first full pass over
  `equinox-ev-real` produced 6,574 trips, none COMPLETE, and 0 total distance, so the web app's
  figures for the car mean nothing yet; not investigated. The car's own drive log
  (`14_LESSONS_LEARNED.md` §13.4) does not depend on it. Also open, waiting on the owner: about
  1,084 emulator reports were imported as the car before the importer learned to skip them
  (2026-11-11); removing their rows has not been approved. The emulator's report files themselves
  were deleted from the receiver with the owner's approval.
- **USB bridge, for no Allow tap: blocked on the stock Equinox (2026-11-12).** The car's
  unknown-sources screen is the same "not supported" stub as the emulator's: every connect since 232
  logs `usb bridge: not installed, unknown-sources screen …Stubs$ManageExternalSourcesActivityStub`,
  so Files cannot install the bridge. It waits on GM shipping a real install screen. A second
  route that installed nothing on the car, the adapter link over the car's hotspot (T15 route B),
  worked in the car and was removed on 2026-11-25 as slower than the tap (the item above). A sideloaded app named
  `android.car.usb.handler` (`host/gm_ccpa/netprobe_app/bridge`) gets the platform's silent grant at
  attach and relays the adapter to the Play app. Two unknowns, both answered only in the car: whether
  the Equinox lets Files install an app (the AAOS 14 emulator does not: its unknown-sources screen is a
  "not supported" stub, and other Equinox EV owners report installs refused as "unknown sources
  blocked" on current software, searched 2026-11-06), and whether the adapter attaches while the
  driver's user is in front. The app
  side (Settings ▸ Advanced ▸ USB bridge, Open Files, the bridge transport) is in 232 (published
  2026-11-06). Owner:
  `08_FUTURE_TASKS.md` T15; `docs/host/01_ANDROID_AND_AAOS.md` "The USB bridge".
- **Vision launcher: misreads, logging-only.** iOS 27's square glyph can read UNKNOWN, so the
  Home button logs UNVERIFIED (re-cut the template from an iOS 27 capture). The VERIFY_FAILED on
  app taps that worked is explained (2026-11-03): on iOS 27 the glyph shows where the toggle leads,
  so an app opened from Home reads as the Dashboard. Grid taps stop using the glyph in 229
  (2026-11-04); a strip tap from such an app can still log VERIFY_FAILED. The moving-map fix from 227 is
  now exercised: under a route on 2026-11-03 22:47:21, Maps from the strip read "using current
  frame" and verified SUCCESS. Owner: `host/gm_ccpa/docs/14_LESSONS_LEARNED.md` §9 item 4.
- **MFi first read returns 40113 instead of 945.** The settle-and-reread mitigation is
  bench-measured (`mfi-i2c-local` `LEN_SETTLE_US`); the cause is unproven
  (`docs/wireless/01_BT_AND_RADIO.md`). Keep mitigation distinct from diagnosis.
- **Exported `--es run` verbs are ungated.** `MainActivity` is exported (it is the launcher) and
  `handleRunExtra` acts on any caller's `run` extra, including `ocbm_forget`, `ocbm_stop`,
  `export_log`, `upload_log` and `usb_announce`. Verified 2026-11-02:
  `grep -n 'getStringExtra("run")' host/gm_ccpa/netprobe_app/app/src/main/java/wasidremin/gmccpa/MainActivity.kt`
  → no caller or debuggable check before `handleRunExtra`.
- **Re-arm dip (~2.3 s):** deferred until btd survives a host change
  (`docs/carplay/02_SESSION_LIFECYCLE.md`). CLEAN bring-up is the present correctness path, not
  evidence that restart latency is closed.
- **`tools/ocbm_push.sh` runs only on macOS.** It uses BSD `stat -f%z` and `md5 -q`, and the owner
  has no Mac. Install with `tools/ocbm_install.sh` meanwhile.
- **Calibration runs `ScreenModel.measureUi` on the UI thread** (0.3 to 1 s of jank, noted on the
  2026-10-28 drive). Not re-checked.

**Closed 2026-11-26: seen working in the car** (owner, 2026-11-26: the built items have been tested in
the car; the uploads through 2026-11-26 00:49 UTC agree, counts below). Reopen any that recurs.

- *EV Insights recorder (Play 269):* the `ev-recorder` status line on every start 2026-11-24 to 11-26,
  `drive card: last mi` on every drive to 2026-11-25, no `recorder thread stuck` and no `recorder fault`.
- *The EV Insights drive log (246):* `drive card: drive log read, 89 drives usable, 9 repeated routes`
  (2026-11-26).
- *Drive card v2, the arrival chip and the first design (247 to 249):* 186 `drive card: last mi` lines
  (2026-11-13 to 11-25), 29 `drive card: arrival` and 14 `drive card: route over` (2026-11-12 to 11-18),
  `drive history: … added` (2026-11-12).
- *The stall sentence (T27, 266):* `startup: stalled after …` on 2026-11-24 and 11-25. (The keeper it was
  paired with went with T15.)
- *Low flash no longer marks the adapter unhealthy (264):* 12 `firmware update confirmed` from 2026-11-15
  to 11-23.
- *The access point's DHCP server and hostapd's lines (262):* `sending ACK` after each of 43
  `AP-STA-CONNECTED` and 10 `AP-STA-DISCONNECTED` with their reasons (2026-11-21 to 11-26); no `still
  there after SIGTERM`.
- *The connect timeline (261):* 37 `[timeline] v1` lines (2026-11-21 to 11-26), the base of T25's work.
- *Data budget (246):* 90 `uploads now go over <network>` lines (2026-11-12 to 11-26).
- *Settings tabs and Capture driver display (248):* 15 `driver display captured 2000x810` (2026-11-12 to
  11-16).
- *CarPlay's map on the driver display (237 to 249):* `opening the map window` 135 times (2026-11-08 to
  11-26), GM's page events, the turn card and lanes (91 `lanes: … highlight=` lines; negative is left).
  Its history moved to `docs/host/02_GM_AAOS_FIELD_REFERENCE.md` §9. Still to build, not open here:
  the status-bar edge and the car's name in Maps (`08_FUTURE_TASKS.md` T21, T22).
- *The enigy ports and the media pane (234, 235, 245) and the lyrics package (T24):* used in the car;
  `lyrics package: up to date (7811 songs)` on each day's first start since 2026-11-17. History moved to
  `docs/host/02_GM_AAOS_FIELD_REFERENCE.md` §10.
- *Which iPhone connects (T12):* the owner reordered the phones from Settings ▸ CarPlay ▸ Phones
  (2026-11-08, 11-15, 11-16, 11-22), and the order is what the adapter pages.
- *CarPlay dead after a walk-away (233):* the walk-away reconnect ran on six days from 2026-11-15 to
  11-25 (`walk-away: driving reconnect — paging confirmed`).
- *HEVC render stalls:* none since 2026-11-13 (227 `render stall` lines from 2026-09-24 to 11-13), when
  `av/StallWatch` stopped timing pauses as stalls.
- *The 2026-11-13 morning drive's findings:* the phone order (T12, T35), the decoder rebuilt after a quiet
  screen (`StallWatch`, above) and track titles in the log (`titleTag`) are done.
- *The adapter updated on 2026-11-13, and T6, T7, T23:* in the car since; EV routing and Apple's turn
  card work (owner, 2026-11-18). Whether Maps uses the car's position is still unknown (T6).
- *The storage-only USB announce (admission path):* `announce configured` on 111 starts from 2026-09-30
  to 11-26.
- *Music muted after a call (228) and after an unanswered ring (229); album art in the cluster with
  full-screen Maps (230, 231); album art missing or late (226, 228, 231, 233); the top-bar Now Playing
  tile (now `nowplaying:` by URL, 234); the CarPlay-screen layout loop (227):* no fault reported since.
- *Calls while the app holds the assistant role:* no echo reported.
- *Slow starts on 2026-11-07, 4½ minutes without CarPlay on 2026-11-04, the 2026-10-28 drive's notes:*
  overtaken by T25's work and the connect timeline; the HELLO time is part of T25.

### Box daemons — `ocbmd` / `carplayd` / `iap2d` / the supervisor

*Re-checked 2026-11-26:* every `Verified open` command from here to "Tests, tooling & install" was re-run
on `main` at `1ff89cc`. Each still returns what its line records, except the car GPS item (moved to
[Found closed](#found-closed--documents-still-call-these-open)) and "One box, one name" (half settled,
updated in place). Where a newer match appeared it was a comment, not a fix (the `UnixListener` and
`vehicleStateProtocol` hits are doc comments; `dropped=` is in the health line, still not in `MGMT_INFO`).

- **`custom_init.sh` still runs `/script/start_bluetooth_wifi.sh`** in `ncm_wifi` mode, once
  `wlan0` appears. This was one of the "Phase 4" code defects that an earlier version of this file
  pointed to but never listed. Of the others it named, the CT_HELLO `out_console` resync and the
  missing `iap_role_switch` install are closed (see Found closed). Verified 2026-11-02:
  `grep -n "start_bluetooth_wifi" ccpa/rootfs/script/custom_init.sh`.

- **No inbound `stopSession` handler.** The box declares a five-value `stopSessionReasons`
  vocabulary and then ignores the command; `disconnectReason` plumbing to the supervisor follows
  from the same gap. Both halves sit behind `CARPLAY_SESSION_MGMT`, so shipping the declaration
  without the handler would be worse than not declaring it.
  Source: `docs/carplay/02_SESSION_LIFECYCLE.md` §10 ("the single highest-value fix"). Verified open 2026-08-16:
  `grep -rn "stopSession" crates ccpa` → three hits, all declaration-side (`info.rs`
  `stopSessionReasons`, an `av.rs` comment); nothing in `session.rs`'s `fn command`.

- **`isRemoteControlOnly`, `sessionUUID`, `sessionCorrelationUUID`, `hijackID` are never read.**
  `isRemoteControlOnly` is a protocol-native holding pattern where ours is improvised, and
  `sessionCorrelationUUID` is confirmed live on the wire.
  Source: `docs/carplay/02_SESSION_LIFECYCLE.md` §10. Verified open 2026-08-16:
  `grep -rn "isRemoteControlOnly\|sessionWillBeHijacked\|sessionCorrelationUUID\|hijackID" crates ccpa host`
  → zero hits; `sessionUUID` appears only in a doc comment.

- **P2 lifecycle: `CT_SESSION_EVENT` was never widened to `[state][reason]`.** Every emitter and
  every consumer (the GM app's `OcbmClient`, `host/ocbm-host`, `ocbm-probe`) still sends and parses
  exactly two bytes, so the box cannot stream real lifecycle state or a failure reason to the app.
  Source: `docs/carplay/02_SESSION_LIFECYCLE.md` §Phasing, [../ops/06_CORRECTIONS_LEDGER.md](../ops/06_CORRECTIONS_LEDGER.md) `R-11-1`. Verified open 2026-08-16:
  `grep -rn "CT_SESSION_EVENT" --include="*.rs" --include="*.swift" --include="*.kt" .` → all
  emitters are `&[p::CT_SESSION_EVENT, sev]`, all consumers gate on `l >= 2`.

- **P2 lifecycle: no single reason-carrying finalize.** `AvSession::teardown` takes only the request
  plist and `reset()` only `&self` — no `OSStatus`-style reason is threaded from clean stop, failure
  or `Drop`, so a failed teardown is indistinguishable from a clean one.
  Source: `docs/carplay/02_SESSION_LIFECYCLE.md` §"Supporting layers", `R-11-1`. Verified open 2026-08-16:
  `grep -n "fn teardown" crates/vendor/receiver/src/session.rs` → no reason argument on either arm.

- **P2 lifecycle: the `ccpad` Rust supervisor daemon does not exist.** Supervision is still the
  hardened shell loop; the strategic endpoint (dependency DAG, real `fork`/`waitpid`, one
  `::respawn:` entry) has no crate and no binary.
  Source: `docs/carplay/02_SESSION_LIFECYCLE.md` §"Formalization & strategic direction", `R-11-1`. Verified open 2026-08-16:
  `grep -rn "ccpad" --include="*.rs" --include="*.sh" --include="*.toml" .` → zero hits.

- **Multi-second blocking I2C still runs with the tunnel's `SESSION` mutex held.** The structural fix
  (compute the action, drop the guard, run the MFi op, re-acquire, re-validate) was never written, so
  a chip stall blocks the RCS reader, the event handler, `POST /command` and teardown.
  Source: `docs/ops/05_AUDITS.md` §8 "Still open". Verified open 2026-08-16:
  `grep -n "SESSION\|execute(" crates/vendor/receiver/src/iap_tunnel.rs` → `execute` is called inside
  the `plock(&SESSION)` guard and calls `mfi_retry("cert", …)` straight into the chip.

- **Artwork duplicate-fragment corruption — the iAP2 link layer still has no duplicate-sequence
  suppression.** An over-length buffer is emitted as a corrupt JPEG and acknowledged `Success`, with
  only an `[art] OVERLONG` log line; the fix was designed and deferred to a Phase 2 that never ran.
  Source: `docs/ops/05_AUDITS.md` §4, `docs/ops/05_AUDITS.md` §"Where the agents corrected the audit" (I2). Verified open
  2026-08-16: `grep -ni "duplicate\|dup_seq\|seen_seq" crates/vendor/iap2-core/src/link.rs` → no
  dedupe; `peer_seq` is only assigned.

- **`CT_INPUT_NACK` was never built.** The box still silently discards `CH_INPUT` while the host is
  GONE, with no instant notification back to the host.
  Source: `docs/ops/05_AUDITS.md` §"Also deferred", `docs/wireless/01_BT_AND_RADIO.md` §3. Verified open 2026-08-16:
  `grep -rn "CT_INPUT_NACK\|INPUT_NACK" crates ccpa host` → zero hits.

- **`av_dropped` / `lo_dropped` are missing from `MGMT_GET_INFO`. Half closed.** By 2026-09-04
  the box's 30 s `[ocbmd] health` line carries `dropped=av:N,lo:N,log:N,input:N`, and it reaches the
  app log (every line of the 2026-10-30 drive read `dropped=av:0,lo:0,log:0,input:0`). Still absent
  from the `MGMT_GET_INFO` JSON the app polls.
  Source: `docs/carplay/02_SESSION_LIFECYCLE.md` §3. Verified 2026-11-02:
  `grep -n "fn health_line\|fn box_info_json" ccpa/ocbmd/src/main.rs`.

- **`wireless_down` orphans the bring-up's forked children.** Both teardown branches `pkill` only
  `btd` and `carplayd` (`rx-connect` was merged into carplayd 2026-09-08); `wlan_on.sh`, `bt_on.sh`
  and `attach_bluetooth.sh` survive. The obvious `pkill` fix is the IW416 wedge the file warns about
  elsewhere, so a lock is probably the right shape.
  Source: the pre-consolidation open-items list §5 (#28). Verified open 2026-11-02:
  `grep -n "pkill" tools/session_supervisor.sh` → 23 hits, none naming `wlan_on.sh`, `bt_on.sh` or
  `attach_bluetooth.sh`.

- **The sub-16-byte opcode-0 decrypt lane never advances its counter.** A `<16` byte VideoFrame body
  passes through as plaintext without incrementing `counter`, so if that lane ever fires the box and
  host nonce counters desync permanently. Only the host half of the fix landed.
  Source: `docs/ops/05_AUDITS.md` §"Where the agents corrected the audit" #3. Verified open 2026-08-16:
  `grep -n "counter += 1" crates/vendor/receiver/src/session.rs` → exactly one occurrence, inside the
  `if body_size >= 16` arm of the `0 =>` opcode match; the `else` arm returns `body.to_vec()` with no
  counter advance.

- **Resource arbitration is a one-shot `takeScreen` at RECORD and is never revisited.** There is no
  untake, no release and no re-negotiation, where both reference implementations model this as a
  stateful two-party negotiation.
  Source: `docs/carplay/05_METADATA_AND_CONTROLS.md` §II.2, `docs/carplay/02_SESSION_LIFECYCLE.md` §Summary item 2. Verified open 2026-08-16:
  `grep -rn "send_take_screen" crates ccpa` → one definition, one call site, no untake emitter.

- **`send_command` does not enforce Apple's `sessionStarted` gate.** Deliberate and documented, but a
  known deviation from `AirPlayReceiverSession.c`'s `require_action_quiet(inSession->sessionStarted, …)`
  that has never been tested either way — and one of the two surviving explanations for the
  `limitedUI` no-op.
  Source: `docs/carplay/03_SDK_GROUND_TRUTH.md` §10 ("untested rather than refuted"). Verified open 2026-08-16:
  `grep -n "session_started" crates/vendor/receiver/src/events.rs` → checked only inside
  `modes_changed_tunnel_nudge()`; `pub fn send_command` has no check.

- **The `carplayd → :9001 → ocbmd` forward path is still local TCP loopback.** The copy elimination
  (unix socket / `splice`) that the 4K@60 track lists as "next" has not started.
  Source: the removed `docs/host/00_MACOS_HOST_APP.md` §"4K@60 optimization track" item 4 (git
  history). Verified open 2026-08-16:
  `grep -n "TcpListener\|UnixListener" ccpa/ocbmd/src/main.rs` → all five A/V seams are
  `TcpListener::bind(("127.0.0.1", port))`; no `UnixListener`, no `splice`.

---

### CarPlay capability surface — the app-pushed config the box does not yet act on

> The single best machine-readable inventory of this workstream is the `EMITTED_BUT_UNREAD` constant
> asserted by `every_emitted_key_is_parsed_or_knowingly_ignored` in
> `crates/vendor/receiver/src/vehicle_config.rs`. It names every key both host apps emit that nothing
> on the box reads. Grep that first.

- **Seven `accessoryConfig` capability keys are still dropped by the box serde gate.**
  `enablesVideoPlayback`, `enablesEnhancedSiri`, `enablesUIContext`, `enablesUISync`,
  `enablesFileTransfer`, `enablesVehicleDataProtocol` and `enablesDCX` reach the box in YAML from
  both host apps and are silently discarded. Adding a field is a one-line formality — it must only
  land in the commit that makes the box act on it.
  Source: `docs/carplay/04_CAPABILITIES_AND_CONFIG.md` TL;DR, [../ops/06_CORRECTIONS_LEDGER.md](../ops/06_CORRECTIONS_LEDGER.md) `R-53-2`. Verified open 2026-08-16:
  `grep -n "serde(default, rename" crates/vendor/receiver/src/vehicle_config.rs` → `AccessoryConfig`
  declares exactly nine keys, and the same seven are listed under `EMITTED_BUT_UNREAD`.

- **`focusTransfer` is advertise-half-armed — no SETUP `enabledFeatures` echo.** The lever and the
  per-view `/info` flag landed, but no SETUP author pushes the `"focusTransfer"` token, so iOS can
  never negotiate it; runtime focus (`accessoryAcquireFocus` / `deviceOfferFocus` /
  `initialFocusOwner`) is entirely absent.
  Source: `docs/carplay/04_CAPABILITIES_AND_CONFIG.md` §1, `R-49-4`. Verified open 2026-08-16:
  `grep -n "feats.push" crates/vendor/receiver/src/session.rs` → eight tokens, none of them
  `focusTransfer`; nor does `host/ocbm-host/src/setup_driver.rs` (the Mac app's
  `VehicleConfig.swift` list went with that app).

- **`uiContext` is completely unimplemented on the box.** No serde field, no lever, no
  `uiContext*URLs` keys in `/info`, no echo, no `changeUIContext` emitter — only an app UI stub.
  Source: `docs/carplay/04_CAPABILITIES_AND_CONFIG.md` §3, `docs/carplay/03_SDK_GROUND_TRUTH.md` §5. Verified open 2026-08-16:
  `grep -rn "uiContext\|changeUIContext" --include="*.rs" crates ccpa` → zero hits outside
  `vehicle_config.rs` test fixtures.

- **The RCS stream-130 client-type gate is still an allowlist of one.** LogTransfer, the two
  VehicleDataProtocol types and a CarPlayClusterControl SETUP are accepted as streams but their
  frames are logged and dropped. Expanding it once unblocks three capabilities below.
  Source: `docs/carplay/04_CAPABILITIES_AND_CONFIG.md` §"Shared infrastructure" 2. Verified open 2026-08-16:
  `grep -n "IAP_CLIENT_TYPE" crates/vendor/receiver/src/session.rs` → one UUID constant is the only
  accept condition, with a doc comment reading "The gate is an allowlist of one".

- **`mainBufferedAudio` Phase B (accept + forward the stream) is unbuilt.** Phase A advertise+echo
  landed and is wired-device-proven; SETUP phase 2 still falls through to the "NOT IMPLEMENTED —
  omitted" arm for MainBuffered/AuxIn/AuxOut, so no buffered audio can arrive. The true
  `mainBufferedInfo` shape and the MainBuffered stream-type number are still unknown, and the
  wireless arm was never captured.
  Source: `docs/carplay/04_CAPABILITIES_AND_CONFIG.md` §5, `docs/ops/04_OPEN_ITEMS.md` step 6. Verified open 2026-08-16:
  `grep -n "Still unimplemented and therefore omitted" -A3 crates/vendor/receiver/src/session.rs`.

- **`vehicleDataProtocol` / VDC route-sharing is entirely unbuilt.** The box advertises neither
  `vehicleStateProtocol` nor `vehicleStateProtocolInfo`, never echoes the token, and no
  VDCSchema-driven characteristic server exists.
  Source: `docs/carplay/04_CAPABILITIES_AND_CONFIG.md` §4 Mechanism A. Verified open 2026-08-16:
  `grep -rn "vehicleStateProtocol" crates ccpa --include="*.rs"` → no hits in box code.

- **`videoPlayback` is inert.** No lever, no `/info` advertisement, no `lunaConfig` or
  `videoPresetSizes`, no echo. **Corrected 2026-10-27:** this said the receiver was blocked on
  Apple granting `com.apple.developer.carplay-video`. That is the iPhone app's entitlement: WWDC26
  session 212 says apps holding it appear on the CarPlay home screen only when the car supports
  video. What blocks the receiver is the uncaptured wire contract. That covers the `/info` shape,
  how the car signals parked or video-unavailable (iOS then falls back to audio-only), and how the
  video is delivered. Whether iOS also checks vehicle certification is unknown.
  Source: `docs/carplay/04_CAPABILITIES_AND_CONFIG.md` §7. Verified open 2026-08-16:
  `grep -rn "videoPlayback\|lunaConfig\|videoPresetSizes" --include="*.rs" crates ccpa` → only
  `vehicle_config.rs` test fixtures.

- **`fileTransfer` is blocked-untestable — even the advertise step is byte-unsafe.** The
  `fileTransferInfo` dict's keys are unknown, as is which transport carries the chunk dict.
  Unrelated to the iAP2 session-2 artwork assembler the box already implements.
  Source: `docs/carplay/04_CAPABILITIES_AND_CONFIG.md` §8. Verified open 2026-08-16:
  `grep -rn "fileTransferInfo" crates ccpa host` → zero hits outside docs.

- **`uiSync` / the CarPlay Cluster Control Channel is blocked, and the cheap UUID-harvest probe has
  not been run.** The recommended low-risk probe — advertise only, then read the box's own
  stream-130 SETUP log line for the unknown `clientTypeUUID` — turns the biggest unknown into a
  captured fact in one connection and is still untaken.
  Source: `docs/carplay/04_CAPABILITIES_AND_CONFIG.md` §9 Step A. Verified open 2026-08-16:
  `grep -rn "uiSync\|ClusterControl" --include="*.rs" crates ccpa` → only test fixtures.

- **DCX is investigation-only — no spatial/APAC pipeline exists anywhere in the tree.** The acronym,
  the `/info` placement and the exchange's wire shape are all open; the doc says write no code
  before the iOS-27 disassembly lands.
  Source: `docs/carplay/04_CAPABILITIES_AND_CONFIG.md` §10. Verified open 2026-08-16:
  `grep -rn "DCXEnabled\|APAC\|spatial" --include="*.rs" crates ccpa` → only test fixtures.

- **`altDisplayPanels[]` is parsed but never emitted; `showsInstruments` and `initialURL` are
  hardcoded box constants on a legacy `displays[]` entry.** This is a doctrine gap (box-decided where
  the app authors the value). **Caveat, do not lose it:** the code's own comment records docs/carplay/03_SDK_GROUND_TRUTH.md §5's
  "root cause" framing as REFUTED — cluster content already works via `showUI` query parameters — so
  anyone reviving the emission must first establish what it adds.
  Source: `docs/carplay/04_CAPABILITIES_AND_CONFIG.md` §Roadmap item 4, `docs/carplay/03_SDK_GROUND_TRUTH.md` §5, `R-26-2`, `R-49-2`. Verified open 2026-08-16:
  `grep -rn "alt_display_panels\|displayPanels" crates/vendor/receiver/src/{vehicle_config,info}.rs`
  → parsed with the comment "PARSED since 2026-08-10; still not emitted"; `info.rs` has no
  `displayPanels` key and a test named `alt_display_panels_are_parse_only_today`.

- **Second main view area is APP-DRIVEN (2026-09-05, later the same day); the bench lever is the
  app-less fallback.** `vehicle_config.rs::apply` now carries `mainVideoStream.viewAreas[1].viewArea`
  (+ our `initial` extension key) into `DeviceConfig::main_view_area_2`, `info.rs::view_area_2`
  prefers it over `CARPLAY_VIEWAREA2` / `/tmp/carplay_viewarea2` and refuses an uncontained rect with
  the same `***` log the lever gets (a refused config area declares ONE area — it does not fall
  through to stale lever state), and `view_areas_enabled()` auto-arms `viewAreas` for a declarable
  second area the way a real inset does. Host side: `VehicleConfigModel.viewArea2{Enabled,X,Y,W,H}`,
  validated by `ViewArea2Rule` in severity order (containment → all four values EVEN → positive dims,
  each a teardown → the PRODUCT floor 800x480 landscape / 480x800 portrait, a lockout) and emitted
  only when enabled AND legal, so the OFF document
  is byte-identical (fixture guard). `events::request_view_area` answers the Dock button with
  `updateViewArea` — device-proven with `1600x960@800,0` on 2400x960 and `600x400@240,760` /
  `1080x1600@0,160` on 1080x1920 (`docs/carplay/06_AV_PIPELINE.md` §3, "Bench lever, 2026-09-05").
  The `VehicleConfigModel`/`ViewArea2Rule` host side above was the macOS app's and went with it on
  2026-10-27. Since 2026-11-07 the GM app declares a second area when Settings ▸ Display ▸ Media pane
  is set to a side, with `initial: true` when the pane was last showing (2026-11-08; see the enigy
  ports entry above). Still open:
  `updateDisplayPanels` live re-negotiation; and the type-112 second cluster.
  Source: `docs/carplay/06_AV_PIPELINE.md` §2–§4, `docs/carplay/03_SDK_GROUND_TRUTH.md` §10, `docs/carplay/02_SESSION_LIFECYCLE.md` §"Apple's session/lifecycle model".

- **`displays[].features` is a hardcoded `if levers::dpad() { 0x1A } else { 0x0A }`, and four parsed
  `hidConfig` keys drive nothing** (`touchpadSupport`, `steeringWheelSupport`, `mediaButtonsSupport`,
  `touchScreenMode`). `0x02` claims Knobs and `0x10` claims Touchpad with no backing `hidDevices[]`
  entry, while `dPadSupport` — the only input the emission consults — contributes nothing to that
  word in Apple's own mapping. The current value is hardware-validated, so correcting it is a wire
  change needing a session. Tracked as workstream C-7/C-8.
  Source: `docs/carplay/03_SDK_GROUND_TRUTH.md` §8 item 5, `docs/carplay/04_CAPABILITIES_AND_CONFIG.md` §3. Verified open 2026-08-16:
  `grep -n "disp_features" crates/vendor/receiver/src/info.rs`;
  `grep -rn "touchpad_support\|steering_wheel_support\|media_buttons_support\|touch_screen_mode" --include="*.rs" crates ccpa`
  → every hit is a struct field or a test.

- **HID uids are hardcoded 1–5; Apple's runtime allocator is not replicated.** The descriptors
  themselves are done and byte-verified, but `steeringWheelID` consumes an ID while emitting no
  device, and the uid-4/uid-5 entries still await hardware validation.
  Source: `docs/carplay/03_SDK_GROUND_TRUTH.md` §8 item 4 and §2 trap 1. Verified open 2026-08-16:
  `grep -n "HID_UID" crates/vendor/receiver/src/info.rs` → five `const` literals, no counter and no
  allocator.

- **Video lanes are fixed and capped at two.** `altVideoStreams[]` parses as an array but only its
  first entry is used, and the video seam's key message carries an `scid` the host discards; a third
  stream would need new channel ids or scid tagging. Audio has no such limit.
  Source: `docs/carplay/01_OCBM_PROTOCOL.md` §"What is genuinely not supported (open item)". Verified open 2026-08-16:
  `grep -n "CH_VIDEO\|CH_ALT_VIDEO" crates/ocbm-proto/src/lib.rs` → exactly two video channels.

- **App-owned CarPlay pairing peer store.** Today `carplayd`'s `DiskPeers`
  (`ccpa/carplayd/src/main.rs` ~:342-439) persists `id → Ed25519 LTPK` on box flash, and
  `PeerSaver::save_peer` (`crates/vendor/pairing/src/setup.rs` ~:29-30) is infallible because
  pairing is already cryptographically complete when it runs. Owner decision (2026-09-02): the
  host app owns the store, per app install (Android app storage), cleared on app uninstall; the box keeps peers in memory only — no box-side disk
  fallback, so an app-less bench boot re-pairs; the app supports removing a peer, and the box
  refuses pair-verify for any peer not in the last pushed list. Mechanism: a `peers:` list in the
  `CT_SUBSCRIBE` YAML the app already re-pushes on every SUBSCRIBE (`crates/ocbm-proto/src/lib.rs`
  `CT_SUBSCRIBE`), staged by `ocbmd` next to the existing config file and read by carplayd's
  `load_device_config`; a new box→host `SEV_PEER_ADDED{id, ltpk}` on `CT_SESSION_EVENT` when
  pair-setup completes; `DiskPeers` → `MemPeers` (trait shape unchanged). Touch list: `ocbm-proto`,
  `ocbmd`, `carplayd`, the GM app (`host/gm_ccpa`: config push + event handling). Status:
  planned, not started.

---

- **View-area resize: what is left after the 2026-09-07 wired matrix** is the "Known-open, in priority
  order" list in [`../carplay/06_AV_PIPELINE.md`](../carplay/06_AV_PIPELINE.md) §5 (moved there from this
  file on 2026-11-26).

### iAP2 & metadata

- **`message.rs` still calls params-6/7 growth "a closed dead end as a class".** The comment sits
  in the wired Identify test (search the phrase). The 2026-08-10 decoded rejects named the specific
  ids iOS refused, so growth is per-id, not dead as a class. Verified open 2026-11-02:
  `grep -n "closed dead end as a class" crates/vendor/iap2-core/src/message.rs`.

- **The Identify serial is a hardcoded string**, `"2025.02.25.1521626a"`, for every box. Verified
  open 2026-11-02: `grep -n 'SERIAL_NUMBER, "' crates/vendor/iap2-core/src/message.rs`.

- **Identify param 30 declares only subs 0 and 1, so road and destination names cannot populate.**
  Subs 2/3 gate `CurrentRoadName`/`DestinationName`. (The title also said `lane_guidance` was
  inert because sub 8 is missing. Our own capture refutes that: `0x5204` arrived ×12 on
  2026-07-29.)
  Source: `docs/carplay/03_SDK_GROUND_TRUTH.md` §8 item 1. Verified open 2026-08-16:
  `grep -n "route_guidance as sub" -A8 crates/vendor/iap2-core/src/message.rs` → the emitter pushes
  only `sub::IDENTIFIER` and `sub::NAME`.

- **Param 30 is still UNGATED across all three transports, which blocks its expansion.** Growing it
  today also grows the byte-pinned BT-time `Wireless` Identify; the doc states gating to
  `AirPlayTunnel` first is "a prerequisite, not an option".
  Source: `docs/carplay/03_SDK_GROUND_TRUTH.md` §8 item 2. Verified open 2026-08-16:
  `grep -n "ROUTE_GUIDANCE_DISPLAY_COMPONENT" crates/vendor/iap2-core/src/message.rs` → the emitter
  `push(…)` sits in a bare block with no `matches!(transport, …)` guard; the gate exists only as a
  commented-out `ROLLBACK` suggestion above it.

- **C-3 — RESOLVED 2026-09-01: `iap2d` now passes the app-pushed vehicle identity on the wire.**
  `ccpa/iap2d/src/main.rs` calls `message::build_ident_info_with` (`Action::SendIdentify`) and
  `build_ident_info_excluding_with` (`Action::RetryIdentify`) with the app-pushed
  `vehicle_identity()` (docs/carplay/04_CAPABILITIES_AND_CONFIG.md C-3). `iap2d` still passes
  `message::accessory_name("CarLink")` at both Identify sites — that half of the original claim
  stands; only the tunnel and BT drivers still pass the baseline identity.
  Source: `docs/carplay/04_CAPABILITIES_AND_CONFIG.md` §3 row C, the pre-consolidation open-items list §4. Verified open 2026-09-01:
  `grep -rn "build_ident_info_with" crates ccpa` → hits both `ccpa/iap2d/src/main.rs` call sites
  plus a `#[test]` in `config.rs`; `grep -n "accessory_name" ccpa/iap2d/src/main.rs` → two
  hardcoded call sites, unchanged.

- **What flips `CarPlayAppListAvailable` from 0 Unknown to 2 Accepted is still UNDETERMINED, and the
  proposed Simulator experiment was never run.** It needs no box work. Related known difference,
  recorded not resolved: we omit sub 1 `CarPlayAppListMax` where Apple's Simulator always emits it.
  Source: `docs/carplay/03_SDK_GROUND_TRUTH.md` §6, corroborated by `docs/ops/05_AUDITS.md` §2 ("`0xAD01` remains uncaptured"). Verified open
  2026-08-16: `grep -n "CarPlayAppListAvailable" crates/vendor/iap2-core/src/features.rs` → still
  reads "remains UNDETERMINED by every normative source"; no matching capture in `docs/ops/captures/`.

- **Nine `communications` (0x4158) capability keys are emitted by the box and read by nothing.**
  `cellularSupported`, `faceTimeAudioEnabled`, `initiateCallAvailable`, `endAndAcceptAvailable`,
  `holdAndAcceptAvailable`, `swapAvailable`, `mergeAvailable`, `holdAvailable`,
  `faceTimeVideoEnabled`. The Mac app's panes, which this gap was about, went with that app; the GM
  app's call pill reads `callState` only. Data reaches the app; nothing uses these keys.
  Source: `docs/ops/05_AUDITS.md` §1 ("Live gap"). Verified open 2026-11-02:
  `grep -rn "cellularSupported\|faceTimeAudioEnabled\|initiateCallAvailable" host/gm_ccpa/netprobe_app/app/src/main`
  → zero hits; the same grep over `--include="*.rs"` hits `iap2-core/src/metadata.rs`.

- **`0x4E0D WirelessCarPlayUpdate` and `0x5700`–`0x5703` are declared but not acted on.**
  `metadata.rs` now decodes `0x4E0D` into a `wirelessCarPlayAvailable` field; nothing acts on it.
  Source: `docs/carplay/02_SESSION_LIFECYCLE.md` §10. Verified open 2026-11-02:
  `grep -rn "0x4E0D" --include="*.rs" crates ccpa` → spec-table entries, declaration lists and the
  `metadata.rs` decoder; no handler.

---

### Wireless transport — the RCS DataStream and the BT→WiFi path

- **An inbound RCS `'sync'` obliges a `'rply'`; the box logs and does not send one — BLOCKED, not
  merely unstarted.** `_controlServer_sendResponseInternal` writes `messageType = 0` while the
  phone's only RCS inbound dispatch accepts `'cmnd'`/`'died'`; both cannot be true, and a malformed
  accessory frame has already wedged this link once, so guessing wrong is asymmetrically expensive.
  Source: `docs/carplay/05_METADATA_AND_CONTROLS.md` §5 item 1, the pre-consolidation open-items list §5 (#15b). Verified open
  2026-08-16: `grep -n "sync" crates/vendor/receiver/src/session.rs` → logs
  `inbound 'sync' … expects a 'rply' — NOT IMPLEMENTED`; `KIND_RPLY` has no non-test consumer.

- **The tunnel SYN declares 2 link sessions at session version 1 where Apple's transport-type-2
  template declares 3 at version 2, and post-`Identified` frames bypass `state::on_message`.** Both
  were deferred so the first hardware test had one variable; that test has since run. A re-`0x1D00`,
  re-`0xAA00` or `0xAA04` after Identify falls through to the generic dispatcher.
  Source: `docs/carplay/05_METADATA_AND_CONTROLS.md` §5 items 2–4. Verified open 2026-08-16:
  `grep -n "SYN_PARAMS_ZERO_ACK_TUNNEL" -A2 crates/vendor/iap2-core/src/link.rs` → leading version
  byte 1 and two 3-byte session blocks.

- **The event capture still watches the `POST /command` carrier that docs/carplay/05_METADATA_AND_CONTROLS.md refuted.**
  `/tmp/carplay_event_capture.bin` is written from the event-channel plist path, a channel now proven
  not to carry wireless iAP2 (that rides the RCS DataStream, stream type 130).
  Source: `docs/ops/05_AUDITS.md` §8 "Still open". Verified open 2026-08-16:
  `grep -rn "carplay_event_capture" --include="*.rs" crates` → one hit, inside
  `events.rs::handle_inbound_event`'s RTSP-body path; no equivalent in `datastream.rs`.

- **An absent `clientTypeUUID` is treated as iAP, diverging from Apple's teardown path.** Apple's
  `_DataStreamSessionSetup` sends an absent UUID to `-6735` teardown identically to an unrecognised
  one. Knowingly kept as permissive-but-harmless; listed only so the index is complete.
  Source: `docs/carplay/03_SDK_GROUND_TRUTH.md` §3. Verified open 2026-08-16:
  `grep -n "clientTypeUUID" crates/vendor/receiver/src/session.rs` →
  `let is_iap = client_type.is_empty() || client_type.eq_ignore_ascii_case(IAP_CLIENT_TYPE);`.

- **`iAPChannel` and `sessionManagement` were never migrated to per-transport app config.** Both are
  still env-gated box-only SETUP tokens with no config key, so an app that authors SETUP must UNION
  them back in from the box's own response or silently kill the wireless iAP2 tunnel. **Latent on
  the car:** the GM app pushes `appDrivenSetup: false`, so the box authors SETUP and adds both
  tokens itself. Only `host/ocbm-host/src/setup_driver.rs` authors SETUP today, and it carries the
  union.
  Source: §Building blocks step 8 below (workstream C/D debt). Verified open 2026-11-02:
  `grep -n "feats.push" crates/vendor/receiver/src/session.rs` (both tokens env-gated);
  `grep -n "appDrivenSetup" host/gm_ccpa/netprobe_app/app/src/main/java/wasidremin/gmccpa/ocbm/VehicleConfigYaml.kt`.

- **Pair-RESUME is absent.** Only pair-setup and pair-verify exist; there is no persisted-session-id
  resume and no resume→verify fallback, so a known device always pays the full pair-verify round trip.
  Source: `docs/carplay/02_SESSION_LIFECYCLE.md` §"Apple's session/lifecycle model", `R-11-1`. Verified open 2026-08-16:
  `grep -rni "pair-resume\|pair_resume\|pairResume" --include="*.rs" --include="*.swift" --include="*.kt" .`
  → zero hits.

---

### Radios, box identity & bring-up

- **Broadcom BT attach is still refused, so a Broadcom unit reports `unsupported` for BT.** NXP is
  no longer refused: `radio_detect.sh` now slices the unit's own SDIO branch and expands that
  branch's literal `var=value` assignments (`nxpWiFiConfig=…`), then strips simple quotes
  (`unquote_simple`). Broadcom's BT line still carries a command substitution
  (`bcmBTMac=\`set_wifi_mac …\``), which `safe_cmd` blanks by design. Emitting the raw text instead is
  the documented silent-false-success failure (docs/wireless/01_BT_AND_RADIO.md §6d).
  Source: `docs/wireless/01_BT_AND_RADIO.md` §7. Verified 2026-11-02:
  `grep -n "safe_cmd\|unquote_simple\|bcmBTMac" ccpa/rootfs/script/radio_detect.sh`.

- **Broadcom is entirely untested, and RTL8733BS / SD8987 are unexercised.** The seam resolves them
  on paper from their own dispatchers — that is the whole design claim — but the claim is measured
  only on 0xc822 (bench) and on the car's adapter, an NXP IW416 (`SDIO_DEVICE=0x9159`,
  `CHIP=nxp_iw416` in every 2026-10-29/10-30 session) on the `owned` backend. On Broadcom, `wlan0`
  does not exist until explicitly created on top of `sta0`, and no Broadcom hardware is available.
  Source: `docs/wireless/01_BT_AND_RADIO.md` §7. A hardware-coverage gap, so no grep can close it.

- **"One box, one name" is not achieved — the Rust side never reads `/etc/carplay_ident` and still
  hardcodes `wlan0`. Half settled 2026-11-26:** the Bluetooth name no longer changes from start to start
  (`iap2-core` `KEPT_SUFFIX`, `/etc/ocbm/name_suffix`, written by the boot preload from `wlan0`; the car's
  adapter is `CarLink-13d0` every time), but it is not the Wi-Fi name: the access point is `ccpa-fe01`,
  from `sta0`'s address (`/etc/carplay_ident`). One name would mean both read one file. `btd`, `bt_on.sh` and `ocbmd`'s `bt_name_from()` each derive a
  name independently, and because the supervisor execs `btd` after `radio_hal.sh bt_on`
  the controller advertises `CarLink-<suffix>` rather than the seam's `ccpa-<4hex>`. The suffixes can
  diverge too, not just the prefixes.
  Source: `docs/wireless/01_BT_AND_RADIO.md` §6c/§7, `R-57-1`. Verified open 2026-11-02:
  `grep -rn "carplay_ident" ccpa crates tools host` → hits in `radio_hal.sh`, `radio_ap_up.sh` and
  `session_supervisor.sh`, none in Rust; `grep -n "wlan0" crates/vendor/wireless/src/av.rs crates/vendor/wireless/src/box_identity.rs crates/vendor/iap2-core/src/message.rs ccpa/ocbmd/src/main.rs`
  → `const WLAN_IFACE: &str = "wlan0"` plus the `/sys/class/net/wlan0/address` reads.

- **There is no reversible radio verification GATE in the baseline, run while still on NCM.** The
  baseline installs the seam and *reports* the resolved platform, but nothing gates the install on a
  pass/fail radio result or reverts on failure.
  Source: `docs/wireless/01_BT_AND_RADIO.md` §7. Verified open 2026-08-16:
  `grep -n "radio_detect" tools/ncm_base_install.sh` → the only invocation pipes
  `sh /script/radio_detect.sh` into a `grep -E "CHIP|SDIO_DEVICE|…"` summary print, with no exit-code
  test and no revert path.

- **`ocbm_install.sh` has no generic manifest cross-reference assertion.** The specific Realtek
  failure it would have caught is closed (`--full` now ships the seam alongside the supervisor), but a
  future shipped script referencing a path the target lacks would still slip through.
  Source: `docs/wireless/01_BT_AND_RADIO.md` §7. Verified open 2026-08-16:
  `grep -n "assert" tools/ocbm_install.sh` → one hit, a comment about the inittab; `manifest()` is a
  flat `local|remote|mode` list consumed by `push_file` with no cross-reference check.

- **The startup decode transient described in `docs/wireless/01_BT_AND_RADIO.md` §6b is unexplained and unfixed.**
  Source: `docs/wireless/01_BT_AND_RADIO.md` §7. Verified open 2026-08-16: no code carries a fix or a tracking marker —
  `grep -rn "decode transient\|startup transient" crates ccpa` → zero hits.

---

### Host-side protocol items

The macOS host app and its open-item list were removed on 2026-10-27 (git history has both). The
items below were filed against it, but they are protocol- or box-level and still apply with the GM
app as the only host.

- **Media-port coordination — the app still cannot choose the media ports.** The box pre-binds and its
  own local response doubles as the oracle; the host copies `dataPort`/`controlPort` verbatim and
  passes non-authorable types straight through. This is the one genuinely-open clause left in
  "Open design items" below. Latent on the car, where the GM app leaves SETUP to the box
  (`appDrivenSetup: false`).
  Source: `docs/carplay/01_OCBM_PROTOCOL.md` §"RTSP channel". Verified open 2026-08-16:
  `grep -n "Pre-bind + oracle" -A4 crates/vendor/receiver/src/relay.rs`.

- **No NACK retransmit.** Recovery is keyframe-request-on-seq-gap only; there is no bidirectional NACK
  channel on either end. Verified open 2026-08-16: every `nack`/`retransmit` hit in the Rust tree is
  iAP2 link-layer; zero in any A/V or OCBM path.

- **The OCBM frame header's `seq` (offset 12, u32) is written by every endpoint and read by none.**
  Distinct from the `SEAM_MAGIC` per-video-frame `u64` seq, which *is* read. Still the dead-field
  footgun the audit flagged. Verified open 2026-08-16:
  `grep -rn "\.seq" --include="*.rs" crates/ocbm-proto ccpa/ocbmd host/ocbm-host | grep -v "self.seq\|seq: "`
  → one hit, a unit assertion.

---

### Tests, tooling & install

- **The AAOS emulator segfaults within about a minute of the driver-display window opening.** Seen
  2026-11-11 and 2026-11-12 (`emulator exit 139`), on an idle machine with 7 GB free as well as
  under load, so it is not only the memory pressure `host/gm_ccpa/docs/14_LESSONS_LEARNED.md` §13.3
  describes. Rendering checks of the driver-display cards therefore come two or three screenshots
  per boot (the 2026-11-12 Drive card check took nine boots). Switching Wi-Fi on in the emulator
  (`svc wifi enable`) also crashes it, so the live arrival of Wi-Fi has never run on the bench.
  Not investigated: the emulator build, the `swiftshader_indirect` GPU mode, the second display.
  2026-11-26: `svc wifi enable` worked (the Wi-Fi only check), but every `input tap` on Settings aborted
  the emulator's `system_server` (`SIGABRT … InputDispatcher`) with the simulated driver display
  (`overlay_display_devices 2000x810/220`) set, `input -d 0` included; the USB dialog's tap that morning
  had worked. Settings checks that need a tap are done by writing the stored preference instead.

- **`skip_list_narrows_the_declaration` is still tautological, and `file_setting()` has no test.** The
  skip lever itself now has genuine coverage via `active_with_skip`, but the named test is unchanged
  and the `/tmp/carplay_metadata` parser has one caller and zero tests.
  Source: `docs/ops/05_AUDITS.md` §6. Verified open 2026-08-16:
  `grep -rn "skip_list_narrows_the_declaration\|file_setting" --include="*.rs" crates` → the test is
  still an `Iterator::filter` assertion; `file_setting` has a definition and one caller, no test.

- **`stream.rs` key derivation is unpinned — no known-answer test for the HKDF salt, info labels or
  nonce layout.** Encrypt/decrypt are exact inverses tested only for determinism, so a typo in
  `"DataStream-Salt"` passes the whole suite and kills every session.
  Source: `docs/ops/05_AUDITS.md` §6. Verified open 2026-08-16:
  `grep -rn "salt" crates/vendor/receiver/src/stream.rs crates/vendor/receiver/tests/*.rs` → the
  literals appear only in `derive_stream_keys`, never in an assertion.

- **`ocbmd` is still built at `opt-level = "z"`; the "measure first" decision was never taken.**
  `chacha20`, `poly1305`, `chacha20poly1305`, `ocbm-proto` and `receiver` are speed-tuned; the daemon
  that runs the poll loop and the per-frame forward is not.
  Source: `docs/ops/05_AUDITS.md` §"Also deferred", `docs/wireless/01_BT_AND_RADIO.md` §3. Verified open 2026-08-16:
  `grep -n "profile.release.package" Cargo.toml` → five overrides, none for `ocbmd`.

- **`CARPLAY_EVENTS_LOG` is set by no spawn site, so docs/ops/02_TESTING.md's byte-level discriminator greps read 0
  on a healthy box.** Reviving them means setting it in the `carplayd` spawn env first.
  Source: `docs/ops/02_TESTING.md` §"The discriminator", `R-46-1`. Verified open 2026-08-16:
  `grep -rn "CARPLAY_EVENTS_LOG" --include="*.rs" --include="*.sh" . | grep -v docs/` → four hits,
  all readers; `grep -n "carplayd" tools/session_supervisor.sh` → the spawn env is
  `OCBM_FWD_ENC=1 $CM $LT $MB`.

- **`carplay-status.sh` — the reader for the `/tmp/carplay_state` verdict — is not installed by the
  current installer.** The supervisor writes the verdict and tells you to "Read it with
  carplay-status", but only the older `install_fhs.sh` subset places the script, so a box built the
  current way has the state file and no reader.
  Source: `docs/carplay/02_SESSION_LIFECYCLE.md` §"Supporting layers" (Observability). Verified open 2026-08-16:
  `grep -rn "carplay-status" tools/*.sh` → `install_fhs.sh` and the supervisor's own message;
  `ocbm_install.sh`'s manifest does not list it.

- **The box network surface is unhardened — weak default PSK, unauthenticated `telnetd`, bare
  dropbear, no AP isolation, no iptables.** `radio_ap_up.sh` refuses to BLANK the passphrase but only
  writes one when the existing key is absent or under 8 chars, so an existing `12345678` is never
  replaced. **This is a deliberate development posture** (telnetd is the documented rollback channel)
  and therefore an owner decision, not a defect — recorded so it is a decision and not an oversight.
  Source: `docs/ops/05_AUDITS.md` §7 ("Outside the review's scope, for the owner"). Verified open 2026-08-16:
  `grep -n "wpa_passphrase" ccpa/rootfs/script/radio_ap_up.sh` → the guard is `[ "${#PSK}" -lt 8 ]`;
  `cat ccpa/rootfs/etc/init.d/rcS` → `busybox telnetd -l /bin/sh -p 23 &`;
  `grep -rn "ap_isolate" ccpa/` → no matches.

---

### Closed by decision — do NOT re-plan these

Recorded so a future session does not mistake an owner decision for unfinished work.

- **LogTransfer Tier-2 (serving the archive) will NOT be implemented** — owner directive 2026-08-07,
  no upside. Tier-1 advertise/negotiate is landed and device-proven. The byte-level unknowns
  (numeric `messageType`/`payloadType`, checksum algorithm and width, chunk-dict serialization,
  default/max `TransferChunkSize`) are recorded in `docs/carplay/04_CAPABILITIES_AND_CONFIG.md` §2 should it ever be revisited.
- **Enhanced Siri was OUT OF SCOPE by owner directive 2026-08-07** (it needs a full hot-word and
  voice-analysis stack outside an A/V adapter's remit). **Superseded 2026-09-22:** it is back on the
  plan as `08_FUTURE_TASKS.md` T11, with the detectors in the GM app rather than on the box. Read
  that entry. Until it lands the box stays advertise-inert (`docs/carplay/04_CAPABILITIES_AND_CONFIG.md`
  §6). Button and steering-wheel Siri work; the always-listening path does not exist.
- **The docs/carplay/03_SDK_GROUND_TRUTH.md §8 item 3 task "Emit `displayPanels[]` — the alt-content root cause" is REFUTED as a
  causal claim** (`R-49-2`, owner-confirmed on hardware 2026-08-11): cluster content works and its
  elements are toggleable via `showUI` query parameters. The *emission* remains unimplemented and is
  listed above under the capability surface — but as a schema-completeness item, not as the cluster
  fix docs/carplay/03_SDK_GROUND_TRUTH.md §8 still presents it as.
- **Phase 1 of the pre-consolidation open-items plan is closed in full** by owner decision (device
  identifiers and the vendor firmware backups). Nothing from it belongs in this index.
- **The `/info` diff of a live CarPlay Simulator session against ours cannot be run in this fork.**
  `docs/carplay/03_SDK_GROUND_TRUTH.md` §11 named it as the productive move; the Simulator is a
  macOS app and the owner has no Mac. The iPhone's own logs (`idevicesyslog`) are the substitute.
  Read with `R-49-1`: `/info` content is app-authored, so any finding changes the app's config, not
  the box.
- **The `android.car.usb.handler` package squat is reverted (2026-09-08) and must not return.** It
  gave a silent USB grant on a Silverado, is dead on the Equinox (no handler component), and Play
  will not ship a reserved `android.*` id (`docs/host/01_ANDROID_AND_AAOS.md` "Closed, do not
  retry"; `host/gm_ccpa/docs/14_LESSONS_LEARNED.md` §6).
- **Upstream PR #2 (lvalen91/ocbm, 2026-10-19) needs nothing taken.** `0c7bc55` (NXP variable
  resolution, BT firmware preload) and `e51da83` (`/sbin/reboot` in `ocbm_install.sh`) are already in
  this tree in its own form; `39b7d40` makes `ncm_base_install.sh` run on a stock Mac, which this
  owner does not have.
- **The `MGMT_ENTER_NCM` second-reboot (2026-09-03) is CLOSED BY OWNER DECISION — not to be
  investigated.** A second, unexplained reboot ~60 s into the resulting NCM boot happened once in
  three cycles that hour and `/tmp` did not survive to record a cause; kept for the record only.
- **Numeric-comparison SSP: box-initiated interactive answer is NOT the default, by owner decision
  after measurement (2026-09-04).** iOS refuses to hold a box-initiated numeric-comparison request
  open for a human to answer on the accessory (`pairingComplete result:162` instantly). The box
  confirms its own side immediately by default; the interactive wait lives on only behind
  `BT_SSP_INTERACTIVE=1` / `pairing: numeric_comparison_interactive` as a bench lever. Full
  measurement: [`../wireless/01_BT_AND_RADIO.md`](../wireless/01_BT_AND_RADIO.md).

---

### Found closed — documents still call these open

Every entry here was listed as open, planned or deferred by a source document (or by this file) and
is shipped in the current tree. Listing them is the point: a stale "open" is exactly the false claim
this index exists to remove.

**Found closed on 2026-11-02 (this file listed them as open):**

- **The car's position reaches the phone (was "Accessory-side NMEA/GPS is parse-only"), 2026-11-26.**
  T6 built it on 2026-11-13: `INPUT_LOCATION` from the app, forwarded as `0xFFFB`
  (`crates/vendor/receiver/src/location.rs`); in the car on 2026-11-18 the phone started location updates,
  took 7 and stopped (`08_FUTURE_TASKS.md` T6). Whether Maps uses it is T6's open question.
- **`connect_seam` is numeric-only.** It parses `target.parse::<SocketAddr>()`; the doc comment
  reads "Literal addresses ONLY" because `getaddrinfo` has no deadline. In the tree by 2026-09-04.
  Verified: `grep -n "fn connect_seam" -A2 ccpa/ocbmd/src/main.rs`.
- **W9 and the Raspberry Pi BT MAC: param 17 carries this unit's own address.** Both Identify sites
  call `accessory_bt_mac()` (`crates/vendor/wireless/src/bt_driver.rs`,
  `crates/vendor/receiver/src/iap_tunnel.rs`), which reads `/sys/class/bluetooth/hci0/address` and
  falls back to the locally administered `02:00:00:00:00:00`. The car's BT-time Identify is accepted
  with it (`[bt-driver] IdentifyAccept`, 2026-10-30), and the log server holds no placeholder
  warning. In the tree by 2026-09-04.
  Verified: `grep -rn "fn accessory_bt_mac" crates`.
- **`RADIO_BT_PRELOAD_CMD` is emitted.** `radio_detect.sh` writes it since 2026-09-23 (`7818b8d`).
  Verified: `grep -n "RADIO_BT_PRELOAD_CMD=" ccpa/rootfs/script/radio_detect.sh`.
- **`tools/ocbm_boot.sh` is no longer a stale duplicate**; it is a one-line pointer to
  `ccpa/rootfs/script/ocbm_boot.sh`. Verified: `wc -l tools/ocbm_boot.sh` → 1.
- **`ocbm_install.sh --full` installs `iap_role_switch`** (`accessory_init/iap_role_switch.armv7`
  → `/usr/bin/iap_role_switch`). Verified: `grep -n "iap_role_switch" tools/ocbm_install.sh`.
- **The CT_HELLO `out_console` resync is fixed**; a HELLO clears the console queue so the ACK is
  never spliced mid-frame. Verified:
  `grep -n "fn hello_clears_the_console_queue_too_so_the_ack_is_not_spliced_mid_frame" ccpa/ocbmd/src/main.rs`.
- **C-4: vehicle status is declared and accepted.** `message.rs`'s AirPlayTunnel arm declares 0xA101
  in param 6 and 0xA100/0xA102 in param 7, only together with param 21 (`features.rs` still cannot
  express the inverted direction, by design). On the car 2026-10-28 the phone accepted the Identify,
  sent `0xA100` and took `0xA101` (`08_FUTURE_TASKS.md` T7).
- **`/tmp/carplay_transport` claimed at `ensure_av_layer` entry is deliberate, not a defect.** The
  in-code rationale (docs/wireless/00_WIRELESS_CARPLAY.md #1.6): until carplayd wrote the flag, the
  wired supervisor's `arm()` guard could kill a correctly configured wireless carplayd mid-handoff.
  A failed spawn still releases it. Verified: `grep -n "carplay_transport" crates/vendor/wireless/src/av.rs`.
- **The Siri and TEARDOWN fixes are exercised on the car.** Button and steering-wheel Siri work
  (`08_FUTURE_TASKS.md` T14, 2026-10-27, with the mic heard). Every app restart tears the session down
  and starts a new one; two did on 2026-10-30, each followed by a working session. No dedicated test
  or capture exists for either.
- **The app-pushed metadata tier is hardware-validated.** Every session on the car logs
  `metadata policy ARMED from pushed config: tier=proven skip=[]` (2026-10-30).
- **`nightMode` and `rightHandDrive`.** `nightMode` was dropped from the pushed YAML 2026-09-02
  (owner decision; the live path is the runtime `setNightMode` command). `rightHandDrive` is an
  `/info` boolean (`kAirPlayKey_RightHandDrive`, R14G17 `AirPlayCommon.h`), emitted by `info.rs`
  since 2026-09-05 and device-verified that day: iOS moved the app rail, status bar and app-grid
  button to the right edge. The GM app pushes `rightHandDrive: false`. Full account:
  `docs/carplay/04_CAPABILITIES_AND_CONFIG.md` §rightHandDrive.
- **From the GM app's 2026-10-04 gates.** Top-bar placement is proven on the car, which uses it
  every drive (`topbar: strip=168px placed=true reason=ok`, and every vision-launcher tap since 225
  verified). `limitedUI` no longer goes through JNI: it rides OCBM `CMD_LIMITED_UI_ON/OFF`, and the
  box confirmed `[input] command setLimitedUI(true) sent=true` on 2026-10-30. The dead
  `Java_zeno_gmccpa_pair_NativeCore_nativeSetLimitedUI` export still sits in
  `host/gm_ccpa/native/carplay-jni/src/lib.rs` (cleanup only). Steering-wheel Siri works (T14).

**From this file's own earlier tracking:**

- **Pairing-hold latch is DEPLOYED, not staged.** `rfcomm_uspace::pairing_aware_connect` (now
  `crates/bt-common/src/rfcomm_uspace.rs`) is on the box and measured against iOS 27
  (2026-09-03/04): [`../wireless/01_BT_AND_RADIO.md`](../wireless/01_BT_AND_RADIO.md) §"Connect hold".
- **The 2026-10-27 tunnel self-deadlock is fixed (2026-10-28).** `iap_tunnel::handle_established`
  called `reset()`, which locks `SESSION`, while its caller still held that guard. It now returns
  true on `0xAA04` and `handle_inbound` drops its guard first. Test:
  `iap_tunnel::established_abort_tests`.

**From `docs/ops/05_AUDITS.md` §DEFERRED, §"DEAD CODE inventory" and its Phase-2 banner** (box side;
entries checked against the macOS app were dropped 2026-11-02 with that app, and git history has
them):

- **The staged box deploy of the video fix happened** and was hardware-validated 2026-08-01.
  Verified: `grep -n "struct OutQueue\|out_video" ccpa/ocbmd/src/main.rs`.
- **M-k stereo PCM uplink RTP timestamp fixed** — the advance is per-channel sample-frames on both
  codec arms. Verified: `grep -n "SAMPLE-FRAMES\|per_ch" crates/vendor/receiver/src/uplink.rs`.
- **Three ocbmd LOWs fixed** — blocking `forward_input`, the 30 s `CT_SRC` srcbench, and the CH_IP
  `conns` leak across sessions. Verified: `grep -n "fn forward_input" -A20 ccpa/ocbmd/src/main.rs`
  and the `conns.clear()` calls on both teardown paths.
- **`net.rs` no longer tears down after one pre-A/V read timeout** — the `WouldBlock` arm consults an
  A/V-activity backstop. Verified:
  `grep -n "av_idle_ms\|AV_IDLE_TEARDOWN_MS" crates/vendor/receiver/src/net.rs`.
- **`AltVideoStream.max_fps` and `HidConfig.knob_support` are applied, not dropped**, and the dead
  `MBTN_*`/`ACODEC_*`/`ATYPE_*`/`sevHostGone`/`videoCounter`/`etaEpoch` symbols are swept.
  Verified: `grep -n "alt_max_fps" crates/vendor/receiver/src/vehicle_config.rs`;
  `grep -rn "MBTN_\|ACODEC_\|ATYPE_" crates/ocbm-proto/src/lib.rs` → none.
- **Communications 0x4158 and List 0x4171 are no longer structurally unreachable** — both are
  Extended-tier features in the generated table and `message.rs` unions `RCV_MSG_IDS` with
  `features::received_ids`. Verified: `grep -n "communications\|call_history" crates/vendor/iap2-core/src/features.rs`.
- **eld-codec `AACENC_SBR_MODE=0` landed** — and the root cause was broader than the doc knew (the
  bitrate was also wrong). Verified: `grep -n "AACENC_SBR_MODE" crates/vendor/eld-codec/csrc/eld_shim.c`.
- **The ocbmd half of the desk-side batch shipped** — `frame_into`/`try_frame_into` and the
  `OutQueue` cursor. Verified: `grep -n "fn frame_into" crates/ocbm-proto/src/lib.rs`.
- **W1 RFCOMM reassembly landed** — a persistent accumulator with a bounded desync drop.
  Verified: `grep -n "reassembl" crates/vendor/wireless/src/bt_driver.rs`.
- **The W-set landed** — ordered thread joins before A/V teardown, and the AV latch vouches for its
  daemon by tracked pid. `rx-connect` was merged into carplayd on 2026-09-08
  (`ccpa/carplayd/src/discovery.rs`), so the latch now tracks carplayd alone and Finding B's
  "reap `rx-connect` by both name forms" no longer has a target. Verified:
  `grep -n "AV_AIRPLAYD_PID" crates/vendor/wireless/src/av.rs`.

**From `docs/carplay/02_SESSION_LIFECYCLE.md` / `docs/carplay/06_AV_PIPELINE.md` and the
pre-consolidation open-items list (their banners under-report):**

- **Partial-stream teardown SHIPPED** — `AvSession::teardown` branches on stream count exactly as
  `AirPlayReceiverSession.c` does, with the empty-array-is-a-FULL-teardown correction applied. Both
  `docs/carplay/02_SESSION_LIFECYCLE.md` and `R-11-1` list it under "P2 — NONE SHIPPED".
  Verified: `grep -n "Partial teardown\|outDone=false" crates/vendor/receiver/src/session.rs`.
- **The control-channel inactivity watchdog gated on A/V-active flags SHIPPED** — neither the doc nor
  the ledger records it either way. Verified: `grep -rn "av_idle_ms" --include="*.rs" .`.
- **The 24 kHz mic rate SHIPPED box-side** — the box negotiates AAC-ELD and OPUS 24 k mono.
  Verified: `grep -rn "24000" --include="*.rs" crates/vendor/receiver ccpa/carplayd`.
- **Backpressure-not-drop is in the tree**, not "BUILT … staged" — per-stream out-queues, video gated
  on its own backlog, audio never gated. Verified: `grep -n -i "backpressure\|never gated" ccpa/ocbmd/src/main.rs`.
- **The knob/telephony descriptors, `lane_guidance` and param 30 all shipped**, so `R-06-1`'s
  remaining-work list is itself partly stale. Verified:
  `grep -n "fn knob_descriptor\|fn telephony_descriptor" crates/vendor/receiver/src/info.rs`;
  `grep -rn "lane_guidance" crates/vendor/iap2-core/src/features.rs`;
  `grep -n "ident_info_wireless_transport_declares_route_guidance_param30" crates/vendor/iap2-core/src/message.rs`.
- **AltVideo shipped box-side** — `CH_ALT_VIDEO 0x24`, seam `:9005`. (The decoder and window were the
  macOS app's; since 2026-11-08 the GM app decodes it into a window on the driver display, see "CarPlay
  map on the driver display" under the GM app items.)
  Verified: `grep -rn "CH_ALT_VIDEO" crates/ ccpa/`.
- **The other two "Open design items" are genuinely resolved** — the `receiver_core` rootfs
  footprint (≈3.8 MiB measured) and the SETUP-relay latency (p99 2.36 ms, GO). Neither carries a
  surviving "Still open" clause; only media-port coordination does.

**From `docs/carplay/03_SDK_GROUND_TRUTH.md` / `docs/carplay/04_CAPABILITIES_AND_CONFIG.md` /
`docs/carplay/05_METADATA_AND_CONTROLS.md` / `../wireless/00_WIRELESS_CARPLAY.md`:**

- **`enablesUIAppearance` / `enablesMapAppearance` ARE parsed**, and `uiAppearanceUpdate`,
  `mapAppearanceUpdate` and `changeMapZoomLevel` all exist — docs/carplay/03_SDK_GROUND_TRUTH.md §5's body text still calls them
  absent. Verified: `grep -n "enablesMapAppearance" crates/vendor/receiver/src/vehicle_config.rs`;
  `grep -n "changeMapZoomLevel\|uiAppearanceUpdate" crates/vendor/receiver/src/events.rs`.
- **`knob_support` is wired end to end** (docs/carplay/03_SDK_GROUND_TRUTH.md §8 item 6 already annotates this STALE; confirmed so
  the index does not re-open it). Verified: `grep -rn "set_knob_advertised" --include="*.rs" crates ccpa`.
- **focusTransfer's lever and per-view `/info` flag DID land** — docs/carplay/04_CAPABILITIES_AND_CONFIG.md's "no `focustransfer()`
  lever exists / hard-false" is refuted. Only the SETUP echo is still missing (listed above).
  Verified: `grep -n "focus_transfer" crates/vendor/receiver/src/{info,levers}.rs`.
- **docs/carplay/04_CAPABILITIES_AND_CONFIG.md's "Current status" for Mechanism B is overtaken** — Identify param 20 no longer
  hardcodes `EngineType=Gasoline` and no longer omits `SupportedChargingConnectors` /
  `PowerForConnectorType`, and param 21 is emitted when the app pushes a `vehicleStatus:` block
  (C-4 above). Verified:
  `grep -n "engine_types\|SUPPORTED_CHARGING_CONNECTORS\|status_caps" crates/vendor/iap2-core/src/message.rs`.
- **docs/carplay/05_METADATA_AND_CONTROLS.md §5 item 5 shipped on both halves** — `viewAreas` IS echoed in the SETUP feature list, and
  `extendedFeatures` was hoisted out of the HEVC block. Verified:
  `grep -n "viewAreas" crates/vendor/receiver/src/session.rs`;
  `grep -n "extendedFeatures" crates/vendor/receiver/src/info.rs`.
- **The wireless research's "Still open" items 1 and 3 are closed** — the Bonjour responder on `wlan0`
  ships (in `rx-connect` until 2026-09-08, inside carplayd since), and the link-key store reuses
  `carplay_peers.bin`. Verified: `grep -n "_airplay._tcp" ccpa/carplayd/src/discovery.rs`;
  `grep -rn "carplay_peers" --include="*.rs" crates ccpa`.
- **docs/wireless/01_BT_AND_RADIO.md's radio re-gating is CLOSED** (`R-51-3`) — the guard sits on `wireless_up` itself, and the
  two call-site strategies that failed must not be retried.

---

### False claims found while verifying

Beyond the "found closed" list, these documents assert something the code or our own captures
contradict. Recorded here, not fixed — this index changes no other document.

- **docs/carplay/05_METADATA_AND_CONTROLS.md's banner says "the host-side pane defect is still open".
  It never was a defect, and since 2026-10-27 there is no such pane.** `docs/ops/05_AUDITS.md` §1
  diagnosed the macOS app's empty Telephony/Power/Device panes as a **stale binary** (the session log
  began before either Release build existed). That app was removed on 2026-10-27. The residue is the
  nine unread `communications` capability keys, listed above. Verified 2026-11-02:
  `grep -n "pane defect" docs/carplay/05_METADATA_AND_CONTROLS.md` → the banner line is unchanged.

- **docs/ops/05_AUDITS.md's Phase-2 banner over-closes.** It reads "⚠️ EVERYTHING IN THIS SECTION
  SHIPPED — do not treat any item below as outstanding work", but `R-50-2`'s evidence covers only the
  desk-side batch and the `Protocol/` deletion. Of the six items from that section this file found
  unshipped on 2026-08-16, two have since shipped (W9 BT MAC and `connect_seam` numeric-only, see
  Found closed). Four have not: I2 link dedupe, `CT_INPUT_NACK`, ocbmd `opt-level=2`, and the
  cross-language ChaCha20 KAT. Separately, its Finding-B inline note ("only fix direction 3 is
  unlanded") is off by one: direction 3 landed, direction 2 did not.
  Verified 2026-11-02: the `Verified open` commands in the sections above.

- **`crates/vendor/eld-codec/csrc/eld_shim.c`'s opening comment still narrates `AACENC_SBR_MODE` as
  "left at -1 (auto)"** eighteen lines above the call that pins it to 0. Historical narration of the
  bug, contradicted by the code below it. Verified 2026-11-02: `grep -n "left at -1"
  crates/vendor/eld-codec/csrc/eld_shim.c`.

Corrected at the source since the last check: the "iOS can never send `0x5204`" claim, in both
`docs/carplay/03_SDK_GROUND_TRUTH.md` §8 item 1 and the `message.rs` comment (marked REFUTED
2026-08-16). Dropped with the macOS app: the claim in its removed host doc that the box offered only
a single-touch descriptor.

---

### Unverified

Listed with exactly what was tried. None of these can be settled by reading the tree.

- **`R4` — "pre-auth gate (Apple 470)"** (`docs/ops/05_AUDITS.md` §"Hardware session 1"). docs/ops/05_AUDITS.md names the item only
  by tag, the underlying fix proposal is in no document in the tree, and no code carries the marker.
  Tried: `grep -rn "470" --include="*.rs" crates ccpa` → nothing; `grep -rn "pre-auth\|preauth" docs/*.md`
  → only docs/ops/05_AUDITS.md's own line. The one in-code "audit R4" tag (`session.rs`, a `/tmp` size cap) is a
  different finding, so the Phase-2 R-numbering does not map onto the in-code audit tags.
- **`R2` — SESSION-lock chip-op restructure** (`docs/ops/05_AUDITS.md` §"Hardware session 2"). The MFi lock
  discipline in `mfi_local.rs` looks sound (bounded non-blocking acquire, held across the whole
  stateful sign sequence), but nothing establishes whether that IS the restructure or the state it
  wanted changed. Tried: `grep -n "flock\|MfiLock::acquire" crates/vendor/wireless/src/mfi_local.rs`;
  the in-code "audit R2" tag is in `events.rs` and is a different finding.
- **Hardware-observation gaps that no grep can settle.** The ACL-drop outcome (plan_A test 3), the
  remaining app/phone checklist steps (plan_A §5 tests 2–8 and 10), and
  `docs/carplay/06_AV_PIPELINE.md`'s ○-marked audio formats (advertisable, decode not
  device-confirmed). Tried: nothing beyond reading the documents — settling any of them needs the
  box and a phone. (The app-pushed metadata tier, once on this list, is validated: see Found closed.
  `U9`/`U13b`, tags for macOS-app input changes, went with that app.)
- **`docs/carplay/02_SESSION_LIFECYCLE.md` §7 items 2–4 — three open protocol questions.** Whether `disableBluetooth` arrives before
  or after session-start on a real device; the literal bytes CINEMO puts in its first tunnelled
  message; and whether iOS honours a subscribe for an id the *tunnel* Identify declared but the *BT*
  Identify did not. Each needs a device capture or vendor observation, not a code read. Tried:
  `grep -rn "disableBluetooth" --include="*.rs" crates ccpa` → the handler exists, but nothing records
  its observed arrival order.

---

## Roadmap

<!-- absorbed: ../ops/04_OPEN_ITEMS.md -->

**History, kept for how each step was validated.** It was written when the host was the macOS app,
so steps 5–8 name its files. The GM app (`host/gm_ccpa`) replaced it, and the macOS app was removed
on 2026-10-27. What is open now is the defect index above and `08_FUTURE_TASKS.md`.

### Proven (on hardware)

- **OCBM transport** — `/dev/usb_accessory` bulk (VID `0x1314` PID `0x2d00`, IF0, EP IN `0x81` / OUT
  `0x01`, 512 MPS, USB 2.0 HS) *(**CORRECTED 2026-08-31** — this said `0x83`/`0x02`, which is what
  the gadget enumerated on an earlier build. docs/carplay/00_ARCHITECTURE.md §"Endpoint-address correction" is the authority
  and this line contradicted both it and §GM-AAOS further down THIS file. Do not hard-code either
  pair: enumerate. Live confirmation, every OCBM session this month: `IF0 bulk IN=0x81 OUT=0x01`.)*, **339 Mbps down / 90 Mbps up** *(**CORRECTED 2026-08-16** — this said
  "~335 / 222 up"; docs/carplay/00_ARCHITECTURE.md's measured table is the authority, and it explains the asymmetry as an
  i.MX6UL `acc_read` limit. The 220 Mbps figure elsewhere is a round-trip echo push, not a raw OUT
  rate)*, non-blocking priority scheduler. Channels
  live: CTRL `0x00` (incl. session-control SUBSCRIBE/heartbeat/presence), MFI `0x01` (chip service),
  CONSOLE `0x02` (root PTY), IP `0x10` (TCP/UDP mux), **FILE** `0x11` (verified binary deploy —
  `ocbm-host push`, md5-matched), **ETH** `0x12` (raw-frame bridge — diagnostic),
  **VIDEO/MEDIA_AUDIO** `0x20`/`0x21` (encrypted A/V forward), **ALT_AUDIO** `0x22` (voice sink,
  seam `:9003`), **METADATA** `0x23` (seam `:9004`), **ALT_VIDEO** `0x24` (cluster screen, seam
  `:9005`), **INPUT** `0x30` (HID uplink), **MIC** `0x31` (mic uplink), **MGMT** `0x40` (box
  management / the app's "CCPA" tab), **RTSP** `0x41` (app-driven SETUP relay, seam `:9106`), ECHO
  `0xFF`, DISCARD `0x0FFF`. `CT_SETTIME` clock-sync verified. Authoritative constants:
  `crates/ocbm-proto/src/lib.rs`.
- **Root + deploy** — open telnet/SSH over USB-NCM (no password set as shipped; no keys or certificates), UART console (persistent 115200), SPI programmer, live
  overlay iteration; plus OCBM `push` for binaries once `ocbmd` is running. **Persistent FHS install**
  (daemons in `/usr/sbin`, tools in `/usr/bin`, scripts in `/script`) + turnkey boot: a cold boot comes
  up with a live OCBM link, zero bootstrapping (reboot-proven). **CORRECTED 2026-08-16 — the installer:**
  the current path is `tools/ncm_base_install.sh` then `tools/ocbm_install.sh --full` (reversible trial
  before finalize; `--full` also places `btd` and the `radio_detect.sh`/`radio_hal.sh`/
  `radio_ap_up.sh` seam). `tools/install_fhs.sh` still works but is the older OCBM-era subset —
  `ocbmd`/`iap2d`/`carplayd`/`rx-connect` + `iap_role_switch` only, no wireless stack and no radio seam.
  See [`../ops/01_RECOVERY.md`](../ops/01_RECOVERY.md).
- **MFi** — genuine 2.0C coprocessor at `/dev/i2c-1 @0x11`, driven from userspace (`iap2d`).
- **Phone-side iAP2 handshake — reaches Identified** against a real iPhone (SYN-ACK → cert/0xAA01 →
  sign/0xAA03 → 0xAA05 → 0x1D01 → 0x1D02), and holds the link. Unblocked by the `link.rs::parse`
  coalesced-read fix.
- **Adapter pairing + key derivation** — `ccpa/carplayd` on `ncm0` completes pair-setup/pair-verify +
  MFi-SAP (local chip) and derives the ChaCha20 session key against a real iPhone. **Disk-backed
  PeerStore** (`/etc/carplay_peers.bin`) persists pairing — a known device reconnects pair-verify-only.
- **Forward-encrypted A/V** — the box forwards the **encrypted** video + audio + hands the per-stream
  key over OCBM (`CH_VIDEO`/`CH_MEDIA_AUDIO`); a Rust debug receiver (`ocbm-host avdec`) decrypted
  host-side: hundreds of video frames + thousands of audio packets, **0 decrypt failures**.
- **Session lifecycle** — host-app-driven (SUBSCRIBE → IDLE→projection → ARM; STOP/crash → TEARDOWN →
  holding pattern), hardware-validated across a reboot. Full spec: [`../carplay/02_SESSION_LIFECYCLE.md`](../carplay/02_SESSION_LIFECYCLE.md).
- **Foundation-hardening pass (2026-07-10)** — a 12-agent code analysis verified our crypto/protocol implementation against Apple's `CarPlaySDK` reference behavior and the published specification (confirmed byte-for-byte correct); the robustness
  findings it raised were addressed in follow-up work.
- **CH_ETH bridge** proved the iPhone's live link-local (mDNS/NDP) traverses OCBM to the Mac — kept as
  a diagnostic (not the A/V path in the committed model).
- **Receiver** — `ncm_carplayd/receiver_core` reached a sustained genuine CarPlay session (iOS 27),
  HEVC confirmed; reused (its pairing/session path now runs on the box).

### Building blocks (committed order)

1. ~~OCBM v1 framed multiplexer + channels~~ **DONE.**
2. ~~Phone-side iAP2 handshake to Identified~~ **DONE.**
3. ~~**Adapter pairing path.**~~ **DONE.** `ccpa/carplayd` reuses receiver_core's `ControlServer`
   (pair-setup/verify + `/info`) + `pairing`/`rtsp`/`mfi`, backed by a `LocalMfiSigner` on the local i2c
   chip. Full pair-setup → pair-verify → MFi-SAP → ChaCha20 session key ran end-to-end against a real
   iPhone; mDNS `_airplay._tcp` on `ncm0` via `rx-connect`. **Disk-backed PeerStore**
   (`/etc/carplay_peers.bin`) persists pairing — known device reconnects pair-verify-only.
4. ~~**Session key + encrypted A/V over OCBM.**~~ **DONE.** The box forwards the encrypted media
   untouched + hands the per-stream key over `CH_VIDEO`/`CH_MEDIA_AUDIO`; a Rust debug receiver
   (`ocbm-host avdec`) decrypted host-side (video + audio, 0 failures).
9. ~~**Session lifecycle & host-presence management.**~~ **DONE.** (**NOTE 2026-08-16** — this item and
   "Metadata" below are both numbered `9.` in the original list; the duplicate is left as written. Where
   the banner above says "item 9" it means **Metadata**.) Host-app-driven: SUBSCRIBE (CH_CTRL)
   → IDLE→projection (`projection_up.sh`) → ARM (`session_supervisor.sh` starts carplayd);
   STOP/crash → TEARDOWN → holding pattern (iap2d stays up). Live-UI = backpressure, not drop (per-stream queues,
   gated seam reads; see docs/carplay/02_SESSION_LIFECYCLE.md); ocbmd tracks presence via `/tmp/host_present`. Hardware-validated across a reboot.
   Full spec: [`../carplay/02_SESSION_LIFECYCLE.md`](../carplay/02_SESSION_LIFECYCLE.md).

**Roadmap items 1–10 below are all complete** (5/9/10 hardware-validated; item 8 wired 2026-08-08 and
wireless 2026-08-10). They are kept because the per-item notes record how each was validated; what is
still open is §4 of this file, not this list.

5. **Host app (THE critical path).** ✅ DONE 2026-08-01 (built + hardware-validated). Rebuild from `carlink_macOS` / `ncm_carplayd/macos` carplay-app:
   claim `1314:2d00`, SUBSCRIBE over CH_CTRL, decrypt with the handed-over key, **decode HEVC + render
   pixels** (VideoToolbox), play audio, and **send touch input back** (the input uplink — CH_INPUT,
   ✅ wired: `CarPlayView` touch → `OCBMClient.sendTouch` → CH_INPUT; ~~not yet exercised~~, corrected
   2026-08-01 — box-side touch/media HID arrival confirmed 2026-07-12). This replaces the
   debug receiver and closes the loop to a usable head unit.
6. **Nav voice / mic.** ✅ **DONE — corrected 2026-08-10 (this line was stale).** Nav voice DOES have
   its channel: `forward.rs` tags every AU on `:9003` (`[rate:u32 BE][ch:u16 BE][atype:u8][len:u32 BE][AU]`) and
   `crates/vendor/receiver/src/session.rs` routes **by `audioType`, not stream type** — media → media
   sink (:9002), everything else (telephony/speechRecognition/alert/default) → the voice sink (:9003)
   (search `Route by audioType`; ~`:871`/`:880` as of 2026-08-16). The mic
   uplink exists too: host `MicCapture.swift` → OCBM → carplayd's `MIC_INGEST_ADDR 127.0.0.1:9112`.
   **Owner-confirmed on hardware 2026-08-10: nav prompts audible, Siri invocable and spoken to, phone
   calls working BOTH ways.**
   ~~Nav voice on `:9003` has no OCBM channel yet; plus the mic uplink for Siri.~~
   STILL OPEN, and it is a DIFFERENT feature: **Enhanced Siri** (`AuxOut` 106 / `AuxIn` 107) — the
   always-on mic path with ECNR, the in-car historical ring buffer and the two mandatory detectors
   (authority: `wwdc2019-252.txt:86-134`). `session.rs` records AuxOutAudio / AuxInAudio /
   MainBuffered as unimplemented and therefore omitted from SETUP responses (search "Still unimplemented
   and therefore omitted"; ~`:1516` as of 2026-08-16). Button-Siri works; the always-listening path does not exist.
7. **YAML config consumption.** ✅ **DONE — marked 2026-08-10** (it was already shipped; this line
   simply never got its marker, which made the doc contradict its own "items 1-10 complete" note).
   App-side truth (`VehicleConfig.swift` + `SettingsWindow`), pushed subset consumed by
   `vehicle_config.rs` and applied per control connection by `carplayd::load_device_config` — runtime
   feature flip, no recompile; `base_device_config()` is the app-less fallback template.
8. **Move SETUP app-side.** ✅ DONE 2026-08-08 (wired, hardware-validated; the
   `accessoryConfig.appDrivenSetup` toggle default was flipped **ON** 2026-08-09) — commits `84d2b80` (P0 RTT harness + P1 box CH_RTSP relay
   + transparency proof), `692cc80` (P2 Rust-harness authoring + live oracle), `89c457b` (P3 Swift
   port). (**CORRECTED 2026-08-16:** the three hashes previously printed here — `c5af75f`, `b09aee7`,
   `3d56e28` — exist in no ref.) The host app
   drives the post-pairing SETUP/RECORD negotiation over the OCBM `CH_RTSP` relay; the box's local
   response is the always-available fallback. Latency gate PASSED (docs/carplay/02_SESSION_LIFECYCLE.md: p99 2.36 ms under A/V load,
   ~21× inside the 50 ms bar). Go/no-go recorded in **docs/carplay/02_SESSION_LIFECYCLE.md**. Box-driven SETUP (`receiver::session`)
   is now the selectable fallback — app-driven SETUP is the default on BOTH transports (wired flipped
   ON 2026-08-09, wireless 2026-08-10 by owner directive; the docs/carplay/04_CAPABILITIES_AND_CONFIG.md earned-fallback pattern done
   right). The selection gate is `appsetup() && seam_up()` — the `!wireless` term is gone. The
   wireless flip required a phase-1 feature-token union (the host preserves the box-only
   `iAPChannel`/`sessionManagement`); see docs/carplay/04_CAPABILITIES_AND_CONFIG.md for why stripping `iAPChannel` would have killed
   the iAP2 tunnel. **Remaining follow-up:** migrate those two tokens to per-transport app config
   (workstream C/D debt, recorded in docs/carplay/04_CAPABILITIES_AND_CONFIG.md).
   Current pick-up: the `iAPChannel` entry in the Wireless section above. This
   realizes the future-proofing target of README §7 (SDK-evolving SETUP logic app-side).
9. **Metadata** (NowPlaying / Nav) — ✅ DONE (surfaced via `features.rs`-generated params 6/7 + subscribes; see docs/carplay/05_METADATA_AND_CONTROLS.md). ~~not yet surfaced; likely needs a subscribe/command or a channel `AvSession` doesn't currently expose.~~
10. **Wireless CarPlay** (BT-RFCOMM iAP2 + WiFi SoftAP + credential handover). ✅ DONE for transport,
    pairing and A/V (see docs/carplay/05_METADATA_AND_CONTROLS.md). ~~**⚠️ The METADATA plane regressed 2026-07-31 → 2026-08-10** (a
    scid guard rejected the type-130 DataStream SETUP; tunnel stuck at `Init`, zero NowPlaying). Fix
    landed 2026-08-10, **not yet hardware-validated** — do not re-mark this line fully DONE until a
    soak shows `0x5001` records arriving.~~ **RESOLVED — the validation gate this line set was met on
    2026-08-11**: Identify accepted, `decryptFail=0`, NowPlaying and route/lane guidance arriving
    (README §Status, 2026-08-11). Regression evidence retained:
    `docs/ops/captures/2026-08-10_REGRESSION_datastream130_scid_rejected.txt`.
    ~~Rootfs radio scripts exist; handshake documented in `CPC200-CCPA_resources`. Later phase.~~

### Open design items

- ~~OCBM media framing: how encrypted stream payloads + the key handoff are carried.~~ **RESOLVED** —
  per-stream channels each carry a key handoff then raw encrypted frames; validated 0-failure host-side
  decrypt. (The video lanes additionally carry `SEAM_MAGIC` + a `u64 LE seq`, and the key is re-handed on
  every seam reconnect — see docs/carplay/01_OCBM_PROTOCOL.md §"Media transport" for the byte-exact framing.)
  ~~Still open: minimal RTSP framing the box relays vs the app parses (the SETUP box↔app boundary, incl.
  media-port coordination), once SETUP moves app-side (step 8).~~ **RESOLVED 2026-08-16 for the framing
  half** — it shipped with step 8: `CH_RTSP 0x0041` carrying the v1 `RS_OPEN`/`RS_REQ`/`RS_RESP`/
  `RS_CLOSE`/`RS_ERR` sub-frames (`crates/ocbm-proto/src/lib.rs`, constants authoritative in
  `receiver::relay`; wire spec in docs/carplay/01_OCBM_PROTOCOL.md §"RTSP channel (`0x0041`)"). **Still open: media-port
  coordination only** — the box pre-binds and its local response doubles as the oracle, so the app
  cannot yet choose the media ports (docs/carplay/01_OCBM_PROTOCOL.md §"RTSP channel" / §"Media transport").
- ~~`receiver_core` footprint on the 6 MB rootfs (crypto crates; run-from-tmp; size-opt/UPX) — measure
  the armv7-musl build (see [`../ops/00_BUILD_AND_DEPLOY.md`](../ops/00_BUILD_AND_DEPLOY.md)).~~ **RESOLVED** — measured
  2026-07-10 and re-measured 2026-08-16: the pairing/session path cross-compiles clean, and the whole
  shipped set is ≈**3.8 MiB unpacked** (`carplayd` ~1.71 MiB, `rx-connect` ~574 KiB, `iap2d` ~551 KiB,
  `btd` ~537 KiB, `ocbmd` ~443 KiB), roughly half that UPX-3.96-packed — comfortably inside
  the ~6 MB free, and run-from-`/tmp` was never needed. Numbers and method:
  [`../ops/00_BUILD_AND_DEPLOY.md`](../ops/00_BUILD_AND_DEPLOY.md).
- ~~**SETUP relay latency** — driving SETUP app-side relays the control connection box↔app over USB;
  measure it doesn't destabilize the timing-sensitive pair/SETUP/RECORD phase.~~ **RESOLVED 2026-08-08
  (docs/carplay/02_SESSION_LIFECYCLE.md): PASS** — p99 2.36 ms under real A/V load (upper-bound path), ~21× inside the 50 ms gate,
  zero timeouts/cap-clears. Go/no-go in docs/carplay/02_SESSION_LIFECYCLE.md.

### Host-app / integration (not adapter firmware)

- **GM AAOS USB permission prompt. Corrected 2026-11-02: this said "SOLVED and DEVICE-TESTED
  (2026-08-17)" by shipping the app under the package `android.car.usb.handler`.** That worked on a
  2024 Silverado (`gminfo37`) and was reverted on 2026-09-08: Play will not ship a reserved
  `android.*` id, and the Equinox has no such handler component. On the Equinox the per-drive Allow
  popup cannot be made persistent by a Play app (VERDICT 2026-10-23: both persistence paths are
  broken on the VCU image, and the grant is keyed to a device node that changes every boot). The
  driver taps Allow once per drive. Full account, refuted alternatives and the endpoint correction
  (`0x81/0x01`, not `0x83/0x02`): [`../host/01_ANDROID_AND_AAOS.md`](../host/01_ANDROID_AND_AAOS.md).
  Adapter side needs no change (the `0x1314:0x2d00` identity is already correct — docs/carplay/00_ARCHITECTURE.md).

### Contingencies (future — if/when measurements demand it)

- **Adapter-driven SETUP — ALREADY THE SHIPPED FALLBACK, not a future contingency** (corrected
  2026-08-10; it also cited the wrong step — app-driven SETUP is step **8**, step 6 is nav voice/mic).
  When `appsetup() && seam_up()` is false the box drives SETUP locally from the app-pushed config
  (the `else` arm of the `levers::appsetup() && receiver::relay::seam_up()` gate in
  `ccpa/carplayd/src/main.rs` — grep the condition, not a line number; that file moves often);
  `CARPLAY_APP_SETUP=0` forces it. Historical note: if app-driven SETUP (step 8) had added too much
  latency and risk session stability, fall back to having the **adapter drive the SETUP negotiation
  locally from the on-box YAML** (the config pushed down). The fallback stays bounded to rendering the
  app-pushed config — adapter-owned SETUP capability does not grow (docs/carplay/04_CAPABILITIES_AND_CONFIG.md directive 4). Keeps the negotiation next to the iPhone (no relay latency) at the cost of
  some future-proofing (the adapter would then carry SETUP logic that evolves with SDK versions). Only
  consider if the SETUP-relay-latency measurement above shows a real problem. See `README.md` §7.
- **Decode-on-adapter fallback** (deep contingency): the box could decode/re-emit typed A/V (the
  original CCPA model) — retained only as a last resort; the i.MX6UL has no VPU, so video is forwarded
  either way and this buys little.
