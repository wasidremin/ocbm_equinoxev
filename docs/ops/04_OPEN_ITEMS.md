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

**Re-verified 2026-11-02** against `main` at `d4b7bbf` and the car logs through the 2026-10-30
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

### GM app and the car (Equinox EV) — re-checked 2026-11-02

Car-side items. The owning document holds the detail; this list only says what is open. Play
high-water mark: versionCode 238 (`host/gm_ccpa/docs/14_LESSONS_LEARNED.md` §6).

- **Cluster album art sometimes missing.** Seen 2026-10-27, 10-29 and 10-30, all on 225 or
  earlier. Each title reached the car as two picture-less cards before its art, and an app restart
  replayed the same sequence. 226 (`4.0+art-hold`, published 2026-10-30) holds a card up to 500 ms
  for its picture. On its first drive (2026-11-02 morning) every title went out once, with its
  picture, and the owner confirms the cluster showed the art. A second cause was found that afternoon, still on
  226. The phone re-titled a playing item `<title> • <artist>` and back, without an art id. The app
  read each change as a new track and dropped the picture, which iOS never resends. The 10-27 11:06
  capture has the same shape. Fixed in 228 (2026-11-02, `isBylineVariant`), not yet seen on the car. A
  third cause on 230 (2026-11-05 evening): the next episode of a Spotify podcast came as a title
  with no picture at all, because the cover had not changed. The card stayed blank for six minutes.
  From 231, the previous picture is kept when no new one is named within 250 ms. Next
  step if it recurs: a granted `content://` art URI for `com.gm.rhmi`. Owner: `08_FUTURE_TASKS.md`
  T13 "A second mechanism" and "A third mechanism".
- **Vision launcher: misreads, logging-only.** iOS 27's square glyph can read UNKNOWN, so the
  Home button logs UNVERIFIED (re-cut the template from an iOS 27 capture). The VERIFY_FAILED on
  app taps that worked is explained (2026-11-03): on iOS 27 the glyph shows where the toggle leads,
  so an app opened from Home reads as the Dashboard. Grid taps stop using the glyph in 229
  (2026-11-04); a strip tap from such an app can still log VERIFY_FAILED. The moving-map fix from 227 is
  now exercised: under a route on 2026-11-03 22:47:21, Maps from the strip read "using current
  frame" and verified SUCCESS. Owner: `host/gm_ccpa/docs/14_LESSONS_LEARNED.md` §9 item 4.
- **CarPlay-screen layout loop — fixed in 227, confirmed on the car 2026-11-02.** `syncChrome`
  re-assigned the rail's `LayoutParams` on every root layout, which requested the next one. That
  morning it ran at 60 Hz all session: 180 `requestLayout() improperly called` warnings a second,
  96% of the upload. On 227 that afternoon there were five in 7.7 minutes of CarPlay, all while the
  activity and its surface were first laid out (17:13:36–17:13:48). Owner:
  `host/gm_ccpa/docs/14_LESSONS_LEARNED.md` §14.9 (correction).
- **EV status for Apple Maps (T7): Maps shows no battery.** The phone accepts the EV Identify
  and the status (2026-10-28, 11-03), and cluster turn-by-turn works with the setting on (11-03).
  The battery percentage reaches the phone, but the owner sees nothing about it in Maps
  (2026-11-04). The likely reason is that Maps shows charge only in EV routing, whose models Apple
  does not publish; unconfirmed without an iPhone log. The phone was also told CCS2 instead of
  the car's CCS1 (fixed in 229, 2026-11-04). Owner: `08_FUTURE_TASKS.md` T7.
- **HEVC render stalls — mitigated, cause unknown.** `HevcRenderer` releases the codec's output
  buffers when a queued frame has not rendered for `STALL_RESET_MS` (3 s), and logs
  `render stall Nms`. It fired on 2026-10-18 (25.0 s), 10-20 (4.2 s), 10-23 (47.8 s, 9.3 s),
  11-02 (10.3 s, 12:35:14, two seconds before an Apple Maps route loaded) and 11-05 on 229 (4.0 s
  at 12:14:02 and 3.3 s at 12:16:18, while driving, with no route). It fired again on 230 that
  evening (14.5 s, reset at 22:20:12, Spotify in front, no route). It did not fire on the 10-27 to
  10-30 drives. No root cause; the recovery is the watchdog. The 11-02 freeze happened during the
  layout loop above, which may or may not matter.
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
- **Admission path not yet proven on the car:** the storage-only USB announce
  (`14_LESSONS_LEARNED.md` §8). The warm-start relink after the 10-minute claim abort (§7 item 2)
  worked on 2026-11-05.
- **Calls while the app holds the assistant role:** no echo reported, not confirmed either way
  (`08_FUTURE_TASKS.md` T14, `host/gm_ccpa/docs/13_AUDIO_ROUTING.md` §4). The 2026-11-02 call
  (6 min 50 s, placed through Siri) peaked at -1 dBFS on the mic, against -8 to -17 dBFS before.
  That is one peak, not a level trace, so it does not tell echo from a loud voice. The same call
  logged 37 track underruns (about five a minute, as on 10-27), which the owner did not hear
  ("not choppy"), so they are recorded in doc 13 §4 and not worked.
- **Music muted for about 3 s after a call: fixed in 228 (2026-11-02), car-verified 2026-11-03.**
  iOS restarted media 0.2 s after hang-up (2026-11-02 17:20:58.7). The call sink held media's
  focus until its 3 s idle release (17:21:01.59), so the first 117 frames played at gain 0. CALL is
  now released about 0.6 s after the telephony stream stops once `phone=0` has arrived. On
  2026-11-03 all three calls released 0.46–0.57 s after `phone=0`. Owner:
  `host/gm_ccpa/docs/13_AUDIO_ROUTING.md` §4.
