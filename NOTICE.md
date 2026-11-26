# Third-party material and reference points

The code in this repository is original work, except the public-domain ports listed under
"Ported from public-domain projects" below. Several external sources were used as *reference* —
to learn a wire format, a constant, or a behaviour — and are recorded here so the distinction is
on the record. (Corrected 2026-11-12: this said nothing was copied from another project, which
stopped being true when the GM app took ports from the projects below.)

## Ported from public-domain projects

These are released under the Unlicense, like this repository, and the ported files say what they
came from in their header comments.

- **lvalen91/carlink** (github.com/lvalen91/carlink) — the CarPlay-to-GM maneuver table, the trip
  shape and the primary-session logic behind cluster turn-by-turn (`nav/`).
- **enigy/carlink** (github.com/enigy/carlink) — the media pane's feature set and the timed-lyrics
  client and cache (`pane/`, `lyrics/`), the echo and voice call tests (`calltest/`),
  navigation-focus claiming, and parts of the split-screen view-area configuration.
- **enigy/ocbm** (github.com/enigy/ocbm) — adapter-side startup fixes and the route-guidance
  additions (exit labels, junction and lane detail).
- **enigy/ocbm**, the connect timeline (2026-11-20, from 801074f, OCBM 2.6.1 and 2.6.5):
  - `crates/box-common/src/timeline.rs` itself;
  - the session record's radio carry-over (`crates/vendor/wireless/src/timeline_marks.rs`);
  - `ocbmd`'s origin stamp;
  - the scripts' stamp helpers;
  - the format and keys of the `[timeline] v1` line.
  `carplayd`'s own stamps (`ccpa/carplayd/src/timeline_own.rs`) and the receiver's hook are written
  here after its design.
- **enigy/ocbm**, the boot radio preload (2026-11-26, from OCBM 2.7.0 and 2.7.1, `v2.7.1`): the idea of
  starting the radios beside the USB gadget behind a mark for the stock GPIO set-up's end, the wait for
  the SDIO card before detection, one Bluetooth attach with no reset in the preload, its helper
  stopped on failure, and the two-boots-in-a-row guard with its `/script/radio_preload_off` file.
  `radio_hal.sh`'s `do_boot_preload` is written here after that design; no code was copied.
- **enigy/ocbm**, the access point and the host link (2026-11-21, from 801074f):
  - the AP's DHCP server as BusyBox's `udhcpd` with a 250 ms ARP probe and `auto_time` (a009f90,
    c4679c0);
  - its stop, which waits for the last server to exit, and this AP's own server judged by its pid
    (d590834), in `ccpa/rootfs/script/radio_ap_up.sh`, once, where enigy keeps a copy per script;
  - `hostapd` in the foreground so its station events reach the box log, confirmed by its own
    `AP-ENABLED` (c05d066, 39f7a13);
  - `ocbmd`'s re-key of the A/V seams when the same host re-attaches over a live session, with its
    four tests (76d0c71).
  `tools/tests/radio_ap_test.sh` is written here after enigy's `tools/radio_ap_up_test.sh`.
