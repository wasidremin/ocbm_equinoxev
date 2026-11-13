//! The accessory side of iAP2 vehicle status (docs/ops/08_FUTURE_TASKS.md T7): what the box tells the
//! iPhone about the car's range and battery, in `VehicleStatusUpdate` (0xA101).
//!
//! DIRECTION. The phone subscribes to US: it sends `StartVehicleStatusUpdates` (0xA100, selecting
//! fields) and `StopVehicleStatusUpdates` (0xA102); the accessory answers with 0xA101. So the Identify
//! declares 0xA101 in param 6 and 0xA100/0xA102 in param 7 — inverted against every metadata feed in
//! `features.rs`, which is why this is not a `features::Feature` (docs/carplay/05_METADATA_AND_CONTROLS.md §1.3).
//!
//! DATA. The box has no vehicle bus. The head-unit app reads the car's own properties (AAOS VHAL) and
//! pushes them to [`STATUS_FILE`] over OCBM `CH_FILE`, as `key=value` lines ([`parse_status_text`]).
//! The file lives on tmpfs, so a reboot clears it and nothing stale outlives the app.
//!
//! WHAT IS SENT. [`build_update`] includes a field only when BOTH hold: the capability that covers it
//! was declared in Identify param 21 (`status_caps`), and — for the selectable fields 3..=20 — the
//! phone selected it in 0xA100. The EV fields (21..=30) are not selectable; they ride the
//! `ElectricChargeInfo` / `MaxRangeInfo` capabilities alone.
//!
//! EV ROUTING. Apple Maps does not count the accessory as an electric car until an update carries
//! both JSON models, `ChargingParameter` (26) and `ConsumptionParameter` (27); see T7 "Cause found
//! (2026-11-13)". The app builds both documents and this module relays them unparsed: what is in
//! them is the app's to choose, and the box only refuses a string it could not frame.

use carplay_metadata::vehicle::encode_vehicle_status_update;
// Re-exported so the receiver needs no direct dependency on `carplay-metadata`.
pub use carplay_metadata::vehicle::parse_start_vehicle_status_updates;
pub use carplay_metadata::{VehicleStatus, VehicleStatusSelection};

use crate::message::vehicle_status as cap;

/// Where the app's `CH_FILE` push lands. tmpfs.
pub const STATUS_FILE: &str = "/tmp/vehicle_status";

/// Refuse to parse an implausibly large file. The app's real one is about 1 KB with both EV-routing
/// models in it. Firmware before 2026-11-13 stopped at 4096, so the app keeps its file under that.
const MAX_STATUS_BYTES: u64 = 16 * 1024;

/// Read and parse [`STATUS_FILE`] (or a test path). `None` when it is absent, unreadable or too big.
pub fn read_status_file(path: &str) -> Option<VehicleStatus> {
    let meta = std::fs::metadata(path).ok()?;
    if meta.len() > MAX_STATUS_BYTES {
        return None;
    }
    Some(parse_status_text(&std::fs::read_to_string(path).ok()?))
}