- **Music muted for about 4 s after an unanswered ring: fixed in 229 (2026-11-04).**
  2026-11-03 22:52:03: the ringtone (ALERT) sink waited out its 3 s idle after `phone=0`. It now
  takes CALL's release. Unverified on the car. Owner: `host/gm_ccpa/docs/13_AUDIO_ROUTING.md` §4.
- **Album art in the cluster while full-screen Maps is in front: works on the car (230,
  2026-11-05), misreads fixed in 231.** The app reads the screen every 2.5 s during a
  route. It ends the cluster's navigation while Maps' turn card is showing and restarts it when Maps
  leaves. On the first drive both top-bar launches switched the cluster at once. Three times the
  card was lost for 5–15 s with Maps still in front and nothing touched. A lost card now needs 20 s
  unless something was sent to the phone. Why the card's side edge failed is still open; the next
  misread logs it. The adapter also forwards iOS's own RouteGuidanceVisibleInApp flag, which needs a
  reflash. Owner: `08_FUTURE_TASKS.md` T13.
- **Which iPhone connects (T12): built 2026-11-05, not on the car.** Settings ▸ Phones gets ↑/↓
  for the connect order and Connect to move CarPlay to another paired phone (a wireless restart with
  that phone first). Both need adapter firmware with `MGMT_SET_ORDER`/`MGMT_CONNECT_DEVICE`
  (`tools/ocbm_install.sh --full`); until then the app shows a note instead. The app side is in 232
  (published 2026-11-06). Untested with two phones. Owner: `08_FUTURE_TASKS.md` T12.
- **CarPlay stayed dead after a walk-away with the accessories on (2026-11-06 17:53–18:01).** The box
  ended the session when the phone left, but its `PROJ_MODE NONE` never reached the app. The app kept
  "CarPlay live", so the walk-away reconnect never ran, and at 17:59 the screen came back black. Only an
  app restart brought CarPlay back. From 233 (published 2026-11-07) the app asks the box when the phone has been out of
  Bluetooth range for 20 s or the screen comes back with no picture, and ends a session the box no
  longer has. Why the frame was lost is open; 233 logs resync bytes once a minute. Unverified on the
  car. Owner: `host/gm_ccpa/docs/04_SYSTEM_MODEL.md` §4b.
- **Wheel answer button: Android sends it to the car's dialer.** Stock AAOS 14 keeps `KEYCODE_CALL`
  from every app and opens the dialer when Android's Telecom has no ringing call, and the iPhone is
  not on the car's Bluetooth. From 233 (published 2026-11-07) the wheel's voice button answers a ringing CarPlay call, a phone
  key that does reach the app answers or ends it, and every key and every focus loss during a call is
  logged. The next ring with a rocker press shows what GM does with the key. Owner:
  `docs/host/01_ANDROID_AND_AAOS.md` "The steering-wheel call key".
- **Slow starts on 2026-11-07.** From the adapter attaching to CarPlay live: 20 s, 35 s, 19 s and 81 s,
  6–9 s of each for the Allow tap. At 10:16 the phone reset the adapter's first Bluetooth connect and
  the box's reconnect loop waited its fixed 10 s (`BACKOFF_START_SECS`) to try again. At 11:19 the
  phone took the Wi-Fi handoff and never joined the AP; the app restarted the session itself after
  55 s. From 233 (published 2026-11-07) that restart runs 15 s after a silent handoff (`04_SYSTEM_MODEL.md` §4b).
  Adapter firmware, 2026-11-07 (on the adapter and bench-tested on the server; not yet driven): a reset page is retried 2 s later instead of 10 s,
  and `hciconfig piscan`, which took 10.0 s in 22 of 177 bring-ups in the car's logs, is a raw
  Write_Scan_Enable (`docs/wireless/01_BT_AND_RADIO.md`, ported from enigy/ocbm). Not done: why the
  phone skipped the AP, which only its own log would show. The AP lease file is now kept across AP
  restarts, which may matter there. Owner: `host/gm_ccpa/docs/04_SYSTEM_MODEL.md` §4b.
- **Album art late enough to miss the card's wait (2026-11-07 11:22, "Believer").** The phone named
  a cover with an empty image, then sent the real one 0.62 s after the title. The card's 0.5 s wait
  ran out, so the car got two cards without art and then one with it inside 0.13 s. Four of the 140
  title changes on record look like this (554–657 ms). From 233 (published 2026-11-07) the card waits up to 1 s. Unverified
  on the car. Owner: `08_FUTURE_TASKS.md` T13.
