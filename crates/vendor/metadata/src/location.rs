//! Location plane: `LocationInformation` (0xFFFB) NMEA-sentence delivery + `StartLocationInformation`
//! (0xFFFA) selectable-sentence subscribe model.
//!
//! DIRECTION (from Apple's first-party spec `source` of truth):
//! - `StartLocationInformation` (0xFFFA) `source=1` (DEVICE→accessory): the iPhone SUBSCRIBES,
//!   selecting which NMEA sentence types it will accept (presence-only params 1..=7) and, optionally,
//!   a per-sentence minimum push interval (`NMEASentence*IntervalMin`, msecs32, ids in the 0x8000+
//!   range). The accessory PARSES it → [`parse_start_location_information`] yields a
//!   [`LocationSelection`].
//! - `LocationInformation` (0xFFFB) `source=0` (ACCESSORY→device): the accessory delivers one or more
//!   `NMEASentence` (utf8) values of the selected types. [`parse_location_information`] collects them.
//!
//! Param ids/types verbatim from Apple's `CarPlaySimulator.devicekitplugin` spec.

use crate::tlv::{parse_groups, u32v, utf8};
use serde::{Deserialize, Serialize};

/// Apple iAP2 parameter ids for the location plane. Defined locally (see dependency-cycle note in
/// `tlv.rs`). Each const carries Apple's authoritative name.
mod pid {
    /// `LocationInformation` (0xFFFB) param id.
    pub const NMEA_SENTENCE: u16 = 0; // NMEASentence (utf8)

    /// `StartLocationInformation` (0xFFFA) presence-only sentence-type selectors.
    pub const GPS_FIX_DATA: u16 = 1; // GlobalPositioningSystemFixData -> NMEA GPGGA
    pub const RECOMMENDED_MIN_GPS_TRANSIT: u16 = 2; // RecommendedMinimumSpecificGPSTransitData -> GPRMC
    pub const GPS_SATELLITES_IN_VIEW: u16 = 3; // GPSSatellitesInView -> GPGSV
    pub const VEHICLE_SPEED_DATA: u16 = 4; // VehicleSpeedData -> PASCD
    pub const VEHICLE_GYRO_DATA: u16 = 5; // VehicleGyroData -> PAGCD
    pub const VEHICLE_ACCELEROMETER_DATA: u16 = 6; // VehicleAccelerometerData -> PAACD
    pub const VEHICLE_HEADING_DATA: u16 = 7; // VehicleHeadingData -> GPHDT

    /// `StartLocationInformation` per-sentence minimum-interval params (msecs32), ids 0x8000+.
    pub const INTERVAL_RSVD: u16 = 0x8000; // NMEASentenceRSVDIntervalMin
    pub const INTERVAL_GPGGA: u16 = 0x8001; // NMEASentenceGPGGAIntervalMin
    pub const INTERVAL_GPRMC: u16 = 0x8002; // NMEASentenceGPRMCIntervalMin
    pub const INTERVAL_GPGSV: u16 = 0x8003; // NMEASentenceGPGSVIntervalMin
    pub const INTERVAL_PASCD: u16 = 0x8004; // NMEASentencePASCDIntervalMin
    pub const INTERVAL_PAGCD: u16 = 0x8005; // NMEASentencePAGCDIntervalMin
    pub const INTERVAL_PAACD: u16 = 0x8006; // NMEASentencePAACDIntervalMin
    pub const INTERVAL_GPHDT: u16 = 0x8007; // NMEASentenceGPHDTIntervalMin
}

/// A `LocationInformation` (0xFFFB) delivery: the raw NMEA-0183 sentence strings the accessory
/// pushed. Apple's note: "One NMEA Sentence of the type(s) specified by StartLocationInformation.
/// Multiple [may be present]" — so a single update can carry several `NMEASentence` (id 0) params;
/// we collect them in wire order.
#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq, Eq)]
pub struct LocationInfo {
    /// Every `NMEASentence` (id 0) in this update, in wire order, e.g.
    /// `"$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"`.
    pub sentences: Vec<String>,
}

impl LocationInfo {
    /// Replace the running sentence set with a non-empty delta (each `LocationInformation` carries a
    /// fresh fix). An empty delta leaves the last known sentences in place, consistent with the
    /// Option-merge convention elsewhere in the crate ("nothing new here").
    pub fn merge(&mut self, delta: LocationInfo) {
        if !delta.sentences.is_empty() {
            self.sentences = delta.sentences;
        }
    }
}

