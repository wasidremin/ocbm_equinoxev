//! carplayd's half of the connect timeline (`box_common::timeline`): its own events and chip times,
//! kept in memory, and the one `[timeline] v1` line it prints when the first video frame of the
//! wireless session goes to the host, with btd's session record (`/tmp/startup_marks`) for the rest.
//!
//! The idea and the keys are enigy/ocbm's (`crates/vendor/receiver/src/startup.rs` at 801074f,
//! Unlicense); this is a smaller version written for this fork, without enigy's early carplayd and its
//! `dial_ok` signal (that key prints `-`). NOTICE.md.
//!
//! Which values describe the connection that produced the picture: `carplayd_start` and `svc_seen`
//! are the process's (the first stands); `dial` is the last accepted connect-out before the control
//! connection began; everything after it is the connection's own, cleared when a new one begins.

use portable_atomic::{AtomicBool, AtomicU64, Ordering};

const UNSET: u64 = u64::MAX;

/// The process's events: the first of each stands.
const PROCESS: [&str; 2] = ["carplayd_start", "svc_seen"];
/// The control connection's events: the first of each in the connection stands.
const CONNECTION: [&str; 7] = ["pv_done", "auth_in", "auth_out", "record_in", "record", "screen_setup", "first_frame"];
/// auth-setup's chip times for the connection: the signature, the certificate read.
const CHIPS: [&str; 2] = ["chip2_ms", "cert2_ms"];

struct Own {
    process: [AtomicU64; PROCESS.len()],
    connection: [AtomicU64; CONNECTION.len()],
    chips: [AtomicU64; CHIPS.len()],
    /// The last accepted connect-out not yet taken by a connection.
    dial_pending: AtomicU64,
    /// The dial the current connection followed.
    dial: AtomicU64,
}

impl Own {
    const fn new() -> Self {
        Self {
            process: [const { AtomicU64::new(UNSET) }; PROCESS.len()],
            connection: [const { AtomicU64::new(UNSET) }; CONNECTION.len()],
            chips: [const { AtomicU64::new(UNSET) }; CHIPS.len()],
            dial_pending: AtomicU64::new(UNSET),
            dial: AtomicU64::new(UNSET),
        }
    }

    fn event(&self, key: &str, ms: u64) {
        if key == "dial" {
            self.dial_pending.store(ms, Ordering::Relaxed);
        } else if let Some(i) = PROCESS.iter().position(|k| *k == key) {
            let _ = self.process[i].compare_exchange(UNSET, ms, Ordering::Relaxed, Ordering::Relaxed);
        } else if let Some(i) = CONNECTION.iter().position(|k| *k == key) {
            let _ = self.connection[i].compare_exchange(UNSET, ms, Ordering::Relaxed, Ordering::Relaxed);
        }
    }

    fn chip(&self, key: &str, ms: u64) {
        if let Some(i) = CHIPS.iter().position(|k| *k == key) {
            let _ = self.chips[i].compare_exchange(UNSET, ms, Ordering::Relaxed, Ordering::Relaxed);
        }
    }

    fn new_connection(&self) {
        for v in self.connection.iter().chain(self.chips.iter()) {
            v.store(UNSET, Ordering::Relaxed);
        }
        self.dial.store(self.dial_pending.swap(UNSET, Ordering::Relaxed), Ordering::Relaxed);
    }

    /// What is stamped, as `box_common::timeline::line` takes it.
    fn values(&self) -> Vec<(&'static str, u64)> {
        let process = PROCESS.iter().zip(&self.process);
        let connection = CONNECTION.iter().zip(&self.connection);
        let chips = CHIPS.iter().zip(&self.chips);
        process
            .chain(connection)
            .chain(chips)
            .map(|(k, v)| (*k, v.load(Ordering::Relaxed)))
            .chain(std::iter::once(("dial", self.dial.load(Ordering::Relaxed))))
            .filter(|&(_, v)| v != UNSET)
            .collect()
    }
}

