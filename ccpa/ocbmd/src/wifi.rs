//! The Wi-Fi link (T15): OCBM over TCP on the car's hotspot, beside the USB accessory, so the app never
//! asks Android for the USB device and the driver never taps Allow.
//!
//! OFF unless [`WIFI_LINK_FILE`] holds a link key (`MGMT_WIFI_LINK`, set once over USB). With none, ocbmd
//! is exactly the USB daemon it was.
//!
//! ON, ocbmd races the two transports before its loop starts: a TCP listener opens on
//! [`ocbm_link::PORT`], and the first host to send anything wins ([`wait_first_host`]), the USB host by
//! a byte waiting on the accessory, the Wi-Fi host by a record after proving the link key
//! ([`ocbm_link::box_handshake`]). Neither is read before it wins, so the loop starts at the first byte.
//! A Wi-Fi winner reaches the loop through a socketpair, so the loop reads and writes one descriptor
//! as it does the accessory's, and a lost link (EOF, a failed record, the keepalive) is the
//! accessory's "transport gone": exit for respawn, which opens the race again. USB always works: a box
//! whose Wi-Fi is misconfigured is still reachable over the cable.
//!
//! The hotspot itself is joined and kept by `radio_hal.sh sta_keep` from `/script/sta_uplink.conf`,
//! which the app already pushes; this file holds the link key only.
//!
//! Ported from mammut3's enigy/ocbm PR #2 (`ccpa/ocbmd/src/wifi.rs`, b76d210, Unlicense; NOTICE.md). Changed
//! here: one socketpair in place of two pipes (this ocbmd's loop has one accessory descriptor and no
//! relay threads, so the race polls the accessory itself); `MGMT_WIFI_LINK` carries the key only and
//! applies at the next start rather than restarting ocbmd; and the client's keeper is `radio_hal.sh`'s.

use ocbm_link::{box_handshake, random32, LinkKey, MAX_RECORD, PORT};
use std::fs::File;
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::os::unix::io::{AsRawFd, FromRawFd, IntoRawFd};
use std::os::unix::net::UnixStream;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

/// `key=<64 hex>` (the link key). Its presence turns the Wi-Fi link on. On flash: set once over USB,
/// kept across reboots and updates.
#[cfg(not(test))]
pub const WIFI_LINK_FILE: &str = "/etc/ocbm/wifi_link";
#[cfg(test)]
pub const WIFI_LINK_FILE: &str = "/tmp/ocbmd_selftest_wifi_link";

/// A stranger gets this long to finish the handshake before the listener moves on.
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);
/// A host that stops taking bytes (out of range, frozen) ends the link after this.
const SEND_TIMEOUT: Duration = Duration::from_secs(10);

/// The link key from `text` (the file's contents): the `key=` line.
pub fn parse_link_file(text: &str) -> Option<LinkKey> {
    text.lines().filter_map(|l| l.trim().strip_prefix("key=")).find_map(LinkKey::from_hex)
}

/// What `MGMT_WIFI_LINK` asks for.
#[derive(Debug, PartialEq, Eq)]
pub enum Provision {
    /// Remove [`WIFI_LINK_FILE`]: the box is a USB-only box again from its next start.
    Clear,
    /// Write it with this link key.
    Set([u8; 32]),
}

/// `MGMT_WIFI_LINK`'s body (after the verb): empty = [`Provision::Clear`], else `[ver 1][key 32 B]`.
pub fn parse_provision(body: &[u8]) -> Result<Provision, &'static str> {
    match body {
        [] => Ok(Provision::Clear),
        [1, key @ ..] if key.len() == 32 => Ok(Provision::Set(key.try_into().map_err(|_| "short key")?)),
        [1, ..] => Err("the key is not 32 bytes"),
        _ => Err("unknown version"),
    }
}

/// [`WIFI_LINK_FILE`]'s text for a key.
pub fn link_file_text(key: &[u8; 32]) -> String {
    format!("# OCBM Wi-Fi link (MGMT_WIFI_LINK)\nkey={}\n", LinkKey(*key).to_hex())
}