/// Which NMEA sentence types and per-sentence minimum intervals the device requested, decoded from a
/// `StartLocationInformation` (0xFFFA) body. The `*` bool flags are presence-only selectors; the
/// `*_interval_min_ms` values are the optional `NMEASentence*IntervalMin` (msecs32) throttles.
#[derive(Serialize, Deserialize, Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct LocationSelection {
    /// `GlobalPositioningSystemFixData` (id 1): accessory may send GPGGA sentences.
    pub gps_fix_data: bool,
    /// `RecommendedMinimumSpecificGPSTransitData` (id 2): may send GPRMC.
    pub recommended_minimum_gps_transit: bool,
    /// `GPSSatellitesInView` (id 3): may send GPGSV.
    pub gps_satellites_in_view: bool,
    /// `VehicleSpeedData` (id 4): may send PASCD.
    pub vehicle_speed_data: bool,
    /// `VehicleGyroData` (id 5): may send PAGCD.
    pub vehicle_gyro_data: bool,
    /// `VehicleAccelerometerData` (id 6): may send PAACD.
    pub vehicle_accelerometer_data: bool,
    /// `VehicleHeadingData` (id 7): may send GPHDT.
    pub vehicle_heading_data: bool,

    /// `NMEASentenceRSVDIntervalMin` (id 0x8000), ms.
    pub rsvd_interval_min_ms: Option<u32>,
    /// `NMEASentenceGPGGAIntervalMin` (id 0x8001), ms.
    pub gpgga_interval_min_ms: Option<u32>,
    /// `NMEASentenceGPRMCIntervalMin` (id 0x8002), ms.
    pub gprmc_interval_min_ms: Option<u32>,
    /// `NMEASentenceGPGSVIntervalMin` (id 0x8003), ms.
    pub gpgsv_interval_min_ms: Option<u32>,
    /// `NMEASentencePASCDIntervalMin` (id 0x8004), ms.
    pub pascd_interval_min_ms: Option<u32>,
    /// `NMEASentencePAGCDIntervalMin` (id 0x8005), ms.
    pub pagcd_interval_min_ms: Option<u32>,
    /// `NMEASentencePAACDIntervalMin` (id 0x8006), ms.
    pub paacd_interval_min_ms: Option<u32>,
    /// `NMEASentenceGPHDTIntervalMin` (id 0x8007), ms.
    pub gphdt_interval_min_ms: Option<u32>,
}

/// The longest sentence [`encode_location_information`] will frame. NMEA-0183 caps a sentence at 82
/// characters; Apple's `PASCD` can carry several samples, so allow some room.
pub const MAX_SENTENCE_BYTES: usize = 255;

/// The sentence types an accessory can send, by the address field after `$`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum SentenceKind {
    Gpgga,
    Gprmc,
    Gpgsv,
    Pascd,
    Pagcd,
    Paacd,
    Gphdt,
}

impl SentenceKind {
    /// Which type a sentence is, or `None` for anything the phone has no selector for.
    pub fn of(sentence: &str) -> Option<Self> {
        Some(match sentence.get(1..6)? {
            "GPGGA" => Self::Gpgga,
            "GPRMC" => Self::Gprmc,
            "GPGSV" => Self::Gpgsv,
            "PASCD" => Self::Pascd,
            "PAGCD" => Self::Pagcd,
            "PAACD" => Self::Paacd,
            "GPHDT" => Self::Gphdt,
            _ => return None,
        })
    }
}

impl LocationSelection {
    /// Whether the phone asked for this sentence type in `StartLocationInformation`.
    pub fn wants(&self, kind: SentenceKind) -> bool {
        match kind {
            SentenceKind::Gpgga => self.gps_fix_data,
            SentenceKind::Gprmc => self.recommended_minimum_gps_transit,
            SentenceKind::Gpgsv => self.gps_satellites_in_view,
            SentenceKind::Pascd => self.vehicle_speed_data,
            SentenceKind::Pagcd => self.vehicle_gyro_data,
            SentenceKind::Paacd => self.vehicle_accelerometer_data,
            SentenceKind::Gphdt => self.vehicle_heading_data,
        }
    }

    /// The shortest time the phone wants between two sentences of this type, if it said.
    pub fn interval_min_ms(&self, kind: SentenceKind) -> Option<u32> {
        match kind {
            SentenceKind::Gpgga => self.gpgga_interval_min_ms,
            SentenceKind::Gprmc => self.gprmc_interval_min_ms,
            SentenceKind::Gpgsv => self.gpgsv_interval_min_ms,
            SentenceKind::Pascd => self.pascd_interval_min_ms,
            SentenceKind::Pagcd => self.pagcd_interval_min_ms,
            SentenceKind::Paacd => self.paacd_interval_min_ms,
            SentenceKind::Gphdt => self.gphdt_interval_min_ms,
        }
    }
}