- **Features ported from enigy/carlink and enigy/ocbm (2026-11-07), in Play 234 (the pane's tap strip in
  235).** On the 2026-11-08 drive the lyrics lookup, the top-bar line and Now Playing by URL were used;
  the rest is not yet tried in the car.
  - *Top-bar Now Playing, Maps, Spotify and Phone open by command:* `CMD_REQUEST_UI_URL` on an adapter
    whose `HELLO_ACK` carries `CAP_UI_URL`, instead of finding and tapping the icon. Benched on the
    owner's iPhone with the recorded picture: `nowplaying:`, `maps:`, `spotify:`, `mobilephone:` (and
    `music:`, `podcasts:`, `messages:`) open their app; the full list is in `01_OCBM_PROTOCOL.md`.
  - *Cluster:* the road text leads with the exit ("Exit 273 · …") now that the adapter asks for
    ExitInfo; the app binds the cluster again by itself (3 times in 10 min at most) if the car ends the
    session mid-route; and it holds Android's NAVIGATION app focus while a route is up with the cluster
    setting on (`nav: focus` lines). What GM does with the focus is unknown.
  - *Settings ▸ Calls ▸ Echo test / Voice test* (parked) for `13_AUDIO_ROUTING.md` §4's echo question.
  - *Settings ▸ Display ▸ Media pane*: a pane with the song, its controls and timed lyrics, in any gear
    (it is for the passenger). On the 2026-11-08 morning drive (app 234) it never showed: the setting was
    Off, its default then (`pane: no split this session`), and the owner saw lyrics only in the top bar.
    So since 2026-11-08 a tap on the glass right of the picture (x 2630–2914 under the top bar) shows or
    hides it, the state is kept for the next drive, and the default is *Over CarPlay*: the pane is laid
    over the right 30% of the picture and nothing is sent to the phone. On the car 2026-11-08 11:13 the
    strip showed and hid the pane three times (Over CarPlay); the owner then chose Right, 40%, and the
    next sessions started split (CarPlay 1482x960, pane 988 px wide). On 237 at 12:00:24 a strip tap
    sent `CMD_VIEW_AREA 0` from the split: the box answered iOS with `updateViewArea` (500 ms), iOS
    sent its burst of unchanged parameter sets, and the main picture carried on (58 fps 10 s later, no
    decoder reset), so a host-sent switch is proven on the car.
    At the owner's request (2026-11-08) the art became a thumbnail beside the title (a sixth of the
    pane's height) and the lyrics show nine lines, three before the sung one and five after, in
    larger type (Play 237). Choosing a side instead declares a second view area (`initial: true`
    only when the pane was last showing), CarPlay on one side and the pane on the other; the strip then
    sends `CMD_VIEW_AREA`. Benched on the owner's iPhone with the app's exact document
    (2400x960, pane right 30%): iOS negotiated `viewAreas`, drew CarPlay in 1680x960 with the right 30%
    black, and put its own area toggle at the top of the dock. The pane's full-screen button
    (`CMD_VIEW_AREA 0`) and back were accepted by the adapter, but the one bench run that sent them
    recorded no picture after the switch; the car answered it on 2026-11-08 (above): the video carries on.
  - *Settings ▸ Playback ▸ Look up lyrics* (off by default; song names go to lrclib.net) feeds the pane;
    *Lyrics in the top bar* shows the current line there, in Park only.
  - Connection time on the new adapter firmware (startup fixes 5389084/749f72c), bench only: when the
    iPhone answered the first page, subscribe → first picture took 8.8 s and 10.4 s, against the car's
    10.7 s median on the old firmware (44 sessions since 2026-10-15, `[startup]` lines; fastest 8.1 s).
    Stage by stage the Bluetooth part is unchanged: discoverable 0.8 s after start, phone connected
    1.6 s later (car 1.0 s), handoff 2.6 s after that (car 3.0 s). The bench's failed sessions are not
    evidence either way: the phone was carried away from the server during them. Two cases are owed a
    car check: a page timeout of 5.2 s whenever the first bonded iPhone (the box holds two) does not
    answer, and the one split-document session that took 28 s from handoff to picture. App 234 logs the
    same `[startup]` stages, so the car's numbers on the new firmware come straight from its uploads
    (stage table: `host/gm_ccpa/docs/05_SESSION_FLOW.md` §9). First car session on it, 2026-11-08 07:50
    (adapter reporting `UI_URL`): subscribe → first picture 13.1 s, against the 10.7 s median. Bluetooth
    came up 3.0 s after subscribe and was discoverable 1.1 s later (the old median for the two together
    was 3.1 s), the first-paged iPhone answered 1.5 s after that (no page timeout), the handoff took the
    usual 3.0 s, and the picture came 4.5 s after the handoff (median 3.7 s). One session; not a trend.
  - Not taken, and why: `08_FUTURE_TASKS.md` "Left off this plan".