/// Parse the app's `key=value` lines. Unknown keys and malformed values are ignored, so a newer app
/// writing an extra key cannot break an older box.
///
/// | key | field | unit |
/// |---|---|---|
/// | `range_km` | Range (3) and RangeElectric (11) — the car is electric-only | km |
/// | `outside_c` | OutsideTemperature (4) | °C |
/// | `range_warning` | RangeWarning (6) and RangeWarningElectric (15) | `0`/`1` |
/// | `current_wh` | CurrentBatteryCharge (22) | Wh |
/// | `max_wh` | MaxBatteryCharge (23) | Wh |
/// | `percent_milli` | DisplayedBatteryPercentage (24) | % × 1000 |
/// | `charging` | isCharging (25) | `0`/`1` |
/// | `min_wh` | MinBatteryCharge (21) | Wh |
/// | `charging_json` | ChargingParameter (26) | one line of JSON |
/// | `consumption_json` | ConsumptionParameter (27) | one line of JSON |
/// | `max_range_km` | MaxRangeElectric (30) | km |
pub fn parse_status_text(text: &str) -> VehicleStatus {
    let mut v = VehicleStatus::default();
    for line in text.lines() {
        let Some((k, val)) = line.split_once('=') else { continue };
        let val = val.trim();
        let flag = || match val {
            "1" | "true" => Some(true),
            "0" | "false" => Some(false),
            _ => None,
        };
        match k.trim() {
            "range_km" => {
                v.range_km = val.parse().ok();
                v.range_electric_km = v.range_km;
            }
            "outside_c" => v.outside_temperature_c = val.parse().ok(),
            "range_warning" => {
                v.range_warning = flag();
                v.range_warning_electric = v.range_warning;
            }
            "current_wh" => v.current_battery_charge_wh = val.parse().ok(),
            "max_wh" => v.max_battery_charge_wh = val.parse().ok(),
            // DisplayedBatteryPercentage is % × 1000, so 100 % is 100000. Anything above is garbage.
            "percent_milli" => {
                v.displayed_battery_percentage_milli = val.parse().ok().filter(|p| *p <= 100_000)
            }
            "charging" => v.is_charging = flag(),
            "min_wh" => v.min_battery_charge_wh = val.parse().ok(),
            "charging_json" => v.charging_parameter = model_json(val),
            "consumption_json" => v.consumption_parameter = model_json(val),
            // A full-charge range of 0 km is a missing reading, not a car.
            "max_range_km" => v.max_range_electric_km = val.parse().ok().filter(|km| *km > 0),
            _ => {}
        }
    }
    v
}

/// An EV-routing model as the app wrote it: one JSON object on one line. Checked only as far as the
/// box needs in order to put it in a NUL-terminated iAP2 string: printable ASCII, braces at both
/// ends, bounded length. Whether Maps accepts what is inside is between the app and the phone.
fn model_json(val: &str) -> Option<String> {
    let ok = val.len() >= 2
        && val.len() <= carplay_metadata::vehicle::MAX_MODEL_BYTES
        && val.starts_with('{')
        && val.ends_with('}')
        && val.bytes().all(|b| (0x20..=0x7E).contains(&b));
    ok.then(|| val.to_string())
}

