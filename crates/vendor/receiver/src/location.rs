//! The car's GPS over the AirPlay iAP2 tunnel (docs/ops/08_FUTURE_TASKS.md T6): answer the iPhone's
//! `StartLocationInformation` (0xFFFA) with `LocationInformation` (0xFFFB) built from the NMEA
//! sentences the head-unit app sends.
//!
//! The Identify half — param 22 and the three message ids — is `iap2_core::message`, armed only when
//! the app pushed a `locationInformation:` block. This module is inert until the phone subscribes,
//! which it can only do after that Identify was accepted.
//!
//! The app sends a fix about once a second on OCBM `CH_INPUT` (`INPUT_LOCATION`, the sentences as
//! lines of text), and `carplayd` hands each one to [`ingest`]. There is no file and no thread: a fix
//! is forwarded as it arrives or dropped. A sentence goes out only when all of these hold: it is
//! well formed, its type was declared in param 22, the phone selected that type in 0xFFFA, and the
//! phone's minimum interval for that type has passed.
//!
//! PRIVACY. A sentence is a coordinate. Nothing here logs one, in whole or in part: the log carries
//! the phone's selection, counts and byte sizes only.

use std::sync::Mutex;
use std::time::{Duration, Instant};

use iap2_core::location::{self, SentenceKind};
use iap2_core::message::location_info as cap;
use iap2_core::spec;

/// Say how many updates have gone out this often, so a drive's log shows the feed is alive.
const REPORT: Duration = Duration::from_secs(300);

fn log(m: &str) {
    println!(
        "@{} [location] {m}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0)
    );
}

struct Sub {
    /// Which sentence types the phone asked for, and how often at most.
    sel: location::LocationSelection,
    /// The param-22 capabilities this session's Identify declared.
    caps: Vec<u16>,
    /// When a sentence of each type last went out.
    last: Vec<(SentenceKind, Instant)>,
    /// Updates sent since the subscribe, and since the last report line.
    sent: u64,
    reported_at: Instant,
    reported_sent: u64,
}

static SUB: Mutex<Option<Sub>> = Mutex::new(None);

/// The param-22 capability that covers a sentence type.
fn cap_for(kind: SentenceKind) -> u16 {
    match kind {
        SentenceKind::Gpgga => cap::GLOBAL_POSITIONING_SYSTEM_FIX_DATA,
        SentenceKind::Gprmc => cap::RECOMMENDED_MINIMUM_SPECIFIC_GPS_TRANSIT_DATA,
        SentenceKind::Gpgsv => cap::GPS_SATELLITES_IN_VIEW,
        SentenceKind::Pascd => cap::VEHICLE_SPEED_DATA,
        SentenceKind::Pagcd => cap::VEHICLE_GYRO_DATA,
        SentenceKind::Paacd => cap::VEHICLE_ACCELEROMETER_DATA,
        SentenceKind::Gphdt => cap::VEHICLE_HEADING_DATA,
    }
}

/// Handle 0xFFFA / 0xFFFC from the tunnel dispatcher. Returns true when `msg_id` was one of them.
pub(crate) fn handle(msg_id: u16, body: &[u8]) -> bool {
    match msg_id {
        spec::MSG_START_LOCATION_INFORMATION => {
            let sel = location::parse_start_location_information(body);
            let caps = iap2_core::config::Iap2Config::load()
                .vehicle_identity()
                .location_caps
                .unwrap_or_default();
            log(&format!("RX 0xFFFA StartLocationInformation: selected {sel:?}; declared caps {caps:?}"));
            let now = Instant::now();
            *crate::plock(&SUB) =
                Some(Sub { sel, caps, last: Vec::new(), sent: 0, reported_at: now, reported_sent: 0 });
            true
        }
        spec::MSG_STOP_LOCATION_INFORMATION => {
            let sent = crate::plock(&SUB).take().map(|s| s.sent).unwrap_or(0);
            log(&format!("RX 0xFFFC StopLocationInformation — updates stop ({sent} sent)"));
            true
        }
        _ => false,
    }
}

/// Forget the subscription when the tunnel session goes away (`iap_tunnel::reset`).
pub(crate) fn reset() {
    *crate::plock(&SUB) = None;
}

/// The sentences of `text` (one per line) that may go to the phone now, given what was declared,
/// what the phone selected, and when each type last went out. Pure, so the rules are testable.
fn admit<'a>(
    text: &'a str,
    sel: &location::LocationSelection,
    caps: &[u16],
    last: &[(SentenceKind, Instant)],
    now: Instant,
) -> Vec<(SentenceKind, &'a str)> {
    let mut out: Vec<(SentenceKind, &str)> = Vec::new();
    for line in text.lines().map(str::trim).filter(|l| !l.is_empty()) {
        if !location::is_well_formed_sentence(line) {
            continue;
        }
        let Some(kind) = SentenceKind::of(line) else { continue };
        if !caps.contains(&cap_for(kind)) || !sel.wants(kind) {
            continue;
        }
        // One sentence of a type per update: a second one in the same frame is a newer duplicate.
        if out.iter().any(|(k, _)| *k == kind) {
            continue;
        }
        let min = Duration::from_millis(sel.interval_min_ms(kind).unwrap_or(0) as u64);
        let too_soon = last
            .iter()
            .find(|(k, _)| *k == kind)
            .map(|(_, at)| now.saturating_duration_since(*at) < min)
            .unwrap_or(false);
        if !too_soon {
            out.push((kind, line));
        }
    }
    out
}

