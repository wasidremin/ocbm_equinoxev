//! Model-B accessory-initiated reconnect (docs/wireless/01_BT_AND_RADIO.md): on boot, drive a bonded iPhone back into wireless
//! CarPlay with no user interaction — the behavior native head units and the stock Carlinkit firmware
//! both have. Purely additive to the accept path (`rfcomm::accept_one` in `main.rs`), which stays the
//! fallback for first-time pairing and any phone-initiated connect.
//!
//! Per the stock firmware (docs/wireless/01_BT_AND_RADIO.md §"What the working implementations do"): page the bonded phone,
//! SDP-*query* it for its iAP2 RFCOMM channel, then become the RFCOMM CLIENT and open the iAP2 channel
//! TO the phone. Steps here:
//!   1. `sdp_client::query`  — L2CAP-connect to the phone's SDP PSM (implicitly pages it) and read the
//!      iAP2 RFCOMM channel. This is also the on-hardware probe for docs/wireless/01_BT_AND_RADIO.md's open unknown (does iOS
//!      expose the service on reconnect?).
//!   2. `rfcomm::connect_to` — RFCOMM-connect OUT to that channel.
//!   3. `bt_driver::run`     — the existing, unchanged iAP2 Identify → auth → WiFi-handoff driver.
//!
//! Only ever attempts while no session is live (`session_active`), so it never fights the accept path
//! or a session already in progress. Bounded backoff (10→60 s) between rounds; reset after any session
//! so a post-drive reconnect is prompt.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

use crate::control::Control;
use crate::{bt_driver, control, sdp_client};

/// Bounds on a single SDP/RFCOMM connect to a quiet or absent phone (seconds). Long enough for a real
/// page+connect over BR/EDR, short enough that a missing phone doesn't park the thread for a whole
/// backoff interval.
const CONNECT_TIMEOUT_SECS: i64 = 8;
const BACKOFF_START_SECS: u64 = 10;
const BACKOFF_MAX_SECS: u64 = 60;