- **CarPlay map on the driver display: in Play 236 (2026-11-08), not yet tried in the car.** The idea is openautolink's
  ("driver-display maps", validated on a 2024 Blazer EV: GM VCU on AAOS 12L), re-implemented here
  without their GPL-3.0 code. Settings ▸ Navigation ▸ CarPlay map on the driver display (off by
  default): at subscribe the config declares Apple's cluster pair (`altDisplayPanels` +
  `altVideoStreams`, `initialURL: maps:/car/instrumentcluster/map`) at the driver display's own size;
  once CarPlay's picture is up the app opens `nav/DriverDisplayActivity` on that display, decodes
  `CH_ALT_VIDEO` into it and sends `CMD_NAV_START`. The box half shipped 2026-07-12
  (`docs/carplay/06_AV_PIPELINE.md` "VALIDATED 2026-07-12"). Emulator-checked on a simulated public
  display (window opens there, the main screen keeps focus); the emulator's own cluster displays are
  private, as the car's may be. Open, in order:
  1. ~~Whether the Equinox's VCU on AAOS 14 shows the driver display to the app at all.~~ **Yes, 2026-11-08
     11:11:** display 2, 2000x810, 220 dpi, flags `0x4083`, `window allowed` (also a private, off
     2103x774 display 11, unused). On the first try the iPhone streamed the cluster map (HEVC 2000x810,
     first frame 0.35 s after the window opened, 0 frames dropped): 62 frames before the window was
     hidden 2.4 s later, then, after a head-unit restart, 640 frames over 33 s before it was hidden
     again. Nothing GM sends the app says why a window is hidden. The first time was 0.6 s after
     Turn-by-turn in the cluster started navigation, the second 12 s after it ended it, so the
     owner's account of the driver display is the missing piece. Same day, a bug: that feature's
     bootstrap took the map window for "the screen in front" and launched the CarAppActivity on the
     driver display over the map (16:15:50); it now tracks main-display screens only.
     **The owner's account (same day):** the CarPlay map showed briefly, with the speedometer still
     visible, then GM's own Google map took the display back; cycling the driver display's pages did
     not bring it back, and worse, the map page itself could not be reached again (a head-unit restart
     followed at ~11:15). The window had been left stopped behind GM's map, and in the second session
     the bootstrap's CarAppActivity too. So from 237: the window closes the moment it is hidden, so the
     display is left exactly as GM had it; a long-press on the strip right of CarPlay opens it again
     (`DriverDisplay.reopen`); and Turn-by-turn in the cluster stands down while this is on
     (`ClusterNav.active`), as GM took the display back 0.6 s after its `navigationStarted`.
     **First drive on 237, 2026-11-08 11:58–12:01:** the window opened 13 s after the app started and
     stayed up for the whole upload (2,100 frames over 2 min, none dropped), including when a route
     started at 12:00:38: Turn-by-turn in the cluster stood down (no `navigationStarted`), and nothing
     took the display back.
     **Evening drives on 237 (19:50–21:12):** the window opened in all four sessions, within a second
     of CarPlay's picture. GM hid it twice: at 19:50:51, 6 s after a steering-wheel track skip, with
     CarPlay still in front on the main screen, so nothing reopened it; and at 20:04:37, together with
     the CarPlay screen itself, when GM's media service took the main screen. From 238
     (`nav/GmClusterPageService`, openautolink's "GM page-return handling" re-implemented): a service in
     GM's cluster category (`com.gm.intent.category.COREAPP`, `SupportedDisplay=IPC`) receives GM's
     `APP_SELECTED`/`FULL_MAP_SELECTED` page events, logs each (`GM page event … state= type=`), and a
     full-map page selected while no map window is open reopens it (at most 3 a minute). GM finds the
     service at boot: one head-unit restart after install. Emulator-checked with synthetic events;
     whether the Equinox sends them is the next upload's question.
     **First drive on 238, 2026-11-08 19:35 local (00:35 UTC 11-09):** the map opened 13 s after the app
     started and stayed up for the whole 5½-minute upload, none dropped; no page event arrived and none
     was needed. The 237 process had run until the update replaced it, so whether the head unit was
     restarted (needed for GM to find the service) is not known from the log.
  2. What the owner sees on 237: the picture, which page of the driver display it needs, whether GM's
     speedometer and gauges stay visible over the window (they must), and whether GM's own turn
     icons and head-up directions give way while it shows (openautolink's finding on the Blazer, so
     it may conflict with Turn-by-turn in the cluster). Their first enable also needed a head-unit
     restart (End Call held ~30 s while parked).
  3. ~~The map's safe area is the whole display for now.~~ **Directions area, 2026-11-08 (after 238,
     not on the car):** Settings ▸ Navigation ▸ Directions area on the driver display sets four edge
     margins (0–35% each) that become the cluster stream's `safeArea` at the next subscribe
     (`DriverDisplay.safeArea`, `VehicleConfigSpec.clusterSafe`); the map still fills the display.
     The box needed nothing: it already carries a real alt inset into `/info` and arms `viewAreas`
     for it (`vehicle_config.rs::the_gm_apps_driver_display_map_arms_the_alt_screen`). "GM layout"
     sets 28/9/15/17%, i.e. 560,72–1700,674, openautolink's Blazer default of 558,70–1694,668; Reset
     returns to the whole display. Each change is outlined on the driver display for 30 s while the
     map window is open (emulator-checked on a simulated 1600x600 display). Open: whether CarPlay
     moves its turn card and arrival time inside it on the car, and which margins clear the Equinox's
     gauges.
  4. **Held for adapter firmware (owner, 2026-11-08):** two more openautolink ideas need the box, so
     they wait with car GPS (the NMEA item below). CarPlay's status-bar edge (Auto / Bottom / Driver
     side): `info.rs::view_areas` hardcodes `viewAreaStatusBarEdge: 0` on both main areas; it needs a
     parsed `statusBarEdge` on `ViewAreaEntry` and an app setting. The car's make, model and year in
     Maps' vehicle name: param 20 subs 1/6 (and 8 `MapsDisplayName`) are the box's constant `name`
     (`message.rs` ~:695), so it needs the C-6 `accessoryName` bound wired into the tunnel Identify,
     where a rejected change costs the whole session (`0x1D03`).
