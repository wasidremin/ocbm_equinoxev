#!/bin/sh
# bench_mode.sh — make room on this server for the emulator bench, and give it back afterwards.
#
#   tools/bench_mode.sh on      # before booting the emulator or a heavy build
#   tools/bench_mode.sh off     # when the bench is done
#   tools/bench_mode.sh status
#
# Why (2026-11-26): the server has 15 GB and 4 GB of swap; with the media stack, Immich and EV
# Insights up, swap was full before the bench started, and the emulator (about 2.5 GB) plus Gradle
# took the load from about 3 to 25 and more, and the emulator's car service crashed under it.
#
# `on` pauses (docker pause: frozen, state kept, nothing restarted) the owner's media stack and
# Immich, and stops the Gradle daemons. It never touches the log receiver (cloudbridge-log, which
# the car's app uploads to), EV Insights, or a container that was already paused, and `off`
# resumes only what `on` paused. Downloads and streaming wait while it is on.
set -u
STATE="${XDG_STATE_HOME:-$HOME/.local/state}/ocbm-bench-paused"
HEAVY="qbittorrent gluetun jellyfin jellyseerr sonarr radarr bazarr prowlarr jackett flaresolverr
lazylibrarian media-guide immich_server immich_machine_learning immich_power_tools immich_postgres
immich_redis"
GRADLE="${GRADLE_BIN:-$HOME/.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin/gradle}"

state_of() { docker inspect -f '{{.State.Status}}' "$1" 2>/dev/null; }

case "${1:-status}" in
  on)
    mkdir -p "$(dirname "$STATE")"
    : >>"$STATE"
    for c in $HEAVY; do
      [ "$(state_of "$c")" = running ] || continue
      docker pause "$c" >/dev/null && echo "$c" >>"$STATE" && echo "paused $c"
    done
    [ -x "$GRADLE" ] && "$GRADLE" --stop -q >/dev/null 2>&1 && echo "stopped the Gradle daemons"
    ;;
  off)
    [ -s "$STATE" ] || { echo "nothing paused by bench mode"; exit 0; }
    sort -u "$STATE" | while read -r c; do
      [ "$(state_of "$c")" = paused ] && docker unpause "$c" >/dev/null && echo "resumed $c"
    done
    rm -f "$STATE"
    ;;
  status)
    if [ -s "$STATE" ]; then echo "bench mode ON: $(sort -u "$STATE" | tr '\n' ' ')"; else echo "bench mode off"; fi
    uptime
    free -h | sed -n '1,3p'
    ;;
  *) echo "usage: $0 on|off|status" >&2; exit 64 ;;
esac