/// Carry out a provision: write (0600, atomically) or remove the file. The listener and the key take
/// effect at ocbmd's next start: every drive's boot. The hotspot is the keeper's (`radio_hal.sh
/// sta_keep`), which runs whenever the app's join settings ask for it.
pub fn apply_provision(p: &Provision) -> std::io::Result<()> {
    match p {
        Provision::Clear => match std::fs::remove_file(WIFI_LINK_FILE) {
            Err(e) if e.kind() != std::io::ErrorKind::NotFound => return Err(e),
            _ => {}
        },
        Provision::Set(key) => {
            use std::os::unix::fs::OpenOptionsExt;
            if let Some(dir) = std::path::Path::new(WIFI_LINK_FILE).parent() {
                std::fs::create_dir_all(dir)?;
            }
            let tmp = format!("{WIFI_LINK_FILE}.tmp");
            {
                let mut f = std::fs::OpenOptions::new().write(true).create(true).truncate(true).mode(0o600).open(&tmp)?;
                f.write_all(link_file_text(key).as_bytes())?;
                f.sync_all()?;
            }
            std::fs::rename(&tmp, WIFI_LINK_FILE)?;
        }
    }
    Ok(())
}

/// The link key, if the Wi-Fi link is on.
pub fn link_key() -> Option<LinkKey> {
    let text = std::fs::read_to_string(WIFI_LINK_FILE).ok()?;
    let k = parse_link_file(&text);
    if k.is_none() {
        eprintln!("[ocbmd] wifi: {WIFI_LINK_FILE} has no valid key= line; the Wi-Fi link stays off");
    }
    k
}

/// The hotspot client's interface, as the app pushed it in `/script/sta_uplink.conf` (`sta_iface=`), for
/// the discovery advert. Pure over the file's text.
pub fn sta_iface(conf: &str) -> String {
    conf.lines()
        .filter_map(|l| l.trim().strip_prefix("sta_iface="))
        .map(str::trim)
        .find(|v| !v.is_empty() && v.chars().all(|c| c.is_ascii_alphanumeric()))
        .unwrap_or("sta0")
        .to_string()
}

/// Who spoke first.
#[derive(Debug, PartialEq, Eq)]
pub enum Winner {
    Usb,
    Tcp,
}

/// Wait until the accessory or the Wi-Fi link's end holds bytes, without reading them: whichever wins,
/// the loop then reads from the start. `tick` runs about once a second meanwhile (the liveness stamp).
/// USB wins a tie: the cable is the established path. An accessory reporting a hangup or an error with
/// nothing to read is not a host: it is left out of the poll for a second at a time, so a gadget with no
/// host can neither win the race nor spin it.
pub fn wait_first_host(usb: &File, tcp: &File, mut tick: impl FnMut()) -> Winner {
    let mut usb_quiet_until: Option<std::time::Instant> = None;
    loop {
        let with_usb = usb_quiet_until.is_none_or(|t| std::time::Instant::now() >= t);
        let mut fds = [
            libc::pollfd { fd: tcp.as_raw_fd(), events: libc::POLLIN, revents: 0 },
            libc::pollfd { fd: usb.as_raw_fd(), events: libc::POLLIN, revents: 0 },
        ];
        let n_fds = if with_usb { 2 } else { 1 };
        // SAFETY: valid pollfds for descriptors the caller keeps open.
        let n = unsafe { libc::poll(fds.as_mut_ptr(), n_fds, 1000) };
        tick();
        if n <= 0 {
            continue;
        }
        if with_usb && fds[1].revents & libc::POLLIN != 0 {
            return Winner::Usb;
        }
        if fds[0].revents != 0 {
            return Winner::Tcp;
        }
        if with_usb && fds[1].revents != 0 {
            if usb_quiet_until.is_none() {
                eprintln!("[ocbmd] wifi: the accessory reports no host (revents {:#x}); still racing", fds[1].revents);
            }
            usb_quiet_until = Some(std::time::Instant::now() + Duration::from_secs(1));
        }
    }
}

/// Keepalive: a host that vanished (the car drove off, the hotspot dropped) is noticed in ~11 s, so
/// the daemon respawns and listens again instead of holding a dead link.
fn keepalive(s: &TcpStream) {
    let fd = s.as_raw_fd();
    let set = |level, opt, v: libc::c_int| {
        // SAFETY: setsockopt with a c_int value on a socket `s` owns.
        unsafe {
            libc::setsockopt(fd, level, opt, &v as *const _ as *const libc::c_void, std::mem::size_of::<libc::c_int>() as libc::socklen_t)
        }
    };
    set(libc::SOL_SOCKET, libc::SO_KEEPALIVE, 1);
    #[cfg(target_os = "linux")]
    {
        set(libc::IPPROTO_TCP, libc::TCP_KEEPIDLE, 5);
        set(libc::IPPROTO_TCP, libc::TCP_KEEPINTVL, 2);
        set(libc::IPPROTO_TCP, libc::TCP_KEEPCNT, 3);
    }
}