- **USB bridge, for no Allow tap: built 2026-11-06, not on the car.** A sideloaded app named
  `android.car.usb.handler` (`host/gm_ccpa/netprobe_app/bridge`) gets the platform's silent grant at
  attach and relays the adapter to the Play app. Two unknowns, both answered only in the car: whether
  the Equinox lets Files install an app (the AAOS 14 emulator does not: its unknown-sources screen is a
  "not supported" stub, and other Equinox EV owners report installs refused as "unknown sources
  blocked" on current software, searched 2026-11-06), and whether the adapter attaches while the
  driver's user is in front. The app
  side (Settings ▸ Advanced ▸ USB bridge, Open Files, the bridge transport) is in 232 (published
  2026-11-06). Owner:
  `08_FUTURE_TASKS.md` T15; `docs/host/01_ANDROID_AND_AAOS.md` "The USB bridge".
- **Top-bar Now Playing tile: works on the car (228, 2026-11-03), too slow from an app.** The
  iOS 26 template matched on iOS 27.2 (0.919), and the tile shows the album art. From full-screen
  Maps it took two toggles and about 3 s, which the owner flagged. 229 (2026-11-04) ends each
  toggle's wait once it has landed (expect about 1.5 s to the icon tap); unverified on the car.
  From 234 (published 2026-11-07), on an adapter whose `HELLO_ACK` carries `CAP_UI_URL`, the tile sends
  `nowplaying:` instead and needs no toggle or icon; the vision launch remains for older adapters.
  Owner: `host/gm_ccpa/docs/14_LESSONS_LEARNED.md` §15 and §9 item 4.
- **4½ min without CarPlay at the 2026-11-04 start (asked the owner).** The 228 process, alive since
  the night before, raised the USB permission dialog at 11:58:53, 11:59:24 and 11:59:54 UTC. All
  three came back `granted=false` (2.4 s, 3 s, and 3 s after parking at 12:02:12). The Play update
  to 229 restarted the app at 12:03:02. Its dialog was granted in 1.7 s, and CarPlay was live at
  12:03:33. Whether the owner dismissed the dialogs or the car refused them is not known. The same
  log showed the EV status pusher retrying every 15 s against the dead link (115 lines in
  29 min). Since 230 (2026-11-05) it pushes only while subscribed. 229 did it again on the morning
  of 11-05 (every 15 s from 11:35 to 11:58); 230 had no failed push in its first 16 minutes. The
  11-05 start was quick: the screen came on at 11:58:22, the dialog was granted 3.4 s after the
  relink asked, and CarPlay was live at 11:58:40.
- **Noted on the 2026-10-28 drive, not yet written up anywhere:** about 17 s to the adapter's HELLO
  after a driver restart; a USB claim that stayed after the adapter was unplugged; Calibration runs
  `ScreenModel.measureUi` on the UI thread (0.3–1 s of jank).
- **`tools/ocbm_push.sh` runs only on macOS.** It uses BSD `stat -f%z` and `md5 -q`, and the owner
  has no Mac. Install with `tools/ocbm_install.sh` meanwhile.

### Box daemons — `ocbmd` / `carplayd` / `iap2d` / the supervisor

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

- **Accessory-side NMEA/GPS is parse-only.** (openautolink feeds the car's GNSS to CarPlay; the owner
  wants it, held 2026-11-08 until adapter work is next taken up.) The box decodes the phone's `StartLocationInformation`
  selection but nothing ever sends `LocationInformation` (0xFFFB). Planned as
  `08_FUTURE_TASKS.md` T6. `message.rs` now has a param-22 builder
  (`build_location_information_component`) that no Identify calls.
  Source: `08_FUTURE_TASKS.md` T6. Verified open 2026-11-02:
  `grep -rn "0xFFFB\|MSG_LOCATION_INFORMATION" --include="*.rs" crates ccpa` → the spec table,
  doc comments in `message.rs`, and `crates/vendor/metadata/src/location.rs` (a parser); no emitter.

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
  hardcodes `wlan0`.** `btd`, `bt_on.sh` and `ocbmd`'s `bt_name_from()` each derive a
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

## HANDOFF 2026-09-05 → 09-07 — CarPlay view-area resize: the rules, the matrix, and what is left

**STATE: the resize matrix is COMPLETE (9 ACCEPTED / 1 LOCKOUT, both orientations, floor to 4K).**
The loop that ran it unattended (arm the rect through the app's model fields, command the
transition over the control socket, judge the decoded frame) lived in the macOS app and its
`tools/va_*` harness, both removed on 2026-10-27; the GM app declares one view area. The rule set
and the measurements below still hold for any host. Of the Known-open list at the end, 1, 2 and 6
are done; 4 and 7 went with the macOS app; 3, 5 and 8 remain (re-checked 2026-11-02, not re-run).

Device-proven on a **CPC200-CCPA, iPhone18,4 / iOS 27.0 (24A5430a)**. The 09-05 work was **wireless**
CarPlay; everything dated 09-07 is **WIRED** (the phone is plugged into the box, so there is no
phone-side log in that arm at all). Where a claim is arm-specific it says so; the rule set below
holds in both.

### The rule set (settled)

1. **ALL FOUR values must be EVEN** — `x`, `y`, `width`, `height`. An odd value is a **TEARDOWN**:
   `kFigEndpointError_InvalidParameter -16720` originating in **`carEndpoint_copyScreenInfo:7001`**,
   propagating `setupScreenStreams:8536` -> `setupStreams:8957` -> `activateInternal:9987` ->
   `Activate_block_invoke_2:10117`. Isolated to one pixel: `356x400@240,760` renders,
   `357x400@240,760` tears down; `601x400@240,760` (odd w, far above any floor) tears down;
   `600x400@240,761` (odd origin Y only) tears down. Cause is almost certainly HEVC 4:2:0 chroma
   subsampling. **This retroactively explains every `-16720` in the project's history** — the old
   `1416x842@492,59` and `1600x842@800,59` failures both carry an odd origin Y and nothing else was
   ever wrong with them. Prior explanations (size floor, "must touch a vertical edge",
   `focusTransfer`, `cornerMasks`) are all REFUTED; see docs/carplay/06_AV_PIPELINE.md.
   **SCOPE, narrowed 2026-09-07 (wired, 4K panel): parity binds what we DECLARE, not what iOS puts
   back on the wire.** iOS's own transition animation streams odd rects freely — 275 of 292 geometry
   records in one round trip carried an odd origin or extent (`3826x2152@7,4`, `3834x2158@3,1`) with
   no teardown — and a SETTLED rect can be odd too (see "What the wired arm added" below). Do not
   "fix" a harness or a validator to reject those; the gate is on the declaration.
2. **Containment** — `x+w <= panelW && y+h <= panelH`. Violation is a TEARDOWN.
3. **PRODUCT FLOOR (owner, non-negotiable): 800x480 landscape / 480x800 portrait**, CarPlay AND
   Android Auto, orientation chosen by the area's own aspect. This OVERRIDES what iOS tolerates.
4. **No origin constraint** beyond parity + containment. No evenness-of-position rule beyond (1), no
   edge-contact requirement (a floating portrait area touching no edge renders). **Re-proven
   2026-09-07 on a 3840x2160 panel over WIRED CarPlay, which is where the free origin was previously
   only assumed**: `1280x720@200,180` (off-centre upper-left), `800x480@3040,1680` (flush into the
   bottom-right corner, `x+w`/`y+h` exactly equal to the panel) and `2560x1440@640,360` each
   transitioned and settled at exactly the armed rect, no refusals, no teardowns. Diagonal offsets
   are not a special case — the rule really is: even, contained, at or above the floor, anywhere.
5. `cornerMasks` and `viewAreaSupportsFocusTransfer` are both **orthogonal** to acceptance.

**iOS's own tolerance, for protocol understanding only — NOT the shipping rule.** Measured floors on
a 1080x1920 panel: width **350**, height **304** (`350x400` renders, `348x400` locks out;
`600x304` renders, `600x302` locks out). The extracted `385 * 0.65 * scale` / `240 * 0.65 * scale`
formula is REFUTED as the gate — it mispredicted `480x400`, `400x400` and `384x400`, all of which
render. Do not resurrect it.

**Two failure classes, never conflate:** an odd/oversized/non-positive value is a **TEARDOWN**
(session dies). Below the floor is a **LOCKOUT** — `[DBLockOut] lockOutMode updating to
viewAreaTooSmall`, string `LOCKOUT_VIEW_AREA_TOO_SMALL_MESSAGE` -> "CarPlay does not support this
display resolution." Black area, session SURVIVES.

### What the WIRED arm added (2026-09-07)

Same box, same iPhone, but the phone is plugged into the BOX — so there is no phone-side log at all
(docs/ops/02_TESTING.md). Every verdict below came from the app's control socket, the app log and the
pixels. The matrix now runs unattended end to end, with no human in the actuation path:

- **The resize button is the pill at the TOP of the CarPlay Dock rail** — two arrows pointing inward,
  above the clock, not among the app icons. Pressing it from the socket works:
  `tap 133 179` (the app's 0..10000 space, area 0 on a 1200x675 window) -> the phone sends
  `requestViewArea index=1` -> the box answers `updateViewArea index=1` -> the picture animates into
  the sub-rect. **The button moves WITH the area**, so the return press is at different coordinates
  (`tap 4109 4039` for `800x480@1520,840` in a 3840x2160 panel). A harness cannot use one constant.
- **A settled rect is not always the declared rect, and it can be ODD.** `1920x1080@1900,1060` settled
  at `1921x1081@1899,1059` and STAYED there — polled every 2 s for 24 s, geometry-record counter
  frozen at 154, so the animation had finished. The far edges landed exactly (`1899+1921 = 1900+1920
  = 3820`); iOS converged one pixel out toward the origin. Three other rects the same session landed
  byte-exact, so this is a per-rect rounding artifact of the transition's final interpolation step,
  not a correctable offset. **Compare settled rects with a ±2 px tolerance** (`rect_close` in
  `tools/va_wired_sweep.sh`); exact string equality scores a good case NO-MOVE.
- **iOS self-transitions at session start.** Unprompted, it shrank into area 1, held ~2.4 s, and grew
  back to area 0 (answered `updateViewArea -> index=0`). A harness that samples one geometry record
  instead of the settled rect reads this as a pass.
- **THE LOCKOUT FLOOR SCALES WITH THE PANEL — the product floor is not panel-independent.**
  `480x800` inside a **2160x3840** panel LOCKS OUT: iOS renders
  `LOCKOUT_VIEW_AREA_TOO_SMALL_MESSAGE` ("CarPlay does not support this display resolution") and the
  session survives, exactly as the two-failure-classes rule predicts. But the wireless measurements
  above found areas as small as `350x400` RENDERING on a **1080x1920** panel — the same panel at half
  the linear size. So the same rect can render on one panel and lock out on a bigger one, and
  `480x800` — our own declared portrait product floor — is NOT safe on a 4K portrait panel. On
  2160x3840 the floor lies between `480x800` (lockout) and `720x1280` (renders, mean luma 76.6);
  it has not been bisected. This does NOT resurrect the refuted `385 * 0.65 * scale` formula — that
  mispredicted specific rects and stays refuted — but it does mean any floor quoted without its
  panel is meaningless. Bisect before treating 800x480/480x800 as universally shippable.
- **A LOCKOUT is not a black area — do not detect it by luminance.** The banner card is sized to
  FILL the view area, so the armed rect reads mean luma 40.0 / darkFrac 0.01 (content!), while the
  panel *around* it is 95% dark. `tools/va_wired_sweep.sh`'s first thresholds (`BLACK_MEAN=12`,
  `BLACK_DARK=0.85`) therefore scored a textbook lockout as ACCEPTED, and only an eye check caught
  it. The usable discriminator is CHROMA, not brightness: a real CarPlay UI is full of coloured app
  icons, the lockout card is flat achromatic grey with white text.
- **`animationDurationMillis` is honoured literally.** Against the hardcoded 3000 we send, measured
  **2.978 s** (shrink) and **2.988 s** (grow) at ~48 geometry records/s. It is a real duration, not a
  hint. Its LIMITS are unmeasured — see "Known-open" below.

### Tooling left behind

The view-area harness (`tools/va_limit_probe.sh`, `va_limit_sweep.sh`, `va_wired_sweep.sh`,
`winshot.sh`) drove the macOS host app and was removed with it on 2026-10-27; git history has it.
The lessons it encoded still hold for any rebuilt harness:

- **Three harness defects the first runs exposed, all the same shape — a case that LOOKS measured
  and is not. Do not reintroduce any of them:**
  1. **Triggering before the geometry settles.** iOS runs its own view-area round trip at session
     start, so a trigger fired in the same second the session comes up lands inside that animation
     and is swallowed; the BEFORE frame is a picture of a rect nobody asked for. `settle_geometry`
     waits for the `changes` counter to hold still.
  2. **Reading `[backfill]` as this session's evidence.** The app replays box log history on
     connect, tagged `[backfill]`. A backfilled `host viewArea index=1 REFUSED` from the PREVIOUS
     case decided `P-720x1280`'s verdict. Verdict-producing scans go through `live_log`.
  3. **Trusting a panel the app silently clamped** — the `PanelRule` bug below. The sweep now
     compares `armed.panel` against what the case asked for and fails it REFUSED-LOCALLY with
     "measures nothing" rather than measuring a rewritten panel.
- **The `PanelRule` bug this cost, fixed 2026-09-07.** The app's panel envelope was per-axis
  (`VehicleConfigModel.minHeight/maxHeight` 480–2160), so `set height 3840` was silently clamped to
  2160 on `save`, every portrait area failed containment against a 2160-tall panel, the emitter
  dropped `viewAreas[1]`, and the box declared one area — while the landscape half passed 5/5 and
  the run looked healthy. The envelope is now `PanelRule` (orientation-agnostic, floor by the
  panel's own aspect) and clamps are reported (`clampNotes` in `save`/`get viewarea`,
  `pendingClamp` in `set`) instead of being silent.

### The matrix — COMPLETE as of 2026-09-07 (wired)

Both orientations, floor to 4K, **9 ACCEPTED / 1 LOCKOUT**. Every case armed through the app door
(`viewarea arm` + `save`, no bench lever) and triggered by `viewarea request` (no tap, no human).
PNGs per case under `/tmp/valog/wired/<label>/`.

| Panel | Area | Verdict |
|---|---|---|
| 3840x2160 | 800x480@1520,840 | ACCEPTED (also OWNER-CONFIRMED by eye, wireless, 09-05) |
| 3840x2160 | 1280x720@1280,720 | ACCEPTED |
| 3840x2160 | 1920x1080@960,540 | ACCEPTED |
| 3840x2160 | 2560x1440@640,360 | ACCEPTED |
| 3840x2160 | 3840x2160@0,0 | ACCEPTED (area [1] == area [0]) |
| 2160x3840 | 480x800@840,1520 | **LOCKOUT** — below iOS's floor FOR THIS PANEL |
| 2160x3840 | 720x1280@720,1280 | ACCEPTED |
| 2160x3840 | 1080x1920@540,960 | ACCEPTED |
| 2160x3840 | 1440x2560@360,640 | ACCEPTED |
| 2160x3840 | 2160x3840@0,0 | ACCEPTED (area [1] == area [0]) |

Plus four off-centre landscape cases proving the free origin at 4K (see rule 4):
`1280x720@200,180`, `800x480@3040,1680` (flush into the corner), `2560x1440@640,360`, and
`1920x1080@1900,1060` (the ±1 settled-rect case).

**The largest panel previously driven was 2400x960**, so 4K panel config in BOTH orientations is
new. The only failure is the portrait floor case, and it is a real device result, not a harness or
config fault — see the panel-scaling finding above and Known-open 8.

The WIRELESS matrix (`tools/va_limit_sweep.sh`) is still one case deep. Nothing here contradicts it —
wired and wireless differ only in carrier — but if a wireless-specific view-area difference is ever
suspected, that is the run to complete.

### Known-open, in priority order

1. **DONE — `ViewArea2Rule` corrected and verified.** Carries the owner product floor
   (800x480 landscape / 480x800 portrait, orientation by the area's own aspect) and the parity rule,
   reported in severity order: containment, parity, positivity, floor. The refuted
   `385 * 0.65 * scale` derivation is gone. `run_tests.sh` 1300 passed; receiver PASS; docs OK.

   **Parity is enforced in the APP ONLY, and that is deliberate — do not "fix" it box-side.**
   CLAUDE.md's design doctrine: anything configurable about CarPlay is app-driven, the box presents
   app-pushed config, and box placement is EARNED (measured app-driven failure + owner approval),
   never designed-in. A box-side parity gate was written, tested and then REVERTED on owner
   direction — the app is where the logic and the options live. There has been no measured
   app-driven failure to earn a box-side copy. The app-less `CARPLAY_VIEWAREA2` bench lever
   therefore still accepts an odd rect and will tear the session down; that is a BENCH tool used by
   an operator who now knows the rule, not a product path.

2. **DONE — transition path commandable, VERIFIED ON DEVICE 2026-09-07.** `:initial` proves iOS
   accepts STARTING in an area; it does not prove iOS animates INTO one on request. Owner-confirmed
   twice by eye — `600x400` on a 1080x1920 panel, and `800x480` inside 3840x2160. The clean fix
   landed: `viewarea request <index>` on the ControlServer → `CMD_VIEW_AREA 0x11` →
   carplayd → `events::switch_view_area`, the same policy function that answers the phone's own
   `requestViewArea` (the removed docs/host/00_MACOS_HOST_APP.md, "Bench control surface"). Two companions for
   the WIRED arm, where there is no phone log: `viewarea arm <WxH@X,Y>` writes the rect through the
   model's own fields (replacing `defaults write` against a stopped app and the `/tmp/carplay_viewarea2`
   lever), and `shot [path]` dumps the decoded frame as PNG — the only LOCKOUT detector this side owns.
   **The rebuilt carplayd IS pushed** (2026-09-07, `tools/ocbm_push.sh … /usr/sbin/carplayd 755`,
   md5 verified against the local cross-build; ocbmd unchanged). Device-proven the same evening:
   `viewarea request 1` → `[events] host viewArea index=1 accepted` → `updateViewArea -> index=1
   duration=3000ms` → the rect settles at the armed area; `request 0` returns; `request 5` is
   REFUSED box-side (`only 2 area(s) declared`) with the session healthy. All ten matrix cases ran
   on this path.
   **This also proves the accessory can drive a transition UNILATERALLY** — nothing asked. The
   earlier finding was that the phone's `requestViewArea` is advisory and the accessory is the
   authority (events.rs); this extends it: no request need exist at all. An old carplayd logs
   `unknown INPUT_COMMAND 0x11 — dropped`, so a box that has not been updated needs
   `TRIGGER_MODE=tap` in the sweep, or the trigger silently looks accepted while nothing moves.
3. **Supervisor teardown/bring-up is not serialised** and reports success for a stack that is dead
   6s later — this is the same defect as the "Restart Wireless stack" button being a destructive
   no-op. Two fixes: serialise on child reap, and verify the bring-up instead of asserting it.
4. **Gone with the macOS app (2026-10-27).** `viewArea2*` keys were UserDefaults-only, so
   Import/Export did not round-trip them.
5. `initial` is parsed box-side and, since 2026-11-07, authored by the GM app's media pane (`initial: true`
   on the second area; since 2026-11-08 only when the pane was showing at the end of the last drive).
