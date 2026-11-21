//! Route A (T34): the phone's AirPlay video inside the CarPlay session, as iOS 26 and later let a head unit
//! offer it. The receiver offers it when the app pushed `enablesVideoPlayback` (`levers::video_playback`):
//! `/info` carries `videoPlaybackInfo` and `playbackCapabilities` ([`info_keys`]), and the phase-1 SETUP
//! echoes `videoPlayback` when the phone proposes it ([`echoes_video`]). The app decides when video may play, only while the car is known to be
//! parked (`setVideoPlaybackAllowed`, [`allowed_command`], sent by `playback.rs`), runs the play queue that
//! `playback.rs` relays, and fetches the video through `tunnel.rs`.
//!
//! Ported from enigy/ocbm `crates/vendor/receiver/src/probe.rs` at 801074f (Unlicense; NOTICE.md), keeping
//! the offer and the log's shape helpers. The probe's bench switches and its request logging are not
//! taken. Changed here: enigy offers video in every session; and a URL shows its scheme and size, never
//! its host, because this log is uploaded and must carry numbers only.
//!
//! Every line written here starts `[video]`. Keys, value types and sizes only, except the string values
//! under [`NAME_KEYS`] (a message's `type`, `kind` and the like, protocol names) and a DataStream's client
//! type. A line repeated word for word is counted, not logged each time.

use std::sync::atomic::{AtomicU32, Ordering};

use plist::{Dictionary, Value};

/// One `[video]` line. A line that repeats one of the last [`REMEMBERED`] word for word (the phone's
/// `playbackInfo` poll, once a second) is counted, and logged again only every [`REPEAT_EVERY`]th time with
/// its count. Of what is left, the first 400 go out whole, then one in 100 with its number, so a chatty
/// channel can't fill the tmpfs log (enigy's bench, 10 Nov: the polls alone used 329 of 400 lines).
pub fn line(msg: std::fmt::Arguments) {
    let text = msg.to_string();
    let seen = repeats(&text);
    if seen > 1 && seen % REPEAT_EVERY != 0 {
        return;
    }
    static N: AtomicU32 = AtomicU32::new(0);
    let n = N.fetch_add(1, Ordering::Relaxed).saturating_add(1);
    let again = if seen > 1 { format!("  (the same line {seen} times so far)") } else { String::new() };
    if n <= 400 {
        eprintln!("[video] {text}{again}");
    } else if n % 100 == 0 {
        eprintln!("[video] {text}{again}  (line {n}: past 400, one in 100 is logged)");
    }
}

/// How many recent distinct lines [`line`] remembers, and how often a repeat of one is logged again.
const REMEMBERED: usize = 32;
const REPEAT_EVERY: u32 = 50;

/// How many times [`line`] has now been asked for `text`, counting this one.
fn repeats(text: &str) -> u32 {
    static RECENT: std::sync::Mutex<Recent> = std::sync::Mutex::new(Recent(Vec::new()));
    crate::plock(&RECENT).count(text)
}

/// The last [`REMEMBERED`] distinct lines, each with how many times it was asked for.
struct Recent(Vec<(String, u32)>);

impl Recent {
    /// Count `text` once more and return its count; a new line forgets the oldest when full.
    fn count(&mut self, text: &str) -> u32 {
        if let Some((_, n)) = self.0.iter_mut().find(|(t, _)| t == text) {
            *n = n.saturating_add(1);
            return *n;
        }
        if self.0.len() == REMEMBERED {
            self.0.remove(0);
        }
        self.0.push((text.to_string(), 1));
        1
    }
}

// ---- The offer: the /info keys, the SETUP echo, setVideoPlaybackAllowed ----

/// `featuresEx`: the feature bits as bytes, low byte first, trailing zero bytes dropped, then Base64
/// (CarPlaySDK's `APSFeaturesCopyStringRepresentation` form, per enigy/ocbm).
pub fn features_ex(features: u64) -> String {
    let bytes = features.to_le_bytes();
    let used = bytes.iter().rposition(|&b| b != 0).map_or(0, |i| i + 1);
    base64(&bytes[..used])
}

