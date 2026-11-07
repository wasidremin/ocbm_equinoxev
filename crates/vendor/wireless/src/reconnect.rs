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
use std::time::{Duration, Instant};

use crate::control::Control;
use crate::{bt_driver, control, sdp_client};

/// Bounds on a single SDP/RFCOMM connect to a quiet or absent phone (seconds). Long enough for a real
/// page+connect over BR/EDR, short enough that a missing phone doesn't park the thread for a whole
/// backoff interval.
const CONNECT_TIMEOUT_SECS: i64 = 8;
const BACKOFF_START_SECS: u64 = 10;
const BACKOFF_MAX_SECS: u64 = 60;
/// One short retry when the FIRST round after start-up fails, instead of the full
/// `BACKOFF_START_SECS`. enigy/ocbm's reset cycle on a CCPA (2026-10-20): in 3 of 8 reconnects the
/// first page failed ~5 s in (`SDP query failed: Connection reset by peer` twice, `Host is down`
/// once) and the very next attempt succeeded, but only after the 10 s floor, so each cost ~15 s. This
/// car shows the same shape (2026-11-07 10:16: the phone reset the SDP query, the box waited 10 s).
/// Once per daemon start, so it cannot spin: every later round still gets the floor and the doubling.
const FIRST_RETRY_SECS: u64 = 2;
/// The pause before the one more try a phone gets when it answers the page and then resets the link
/// ([`Attempt::Reset`]), before the next phone in the call order is paged. enigy/ocbm's bench (2 Nov):
/// 32 s after a power cut the first-called iPhone accepted the page and reset it 4 s later, and the
/// walk went straight on to the second phone, which got CarPlay. [`FIRST_RETRY_SECS`] only helps when
/// no phone in the round connected.
const RESET_RETRY_SECS: u64 = 2;

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
    /// The phone answered the page and then reset the link (`ConnectionReset` from SDP or RFCOMM): it is
    /// there but not ready, as an iPhone still letting go of a session a power cut ended. Paged once more
    /// before the next phone ([`walk`]).
    Reset,
    /// The phone rejected the SSP re-pair (`rfcomm_uspace::connect_to` → `PermissionDenied`):
    /// paging it again just produces another prompt it cannot accept. Hold off for the maximum
    /// backoff and let the operator re-pair from the iPhone.
    PairRejected,
}

/// [`Attempt::Reset`] when the phone reset the link, else [`Attempt::Failed`].
fn failure(e: &std::io::Error) -> Attempt {
    if e.kind() == std::io::ErrorKind::ConnectionReset {
        Attempt::Reset
    } else {
        Attempt::Failed
    }
}

/// What one walk down the call order did.
#[derive(Debug, Default, PartialEq, Eq)]
struct Walk {
    ran: bool,
    rejected: bool,
}

/// Rounds in a row in which a phone answered the page and reset the link, for [`ocbm_proto::BTP_BOND_LOST`].
///
/// Counted once per round, with the round's own retry ([`walk`]) as part of it: a still-paired iPhone letting go
/// of a session a power cut ended answers and resets too, and with the 2 s retries a per-page count reached three
/// inside the first ~17 s, which is exactly that case. Three rounds (about 45 s with the quick retry and the first
/// backoff) with nothing else between mean it most likely forgot the pairing (enigy/ocbm bench, 5 Nov: six resets,
/// and the phone offered pairing only a minute later). Told once per streak.
///
/// A phone's new bond starts its count over and holds the news back for [`Self::NEW_BOND_HOLD`]: right after a
/// pairing, until the driver accepts CarPlay on the iPhone, it refuses iAP2 and answers-and-resets too. Each phone's
/// bond is its own stored link-key record, so pairing or forgetting ANOTHER phone touches nobody else's count.
#[derive(Debug, Default)]
struct ResetStreak {
    /// Each phone's own run: another phone's page doesn't end it. With two bonded iPhones and one away, every
    /// round pages both, and the absent one's failure would otherwise end the count.
    runs: std::collections::HashMap<[u8; 6], Run>,
    /// Each phone's bond as last seen.
    bonds: std::collections::HashMap<[u8; 6], Bond>,
}

#[derive(Debug, Default)]
struct Run {
    count: u32,
    told: bool,
}

