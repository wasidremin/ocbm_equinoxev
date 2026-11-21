//! btd's half of the connect timeline (`box_common::timeline`): the session record carplayd reads at
//! the first video frame. [`begin`] starts it at each session with the origin and `btd_start`, and
//! carries over the radio bring-up's stamps the shell appended; [`stamp`] and [`duration`] add btd's
//! own events and chip times, the first of each standing. Best effort: a failed write costs a value.
//!
//! PORTED from enigy/ocbm (github.com/enigy/ocbm, Unlicense) `crates/vendor/wireless/src/startup.rs`
//! at 801074f: its timeline part only. Its `[startup]` counts (host_present, the preload state, the box
//! log marks, tallies) are not ported; this fork's app keeps its own `[startup]` stages. NOTICE.md.

use std::io::Write as _;

use box_common::timeline::{self, DURATION, STAMP};

/// The record, read back by carplayd at the first frame.
pub const MARKS: &str = timeline::MARKS;

/// Start this session's record: the origin as ocbmd stamped it, this session's start, and the radio
/// bring-up's stamps already in the old record when they belong to this host (see [`carry_over`]).
pub fn begin() {
    let mut fresh = String::new();
    if let Some(origin) = timeline::read_origin(timeline::ORIGIN) {
        fresh.push_str(&timeline::entry(STAMP, timeline::ORIGIN_KEY, origin));
    }
    if let Some(now) = timeline::now_ms() {
        fresh.push_str(&timeline::entry(STAMP, "btd_start", now));
    }
    let prev = std::fs::read_to_string(MARKS).unwrap_or_default();
    let _ = std::fs::write(MARKS, carry_over(&prev, &fresh));
}

/// The record a session starts with: `fresh`, then the radio bring-up's stamps in `prev`
/// ([`radio_bring_up`]): the shell wrote them there before this session began.
fn carry_over(prev: &str, fresh: &str) -> String {
    let radio = radio_bring_up(prev, timeline::find(fresh, STAMP, timeline::ORIGIN_KEY));
    format!("{fresh}{radio}")
}

/// The radio bring-up's stamps in `prev` (`timeline::RADIO`), from its last `sup_radios` on: the
/// bring-up this session runs on. Nothing when that came before the origin (`origin`; any, when
/// unknown): an earlier host's, whose radios went down with it. Stamps from btd's earlier sessions are
/// not carried: each session's are its own.
fn radio_bring_up(prev: &str, origin: Option<u64>) -> String {
    let lines: Vec<&str> = prev.lines().collect();
    let opens = |l: &str| timeline::find(l, STAMP, "sup_radios");
    let Some(start) = lines.iter().rposition(|l| opens(l).is_some()) else { return String::new() };
    if origin.is_some_and(|o| opens(lines[start]).is_some_and(|at| at < o)) {
        return String::new();
    }
    let radio = |l: &&&str| timeline::RADIO.iter().any(|key| timeline::find(l, STAMP, key).is_some());
    lines[start..].iter().filter(radio).map(|l| format!("{l}\n")).collect()
}

/// Note timeline event `key` now, on the uptime clock, once per session: the first stands.
pub fn stamp(key: &str) {
    if let Some(ms) = timeline::now_ms() {
        note(MARKS, STAMP, key, ms);
    }
}

/// Note one of btd's chip times (`chip1_ms`, `cert1_ms`), once per session: the first stands.
pub fn duration(key: &str, ms: u64) {
    note(MARKS, DURATION, key, ms);
}

/// A `<kind> <key> <ms>` line in the record at `path`, unless it has one for `key` already.
fn note(path: &str, kind: &str, key: &str, ms: u64) {
    let have = std::fs::read_to_string(path).unwrap_or_default();
    if timeline::find(&have, kind, key).is_none() {
        append(path, &timeline::entry(kind, key, ms));
    }
}

