//! The receiver's events for the connect timeline (`box_common::timeline`, carplayd's
//! `timeline_own`): pair-verify done, auth-setup in and out, RECORD in and answered, the screen
//! stream's SETUP and the first video frame handed on. This crate only names the moment; the binary
//! that wants the timeline installs a [`Hook`] that stamps it on its clock and decides what to do with
//! it. No hook (the app's JNI build, the tests): nothing happens. Keeping the clock out of here keeps
//! this crate free of `unsafe` and of box-only code (2026-11-20).

use std::sync::OnceLock;

/// Called with the event's key, the timeline's own spelling (`pv_done`, `first_frame`, ...).
pub type Hook = fn(&'static str);

static HOOK: OnceLock<Hook> = OnceLock::new();

/// Install the hook, once per process; a second call is ignored.
pub fn set_hook(hook: Hook) {
    let _ = HOOK.set(hook);
}

/// The event `key` happened now.
pub fn event(key: &'static str) {
    if let Some(hook) = HOOK.get() {
        hook(key);
    }
}