6. **CLOSED 2026-09-09 — `animationDurationMillis` is fully characterised; there is no iOS floor or cap.** Swept on hardware (CPC200-CCPA, wired, 1920x1080, second view area): **10 ms renders the switch as an instant flicker; 10000 ms runs the full 10 s.** Honoured literally at both extremes. One nuance worth keeping: at 10000 ms the UI reaches the target geometry at roughly 5 s and then idles showing CarPlay's transition blur until the envelope expires — the duration is the ANIMATION window, not the geometry change, so the accessory gets its settled geometry earlier than the declared duration. Untested: whether a larger area delta occupies more of the envelope instead of finishing early. The shipped range is **100–10000** since 2026-11-07 (enigy/ocbm's floor, ported with the GM app's media pane, whose switch takes 500 ms); from 2026-09-09 it was 1000–10000 by owner decision, when sub-second was judged abrupt in a car. Either way a product limit, NOT a protocol one; 10 ms works and is simply not offered. The original text of this item follows for the record.

   ~~**`animationDurationMillis` limits are unmeasured; its FLOOR is the open question.**~~ (Corrected
   2026-09-09 — it was "hardcoded 3000, not yet a variable"; both halves are now false.) On the bench
   that night 3000 → 1000 was DEVICE-PROVEN to visibly speed up the Dock resize, the first time anyone
   varied it on hardware, and it is now a pushed value: top-level `view_area_anim_ms` in the vehicle
   config (our extension, `wifi_ap`-shaped; absent = 3000, the app emits it only when it differs),
   parsed by `receiver::vehicle_config`, armed per connection into `levers::view_area_anim_ms`, and
   consumed by `events::switch_view_area` for BOTH the inbound `requestViewArea` answer and the
   `viewarea request <index>` door. Out of range CLAMPS to 0..=10000 (logged by carplayd, never a
   rejection), so the ladder below past 10000 — 30000, 60000, `INT32_MAX`, a negative — is NOT
   reachable from the app without widening that clamp deliberately; 0, 1, 100, 250, 1000, 3000, 10000
   are. iOS never acknowledges the field, so the `[events] updateViewArea -> … duration=Nms` line
   (post-clamp value) plus a stopwatch on the geometry stream is the whole measurement. What the
   sources give: `CarPlaySDK.framework` carries the key name only;
   CarKit has an explicit clamp message for the INDEX (`Resetting to first view area … out of range`)
   but **no duration-validation string anywhere in the iOS 27 CarKit or AirPlaySender extracts**;
   R14G17 predates view areas entirely; CINEMO logs it as `duration: %ums`, unsigned, so a negative
   is outside a shipping vendor's own model. Apple's Simulator and WWDC 2019-252 both use 3 s.
   Since iOS honours the value literally (measured above), the ladder worth running is 0, 1, 100, 250,
   1000, 3000 (known good), 10000, 30000, 60000, plus `INT32_MAX` and a negative — watching for
   whether 0 snaps or is refused, whether a long duration blocks a second `requestViewArea` mid-flight,
   and whether anything tears the session down. The per-value recompile that ladder needed is gone
   (push a config with `view_area_anim_ms: N` and reconnect); a per-request `[ms]` argument on the
   `viewarea request` door would only matter for sweeping values within one session.
