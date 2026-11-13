//! Vehicle status over the AirPlay iAP2 tunnel (docs/ops/08_FUTURE_TASKS.md T7): answer the
//! iPhone's `StartVehicleStatusUpdates` (0xA100) with `VehicleStatusUpdate` (0xA101) built from the
//! status file the head-unit app pushes.
//!
//! The Identify half — param 21 and the three message ids — is `iap2_core::message`, armed only when
//! the app pushed a `vehicleStatus:` block. This module is inert until the phone actually subscribes,
//! which it can only do after that Identify was accepted.
//!
//! One sender thread, started on the first subscribe, polls [`iap2_core::vehicle_status::STATUS_FILE`]
//! every [`POLL`]. It sends when the encoded body changes, and once straight after every subscribe so
//! the phone never waits for the car to change before it learns the current values. A body that
//! comes out empty (nothing declared, selected and known) is never sent.
//!
//! Two updates are never closer together than [`MIN_GAP`]. With the EV-routing models in the update
//! Apple Maps plans routes from it, and a project that sent every 5 s saw Maps recalculate the route
//! over and over (T7 "Cause found (2026-11-13)").

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use iap2_core::{spec, vehicle_status};

/// How often the status file is checked while subscribed.
const POLL: Duration = Duration::from_secs(2);
/// Re-send an unchanged body this often, so a lost frame or a phone that dropped its cached values
/// recovers without waiting for the battery to move.
const REFRESH: Duration = Duration::from_secs(60);
/// The shortest time between two updates. The first one after a subscribe is exempt.
const MIN_GAP: Duration = Duration::from_secs(10);

/// Whether `body` should go out now, given the last body sent and how long ago that was.
fn due(last: Option<(&[u8], Duration)>, body: &[u8]) -> bool {
    match last {
        None => true,
        Some((_, since)) if since < MIN_GAP => false,
        Some((sent, since)) => sent != body || since >= REFRESH,
    }
}

/// `status` for the log: the two models are a kilobyte of JSON that never changes within a drive,
/// so they are named by length.
fn summary(status: &vehicle_status::VehicleStatus) -> String {
    let len = |m: &Option<String>| m.as_ref().map(|s| s.len()).unwrap_or(0);
    let (charging, consumption) = (len(&status.charging_parameter), len(&status.consumption_parameter));
    let scalars = vehicle_status::VehicleStatus {
        charging_parameter: None,
        consumption_parameter: None,
        ..status.clone()
    };
    format!("{scalars:?}; models: charging {charging} B, consumption {consumption} B")
}

fn log(m: &str) {
    println!(
        "@{} [vehicle-status] {m}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0)
    );
}

struct Sub {
    /// Which selectable fields (3..=20) the phone asked for.
    sel: vehicle_status::VehicleStatusSelection,
    /// The param-21 capabilities this session's Identify declared.
    caps: Vec<u16>,
    /// The last body sent, and when; `None` forces a send on the next poll.
    last: Option<(Vec<u8>, Instant)>,
}

static SUB: Mutex<Option<Sub>> = Mutex::new(None);
static SENDER: AtomicBool = AtomicBool::new(false);

/// Handle 0xA100 / 0xA102 from the tunnel dispatcher. Returns true when `msg_id` was one of them.
pub(crate) fn handle(msg_id: u16, body: &[u8]) -> bool {
    match msg_id {
        spec::MSG_START_VEHICLE_STATUS_UPDATES => {
            let sel = vehicle_status::parse_start_vehicle_status_updates(body);
            // The caps the Identify declared come from the same pushed document it was built from.
            let caps = iap2_core::config::Iap2Config::load()
                .vehicle_identity()
                .status_caps
                .unwrap_or_default();
            log(&format!("RX 0xA100 StartVehicleStatusUpdates: selected {sel:?}; declared caps {caps:?}"));
            *crate::plock(&SUB) = Some(Sub { sel, caps, last: None });
            ensure_sender();
            true
        }
        spec::MSG_STOP_VEHICLE_STATUS_UPDATES => {
            log("RX 0xA102 StopVehicleStatusUpdates — updates stop");
            *crate::plock(&SUB) = None;
            true
        }
        _ => false,
    }
}

/// Forget the subscription when the tunnel session goes away (`iap_tunnel::reset`).
pub(crate) fn reset() {
    *crate::plock(&SUB) = None;
}

fn ensure_sender() {
    if SENDER.swap(true, Ordering::AcqRel) {
        return;
    }
    let spawned = std::thread::Builder::new()
        .name("vehicle-status".into())
        .spawn(|| loop {
            tick();
            std::thread::sleep(POLL);
        });
    if let Err(e) = spawned {
        log(&format!("could not start the sender thread: {e} — no updates this process"));
        SENDER.store(false, Ordering::Release);
    }
}

/// One poll: build the body from the file and send it if it changed or the refresh is due.
fn tick() {
    let (sel, caps, last) = {
        let g = crate::plock(&SUB);
        let Some(s) = g.as_ref() else { return };
        (s.sel, s.caps.clone(), s.last.clone())
    };
    let Some(status) = vehicle_status::read_status_file(vehicle_status::STATUS_FILE) else {
        return; // the app has not pushed anything yet
    };
    let body = vehicle_status::build_update(&status, &sel, &caps);
    if body.is_empty() {
        return;
    }
    if !due(last.as_ref().map(|(b, at)| (b.as_slice(), at.elapsed())), &body) {
        return;
    }
    let (sent, n) = crate::iap_tunnel::send_subscribe(spec::MSG_VEHICLE_STATUS_UPDATE, &body);
    if sent {
        if last.as_ref().map(|(b, _)| b != &body).unwrap_or(true) {
            log(&format!("TX 0xA101 VehicleStatusUpdate ({n} B): {}", summary(&status)));
        }
        if let Some(s) = crate::plock(&SUB).as_mut() {
            s.last = Some((body, Instant::now()));
        }
    } else {
        log("TX 0xA101 failed (no tunnel session) — retrying on the next poll");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const A: &[u8] = &[1, 2, 3];
    const B: &[u8] = &[1, 2, 4];

    /// The first update after a subscribe goes at once; after that a changed body waits out
    /// `MIN_GAP`, and an unchanged one waits for `REFRESH`.
    #[test]
    fn updates_are_spaced_out() {
        assert!(due(None, A));
        assert!(!due(Some((A, Duration::from_secs(2))), B), "a change inside the gap waits");
        assert!(!due(Some((A, MIN_GAP - Duration::from_millis(1))), B));
        assert!(due(Some((A, MIN_GAP)), B), "and goes once the gap has passed");
        assert!(!due(Some((A, Duration::from_secs(30))), A), "an unchanged body is not repeated yet");
        assert!(due(Some((A, REFRESH)), A), "until the refresh is due");
    }

    /// The log line names the models by length and still shows the scalar fields.
    #[test]
    fn the_log_summary_leaves_the_models_out() {
        let status = vehicle_status::VehicleStatus {
            range_km: Some(312),
            charging_parameter: Some("{\"model_id\":12582922}".into()),
            consumption_parameter: Some("{\"model_id\":12582921}".into()),
            ..Default::default()
        };
        let line = summary(&status);
        assert!(line.contains("312"));
        assert!(!line.contains("model_id"));
        assert!(line.ends_with("models: charging 21 B, consumption 21 B"));
    }
}