/// `@<unix_ms> ` write-time stamp (docs/carplay/01_OCBM_PROTOCOL.md CH_LOG): the box.log tailer
/// parses this prefix and uses it instead of the millisecond it happened to READ the line at.
fn log(m: &str) {
    println!("@{} [reconnect] {m}", now_ms());
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Sleep `secs`, but wake every second to observe `shutdown`. Returns early `false` if shutdown fired.
fn interruptible_sleep(secs: u64, shutdown: &AtomicBool) -> bool {
    for _ in 0..secs {
        if shutdown.load(Ordering::Relaxed) {
            return false;
        }
        thread::sleep(Duration::from_secs(1));
    }
    !shutdown.load(Ordering::Relaxed)
}

/// As [`interruptible_sleep`], but also cuts short when the device screen has asked for a connect.
///
/// A tap on *Connect* used to do nothing for up to `BACKOFF_MAX_SECS` — the request is read only at
/// the top of the loop — while the app had already answered `{"ok":true}`. Peeks, so the request is
/// still there for the loop to consume.
///
/// Deliberately NOT used by the no-bonds sleep: a request cannot be served with nothing bonded, so
/// waking on it there would spin the loop instead of waiting for a bond.
fn sleep_or_request(secs: u64, shutdown: &AtomicBool, ctrl: &Control) -> bool {
    for _ in 0..secs {
        if shutdown.load(Ordering::Relaxed) {
            return false;
        }
        if ctrl.has_request() {
            return true;
        }
        thread::sleep(Duration::from_secs(1));
    }
    !shutdown.load(Ordering::Relaxed)
}

/// One reconnect attempt against `peer`: SDP-query → RFCOMM-connect → hand to the iAP2 driver.
/// Returns `true` if a session actually ran (so the caller resets its backoff); `false` also covers
/// losing the single-session claim to the accept path. The slot is CLAIMED via `compare_exchange` right
/// before the driver runs (not held across the connect) and cleared only by the claim owner.
/// What one [`attempt`] did, so the loop can pick the right backoff.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
enum Attempt {
    /// The iAP2 driver ran (a real session, however short).
    Ran,
    /// SDP/RFCOMM failed for an ordinary reason (absent phone, page timeout, lost claim).
    Failed,
    /// The phone rejected the SSP re-pair (`rfcomm_uspace::connect_to` → `PermissionDenied`):
    /// paging it again just produces another prompt it cannot accept. Hold off for the maximum
    /// backoff and let the operator re-pair from the iPhone.
    PairRejected,
}

fn attempt(
    peer: [u8; 6],
    name: &str,
    shutdown: &AtomicBool,
    session_active: &AtomicBool,
    ctrl: &Control,
) -> Attempt {
    let services = match sdp_client::query(peer, CONNECT_TIMEOUT_SECS) {
        Ok(s) => s,
        Err(e) => {
            log(&format!("SDP query failed: {e}"));
            return Attempt::Failed;
        }
    };
    let Some(channel) = services.iap2 else {
        // Not an iPhone, or an iPhone not exposing iAP2 right now. Nothing to connect to.
        log("the bonded peer exposes no iAP2 service — nothing to connect to");
        return Attempt::Failed;
    };
    let sock = match crate::rfcomm_connect(peer, channel, CONNECT_TIMEOUT_SECS) {
        Ok(s) => s,
        Err(e) if e.kind() == std::io::ErrorKind::PermissionDenied => {
            log(&format!(
                "RFCOMM connect to channel {channel} aborted: {e} — holding off {BACKOFF_MAX_SECS}s; \
                 re-pair on the iPhone (Settings ▸ Bluetooth) or switch pairing to Just-Works"
            ));
            return Attempt::PairRejected;
        }
        Err(e) => {
            log(&format!("RFCOMM connect to channel {channel} failed: {e}"));
            return Attempt::Failed;
        }
    };
    log(&format!("RFCOMM connected OUT to the phone (ch {channel}) — starting iAP2 handshake"));
    // The real success signal is iAP2 progress inside bt_driver::run, NOT the mgmt DEVICE_CONNECTED
    // for our own outbound connect (docs/wireless/01_BT_AND_RADIO.md §Design notes — the Model-A latching gotcha).
    // Claim the single-session slot ATOMICALLY, immediately before running the driver. compare_exchange
    // (not a plain store) closes the TOCTOU with the accept path: the ~16 s SDP-query + RFCOMM-connect
    // above can overlap an inbound accept that took the slot in the meantime. If we lose the claim, stand
    // down — drop the freshly-connected socket (the phone retries) rather than run a second concurrent
    // bt_driver::run against the same phone. Only the claim owner clears the flag (below), so a finishing
    // session can never clobber another owner's still-live claim.
    // The guard claims the slot, publishes the peer for the device screen, and releases BOTH on
    // every exit from here — so the claim and its release cannot drift apart as this block grows,
    // which is what had already happened between this call site and the accept path's.
    let Some(_claim) = control::SessionClaim::try_claim(session_active, ctrl, Some(peer)) else {
        log("session already active — standing down (dropping outbound connect)");
        return Attempt::Failed;
    };
    bt_driver::run(sock, name, shutdown);
    log("reconnect session ended");
    Attempt::Ran
}

/// The reconnect loop. Spawns nothing. Whenever no session is live, re-reads the bond list and tries
/// each bonded phone in turn with bounded backoff, until `shutdown`. When nothing is bonded it idles
/// (re-checking every `BACKOFF_MAX_SECS`) rather than exiting, so a phone paired via the accept path
/// after boot becomes reconnect-eligible without a daemon restart (audit Fix #22).
///
/// ## Connection policy
///
/// The order comes from [`Control::ordered_bonds`] — the projection app's configured
/// first-to-connect list, with any bond it does not mention appended so a newly paired phone is
/// never invisible. With `autoConnect` off the loop does **not** drive on its own; it waits for an
/// explicit request from the device screen, which is GM's "tap to connect".
///
/// An explicit request always wins, including while `autoConnect` is off and including out of
/// order: pressing Connect on a specific phone means that phone, now.
pub fn run(
    name: &str,
    shutdown: &Arc<AtomicBool>,
    session_active: &Arc<AtomicBool>,
    ctrl: &Arc<Control>,
) {
    // First bonded-phone attempt is immediate; subsequent attempts retain the
    // existing bounded backoff. The session claim still arbitrates incoming RFCOMM.
    let mut backoff = BACKOFF_START_SECS;
    // Log only on a change in the bonded/idle state, so the per-round re-read below doesn't spam the log.
    // `None` = nothing logged yet; `Some(true)` = had bonds; `Some(false)` = idle.
    let mut last_state: Option<bool> = None;
    let mut last_manual_log = false;
    while !shutdown.load(Ordering::Relaxed) {
        // Never fight an in-progress session (accept path or a prior reconnect). Advisory fast-path
        // only — the authoritative claim is the compare_exchange in attempt().
        if session_active.load(Ordering::Relaxed) {
            if !interruptible_sleep(BACKOFF_START_SECS, shutdown) {
                break;
            }
            backoff = BACKOFF_START_SECS; // a live session means the phone is here; retry promptly after it
            continue;
        }
        // Re-read the bond list every round (audit Fix #22): a phone paired via the accept path AFTER
        // boot must become reconnect-eligible without a daemon restart. The old code snapshotted bonds
        // once and returned early when the set was empty, so a box that booted unpaired never drove
        // reconnect for a later-paired phone. This just reads the persisted link-key file — cheap, no
        // paging, no side effects — now ordered by the app's policy.
        let bonds = ctrl.ordered_bonds();
        let has_bonds = !bonds.is_empty();
        if last_state != Some(has_bonds) {
            if has_bonds {
                log(&format!("{} bonded phone(s) — driving reconnect when idle", bonds.len()));
            } else {
                log("no bonded phones — reconnect idle (accept path handles first pairing; re-checking)");
            }
            last_state = Some(has_bonds);
        }
        if !has_bonds {
            if !interruptible_sleep(BACKOFF_MAX_SECS, shutdown) {
                break;
            }
            continue;
        }

        // An explicit request from the device screen. Consumed here, so it fires exactly once.
        let request = ctrl.take_request();
        let auto = ctrl.policy().auto_connect;

        // Tap-to-connect with nothing pending: stay idle rather than paging phones the driver did
        // not ask for. Logged once per transition — this is a configured state, not a fault.
        if !auto && request.is_none() {
            if !last_manual_log {
                log("autoConnect off — idle until the device screen asks for a phone");
                last_manual_log = true;
            }
            if !sleep_or_request(BACKOFF_START_SECS, shutdown, ctrl) {
                break;
            }
            continue;
        }
        last_manual_log = false;

        // A named request is tried alone and first. Anything else walks the policy order.
        let targets: Vec<[u8; 6]> = match request {
            Some(Some(addr)) => {
                if bonds.contains(&addr) {
                    log(&format!("explicit connect request for {}", control::fmt_addr(&addr)));
                    vec![addr]
                } else {
                    // Requesting an unbonded phone cannot work — there is no link key to offer, so
                    // the connect would fail at pairing. Say so instead of silently trying it.
                    log(&format!(
                        "connect requested for {} but it is not bonded — ignoring",
                        control::fmt_addr(&addr)
                    ));
                    Vec::new()
                }
            }
            _ => bonds.clone(),
        };

        let mut ran = false;
        let mut rejected = false;
        for &peer in &targets {
            if shutdown.load(Ordering::Relaxed) || session_active.load(Ordering::Relaxed) {
                break;
            }
            match attempt(peer, name, shutdown, session_active, ctrl) {
                Attempt::Ran => ran = true,
                Attempt::Failed => {}
                Attempt::PairRejected => {
                    rejected = true;
                    break; // do not page the next phone into the same rejection storm
                }
            }
        }
        if rejected {
            backoff = BACKOFF_MAX_SECS; // the phone said no; more paging only rotates codes
        } else if ran {
            backoff = BACKOFF_START_SECS; // reset toward prompt retry after a real attempt…
        }
        // …but ALWAYS sleep at least the current backoff (≥ BACKOFF_START_SECS) before the next attempt
        // (audit B5). `attempt()` returns true when the bt_driver was merely INVOKED, not when it reached a
        // real milestone, so a bonded phone that is RFCOMM-connectable but never completes iAP2 (accepts the
        // DLC then drops, or fails auth fast) makes each attempt return in well under a second. Without this
        // floor the old `continue` spun the loop back-to-back — pinning the single i.MX6UL core and paging
        // the phone continuously. A genuine session that just ended still retries within BACKOFF_START_SECS.
        if !sleep_or_request(backoff, shutdown, ctrl) {
            break;
        }
        if rejected {
            // The hold-off is over: drop the flag so the next attempt is judged on its own.
            let _ = std::fs::remove_file(bt_common::rfcomm_uspace::PAIR_REJECTED_FLAG);
        } else if !ran {
            backoff = (backoff * 2).min(BACKOFF_MAX_SECS);
        }
    }
    log("reconnect loop exiting");
}