/// One fix from the app: NMEA sentences, one per line. Forwarded to the phone if it is subscribed.
/// Returns how many sentences went out. Never logs the text.
pub fn ingest(text: &str) -> usize {
    let (sel, caps, last) = {
        let g = crate::plock(&SUB);
        let Some(s) = g.as_ref() else { return 0 };
        (s.sel, s.caps.clone(), s.last.clone())
    };
    let now = Instant::now();
    let admitted = admit(text, &sel, &caps, &last, now);
    if admitted.is_empty() {
        return 0;
    }
    let sentences: Vec<&str> = admitted.iter().map(|(_, s)| *s).collect();
    let body = location::encode_location_information(&sentences);
    let (sent, n) = crate::iap_tunnel::send_subscribe(spec::MSG_LOCATION_INFORMATION, &body);
    if !sent {
        return 0;
    }
    let mut g = crate::plock(&SUB);
    let Some(s) = g.as_mut() else { return 0 };
    for (kind, _) in &admitted {
        s.last.retain(|(k, _)| k != kind);
        s.last.push((*kind, now));
    }
    s.sent += 1;
    if s.sent == 1 {
        log(&format!("TX 0xFFFB LocationInformation: first update, {} sentences, {n} B", admitted.len()));
    } else if now.saturating_duration_since(s.reported_at) >= REPORT {
        log(&format!(
            "TX 0xFFFB LocationInformation: {} updates in the last {} s ({} since the phone subscribed)",
            s.sent - s.reported_sent,
            now.saturating_duration_since(s.reported_at).as_secs(),
            s.sent
        ));
        s.reported_at = now;
        s.reported_sent = s.sent;
    }
    admitted.len()
}

#[cfg(test)]
mod tests {
    use super::*;

    // The NMEA textbook examples: invented coordinates, not a real place.
    const GGA: &str = "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47";
    const RMC: &str = "$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A";

    fn all() -> location::LocationSelection {
        location::LocationSelection {
            gps_fix_data: true,
            recommended_minimum_gps_transit: true,
            vehicle_speed_data: true,
            vehicle_heading_data: true,
            ..Default::default()
        }
    }

    const CAPS: &[u16] = &[
        cap::GLOBAL_POSITIONING_SYSTEM_FIX_DATA,
        cap::RECOMMENDED_MINIMUM_SPECIFIC_GPS_TRANSIT_DATA,
    ];

    fn kinds(v: &[(SentenceKind, &str)]) -> Vec<SentenceKind> {
        v.iter().map(|(k, _)| *k).collect()
    }

    #[test]
    fn declared_and_selected_sentences_are_admitted() {
        let text = format!("{GGA}\n{RMC}\n");
        let got = admit(&text, &all(), CAPS, &[], Instant::now());
        assert_eq!(kinds(&got), vec![SentenceKind::Gpgga, SentenceKind::Gprmc]);
        assert_eq!(got[0].1, GGA);
    }

    #[test]
    fn undeclared_unselected_and_malformed_sentences_are_dropped() {
        let now = Instant::now();
        // GPRMC not declared.
        let both = format!("{GGA}\n{RMC}");
        let got = admit(&both, &all(), &[cap::GLOBAL_POSITIONING_SYSTEM_FIX_DATA], &[], now);
        assert_eq!(kinds(&got), vec![SentenceKind::Gpgga]);
        // GPGGA not selected by the phone.
        let sel = location::LocationSelection { recommended_minimum_gps_transit: true, ..Default::default() };
        assert_eq!(kinds(&admit(&format!("{GGA}\n{RMC}"), &sel, CAPS, &[], now)), vec![SentenceKind::Gprmc]);
        // Wrong checksum, an unknown type, a blank line, and a repeat of a type already admitted.
        let text = format!("$GPGGA,1*00\n$GPGLL,1,2*4B\n\n{GGA}\n{GGA}\n");
        assert_eq!(kinds(&admit(&text, &all(), CAPS, &[], now)), vec![SentenceKind::Gpgga]);
        // Nothing declared, nothing sent.
        assert!(admit(&format!("{GGA}\n{RMC}"), &all(), &[], &[], now).is_empty());
    }

    #[test]
    fn the_phones_minimum_interval_is_kept_per_type() {
        let sel = location::LocationSelection { gpgga_interval_min_ms: Some(1000), ..all() };
        let now = Instant::now();
        let just_sent = [(SentenceKind::Gpgga, now), (SentenceKind::Gprmc, now)];
        let soon = now + Duration::from_millis(400);
        // GPGGA waits out its second; GPRMC has no minimum and goes.
        assert_eq!(
            kinds(&admit(&format!("{GGA}\n{RMC}"), &sel, CAPS, &just_sent, soon)),
            vec![SentenceKind::Gprmc]
        );
        let later = now + Duration::from_millis(1000);
        assert_eq!(
            kinds(&admit(&format!("{GGA}\n{RMC}"), &sel, CAPS, &just_sent, later)),
            vec![SentenceKind::Gpgga, SentenceKind::Gprmc]
        );
    }

    /// With no subscription a fix goes nowhere and nothing panics.
    #[test]
    fn a_fix_with_no_subscriber_is_dropped() {
        reset();
        assert_eq!(ingest(&format!("{GGA}\n{RMC}")), 0);
    }
}