static OWN: Own = Own::new();
static PRINTED: AtomicBool = AtomicBool::new(false);

/// The environment variable btd sets on the wireless carplayd alone (`av.rs`): the wired carplayd's
/// first frame must not print a line from the last wireless session's record.
pub const WIRELESS_ENV: &str = "CARPLAY_TIMELINE";

/// The receiver's events (`receiver::timeline`) and carplayd's own: stamp `key` now.
pub fn event(key: &'static str) {
    let Some(ms) = box_common::timeline::now_ms() else { return };
    OWN.event(key, ms);
    if key == "first_frame" {
        print_once();
    }
}

/// One of auth-setup's chip times (ms), the first in the connection standing.
pub fn chip(key: &'static str, ms: u64) {
    OWN.chip(key, ms);
}

/// A new control connection: the line describes the one that produces the picture.
pub fn new_connection() {
    OWN.new_connection();
}

/// The line, once per carplayd (one per wireless session), in one write: another thread's log line
/// must not land inside it.
fn print_once() {
    if std::env::var_os(WIRELESS_ENV).is_none() || PRINTED.swap(true, Ordering::Relaxed) {
        return;
    }
    let marks = std::fs::read_to_string(box_common::timeline::MARKS).unwrap_or_default();
    let line = box_common::timeline::line(&marks, &OWN.values());
    use std::io::Write as _;
    let _ = std::io::stderr().lock().write_all(format!("{line}\n").as_bytes());
}

#[cfg(test)]
mod tests {
    use super::*;

    fn get(own: &Own, key: &str) -> Option<u64> {
        own.values().into_iter().find(|(k, _)| *k == key).map(|(_, v)| v)
    }

    #[test]
    fn the_connection_that_made_the_picture_is_the_one_described() {
        let own = Own::new();
        own.event("carplayd_start", 100);
        own.event("svc_seen", 200);
        own.event("svc_seen", 900);
        own.event("dial", 210);
        own.new_connection(); // a first connection that never reached the picture
        own.event("pv_done", 300);
        own.chip("chip2_ms", 1650);
        own.event("dial", 950);
        own.new_connection();
        own.event("pv_done", 1000);
        own.event("pv_done", 1100);
        own.chip("cert2_ms", 120);
        assert_eq!(get(&own, "carplayd_start"), Some(100));
        assert_eq!(get(&own, "svc_seen"), Some(200), "the process's first stands");
        assert_eq!(get(&own, "dial"), Some(950), "the dial this connection followed");
        assert_eq!(get(&own, "pv_done"), Some(1000), "this connection's first, not the last one's");
        assert_eq!(get(&own, "chip2_ms"), None, "the last connection's chip time went with it");
        assert_eq!(get(&own, "cert2_ms"), Some(120));
        own.new_connection();
        assert_eq!(get(&own, "dial"), None, "a connection the phone opened itself followed no dial");
    }

    #[test]
    fn the_values_read_as_a_timeline_line() {
        let own = Own::new();
        own.event("carplayd_start", 64400);
        own.event("dial", 65150);
        own.new_connection();
        own.event("first_frame", 66700);
        own.chip("chip2_ms", 1650);
        let l = box_common::timeline::line("t host 61234\n", &own.values());
        assert!(l.contains(" carplayd_start=3166 ") && l.contains(" dial=3916 "), "{l}");
        assert!(l.contains(" first_frame=5466 ") && l.contains(" chip2_ms=1650 "), "{l}");
        assert!(l.contains(" dial_ok=- "), "not stamped in this fork: {l}");
        assert!(box_common::timeline::is_line(&l), "{l}");
    }

    #[test]
    fn an_unknown_key_changes_nothing() {
        let own = Own::new();
        own.event("not_a_key", 5);
        own.chip("pv_done", 5);
        assert!(own.values().is_empty());
    }
}