/// The TCP side, open: the loop's end of the link (non-blocking; EOF = the link ended), and the switch
/// that turns away a Wi-Fi host once USB has won.
pub struct TcpTransport {
    pub loop_end: File,
    pub usb_won: Arc<AtomicBool>,
}

/// Open the TCP side: a listener on `0.0.0.0:`[`PORT`] and a thread that takes the first host to complete
/// the handshake.
pub fn start_tcp_transport(key: LinkKey) -> std::io::Result<TcpTransport> {
    start_tcp_transport_on(key, SocketAddr::from(([0, 0, 0, 0], PORT))).map(|(t, _)| t)
}

/// [`start_tcp_transport`] on a chosen address; also returns the bound one (tests use port 0).
pub fn start_tcp_transport_on(key: LinkKey, addr: SocketAddr) -> std::io::Result<(TcpTransport, SocketAddr)> {
    let listener = TcpListener::bind(addr)?;
    let bound = listener.local_addr()?;
    let (loop_end, relay_end) = UnixStream::pair()?;
    loop_end.set_nonblocking(true)?;
    // SAFETY: into_raw_fd hands over sole ownership of the descriptor.
    let loop_end = unsafe { File::from_raw_fd(loop_end.into_raw_fd()) };
    let usb_won = Arc::new(AtomicBool::new(false));
    let gate = usb_won.clone();
    eprintln!("[ocbmd] wifi: listening on {bound} (OCBW v1)");
    std::thread::Builder::new()
        .name("wifi-accept".into())
        .stack_size(128 * 1024)
        .spawn(move || accept_one(listener, key, relay_end, gate))?;
    Ok((TcpTransport { loop_end, usb_won }, bound))
}

/// Take hosts until one proves the key, then relay it for good. Strangers are logged and dropped, and so
/// is everyone once USB has won.
fn accept_one(listener: TcpListener, key: LinkKey, relay_end: UnixStream, usb_won: Arc<AtomicBool>) {
    loop {
        let (mut s, peer) = match listener.accept() {
            Ok(x) => x,
            Err(e) => {
                eprintln!("[ocbmd] wifi: accept failed ({e})");
                std::thread::sleep(Duration::from_millis(500));
                continue;
            }
        };
        if usb_won.load(Ordering::Acquire) {
            eprintln!("[ocbmd] wifi: a host connected, but USB holds the link this start; turned away");
            continue;
        }
        let _ = s.set_read_timeout(Some(HANDSHAKE_TIMEOUT));
        let _ = s.set_write_timeout(Some(HANDSHAKE_TIMEOUT));
        let nonce = match random32() {
            Ok(n) => n,
            Err(e) => {
                eprintln!("[ocbmd] wifi: no randomness ({e}); refusing a host");
                continue;
            }
        };
        match box_handshake(&mut s, &key, nonce) {
            Ok(session) => {
                eprintln!("[ocbmd] wifi: a host proved the link key");
                drop(listener); // one host holds the link; the next is taken after the respawn
                run_link(s, session, relay_end);
                return;
            }
            // The peer's address is never logged: it is a device on the car's network.
            Err(e) => eprintln!("[ocbmd] wifi: refused a host ({e}; {})", kind(&peer)),
        }
    }
}

fn kind(peer: &SocketAddr) -> &'static str {
    if peer.is_ipv4() { "IPv4" } else { "IPv6" }
}