- **enigy/ocbm**, route A, the phone's video inside CarPlay (2026-11-21, from 801074f, OCBM 2.0):
  - the offer (`/info`'s `videoPlaybackInfo` and `playbackCapabilities`, the SETUP echo) and the log's
    shape helpers, from `probe.rs`, in `crates/vendor/receiver/src/video.rs`;
  - the shared-DataStream relay and its JSON reader (`playback.rs`) and the tunnel to the phone's proxy
    (`tunnel.rs`), each in the file of the same name;
  - a partial TEARDOWN that stops only the DataStream it names (a86e552);
  - the stream message on the event channel, carplayd's `INPUT_DATASTREAM`, `INPUT_TUNNEL` and
    video-allowed handling, and ocbmd's `CH_TUNNEL` queue, with their tests.
  Here the offer waits for the app's `enablesVideoPlayback`, a URL's host never reaches the log, and
  the phone's `setProxyParameters` reaches the app by a sender that waits for the seam.
- **enigy/carlink**, route A's app half (2026-11-21, from a04f4c0, `com.carlink.video`): the play queue
  (`VideoPlayback`), the player (`ExoVideoEngine`, `VideoEngine`), the phone's proxy offer, its TLS-PSK
  handshake, the tunnels, the HTTP/1.1 client, the origin's TLS and the Media3 data source
  (`video/proxy/`), and their tests, in `host/gm_ccpa/.../video/`. The screen (`VideoOverlayView`) and the
  wiring (`RouteA`) are written here after enigy's Compose overlay and `CarlinkManager`. Here the log
  carries no part of a video's address or host.

- **enigy/ocbm** and **enigy/carlink**, the Wi-Fi link (2026-11-21, mammut3's PR #2 to each: enigy/ocbm
  b76d210, enigy/carlink aca4733): `crates/ocbm-link` (OCBW v1) unchanged in its bytes; `ocbmd`'s
  `wifi.rs` (the provisioning, the race, the TCP relay, with their tests) and `mdns.rs`; the app's
  `ocbm/OcbwLink.kt`, `WifiBulkTransport.kt` and `OcbwLinkTest.kt`; `WifiLink.kt`'s discovery after
  `WifiBoxFinder.kt` and `WifiLinkStore.kt`; `ocbm-host`'s `OCBM_TCP`. Here the key travels alone and
  applies at the adapter's next start, the race runs on one socketpair, the client's keeper is
  `radio_hal.sh`'s (`sta_keep`, after enigy's `wifi_sta.sh`), the app also sweeps the hotspot's subnet,
  and no address reaches a log.
  **Removed 2026-11-25** (T15): none of it is in the tree now; it is in git history at `1b25f52`.

## Built from upstream source

- **wpa_supplicant 2.11** (w1.fi, BSD licence) and **libnl 3.12.0** (github.com/thom311/libnl,
  LGPL-2.1, linked statically into it for its nl80211 driver): the adapter's Wi-Fi client for joining
  the car's hotspot (T15), shipped in the adapter firmware from 2026-11-15 and **no longer built or
  shipped since 2026-11-25**. Adapters that took it keep the binary. As the LGPL asks of a static
  link, the build script, which downloads both releases, checks their pinned SHA-256 and builds them
  unmodified, stays available to relink it: `git show 1b25f52:tools/build_wpa_supplicant.sh`. The
  build followed **enigy/ocbm** PR #2 (github.com/enigy/ocbm/pull/2, Unlicense).

## Ideas, no code

**openautolink** (github.com/mossyhub/openautolink, GPL-3.0) — showed on a Blazer EV that GM
exposes the driver display to an ordinary app, and how GM's cluster page events can bring a map
back. The GM app's driver-display window, page-return handling and default directions area were
written here from that description; none of its code is used.

**egmp-carplay-ev-routing** (github.com/isaacwisdom/egmp-carplay-ev-routing, GPL-3.0-or-later) and
**evmaps** (github.com/augard/evmaps, no licence stated) — between them showed that Apple Maps
plans charging stops once a CarPlay accessory sends a charging and a consumption model, and
documented the shape of those two JSON documents and the model ids Maps accepts. The adapter's
relay and the app's models for the Equinox EV were written here from those facts; none of their
code or vehicle data is used.

**enigy/ocbm**, update design (github.com/enigy/ocbm, Unlicense) — `ccpa/installer/ocbm_update.sh`
and `ocbm_dispatch.sh` showed how an adapter update survives a power cut and a car switched off:
place every file before any rename, journal before the swap, undo an unplaced journal at boot,
start the trial's clock only when a host connects, and count flash at 0.7 of a file's size.
`ccpa/rootfs/script/fw_apply.sh` follows that order, written for this tree's per-file staging.