/// The 0xA101 body for `status`, restricted to what was declared (`caps`, Identify param 21 sub ids)
/// and, for fields 3..=20, to what the phone selected (`sel`). Empty when nothing qualifies.
pub fn build_update(status: &VehicleStatus, sel: &VehicleStatusSelection, caps: &[u16]) -> Vec<u8> {
    let has = |c: u16| caps.contains(&c);
    let ev = has(cap::ELECTRIC_CHARGE_INFO);
    let out = VehicleStatus {
        range_km: status.range_km.filter(|_| has(cap::RANGE) && sel.range),
        outside_temperature_c: status
            .outside_temperature_c
            .filter(|_| has(cap::OUTSIDE_TEMPERATURE) && sel.outside_temperature),
        range_warning: status.range_warning.filter(|_| has(cap::RANGE_WARNING) && sel.range_warning),
        range_electric_km: status
            .range_electric_km
            .filter(|_| has(cap::RANGE_ELECTRIC) && sel.range_electric),
        range_warning_electric: status
            .range_warning_electric
            .filter(|_| has(cap::RANGE_WARNING_ELECTRIC) && sel.range_warning_electric),
        min_battery_charge_wh: status.min_battery_charge_wh.filter(|_| ev),
        current_battery_charge_wh: status.current_battery_charge_wh.filter(|_| ev),
        max_battery_charge_wh: status.max_battery_charge_wh.filter(|_| ev),
        displayed_battery_percentage_milli: status.displayed_battery_percentage_milli.filter(|_| ev),
        is_charging: status.is_charging.filter(|_| ev),
        charging_parameter: status.charging_parameter.clone().filter(|_| ev),
        consumption_parameter: status.consumption_parameter.clone().filter(|_| ev),
        max_range_electric_km: status.max_range_electric_km.filter(|_| has(cap::MAX_RANGE_INFO)),
        ..Default::default()
    };
    encode_vehicle_status_update(&out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use carplay_metadata::vehicle::parse_vehicle_status_update;

    const SAMPLE: &str = "range_km=312\noutside_c=-4\nrange_warning=0\ncurrent_wh=52000\n\
                          max_wh=84590\npercent_milli=61473\ncharging=1\nfuture_key=7\n";

    fn all_selected() -> VehicleStatusSelection {
        VehicleStatusSelection {
            range: true,
            outside_temperature: true,
            range_warning: true,
            range_electric: true,
            range_warning_electric: true,
            ..Default::default()
        }
    }

    const EV_CAPS: &[u16] = &[
        cap::RANGE,
        cap::OUTSIDE_TEMPERATURE,
        cap::RANGE_WARNING,
        cap::RANGE_ELECTRIC,
        cap::RANGE_WARNING_ELECTRIC,
        cap::ELECTRIC_CHARGE_INFO,
    ];

    #[test]
    fn the_app_file_parses_and_ignores_unknown_keys() {
        let v = parse_status_text(SAMPLE);
        assert_eq!(v.range_km, Some(312));
        assert_eq!(v.range_electric_km, Some(312));
        assert_eq!(v.outside_temperature_c, Some(-4));
        assert_eq!(v.range_warning, Some(false));
        assert_eq!(v.range_warning_electric, Some(false));
        assert_eq!(v.current_battery_charge_wh, Some(52_000));
        assert_eq!(v.max_battery_charge_wh, Some(84_590));
        assert_eq!(v.displayed_battery_percentage_milli, Some(61_473));
        assert_eq!(v.is_charging, Some(true));
    }

    #[test]
    fn malformed_values_are_dropped_not_guessed() {
        let v = parse_status_text("range_km=-3\npercent_milli=250000\ncharging=maybe\nnoequals\n");
        assert_eq!(v, VehicleStatus::default());
    }

    #[test]
    fn everything_declared_and_selected_goes_out() {
        let body = build_update(&parse_status_text(SAMPLE), &all_selected(), EV_CAPS);
        let back = parse_vehicle_status_update(&body);
        assert_eq!(back.range_km, Some(312));
        assert_eq!(back.range_electric_km, Some(312));
        assert_eq!(back.displayed_battery_percentage_milli, Some(61_473));
        assert_eq!(back.is_charging, Some(true));
    }

    /// A field the phone did not select must not be sent, even when declared.
    #[test]
    fn unselected_fields_stay_off_the_wire() {
        let sel = VehicleStatusSelection { range_electric: true, ..Default::default() };
        let back = parse_vehicle_status_update(&build_update(&parse_status_text(SAMPLE), &sel, EV_CAPS));
        assert_eq!(back.range_electric_km, Some(312));
        assert_eq!(back.range_km, None);
        assert_eq!(back.outside_temperature_c, None);
        // EV fields are not selectable; the ElectricChargeInfo capability alone carries them.
        assert_eq!(back.current_battery_charge_wh, Some(52_000));
    }

    /// Nothing undeclared goes out, whatever the phone asks for.
    #[test]
    fn undeclared_capabilities_are_never_sent() {
        let body = build_update(&parse_status_text(SAMPLE), &all_selected(), &[cap::RANGE_ELECTRIC]);
        let back = parse_vehicle_status_update(&body);
        assert_eq!(back.range_electric_km, Some(312));
        assert_eq!(back.range_km, None);
        assert_eq!(back.current_battery_charge_wh, None);
        assert_eq!(back.is_charging, None);
        assert!(build_update(&parse_status_text(SAMPLE), &all_selected(), &[]).is_empty());
    }

    const CHARGING: &str = "{\"model_id\":12582922,\"vehicle_parameters\":{\"efficiency_factor\":0.93}}";
    const CONSUMPTION: &str =
        "{\"model_id\":12582921,\"vehicle_parameters\":{\"vehicle_auxiliary_power_w\":500}}";

    fn routing_sample() -> String {
        format!("{SAMPLE}min_wh=0\nmax_range_km=540\ncharging_json={CHARGING}\nconsumption_json={CONSUMPTION}\n")
    }

    /// The four EV-routing keys parse, and the models arrive byte for byte.
    #[test]
    fn the_ev_routing_keys_parse() {
        let v = parse_status_text(&routing_sample());
        assert_eq!(v.min_battery_charge_wh, Some(0));
        assert_eq!(v.max_range_electric_km, Some(540));
        assert_eq!(v.charging_parameter.as_deref(), Some(CHARGING));
        assert_eq!(v.consumption_parameter.as_deref(), Some(CONSUMPTION));
        // The older keys are untouched by the new ones.
        assert_eq!(v.current_battery_charge_wh, Some(52_000));
    }

    /// A model the box could not frame is dropped; so is a zero full-charge range.
    #[test]
    fn unframeable_models_are_dropped() {
        for bad in ["", "{", "not json", "{\"a\":\"\u{e9}\"}", "{\"a\":\t1}", "[1,2]"] {
            let v = parse_status_text(&format!("charging_json={bad}\nconsumption_json={bad}\n"));
            assert_eq!(v.charging_parameter, None, "{bad:?}");
            assert_eq!(v.consumption_parameter, None, "{bad:?}");
        }
        let long = format!("{{\"a\":\"{}\"}}", "x".repeat(carplay_metadata::vehicle::MAX_MODEL_BYTES));
        assert_eq!(parse_status_text(&format!("charging_json={long}\n")).charging_parameter, None);
        assert_eq!(parse_status_text("max_range_km=0\n").max_range_electric_km, None);
    }

    /// The models and the minimum ride ElectricChargeInfo; the full-charge range rides MaxRangeInfo.
    #[test]
    fn the_models_ride_electric_charge_info() {
        let status = parse_status_text(&routing_sample());
        let with_max: Vec<u16> = EV_CAPS.iter().copied().chain([cap::MAX_RANGE_INFO]).collect();
        let back = parse_vehicle_status_update(&build_update(&status, &all_selected(), &with_max));
        assert_eq!(back.min_battery_charge_wh, Some(0));
        assert_eq!(back.charging_parameter.as_deref(), Some(CHARGING));
        assert_eq!(back.consumption_parameter.as_deref(), Some(CONSUMPTION));
        assert_eq!(back.max_range_electric_km, Some(540));

        // Without MaxRangeInfo the range at full charge stays off the wire; the models still go.
        let back = parse_vehicle_status_update(&build_update(&status, &all_selected(), EV_CAPS));
        assert_eq!(back.max_range_electric_km, None);
        assert_eq!(back.charging_parameter.as_deref(), Some(CHARGING));

        // Without ElectricChargeInfo none of the EV-routing fields go.
        let back =
            parse_vehicle_status_update(&build_update(&status, &all_selected(), &[cap::RANGE_ELECTRIC]));
        assert_eq!(back.min_battery_charge_wh, None);
        assert_eq!(back.charging_parameter, None);
        assert_eq!(back.consumption_parameter, None);
    }

    /// An older app's file (no routing keys) builds the same update as before.
    #[test]
    fn an_older_apps_file_sends_no_models() {
        let back = parse_vehicle_status_update(&build_update(&parse_status_text(SAMPLE), &all_selected(), EV_CAPS));
        assert_eq!(back.min_battery_charge_wh, None);
        assert_eq!(back.charging_parameter, None);
        assert_eq!(back.consumption_parameter, None);
        assert_eq!(back.max_range_electric_km, None);
    }

    #[test]
    fn a_missing_file_reads_as_none() {
        assert!(read_status_file("/nonexistent/vehicle_status").is_none());
    }
}