/// Whether `s` is a sentence that can go on the wire: `$`, printable ASCII only, a `*HH` checksum
/// that matches, and at most [`MAX_SENTENCE_BYTES`]. No line ending; the TLV's NUL ends it.
pub fn is_well_formed_sentence(s: &str) -> bool {
    let b = s.as_bytes();
    if b.len() < 9 || b.len() > MAX_SENTENCE_BYTES || b[0] != b'$' {
        return false;
    }
    if !b.iter().all(|c| (0x20..=0x7E).contains(c)) {
        return false;
    }
    let Some(star) = s.rfind('*') else { return false };
    if star + 3 != b.len() {
        return false;
    }
    let sum = b[1..star].iter().fold(0u8, |a, c| a ^ c);
    u8::from_str_radix(&s[star + 1..], 16).map(|want| want == sum).unwrap_or(false)
}

/// Encode a `LocationInformation` (0xFFFB, accessory-source) body: one `NMEASentence` (id 0, utf8,
/// NUL-terminated) per sentence, in the order given. Sentences that are not well formed are left
/// out, so nothing malformed reaches the phone's parser.
pub fn encode_location_information<S: AsRef<str>>(sentences: &[S]) -> Vec<u8> {
    let mut o = Vec::new();
    for s in sentences.iter().map(AsRef::as_ref).filter(|s| is_well_formed_sentence(s)) {
        o.extend_from_slice(&((4 + s.len() + 1) as u16).to_be_bytes());
        o.extend_from_slice(&pid::NMEA_SENTENCE.to_be_bytes());
        o.extend_from_slice(s.as_bytes());
        o.push(0);
    }
    o
}

/// Parse a `LocationInformation` (0xFFFB, accessory-source) body: a flat sequence of `NMEASentence`
/// (id 0, utf8) params. Any non-zero param id is ignored.
pub fn parse_location_information(body: &[u8]) -> LocationInfo {
    let mut info = LocationInfo::default();
    for (id, val) in parse_groups(body) {
        if id == pid::NMEA_SENTENCE {
            if let Some(s) = utf8(val) {
                info.sentences.push(s);
            }
        }
    }
    info
}

/// Parse a `StartLocationInformation` (0xFFFA, device-source) body into the selected sentence types
/// and per-sentence minimum intervals.
pub fn parse_start_location_information(body: &[u8]) -> LocationSelection {
    let mut sel = LocationSelection::default();
    for (id, val) in parse_groups(body) {
        match id {
            pid::GPS_FIX_DATA => sel.gps_fix_data = true,
            pid::RECOMMENDED_MIN_GPS_TRANSIT => sel.recommended_minimum_gps_transit = true,
            pid::GPS_SATELLITES_IN_VIEW => sel.gps_satellites_in_view = true,
            pid::VEHICLE_SPEED_DATA => sel.vehicle_speed_data = true,
            pid::VEHICLE_GYRO_DATA => sel.vehicle_gyro_data = true,
            pid::VEHICLE_ACCELEROMETER_DATA => sel.vehicle_accelerometer_data = true,
            pid::VEHICLE_HEADING_DATA => sel.vehicle_heading_data = true,
            pid::INTERVAL_RSVD => sel.rsvd_interval_min_ms = u32v(val),
            pid::INTERVAL_GPGGA => sel.gpgga_interval_min_ms = u32v(val),
            pid::INTERVAL_GPRMC => sel.gprmc_interval_min_ms = u32v(val),
            pid::INTERVAL_GPGSV => sel.gpgsv_interval_min_ms = u32v(val),
            pid::INTERVAL_PASCD => sel.pascd_interval_min_ms = u32v(val),
            pid::INTERVAL_PAGCD => sel.pagcd_interval_min_ms = u32v(val),
            pid::INTERVAL_PAACD => sel.paacd_interval_min_ms = u32v(val),
            pid::INTERVAL_GPHDT => sel.gphdt_interval_min_ms = u32v(val),
            _ => {}
        }
    }
    sel
}

#[cfg(test)]
mod tests {
    use super::*;

    fn group(pid: u16, value: &[u8]) -> Vec<u8> {
        let mut g = Vec::new();
        g.extend_from_slice(&((4 + value.len()) as u16).to_be_bytes());
        g.extend_from_slice(&pid.to_be_bytes());
        g.extend_from_slice(value);
        g
    }