**mib2q-carplay-rgi** (github.com/luka-dev/mib2q-carplay-rgi, GPL-3.0) — the field layout of
Apple's `PASCD` wheel-speed sentence was taken from it as a fact. None of its code is used.

**lrclib.net** — a public lyrics service the app queries at run time when "Look up lyrics" is on.
No lyrics are stored in this repository.

## Referenced, not incorporated

**aasdk** (`github.com/f1xpl/aasdk`, GPL-3.0-or-later) — an open-source Android Auto
head-unit library, used as a starting reference for the AA transport. In practice its approach
did not work as-is against a current phone, so the implementation here was arrived at by
iterating variants until the wire behaviour matched, and several of its behaviours end up
deliberately opposed to aasdk's:

- **TLS framing.** aasdk reassembles a fragmented message's *ciphertext* per channel before
  decrypting. Following that shape produced a session that died every 57-94 s with
  `errSSLDecryptionFail`, because one TLS stream is shared by every channel and the phone
  interleaves them mid-message. This implementation decrypts each frame as it arrives and
  accumulates *plaintext* per channel instead.
- **Channel ids** follow the decompiled gearhead app's ordinals, not aasdk's — `SENSOR = 1`
  and `INPUT = 8` here versus `2` and `1` there.
- **Flow control.** The video window is advertised as `max_unacked = 64`; the stop-and-wait
  value wedged video after about two minutes over a higher-latency relay.
- **Protocol version.** 6.1 is requested rather than 1.7, because the phone selects its
  keyframe interval from the version the head unit asks for.

What is genuinely shared is the wire format itself — frame flags FIRST/LAST/BULK, the
encryption and message-type bits — which are protocol facts every correct implementation
carries identically. No aasdk code is present here, and the AA client is Rust and Swift with
no structure in common with aasdk's C++. aasdk's head-unit certificate and key are **not**
distributed with this repository; `host/aa-headunit/README.md` explains where to obtain them.

**gearhead** (the Android Auto phone app) — decompiled to establish protocol facts:
channel ordinals, keyframe-interval behaviour by requested protocol version, the
`InputReport` field layout, and the keycode set the phone echoes back.

**Apple CarPlay Communication Plug-in SDK** — licensed first-party material accessed under
an active Apple Developer Program membership, used to check protocol conformance. It is not
redistributed here: the reference tree is outside this repository and excluded from it. See
`docs/ops/07_AUTHORIZATION.md` for the scope and authorization statement.

## Included third-party files

**Gradle wrapper** (`host/CarlinkAndroid/gradlew`, `gradle-wrapper.properties`) — Apache-2.0,
unmodified, retaining its own license header.

## Bundled in the app, fetched at build time (not in this repository)

Both are downloaded from Maven by the app's Gradle build and ship inside the APK, unmodified, for
route A (T34; the owner chose them on 2026-11-21 over writing a TLS client here):

- **AndroidX Media3** 1.8.0 (`media3-exoplayer`, `media3-exoplayer-hls`; Apache-2.0) — the player for the
  phone's HLS video.
- **Bouncy Castle** 1.80 (`bcprov`, `bctls`, `bcutil`, `-jdk18on`; the Bouncy Castle licence, MIT-style) —
  TLS 1.3 with the external pre-shared key the phone's proxy requires, which Android's own TLS lacks.

## Linked at build time, not vendored

**libfdk-aac** (Fraunhofer FDK AAC Codec Library) — the AAC-ELD encoder for the CarPlay mic
uplink links against it (`crates/vendor/eld-codec`, behind the `mic-uplink-eld` feature). Its
source is not included here and is built separately. Binaries you distribute that link it
carry Fraunhofer's own license and notice requirements, which this dedication does not and
cannot waive.

## Patents

This dedication waives copyright. It grants no patent rights, and none are implied. Media
codecs (AAC, H.264, HEVC) and accessory authentication in this domain are patent-encumbered
independently of copyright; that is the user's responsibility to assess.
