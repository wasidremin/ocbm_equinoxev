"""Hardware-free startup invariants; run with python3 -m unittest discover -s tools/tests."""
import pathlib
import subprocess
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


class StartupContract(unittest.TestCase):
    def test_shell_syntax(self):
        for path in ("tools/session_supervisor.sh", "ccpa/rootfs/script/radio_hal.sh"):
            subprocess.run(["sh", "-n", str(ROOT / path)], check=True)

    def test_boot_job_only_prewarms_bt(self):
        source = (ROOT / "tools/session_supervisor.sh").read_text()
        job = source.split("if [ ! -e /tmp/.radio_bt_prewarm_started ]; then", 1)[1].split("while :; do", 1)[0]
        self.assertIn("radio_hal.sh bt_prewarm", job)
        self.assertNotIn("wifi_ap_on", job)
        self.assertNotIn("arm_aa", job)
        self.assertNotIn("/usr/sbin/btd", job)

    def test_prewarm_cannot_reset_an_early_session(self):
        source = (ROOT / "ccpa/rootfs/script/radio_hal.sh").read_text()
        body = source.split("do_bt_prewarm() {", 1)[1].split("do_bt_off()", 1)[0]
        self.assertLess(body.index("/tmp/host_present"), body.index("do_bt_on"))
        # The prewarm leaves scan disabled: the last scan write is the raw Write_Scan_Enable(0x00)
        # (not `hciconfig noscan`, whose ioctl can sleep 10 s on the CCPA's 3.14 kernel).
        self.assertLess(body.rindex('hci_scan_off "$_warm_dev"'), body.index("echo \"$_warm_dev\""))
        self.assertNotIn("hciconfig \"$_warm_dev\" noscan", body)
        self.assertIn("bt_responsive_confirmed", body)

    def test_scan_off_is_one_raw_helper(self):
        source = (ROOT / "ccpa/rootfs/script/radio_hal.sh").read_text()
        self.assertEqual(source.count("0x03 0x001a 0x00"), 1, "one helper, no copies to drift")
        bt_off = source.split("do_bt_off() {", 1)[1].split("\n}", 1)[0]
        self.assertIn('hci_scan_off "$_d"', bt_off)
        self.assertNotIn("noscan", bt_off.split("#")[0])
        self.assertLess(bt_off.index("hci_drop_acls"), bt_off.index('hci_rt "$_d" down'))

    def test_teardown_blocks_cannot_kill_themselves(self):
        # `setsid sh -c '<text>'` puts the whole block, comments included, in the subshell's argv, and
        # `pkill -f` matches argv. A `[x]yz` pattern does not match its own text, but the plain word
        # anywhere else in the block does, and the teardown then SIGTERMs itself before wifi_ap_off and
        # bt_off (seen on the adapter 2026-11-07; enigy/ocbm a902008).
        source = (ROOT / "tools/session_supervisor.sh").read_text()
        blocks = re.findall(r"^[ \t]*setsid sh -c '\n(.*?)\n[ \t]*' </dev/null", source, re.S | re.M)
        self.assertGreaterEqual(len(blocks), 2, "the two wireless_down teardown blocks")
        checked = 0
        for body in blocks:
            # The pkill -f patterns are regexes; these are plain enough for Python's engine to read the same.
            for pattern in re.findall(r'p(?:kill|grep)\b[^\n"]*"(\[.\][^"]*)"', body):
                checked += 1
                self.assertIsNone(re.search(pattern, body), f"the block text matches its own pattern {pattern}")
        self.assertGreater(checked, 0)

    def test_first_reconnect_has_no_settle_sleep(self):
        source = (ROOT / "crates/vendor/wireless/src/reconnect.rs").read_text()
        body = source.split("pub fn run(", 1)[1].split("let mut backoff", 1)[0]
        self.assertNotIn("sleep(", body)
        self.assertNotIn("INITIAL_SETTLE", source)
        # The 10 s floor between rounds stays; the one exception is a single 2 s retry after a first
        # round that failed outright (a phone that answered the page and reset the link).
        self.assertIn("sleep_or_request(if quick { FIRST_RETRY_SECS } else { backoff }, shutdown, ctrl)", source)
        self.assertIn("let quick = first_round && !ran && !rejected;", source)
        self.assertIn("const FIRST_RETRY_SECS: u64 = 2;", source)

    def test_idle_watchdog_keeps_five_minute_prehello_threshold(self):
        source = (ROOT / "tools/session_supervisor.sh").read_text()
        body = source.split("ocbmd_wedged() {", 1)[1].split("\n}", 1)[0]
        # Uptime-domain stamp in milliseconds (2026-10-02): 300 s before the first HELLO, 60 s after.
        self.assertIn("_alive_ms=300000", body)
        self.assertIn('[ -e /tmp/ocbm_hello_seen ] && _alive_ms=60000', body)
        self.assertIn('-ge "$_alive_ms"', body)
        self.assertNotIn('[ -e /tmp/ocbm_hello_seen ] || return', body)
        daemon = (ROOT / "ccpa/ocbmd/src/main.rs").read_text()
        hello = daemon.split("if pl.first() == Some(&p::CT_HELLO) {", 1)[1]
        self.assertLess(hello.index("touch_alive_file()"), hello.index("ocbm_hello_seen"))

    def test_announce_retry_default_is_zero(self):
        source = (ROOT / "ccpa/rootfs/script/ocbm_boot.sh").read_text()
        self.assertIn("USB_ANNOUNCE_RETRIES=0", source)
        self.assertNotIn("USB_ANNOUNCE_RETRIES=2", source)


if __name__ == "__main__":
    unittest.main()
