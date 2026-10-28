# host/ — host-side tools

Tools and apps that run on the machine talking to the adapter: Linux, and the car's Android Automotive
head unit.

| Path | What it is |
|---|---|
| `accbench.c` | libusb throughput **benchmark** for the `/dev/usb_accessory` pipe — prints the device's USB descriptors and measures IN/OUT throughput. Proven the transport: VID 0x1314, IF0 class 0xFF, EP IN 0x83 / OUT 0x02, 512 MPS; **339 Mbps read / 90 Mbps write**. |
| `uart_cmd.sh` | drive the box's serial root console from the host (single-fd, 115200) — send a command, capture output. Works when NCM is down / during partial boots. |
| `ocbm-host/` | the host end of the OCBM multiplexer (Rust) — claims the accessory interface, demuxes channels (IP tunnel, MFi bridge, A/V, control). Subcommands: `settime`, `echo`, `rtt`, `mfi`, `console`, `ip`, `srcbench`, `sinkbench`, `bridge`, `av`, **`avdec`**, `session`, `setup-relay`, `push`, `pull`. (`hello` is the DEFAULT mode string rather than a dispatch arm: every invocation sends HELLO and pushes the box clock before the `match`, and "hello" falls through it — so `ocbm-host hello`, like a bare `ocbm-host`, is a HELLO-plus-clock-push run and nothing more.) |
| `gm_ccpa/` | **The Equinox EV app** (`wasidremin.gmccpa`, AAOS 14). On the Equinox the adapter runs its own Wi-Fi AP and the CarPlay receiver and forwards the encrypted A/V over OCBM; the app decrypts, decodes and renders. It keeps upstream's Silverado vehicle-hotspot mode behind a switch. See [`gm_ccpa/README.md`](gm_ccpa/README.md). |
| `evinsights/` | Self-hosted EV telemetry workbench (FastAPI/Postgres + React). See [`evinsights/README.md`](evinsights/README.md). |

## Build

```
# accbench (Linux, needs libusb-1.0-dev):
clang accbench.c -o accbench -lusb-1.0
./accbench info 1314                                            # dump descriptors
./accbench read 1314 1520 6 262144                              # bulk-IN throughput
```

## Implemented

The two "planned" tools landed as `ocbm-host/`:
- host OCBM multiplexer client (claims the accessory interface, demuxes IP tunnel / MFi bridge /
  A/V / control).
- the `ocbm-rescue` role is `ocbm-host console` — `MODE_SELECT { CONSOLE }` bridging a root PTY
  over the bulk pipe (no NCM/WiFi needed). See [`../docs/ops/01_RECOVERY.md`](../docs/ops/01_RECOVERY.md).
