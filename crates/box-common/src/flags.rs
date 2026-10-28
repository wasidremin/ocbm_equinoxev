//! Session-arbitration flags — the `/tmp` substrate the box's processes read so a live session is
//! never interrupted by another connecting phone.
//!
//! `session_supervisor.sh` arbitrates wired↔wireless CarPlay via `/tmp/carplay_transport`
//! (= "wireless" when the wireless arm owns the box) and the unified `/tmp/projection_owner`.
//! `ProjectionOwner::as_str()` is byte-compatible with the shell: "wireless" == WirelessCp, so an
//! existing `carplay_transport` file still reads correctly.

use std::fs;

/// Single projection owner across ALL transports (supersedes the CarPlay-only `carplay_transport`).
pub const PROJECTION_OWNER: &str = "/tmp/projection_owner";
/// Legacy CarPlay-only ownership flag still written by the wireless arm (av.rs). Read for back-compat.
pub const CARPLAY_TRANSPORT: &str = "/tmp/carplay_transport";

/// Who owns the single phone-facing controller / projection session right now.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ProjectionOwner {
    /// Idle — no session; a new phone may claim the box.
    None,
    /// Wired CarPlay (iap2d + carplayd over NCM).
    WiredCp,
    /// Wireless CarPlay (btd: BT + WiFi AP). Byte-compatible with the legacy "wireless".
    WirelessCp,
}

impl ProjectionOwner {
    /// On-disk token. `WirelessCp` == "wireless" so an existing `carplay_transport` reads correctly.
    pub fn as_str(self) -> &'static str {
        match self {
            ProjectionOwner::None => "",
            ProjectionOwner::WiredCp => "wired-cp",
            ProjectionOwner::WirelessCp => "wireless",
        }
    }

    pub fn from_str(s: &str) -> ProjectionOwner {
        match s.trim() {
            "wired-cp" => ProjectionOwner::WiredCp,
            "wireless" | "wireless-cp" => ProjectionOwner::WirelessCp,
            _ => ProjectionOwner::None,
        }
    }

    /// The `PM_*` byte ocbmd puts on the wire in `CT_PROJ_MODE`, so the host app learns WHICH
    /// transport owns the box.
    pub fn wire_code(self) -> u8 {
        match self {
            ProjectionOwner::None => ocbm_proto::PM_NONE,
            ProjectionOwner::WiredCp => ocbm_proto::PM_WIRED_CP,
            ProjectionOwner::WirelessCp => ocbm_proto::PM_WIRELESS_CP,
        }
    }
}

/// Read the current projection owner, preferring the unified flag and falling back to the legacy
/// CarPlay-only `carplay_transport` (so this works before the shell is migrated).
pub fn owner() -> ProjectionOwner {
    if let Ok(s) = fs::read_to_string(PROJECTION_OWNER) {
        let o = ProjectionOwner::from_str(&s);
        if o != ProjectionOwner::None {
            return o;
        }
    }
    match fs::read_to_string(CARPLAY_TRANSPORT) {
        Ok(s) => ProjectionOwner::from_str(&s),
        Err(_) => ProjectionOwner::None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn owner_roundtrip_and_legacy_compat() {
        assert_eq!(ProjectionOwner::from_str("wireless"), ProjectionOwner::WirelessCp);
        assert_eq!(ProjectionOwner::WirelessCp.as_str(), "wireless");
        assert_eq!(ProjectionOwner::from_str("wired-cp"), ProjectionOwner::WiredCp);
        // The removed Android Auto tokens (2026-10-27) read as idle, never as a CarPlay owner.
        assert_eq!(ProjectionOwner::from_str("wired-aa"), ProjectionOwner::None);
        assert_eq!(ProjectionOwner::from_str("wireless-aa"), ProjectionOwner::None);
    }
}