/// Standard Base64 (RFC 4648 §4) with padding. The box crates carry no encoder, and this is all of one.
pub(crate) fn base64(bytes: &[u8]) -> String {
    const ABC: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let n = chunk.iter().enumerate().fold(0u32, |n, (i, &b)| n | (u32::from(b) << (16 - 8 * i)));
        for i in 0..4 {
            if i <= chunk.len() {
                out.push(char::from(ABC[((n >> (18 - 6 * i)) & 63) as usize]));
            } else {
                out.push('=');
            }
        }
    }
    out
}

/// The two `/info` keys that offer video: `videoPlaybackInfo` holds `videoPlaybackAllowed` (true: offered;
/// `setVideoPlaybackAllowed` says when it may play) and `featuresEx` (`features` again), and
/// `playbackCapabilities` says no FairPlay secure stop and no UI for audio-only content, so protected video
/// (the TV app's) stays on the phone. `features` and `extendedFeatures` are left as they are.
pub fn info_keys(features: u64) -> [(&'static str, Value); 2] {
    let mut info = Dictionary::new();
    info.insert("videoPlaybackAllowed".into(), Value::Boolean(true));
    info.insert("featuresEx".into(), Value::String(features_ex(features)));
    let mut caps = Dictionary::new();
    caps.insert("supportsFPSSecureStop".into(), Value::Boolean(false));
    caps.insert("supportsUIForAudioOnlyContent".into(), Value::Boolean(false));
    [("videoPlaybackInfo", Value::Dictionary(info)), ("playbackCapabilities", Value::Dictionary(caps))]
}

/// Should this phase-1 SETUP echo `videoPlayback`? Only when the app `offered` it (`enablesVideoPlayback`,
/// the lever) and the phone's `features` proposed it: an echo of a word the phone didn't propose would be a
/// feature it never asked for.
pub fn echoes_video(offered: bool, req: Option<&Dictionary>) -> bool {
    offered
        && req
            .and_then(|d| d.get("features"))
            .and_then(Value::as_array)
            .is_some_and(|words| words.iter().any(|w| w.as_string() == Some("videoPlayback")))
}

/// `setVideoPlaybackAllowed`: `{type, params: {videoPlaybackAllowed, playbackStatus}}` on the event channel,
/// the status `idle`, `video` or `audioOnly` (the shape enigy/ocbm sends, from LIVI's receiver).
pub fn allowed_command(allowed: bool, status: &str) -> Vec<u8> {
    let mut params = Dictionary::new();
    params.insert("videoPlaybackAllowed".into(), Value::Boolean(allowed));
    params.insert("playbackStatus".into(), Value::String(status.into()));
    let mut d = Dictionary::new();
    d.insert("type".into(), Value::String("setVideoPlaybackAllowed".into()));
    d.insert("params".into(), Value::Dictionary(params));
    let mut body = Vec::new();
    // A dictionary of three plain values always serializes; an empty body would just fail to send.
    let _ = Value::Dictionary(d).to_writer_binary(&mut body);
    body
}

/// The RCS client types, by `clientTypeUUID`: CarPlaySDK's names (docs/carplay/05_METADATA_AND_CONTROLS.md
/// §1.2) and, for the video ones, what enigy/ocbm's sources call them.
pub fn channel_name(client_type: &str) -> &'static str {
    const KNOWN: [(&str, &str); 7] = [
        ("E9459FD0-BCAD-4C45-820F-1E72447EF2F2", "iAP"),
        ("75AD9926-4777-42B2-A7D8-823EBEECF7AA", "LogTransfer"),
        ("3E2F3C61-AAD0-42CB-A8AA-BF22186DA62E", "VehicleDataProtocol"),
        ("FF4A6720-F2BE-4F56-A3E1-DB3B4E37D634", "VehicleDataProtocolHigh"),
        ("A6B27562-B43A-4F2D-B75F-82391E250194", "UrlFling (video Playback)"),
        ("E3DC3EA6-E6C3-4B30-847C-B7ACFEBEA654", "OverlayUI"),
        ("BB493F61-A6B8-4769-8D74-80C23A9F71C4", "SenderSettingsData"),
    ];
    KNOWN
        .iter()
        .find(|(uuid, _)| uuid.eq_ignore_ascii_case(client_type))
        .map_or("unknown client type", |&(_, name)| name)
}