/// The two relay directions for an authenticated host. Each ends on the first failure and shuts the
/// socketpair down, which the loop reads as the transport's end.
fn run_link(s: TcpStream, session: ocbm_link::Session, relay_end: UnixStream) {
    let _ = s.set_nodelay(true);
    let _ = s.set_read_timeout(None);
    let _ = s.set_write_timeout(Some(SEND_TIMEOUT));
    keepalive(&s);
    let ocbm_link::Session { mut tx, mut rx } = session;
    let (mut rs, mut to_loop) = match (s.try_clone(), relay_end.try_clone()) {
        (Ok(a), Ok(b)) => (a, b),
        _ => {
            eprintln!("[ocbmd] wifi: could not clone the link's sockets; dropping it");
            return;
        }
    };
    let _ = std::thread::Builder::new().name("wifi-read".into()).stack_size(128 * 1024).spawn(move || {
        let mut buf = vec![0u8; 32 * 1024];
        let why = loop {
            let n = match rs.read(&mut buf) {
                Ok(0) => break "the host closed the connection".to_string(),
                Ok(n) => n,
                Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
                Err(e) => break format!("read failed: {e}"),
            };
            rx.push(&buf[..n]);
            let mut failed = None;
            loop {
                match rx.next() {
                    Ok(Some(plain)) => {
                        if let Err(e) = to_loop.write_all(&plain) {
                            failed = Some(format!("socketpair write failed: {e}"));
                            break;
                        }
                    }
                    Ok(None) => break,
                    Err(e) => {
                        failed = Some(format!("link error: {e}"));
                        break;
                    }
                }
            }
            if let Some(w) = failed {
                break w;
            }
        };
        eprintln!("[ocbmd] wifi: link reader stopped: {why}");
        let _ = rs.shutdown(std::net::Shutdown::Both);
        // The loop reads EOF and exits for respawn.
        let _ = to_loop.shutdown(std::net::Shutdown::Both);
    });
    let mut from_loop = relay_end;
    let mut ws = s;
    let mut buf = vec![0u8; MAX_RECORD];
    let why = loop {
        let n = match from_loop.read(&mut buf) {
            Ok(0) => break "the loop closed its end".to_string(),
            Ok(n) => n,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) => break format!("socketpair read failed: {e}"),
        };
        if let Err(e) = ws.write_all(&tx.seal(&buf[..n])) {
            break format!("send failed: {e}");
        }
    };
    eprintln!("[ocbmd] wifi: link writer stopped: {why}");
    let _ = ws.shutdown(std::net::Shutdown::Both);
    let _ = from_loop.shutdown(std::net::Shutdown::Both);
}

#[cfg(test)]
mod tests {
    use super::*;
    use ocbm_link::host_handshake;

    #[test]
    fn provisioning_sets_a_32_byte_key_or_clears_it() {
        assert_eq!(parse_provision(&[]), Ok(Provision::Clear));
        let mut body = vec![1u8];
        body.extend_from_slice(&[3u8; 32]);
        assert_eq!(parse_provision(&body), Ok(Provision::Set([3; 32])));
        // The file it writes parses back to the same key.
        assert_eq!(parse_link_file(&link_file_text(&[3; 32])).unwrap().0, [3; 32]);
        assert!(parse_provision(&body[..20]).is_err(), "a short key");
        body.push(0);
        assert!(parse_provision(&body).is_err(), "trailing bytes");
        assert!(parse_provision(&[2, 0]).is_err(), "an unknown version");
    }

    #[test]
    fn link_file_parsing() {
        let hex = "ab".repeat(32);
        assert!(parse_link_file(&format!("# ocbm wifi\nkey={hex}\n")).is_some());
        assert!(parse_link_file("key=short").is_none());
        assert!(parse_link_file("").is_none());
    }

    #[test]
    fn the_advert_names_the_client_interface_the_app_pushed() {
        assert_eq!(sta_iface("enabled=1\nsta_iface=sta0\nssid=x\n"), "sta0");
        assert_eq!(sta_iface("sta_iface=wlan1\n"), "wlan1");
        assert_eq!(sta_iface(""), "sta0", "none pushed: the IW416's");
        assert_eq!(sta_iface("sta_iface=../etc\n"), "sta0", "never a path");
    }

    fn read_all_nonblocking(f: &mut File, want: usize) -> Vec<u8> {
        let mut out = Vec::new();
        let deadline = std::time::Instant::now() + Duration::from_secs(3);
        let mut b = [0u8; 4096];
        while out.len() < want && std::time::Instant::now() < deadline {
            match f.read(&mut b) {
                Ok(0) => break,
                Ok(n) => out.extend_from_slice(&b[..n]),
                Err(_) => std::thread::sleep(Duration::from_millis(10)),
            }
        }
        out
    }