    fn str_val(s: &str) -> Vec<u8> {
        let mut v = s.as_bytes().to_vec();
        v.push(0);
        v
    }

    #[test]
    fn parse_location_information_collects_multiple_nmea_sentences() {
        let gga = "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47";
        let rmc = "$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A";
        let body = [
            group(pid::NMEA_SENTENCE, &str_val(gga)),
            group(pid::NMEA_SENTENCE, &str_val(rmc)),
        ]
        .concat();
        let info = parse_location_information(&body);
        assert_eq!(info.sentences, vec![gga.to_string(), rmc.to_string()]);
    }

    #[test]
    fn parse_start_location_selects_types_and_intervals() {
        let body = [
            group(pid::GPS_FIX_DATA, &[]),          // GPGGA
            group(pid::RECOMMENDED_MIN_GPS_TRANSIT, &[]), // GPRMC
            group(pid::VEHICLE_HEADING_DATA, &[]),  // GPHDT
            group(pid::INTERVAL_GPGGA, &1000u32.to_be_bytes()),
            group(pid::INTERVAL_GPRMC, &500u32.to_be_bytes()),
        ]
        .concat();
        let sel = parse_start_location_information(&body);
        assert!(sel.gps_fix_data && sel.recommended_minimum_gps_transit && sel.vehicle_heading_data);
        assert!(!sel.gps_satellites_in_view && !sel.vehicle_speed_data);
        assert_eq!(sel.gpgga_interval_min_ms, Some(1000));
        assert_eq!(sel.gprmc_interval_min_ms, Some(500));
        assert_eq!(sel.gpgsv_interval_min_ms, None);
    }

    /// The textbook sentences (invented coordinates from the NMEA examples, not a real place).
    const GGA: &str = "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47";
    const RMC: &str = "$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A";

    #[test]
    fn encode_round_trips_and_drops_malformed_sentences() {
        let body = encode_location_information(&[GGA, "$GPGGA,bad*00", "no dollar*00", RMC]);
        assert_eq!(
            parse_location_information(&body).sentences,
            vec![GGA.to_string(), RMC.to_string()]
        );
        assert!(encode_location_information::<&str>(&[]).is_empty());
    }

    #[test]
    fn well_formed_means_dollar_printable_and_a_matching_checksum() {
        assert!(is_well_formed_sentence(GGA));
        assert!(!is_well_formed_sentence("$GPGGA,123519*00"), "wrong checksum");
        assert!(!is_well_formed_sentence(&GGA.replace('$', "")));
        assert!(!is_well_formed_sentence(&format!("{GGA}\r\n")), "no line ending on the wire");
        assert!(!is_well_formed_sentence(&GGA.replace("545.4", "545\u{0}4")));
        let long = format!("$GPGGA,{}", "1".repeat(MAX_SENTENCE_BYTES));
        assert!(!is_well_formed_sentence(&long));
    }

    #[test]
    fn sentences_are_classified_and_matched_to_the_selection() {
        assert_eq!(SentenceKind::of(GGA), Some(SentenceKind::Gpgga));
        assert_eq!(SentenceKind::of(RMC), Some(SentenceKind::Gprmc));
        assert_eq!(SentenceKind::of("$PASCD,1.0,C,D,0,1,0.00,5.2*00"), Some(SentenceKind::Pascd));
        assert_eq!(SentenceKind::of("$GPGLL,1,2*00"), None);
        assert_eq!(SentenceKind::of("$GP"), None);
        let sel = LocationSelection {
            gps_fix_data: true,
            gpgga_interval_min_ms: Some(1000),
            ..Default::default()
        };
        assert!(sel.wants(SentenceKind::Gpgga) && !sel.wants(SentenceKind::Gprmc));
        assert_eq!(sel.interval_min_ms(SentenceKind::Gpgga), Some(1000));
        assert_eq!(sel.interval_min_ms(SentenceKind::Pascd), None);
    }

    #[test]
    fn merge_keeps_last_fix_when_delta_is_empty() {
        let mut snap = LocationInfo { sentences: vec!["$GPGGA,old".into()] };
        snap.merge(LocationInfo::default());
        assert_eq!(snap.sentences, vec!["$GPGGA,old".to_string()]);
        snap.merge(LocationInfo { sentences: vec!["$GPGGA,new".into()] });
        assert_eq!(snap.sentences, vec!["$GPGGA,new".to_string()]);
    }
}