/// A plist dictionary's `params.data`, else its own `data`, when that is bytes: a shared DataStream message's
/// payload.
pub fn dict_data(d: &Dictionary) -> Option<&[u8]> {
    let in_params = d.get("params").and_then(Value::as_dictionary).and_then(|p| p.get("data"));
    in_params.or_else(|| d.get("data")).and_then(Value::as_data)
}

// ---- What the log may say about a value ----

/// How deep a shape goes, and how long it may grow, before it is cut.
const SHAPE_DEPTH: usize = 6;
const SHAPE_MAX: usize = 1200;

/// A plist's shape for the log: keys, value types and sizes; the value itself only for a protocol name
/// under one of [`NAME_KEYS`].
pub fn shape(v: &Value) -> String {
    let mut out = String::new();
    plist_shape(&mut out, None, v, 0);
    out
}

/// [`shape`] for a dictionary already in hand.
pub fn dict_shape(d: &Dictionary) -> String {
    let mut out = String::new();
    dict_into(&mut out, d, 0);
    out
}

fn dict_into(out: &mut String, d: &Dictionary, depth: usize) {
    if depth >= SHAPE_DEPTH {
        out.push_str(&format!("{{{} keys}}", d.len()));
        return;
    }
    out.push('{');
    for (i, (k, v)) in d.iter().enumerate() {
        if i > 0 {
            out.push_str(", ");
        }
        out.push_str(&key_text(k));
        out.push_str(": ");
        plist_shape(out, Some(k), v, depth + 1);
    }
    out.push('}');
}

fn plist_shape(out: &mut String, key: Option<&str>, v: &Value, depth: usize) {
    if out.len() > SHAPE_MAX {
        if !out.ends_with('…') {
            out.push('…');
        }
        return;
    }
    match v {
        Value::Dictionary(d) => dict_into(out, d, depth),
        Value::Array(a) => {
            out.push_str(&format!("[{}", a.len()));
            if let Some(first) = a.first().filter(|_| depth < SHAPE_DEPTH) {
                out.push_str(" of ");
                plist_shape(out, None, first, depth + 1);
            }
            out.push(']');
        }
        Value::String(s) => out.push_str(&string_shape(key, s)),
        Value::Data(b) => out.push_str(&format!("data({})", b.len())),
        Value::Integer(_) => out.push_str("int"),
        Value::Real(_) => out.push_str("real"),
        Value::Boolean(_) => out.push_str("bool"),
        Value::Date(_) => out.push_str("date"),
        Value::Uid(_) => out.push_str("uid"),
        _ => out.push('?'),
    }
}

/// A dictionary key as the log shows it: the key itself when it reads as a protocol name, else its size.
fn key_text(k: &str) -> String {
    if !k.is_empty() && k.len() <= 48 && k.chars().all(|c| c.is_ascii_alphanumeric() || "_-.".contains(c)) {
        k.to_string()
    } else {
        format!("<key of {} B>", k.len())
    }
}

/// Keys whose string values are protocol names, shown as they are when they read as one: a message's
/// `type` and `kind`, `setProperty`'s `property`, an item's `mediaType` and `audioMode`, and `messageType`.
/// Titles, names and addresses are never under these.
const NAME_KEYS: [&str; 6] = ["type", "kind", "property", "mediaType", "audioMode", "messageType"];

/// A command or message name: short, and made of identifier characters.
fn is_identifier(s: &str) -> bool {
    !s.is_empty() && s.len() <= 64 && s.chars().all(|c| c.is_ascii_alphanumeric() || "_-.:".contains(c))
}

/// A string as the log shows it: a protocol name under one of [`NAME_KEYS`]; a URL's scheme and size;
/// otherwise its size.
fn string_shape(key: Option<&str>, s: &str) -> String {
    if key.is_some_and(|k| NAME_KEYS.contains(&k)) && is_identifier(s) {
        return format!("{s:?}");
    }
    match url_scheme(s) {
        Some(scheme) => format!("url({scheme}, {} B)", s.len()),
        None => format!("string({})", s.len()),
    }
}

