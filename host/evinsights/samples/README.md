# SYNTHETIC ONLY — NOT VEHICLE EVIDENCE

`generate.py` deterministically simulates temperature/speed associations, routes,
short stops, charging interruptions and missing temperature. It writes ONLY to
this directory. Generated events use `meta.synthetic=true` and an explicitly
synthetic device/vehicle. They do not establish Equinox capabilities or efficiency.

Run `python3 samples/generate.py --days 60`. Upload via `--upload URL --key KEY`
only after provisioning the synthetic vehicle with ALLOW_SYNTHETIC=true.
Batches upload newest first to exercise out-of-order derivation.
The small checked-in `fixture.json` is also synthetic and contract-valid.