#[derive(Debug)]
struct Bond {
    /// The phone's stored link-key record (`ssp_agent::bond_record`); a re-pair rewrites it.
    record: Option<Vec<u8>>,
    /// When btd saw this bond new, on the monotonic clock; `None` for a bond older than btd.
    new_since: Option<Instant>,
}

impl ResetStreak {
    const TELL_AT: u32 = 3;
    const NEW_BOND_HOLD: Duration = Duration::from_secs(10 * 60);

    /// One round's outcome for `peer`, with that phone's stored link-key `record` now: true exactly when its run
    /// reaches [`Self::TELL_AT`] and its bond is not newer than [`Self::NEW_BOND_HOLD`]. A session (Ran) ends every
    /// run; the phone's own failure or new bond ends its own. `store_fresh` (the store was written within the hold,
    /// [`store_fresh`]) is used only the first time btd sees a phone, when it cannot tell whose bond the write was.
    fn note(&mut self, peer: [u8; 6], result: Attempt, record: Option<Vec<u8>>, store_fresh: bool, mono: Instant) -> bool {
        match self.bonds.get_mut(&peer) {
            Some(b) if b.record != record => {
                b.record = record;
                b.new_since = Some(mono);
                self.runs.remove(&peer);
            }
            Some(_) => {}
            None => {
                self.bonds.insert(peer, Bond { record, new_since: store_fresh.then_some(mono) });
            }
        }
        match result {
            Attempt::Reset => {}
            Attempt::Ran => {
                self.runs.clear();
                return false;
            }
            Attempt::Failed | Attempt::PairRejected => {
                self.runs.remove(&peer);
                return false;
            }
        }
        let held = self
            .bonds
            .get(&peer)
            .and_then(|b| b.new_since)
            .is_some_and(|t| mono.duration_since(t) < Self::NEW_BOND_HOLD);
        let run = self.runs.entry(peer).or_default();
        run.count += 1;
        if run.count >= Self::TELL_AT && !run.told && !held {
            run.told = true;
            return true;
        }
        false
    }

    /// A session on either path: the phone knows the adapter, and every run ends.
    fn session(&mut self) {
        self.runs.clear();
    }
}

/// Whether the link-key store was written within [`ResetStreak::NEW_BOND_HOLD`]. A write stamped later than the
/// wall clock (the box clock reads 2020 until the host's CT_SETTIME lands) has no age and counts as fresh: the
/// hold then runs from when btd first saw the phone, so it cannot last.
fn store_fresh(now: std::time::SystemTime) -> bool {
    match std::fs::metadata(bt_common::ssp_agent::link_key_store()).and_then(|m| m.modified()) {
        Ok(written) => now.duration_since(written).map_or(true, |age| age < ResetStreak::NEW_BOND_HOLD),
        Err(_) => false,
    }
}