7. **Gone with the macOS app's harness (2026-10-27); the lesson stands for any rebuilt one.** The sweep's LOCKOUT detector was WRONG and scored a lockout as ACCEPTED. Device-proven
   2026-09-07 on `480x800@840,1520` in a 2160x3840 panel: `BLACK_MEAN=12` / `BLACK_DARK=0.85` never
   fired because **the lockout card is sized to FILL the view area** — the armed rect reads mean luma
   **40.0**, darkFrac **0.01** (i.e. content), while the panel *around* it is 95% dark. The verdict
   was caught only by looking at the PNG. No luminance threshold can fix this: a real render and a
   lockout are both "not black". The discriminator is **CHROMA** — a real CarPlay UI is full of
   coloured app icons, the lockout card is flat achromatic grey with white text. `tools/winshot.swift`
   `stats` needs a saturation metric and the classifier needs to use it. Until then, **every ACCEPTED
   verdict near a floor must be eyeballed** against `$OUT/<label>/after_frame.png`.
8. **Bisect the lockout floor per panel — the shipping floor may be wrong.** Still open; it needs a rebuilt harness, and the Equinox panel is landscape, so the portrait case does not bite this car. `480x800` locks out on
   2160x3840 but the wireless data has `350x400` RENDERING on 1080x1920, so the floor scales with the
   panel and our declared portrait product floor is not safe on a 4K portrait panel. On 2160x3840 it
   lies between `480x800` (lockout) and `720x1280` (renders). Bisect both orientations at 4K, then
   decide whether the product floor stays a constant or becomes panel-relative. Do item 7 first — a
   bisect run on a detector that cannot see a lockout produces a confidently wrong number.
