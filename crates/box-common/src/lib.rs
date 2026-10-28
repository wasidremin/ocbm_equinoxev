//! box-common — box-side foundation shared by `ocbmd` and `btd`: the projection-owner flag, the
//! SoftAP gateway address, and the env-lever helper.

pub mod flags;
pub mod net;

/// The OCBM framing/channel layer, re-exported from `ocbm-proto` so every box crate references ONE
/// definition of the channel ids and frame codec.
pub mod ocbm {
    pub use ocbm_proto::*;
}

/// Read an environment lever by its current name, falling back to the pre-2026-09-08 `CARPLAY_*`
/// name it was renamed from.
///
/// The renamed levers are the PROTOCOL-NEUTRAL ones: Bluetooth SSP/HCI/RFCOMM settings and box-level
/// settings like the SoftAP role. Genuinely Apple levers (`CARPLAY_DEVICE_ID`, `CARPLAY_PI`,
/// `CARPLAY_HEVC`, …) keep their names: there the prefix is information, not noise.
///
/// The fallback exists because a lever that silently reverts to its default is the worst failure
/// shape here — `CARPLAY_WIFI_AP` was exported by the supervisor and read by NOTHING for as long as
/// the bridge role existed, and nothing reported it. So an old name still works, and says so.
pub fn lever(name: &str, legacy: &str) -> Option<String> {
    if let Ok(v) = std::env::var(name) {
        return Some(v);
    }
    match std::env::var(legacy) {
        Ok(v) => {
            eprintln!("[lever] {legacy} is the OLD name for {name} — honouring it, but update the launcher");
            Some(v)
        }
        Err(_) => None,
    }
}