/// Page `targets` in order until `stop` says not to go on. A phone that answers and resets the link
/// ([`Attempt::Reset`]) is paged once more, after `pause` (false: go no further), before the next one.
/// `outcome` hears each phone's result for the round once, after its retry.
fn walk(
    targets: &[[u8; 6]],
    mut stop: impl FnMut() -> bool,
    mut pause: impl FnMut([u8; 6]) -> bool,
    mut page: impl FnMut([u8; 6]) -> Attempt,
    mut outcome: impl FnMut([u8; 6], Attempt),
) -> Walk {
    let mut out = Walk::default();
    for &peer in targets {
        if stop() {
            break;
        }
        let mut result = page(peer);
        if result == Attempt::Reset {
            if !pause(peer) {
                outcome(peer, result);
                break;
            }
            result = page(peer);
        }
        outcome(peer, result);
        match result {
            Attempt::Ran => out.ran = true,
            Attempt::Failed | Attempt::Reset => {}
            Attempt::PairRejected => {
                out.rejected = true;
                break; // do not page the next phone into the same rejection storm
            }
        }
    }
    out
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
            return failure(&e);
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
            return failure(&e);
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
    let mut streak = ResetStreak::default();
    let mut sessions_seen = ctrl.sessions_started();
    let mut first_round = true;
    // Log only on a change in the bonded/idle state, so the per-round re-read below doesn't spam the log.
    // `None` = nothing logged yet; `Some(true)` = had bonds; `Some(false)` = idle.
    let mut last_state: Option<bool> = None;
    let mut last_manual_log = false;
    while !shutdown.load(Ordering::Relaxed) {
        // A session on either path since the last look, including one the phone started and ended inside a
        // backoff sleep, where `session_active` below never sees it: that phone knows the adapter.
        let sessions = ctrl.sessions_started();
        if sessions != sessions_seen {
            sessions_seen = sessions;
            streak.session();
        }
        // Never fight an in-progress session (accept path or a prior reconnect). Advisory fast-path
        // only — the authoritative claim is the compare_exchange in attempt().
        if session_active.load(Ordering::Relaxed) {
            streak.session();
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

        let Walk { ran, rejected } = walk(
            &targets,
            || shutdown.load(Ordering::Relaxed) || session_active.load(Ordering::Relaxed),
            |peer| {
                log(&format!(
                    "{} answered and reset the link -- paging it once more in {RESET_RETRY_SECS}s before the next phone",
                    control::fmt_addr(&peer)
                ));
                sleep_or_request(RESET_RETRY_SECS, shutdown, ctrl)
                    && !ctrl.has_request()
                    && !session_active.load(Ordering::Relaxed)
            },
            |peer| attempt(peer, name, shutdown, session_active, ctrl),
            |peer, result| {
                let record = bt_common::ssp_agent::bond_record(&peer);
                if streak.note(peer, result, record, store_fresh(std::time::SystemTime::now()), Instant::now())
                    && !session_active.load(Ordering::Relaxed)
                {
                    log(&format!(
                        "{} answered and reset the link in {} rounds in a row: it has most likely forgotten the pairing",
                        control::fmt_addr(&peer),
                        ResetStreak::TELL_AT
                    ));
                    bt_driver::publish_bt_phase(ocbm_proto::BTP_BOND_LOST);
                }
            },
        );
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
        // The single exception is FIRST_RETRY_SECS, once, after a first round that failed outright.
        let quick = first_round && !ran && !rejected;
        first_round = false;
        if quick {
            log(&format!("first page round failed -- one quick retry in {FIRST_RETRY_SECS}s"));
        }
        if !sleep_or_request(if quick { FIRST_RETRY_SECS } else { backoff }, shutdown, ctrl) {
            break;
        }
        if rejected {
            // The hold-off is over: drop the flag so the next attempt is judged on its own.
            let _ = std::fs::remove_file(bt_common::rfcomm_uspace::PAIR_REJECTED_FLAG);
        } else if !ran && !quick {
            backoff = (backoff * 2).min(BACKOFF_MAX_SECS);
        }
    }
    log("reconnect loop exiting");
}

#[cfg(test)]
mod tests {
    use super::{failure, walk, Attempt, ResetStreak, Walk};
    use std::time::{Duration, Instant};

    const SCOTT: [u8; 6] = [1, 0, 0, 0, 0, 0];
    const YODA: [u8; 6] = [2, 0, 0, 0, 0, 0];
    const KEY: Option<&[u8]> = Some(&[7; 25]);

    fn key() -> Option<Vec<u8>> {
        KEY.map(<[u8]>::to_vec)
    }

    /// Walk `[SCOTT, YODA]` with each phone's answers in turn (Failed once they run out), never stopping and
    /// always ready to retry: which phones were paged, in order, each phone's outcome, and what the walk did.
    fn walk_with(answers: &[(&[u8; 6], Attempt)]) -> (Vec<[u8; 6]>, Vec<([u8; 6], Attempt)>, Walk) {
        let mut queue: Vec<_> = answers.iter().map(|(p, a)| (**p, *a)).collect();
        let mut paged = Vec::new();
        let mut outcomes = Vec::new();
        let walked = walk(
            &[SCOTT, YODA],
            || false,
            |_| true,
            |peer| {
                paged.push(peer);
                match queue.iter().position(|(p, _)| *p == peer) {
                    Some(i) => queue.remove(i).1,
                    None => Attempt::Failed,
                }
            },
            |peer, result| outcomes.push((peer, result)),
        );
        (paged, outcomes, walked)
    }

    /// A phone that answers the page and resets the link (car, 2026-11-07 10:16) is paged once more before
    /// the walk moves to the next phone, and the round reports one outcome for it.
    #[test]
    fn a_phone_that_answers_and_resets_is_paged_once_more_before_the_next() {
        let (paged, outcomes, walked) = walk_with(&[(&SCOTT, Attempt::Reset), (&SCOTT, Attempt::Ran)]);
        assert_eq!(&paged[..2], &[SCOTT, SCOTT]);
        assert_eq!(outcomes[0], (SCOTT, Attempt::Ran), "one outcome per phone per round, after its retry");
        assert!(walked.ran);
        let (paged, outcomes, walked) =
            walk_with(&[(&SCOTT, Attempt::Reset), (&SCOTT, Attempt::Reset), (&YODA, Attempt::Ran)]);
        assert_eq!(paged, vec![SCOTT, SCOTT, YODA], "only once: then the next phone");
        assert_eq!(outcomes, vec![(SCOTT, Attempt::Reset), (YODA, Attempt::Ran)]);
        assert!(walked.ran);
    }

    /// Three rounds of answered-and-reset say the bond is lost, once per streak.
    #[test]
    fn three_reset_rounds_in_a_row_say_the_bond_is_lost_once() {
        let mut s = ResetStreak::default();
        let m = Instant::now();
        assert!(!s.note(SCOTT, Attempt::Reset, key(), false, m));
        assert!(!s.note(SCOTT, Attempt::Reset, key(), false, m));
        assert!(s.note(SCOTT, Attempt::Reset, key(), false, m), "the third round in a row tells");
        assert!(!s.note(SCOTT, Attempt::Reset, key(), false, m), "once per streak");
        assert!(!s.note(SCOTT, Attempt::Failed, key(), false, m), "its own failure ends its run");
        assert!(!s.note(SCOTT, Attempt::Reset, key(), false, m));
        assert!(!s.note(SCOTT, Attempt::Ran, key(), false, m));
        assert!(!s.note(SCOTT, Attempt::Reset, key(), false, m));
        assert!(!s.note(SCOTT, Attempt::Reset, key(), false, m), "a session between: the count starts again");
        assert!(s.note(SCOTT, Attempt::Reset, key(), false, m));
    }

    /// The review's case: a still-paired iPhone letting go of the old session resets the page AND its 2 s retry in
    /// the first round and the quick-retry round, then connects. Counted per page that was four resets and a
    /// BOND_LOST at ~17 s; counted per round it is two, and nothing is said.
    #[test]
    fn a_slow_still_paired_phone_is_not_called_lost() {
        let mut s = ResetStreak::default();
        let m = Instant::now();
        let mut told = false;
        for round in [[Attempt::Reset, Attempt::Reset], [Attempt::Reset, Attempt::Reset], [Attempt::Reset, Attempt::Ran]] {
            let mut answers = round.into_iter();
            walk(&[SCOTT], || false, |_| true, |_| answers.next().unwrap_or(Attempt::Failed), |peer, result| {
                told |= s.note(peer, result, key(), false, m);
            });
        }
        assert!(!told);
    }

    /// Re-paired, then the iPhone answers-and-resets while the driver has not yet accepted CarPlay: its new bond
    /// starts its count over and holds the news back while it is new.
    #[test]
    fn a_new_bond_starts_the_count_over_and_holds_it_back() {
        let mut s = ResetStreak::default();
        let m = Instant::now();
        assert!(!s.note(SCOTT, Attempt::Reset, key(), false, m));
        assert!(!s.note(SCOTT, Attempt::Reset, key(), false, m));
        let new = Some(vec![9; 25]); // NEW_LINK_KEY: this phone's record is rewritten
        assert!(!s.note(SCOTT, Attempt::Reset, new.clone(), false, m), "the count starts over");
        assert!(!s.note(SCOTT, Attempt::Reset, new.clone(), false, m));
        assert!(!s.note(SCOTT, Attempt::Reset, new.clone(), false, m + Duration::from_secs(60)), "three, but the bond is a minute old");
        let later = m + ResetStreak::NEW_BOND_HOLD + Duration::from_secs(1);
        assert!(s.note(SCOTT, Attempt::Reset, new, false, later), "still resetting once the bond is no longer new: tell");
    }

    /// The review's case: pairing or forgetting ANOTHER phone rewrites the store. That must neither reset this
    /// phone's count nor hold its news back.
    #[test]
    fn another_phone_s_new_bond_does_not_touch_this_phone() {
        let mut s = ResetStreak::default();
        let m = Instant::now();
        assert!(!s.note(SCOTT, Attempt::Reset, key(), false, m));
        assert!(!s.note(YODA, Attempt::Failed, Some(vec![1; 25]), false, m));
        assert!(!s.note(SCOTT, Attempt::Reset, key(), false, m));
        assert!(!s.note(YODA, Attempt::Failed, Some(vec![2; 25]), false, m), "YODA re-paired");
        assert!(s.note(SCOTT, Attempt::Reset, key(), false, m), "SCOTT's own run and bond are untouched");
    }

    /// A store written just before btd started (or stamped ahead of an unset box clock) is a bond btd cannot
    /// attribute: each phone first seen then is held for the hold from when btd saw it, and the hold ends.
    #[test]
    fn a_fresh_store_at_first_sight_holds_until_the_hold_ends() {
        let mut s = ResetStreak::default();
        let m = Instant::now();
        assert!(!s.note(SCOTT, Attempt::Reset, key(), true, m));
        assert!(!s.note(SCOTT, Attempt::Reset, key(), true, m));
        assert!(!s.note(SCOTT, Attempt::Reset, key(), true, m + Duration::from_secs(60)), "held while it is new to btd");
        let after = m + ResetStreak::NEW_BOND_HOLD + Duration::from_secs(1);
        assert!(s.note(SCOTT, Attempt::Reset, key(), true, after), "the hold ends on the monotonic clock");
    }

    /// Two bonded iPhones, the one that forgot the adapter here and the other away. Every round pages both, and
    /// the absent one's failure used to end the count, so it never told. Each phone keeps its own run.
    #[test]
    fn another_phone_away_does_not_hide_the_one_that_forgot() {
        let m = Instant::now();
        for order in [[SCOTT, YODA], [YODA, SCOTT]] {
            let mut s = ResetStreak::default();
            let mut told = Vec::new();
            for round in 1..=4 {
                walk(
                    &order,
                    || false,
                    |_| true,
                    |peer| if peer == SCOTT { Attempt::Reset } else { Attempt::Failed },
                    |peer, result| {
                        if s.note(peer, result, key(), false, m) {
                            told.push((round, peer));
                        }
                    },
                );
            }
            assert_eq!(told, vec![(3, SCOTT)], "{order:?}: told once, in the third round");
        }
        let mut s = ResetStreak::default();
        assert!(!s.note(SCOTT, Attempt::Reset, key(), false, m));
        assert!(!s.note(SCOTT, Attempt::Reset, key(), false, m));
        s.session(); // a session on either path: it knows the adapter
        assert!(!s.note(SCOTT, Attempt::Reset, key(), false, m), "the run starts over after a session");
        assert!(!s.note(YODA, Attempt::Ran, key(), false, m));
        assert!(!s.note(SCOTT, Attempt::Reset, key(), false, m));
        assert!(!s.note(SCOTT, Attempt::Reset, key(), false, m), "any phone's session ends every run");
        assert!(s.note(SCOTT, Attempt::Reset, key(), false, m));
    }

    #[test]
    fn an_absent_phone_is_not_paged_twice() {
        let (paged, _, _) = walk_with(&[(&SCOTT, Attempt::Failed), (&YODA, Attempt::Ran)]);
        assert_eq!(paged, vec![SCOTT, YODA]);
    }

    #[test]
    fn a_request_or_a_session_during_the_pause_ends_the_walk() {
        let mut paged = Vec::new();
        let mut outcomes = Vec::new();
        let walked = walk(
            &[SCOTT, YODA],
            || false,
            |_| false,
            |peer| {
                paged.push(peer);
                Attempt::Reset
            },
            |peer, result| outcomes.push((peer, result)),
        );
        assert_eq!(paged, vec![SCOTT]);
        assert_eq!(outcomes, vec![(SCOTT, Attempt::Reset)], "the cut-short visit still reports its reset");
        assert_eq!(walked, Walk::default());
    }

    #[test]
    fn a_rejected_pairing_still_ends_the_walk() {
        let (paged, _, walked) = walk_with(&[(&SCOTT, Attempt::PairRejected)]);
        assert_eq!(paged, vec![SCOTT]);
        assert!(walked.rejected && !walked.ran);
    }

    #[test]
    fn only_a_reset_link_counts_as_a_reset() {
        let reset = std::io::Error::from_raw_os_error(libc::ECONNRESET);
        assert_eq!(failure(&reset), Attempt::Reset);
        for errno in [libc::EHOSTDOWN, libc::ETIMEDOUT, libc::ECONNREFUSED] {
            assert_eq!(failure(&std::io::Error::from_raw_os_error(errno)), Attempt::Failed, "errno {errno}");
        }
    }
}