    /// End to end on loopback: a stranger is refused, the real host is relayed both ways through the one
    /// socketpair end, and its leaving reads as EOF there.
    #[test]
    fn tcp_transport_relays_an_authenticated_host_and_refuses_a_stranger() {
        let key = LinkKey([5u8; 32]);
        let (t, addr) = start_tcp_transport_on(key.clone(), "127.0.0.1:0".parse().unwrap()).unwrap();
        let mut end = t.loop_end;

        let mut bad = TcpStream::connect(addr).unwrap();
        bad.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        assert!(host_handshake(&mut bad, &LinkKey([6u8; 32]), [1u8; 32]).is_err());

        let mut h = TcpStream::connect(addr).unwrap();
        h.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
        let mut sess = host_handshake(&mut h, &key, [2u8; 32]).unwrap();

        h.write_all(&sess.tx.seal(b"HELLO frame bytes")).unwrap();
        assert_eq!(read_all_nonblocking(&mut end, 17), b"HELLO frame bytes");

        end.write_all(b"HELLO_ACK").unwrap();
        let mut got = Vec::new();
        let mut b = [0u8; 256];
        while sess.rx.next().unwrap().map(|p| got.extend_from_slice(&p)).is_none() {
            let n = h.read(&mut b).unwrap();
            sess.rx.push(&b[..n]);
        }
        assert_eq!(got, b"HELLO_ACK");

        drop(h);
        let deadline = std::time::Instant::now() + Duration::from_secs(3);
        let mut eof = false;
        while std::time::Instant::now() < deadline {
            match end.read(&mut b) {
                Ok(0) => {
                    eof = true;
                    break;
                }
                _ => std::thread::sleep(Duration::from_millis(20)),
            }
        }
        assert!(eof, "the loop's end should read EOF once the host leaves");
    }

    /// Once USB has won, a Wi-Fi host with the right key is turned away before the handshake.
    #[test]
    fn a_wifi_host_is_turned_away_once_usb_holds_the_link() {
        let key = LinkKey([9u8; 32]);
        let (t, addr) = start_tcp_transport_on(key.clone(), "127.0.0.1:0".parse().unwrap()).unwrap();
        t.usb_won.store(true, Ordering::Release);
        let mut h = TcpStream::connect(addr).unwrap();
        h.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        assert!(host_handshake(&mut h, &key, [2u8; 32]).is_err());
    }

    /// An accessory that hangs up with nothing to read (a gadget with no host) neither wins nor spins:
    /// a Wi-Fi host that speaks a moment later still gets the link. A pipe whose writer is gone reports
    /// POLLHUP alone on Linux, which is that case.
    #[cfg(target_os = "linux")]
    #[test]
    fn a_hung_up_accessory_does_not_win_the_race() {
        let mut p = [0 as libc::c_int; 2];
        // SAFETY: pipe(2) fills two descriptors we then own.
        assert_eq!(unsafe { libc::pipe(p.as_mut_ptr()) }, 0);
        // SAFETY: both descriptors are ours; the write end is closed at once.
        let usb = unsafe {
            libc::close(p[1]);
            File::from_raw_fd(p[0])
        };
        let (t_r, mut t_w) = UnixStream::pair().unwrap();
        // SAFETY: into_raw_fd hands over sole ownership.
        let tcp = unsafe { File::from_raw_fd(t_r.into_raw_fd()) };
        let writer = std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(1500));
            t_w.write_all(b"y").unwrap();
            t_w
        });
        let started = std::time::Instant::now();
        let mut ticks = 0;
        assert_eq!(wait_first_host(&usb, &tcp, || ticks += 1), Winner::Tcp);
        assert!(started.elapsed() >= Duration::from_millis(1400));
        assert!(ticks < 20, "the race spun: {ticks} passes in {:?}", started.elapsed());
        drop(writer.join());
    }

    #[test]
    fn first_host_wins_without_consuming_bytes() {
        let (u_r, mut u_w) = UnixStream::pair().unwrap();
        let (t_r, _t_w) = UnixStream::pair().unwrap();
        // SAFETY: into_raw_fd hands over sole ownership.
        let (usb, tcp) = unsafe { (File::from_raw_fd(u_r.into_raw_fd()), File::from_raw_fd(t_r.into_raw_fd())) };
        u_w.write_all(b"x").unwrap();
        assert_eq!(wait_first_host(&usb, &tcp, || {}), Winner::Usb);
        let mut b = [0u8; 1];
        assert_eq!((&usb).read(&mut b).unwrap(), 1, "still readable: nothing was consumed");
    }
}
