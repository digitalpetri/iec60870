#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# CS101 BALANCED interop peer entrypoint.
#
# A serial line is point-to-point, but Testcontainers exposes TCP ports, not
# serial devices. This script bridges the two with socat:
#
#   socat PTY,link=/dev/ttyCS101  <->  TCP-LISTEN:2404
#
# It creates a pseudo-terminal, symlinks its slave side to /dev/ttyCS101 (the
# device the C peer opens), and exposes the other end as TCP-LISTEN:2404 (the
# container port Testcontainers maps to the host). On the host side the test
# runs its own `socat PTY,link=<host pty> TCP:localhost:<mappedPort>`, so the
# full octet path is:
#
#   Java <-> host PTY <-> host socat <-> TCP <-> container socat <-> /dev/ttyCS101 <-> C peer
#
# After the device appears, it execs the C peer (interop_cs101) under
# `stdbuf -oL -eL` so its printf log markers stream under `docker logs`.
#
# Environment:
#   INTEROP_CS101_MODE     balanced (default) | unbalanced -- FT1.2 link mode
#   INTEROP_CS101_ROLE     slave (default) | master   -- which peer to run
#   INTEROP_CS101_DEVICE   serial device path (default /dev/ttyCS101)
#   INTEROP_CS101_BAUD     baud rate (default 9600)
#   INTEROP_CS101_TCP_PORT TCP-LISTEN port (default 2404)
#   plus the per-role env consumed by interop_cs101 (INTEROP_CA, etc.)
#
# The mode/role env vars are read directly by interop_cs101 (they are inherited
# by the exec below); this script only needs to bridge the PTY to TCP.
set -euo pipefail

DEVICE="${INTEROP_CS101_DEVICE:-/dev/ttyCS101}"
BAUD="${INTEROP_CS101_BAUD:-9600}"
TCP_PORT="${INTEROP_CS101_TCP_PORT:-2404}"

echo "INTEROP-CS101-BRIDGE starting socat PTY ${DEVICE} <-> TCP-LISTEN:${TCP_PORT} (b${BAUD})"

# Bridge the PTY (whose slave side is symlinked to ${DEVICE}) to TCP-LISTEN.
# Runs in the background; the C peer opens ${DEVICE} once socat has created it.
socat "PTY,link=${DEVICE},raw,echo=0,b${BAUD}" \
      "TCP-LISTEN:${TCP_PORT},reuseaddr,nodelay" &
SOCAT_PID=$!

# Wait for socat to create the device symlink before launching the C peer.
tries=0
until [ -e "${DEVICE}" ]; do
  tries=$((tries + 1))
  if [ "${tries}" -gt 100 ]; then
    echo "INTEROP-CS101-BRIDGE FAILED: ${DEVICE} not created after 10s"
    kill "${SOCAT_PID}" 2>/dev/null || true
    exit 1
  fi
  # If socat died, fail fast.
  if ! kill -0 "${SOCAT_PID}" 2>/dev/null; then
    echo "INTEROP-CS101-BRIDGE FAILED: socat exited before creating ${DEVICE}"
    exit 1
  fi
  sleep 0.1
done

echo "INTEROP-CS101-BRIDGE ${DEVICE} ready; launching peer (mode=${INTEROP_CS101_MODE:-balanced} role=${INTEROP_CS101_ROLE:-slave})"

# Line-buffer the C peer's stdout/stderr so log markers stream under docker logs.
exec stdbuf -oL -eL interop_cs101