/// A URL's scheme, when `s` is a URL. Never its host: a video's host says what was watched.
fn url_scheme(s: &str) -> Option<&str> {
    let (scheme, _) = s.split_once("://")?;
    let ok = scheme.len() <= 16
        && scheme.starts_with(|c: char| c.is_ascii_alphabetic())
        && scheme.chars().all(|c| c.is_ascii_alphanumeric() || "+-.".contains(c));
    ok.then_some(scheme)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn features_ex_is_the_features_low_byte_first_in_base64() {
        assert_eq!(features_ex(0x0000_0061_4444_0B80), "gAtERGE=");
        assert_eq!(features_ex(0), "");
        assert_eq!(base64(b"Man"), "TWFu");
        assert_eq!(base64(b"Ma"), "TWE=");
        assert_eq!(base64(b"M"), "TQ==");
    }

    #[test]
    fn video_is_echoed_only_when_offered_and_the_phone_proposed_it() {
        let mut d = Dictionary::new();
        assert!(!echoes_video(true, Some(&d)));
        assert!(!echoes_video(true, None));
        d.insert("features".into(), Value::Array(vec![Value::String("altScreen".into())]));
        assert!(!echoes_video(true, Some(&d)));
        d.insert(
            "features".into(),
            Value::Array(vec![Value::String("altScreen".into()), Value::String("videoPlayback".into())]),
        );
        assert!(echoes_video(true, Some(&d)));
        assert!(!echoes_video(false, Some(&d)), "not offered: never echoed, whatever the phone proposes");
    }

    #[test]
    fn the_allowed_command_carries_the_word_and_the_status() {
        let v = Value::from_reader(std::io::Cursor::new(allowed_command(true, "video"))).unwrap();
        let d = v.as_dictionary().unwrap();
        assert_eq!(d["type"].as_string(), Some("setVideoPlaybackAllowed"));
        let p = d["params"].as_dictionary().unwrap();
        assert_eq!(p["videoPlaybackAllowed"].as_boolean(), Some(true));
        assert_eq!(p["playbackStatus"].as_string(), Some("video"));
    }

    #[test]
    fn a_shape_shows_names_and_sizes_and_never_a_urls_host_or_a_title() {
        let mut item = Dictionary::new();
        item.insert("Content-Location".into(), Value::String("https://cdn.example.com/v/master.m3u8".into()));
        item.insert("title".into(), Value::String("A Private Title".into()));
        let mut m = Dictionary::new();
        m.insert("type".into(), Value::String("insertPlayQueueItem".into()));
        m.insert("item".into(), Value::Dictionary(item));
        m.insert("blob".into(), Value::Data(vec![1, 2, 3]));
        let s = dict_shape(&m);
        assert_eq!(s, r#"{type: "insertPlayQueueItem", item: {Content-Location: url(https, 37 B), title: string(15)}, blob: data(3)}"#);
        assert!(!s.contains("example") && !s.contains("Private"), "{s}");
        // A title under `type` that doesn't read as an identifier is a size too.
        let mut odd = Dictionary::new();
        odd.insert("type".into(), Value::String("two words".into()));
        assert_eq!(dict_shape(&odd), "{type: string(9)}");
    }

    #[test]
    fn a_shared_messages_data_is_found_in_params_or_at_the_top() {
        let mut p = Dictionary::new();
        p.insert("data".into(), Value::Data(vec![7]));
        let mut d = Dictionary::new();
        d.insert("params".into(), Value::Dictionary(p));
        assert_eq!(dict_data(&d), Some(&[7u8][..]));
        let mut top = Dictionary::new();
        top.insert("data".into(), Value::Data(vec![8]));
        assert_eq!(dict_data(&top), Some(&[8u8][..]));
        assert_eq!(dict_data(&Dictionary::new()), None);
    }

    #[test]
    fn the_playback_channel_is_named() {
        assert_eq!(channel_name("a6b27562-b43a-4f2d-b75f-82391e250194"), "UrlFling (video Playback)");
        assert_eq!(channel_name("nope"), "unknown client type");
    }
}
