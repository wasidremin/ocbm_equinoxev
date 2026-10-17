"""Hardware-free startup invariants; run with python3 -m unittest discover -s tools/tests."""
import pathlib
import subprocess
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
        self.assertLess(body.rindex('noscan'), body.index("echo \"$_warm_dev\""))
        self.assertIn("bt_responsive_confirmed", body)

    def test_first_reconnect_has_no_settle_sleep(self):
        source = (ROOT / "crates/vendor/wireless/src/reconnect.rs").read_text()
        body = source.split("pub fn run(", 1)[1].split("let mut backoff", 1)[0]
        self.assertNotIn("sleep(", body)
        self.assertNotIn("INITIAL_SETTLE", source)
        self.assertIn("sleep_or_request(backoff, shutdown, ctrl)", source)

    def test_idle_watchdog_keeps_five_minute_prehello_threshold(self):
        source = (ROOT / "tools/session_supervisor.sh").read_text()
        body = source.split("ocbmd_wedged() {", 1)[1].split("\n}", 1)[0]
        self.assertIn("_alive_mmin=4", body)
        self.assertIn('[ -e /tmp/ocbm_hello_seen ] && _alive_mmin=0', body)
        self.assertIn("-mmin +$_alive_mmin", body)
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