/// Append `line` to the record at `path` when there is one (a session began it), in one write: the
/// shell appends to it too, and a line written in pieces could interleave with one of its.
fn append(path: &str, line: &str) {
    if let Ok(mut f) = std::fs::OpenOptions::new().append(true).open(path) {
        let _ = f.write_all(line.as_bytes());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_radio_bring_up_is_carried_into_each_session_it_serves() {
        // A new host's first session: the last host's record, its bring-up, then this host's appended by the shell.
        let prev = "t host 5000\nt sup_radios 5300\nt bt_power 5550\nt btd_start 5600\nt page_start 5700\n\
                    t sup_radios 61640\nt bt_power 62480\n";
        let fresh = "t host 61234\nt btd_start 62510\n";
        let rec = carry_over(prev, fresh);
        assert_eq!(rec, format!("{fresh}t sup_radios 61640\nt bt_power 62480\n"));

        // A second session under the same bring-up (the phone dropped and came back): the bring-up again, with the
        // AP's stamps that came since; the first session's own stamps and chip times stay behind.
        let prev2 = format!("{rec}t page_start 62530\nd chip1_ms 298\nt ap_start 62900\nt ap_up 63600\nt wifi_rsp 63930\n");
        let fresh2 = "t host 61234\nt btd_start 90100\n";
        let rec2 = carry_over(&prev2, fresh2);
        assert_eq!(rec2, format!("{fresh2}t sup_radios 61640\nt bt_power 62480\nt ap_start 62900\nt ap_up 63600\n"));
        assert_eq!(timeline::find(&rec2, STAMP, "page_start"), None, "the stamps are the new session's");
        assert_eq!(timeline::find(&rec2, DURATION, "chip1_ms"), None);
        assert_eq!(timeline::find(&rec2, STAMP, "btd_start"), Some(90100));

        // The radios were restarted under the same host (the app's Connect, a Restart Wi-Fi): the later bring-up is the one.
        let prev3 = format!("{rec2}t sup_radios 95000\nt bt_power 95400\n");
        assert_eq!(carry_over(&prev3, fresh2), format!("{fresh2}t sup_radios 95000\nt bt_power 95400\n"));

        // A host whose bring-up stamped nothing: the last host's is not carried. With no origin, the last one is.
        let old = "t host 5000\nt sup_radios 5300\nt bt_power 5550\n";
        assert_eq!(carry_over(old, fresh), fresh);
        assert_eq!(carry_over(old, ""), "t sup_radios 5300\nt bt_power 5550\n");
    }

    #[test]
    fn a_stamp_is_noted_once_and_only_in_a_record() {
        let path = std::env::temp_dir().join(format!("btd_marks_{}", std::process::id()));
        let p = path.to_str().unwrap();
        let _ = std::fs::remove_file(&path);
        note(p, STAMP, "acl", 62790);
        assert!(!path.exists(), "no session began a record: nothing is created");
        std::fs::write(&path, "t host 61234\n").unwrap();
        note(p, STAMP, "acl", 62790);
        note(p, STAMP, "acl", 63000);
        note(p, DURATION, "chip1_ms", 298);
        note(p, DURATION, "chip1_ms", 512);
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "t host 61234\nt acl 62790\nd chip1_ms 298\n");
        let _ = std::fs::remove_file(&path);
    }

    /// The record btd and the shell write is the one the line reads: stamps from both, against the origin.
    #[test]
    fn a_record_as_btd_and_the_shell_write_it_reads_as_a_line() {
        let rec = carry_over("t sup_radios 61640\nt bt_power 62480\n", "t host 61234\nt btd_start 62510\n")
            + "t ap_start 62900\nt acl 62790\nd chip1_ms 1650\n";
        let l = timeline::line(&rec, &[("first_frame", 66700)]);
        assert!(l.starts_with("[timeline] v1 sup_radios=406 bt_armed=0 bt_power=1246 btd_start=1276 "), "{l}");
        assert!(l.contains(" acl=1556 ") && l.contains(" ap_start=1666 ") && l.contains(" chip1_ms=1650 "), "{l}");
        assert!(l.contains(" first_frame=5466 "), "{l}");
        assert!(timeline::is_line(&l), "{l}");
    }
}
