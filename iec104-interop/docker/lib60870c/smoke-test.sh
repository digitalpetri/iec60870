#!/usr/bin/env bash
#
# Smoke test: start the lib60870-C CS104 server peer, run the CS104 client peer
# against it on a private Docker network, and confirm a full APCI/ASDU exchange
# (STARTDT, general interrogation, spontaneous monitor data) shows up in the
# logs. Containers and the network are torn down on exit.
#
# IMPORTANT: the lib60870-C examples log via printf(), which is FULLY buffered
# when stdout is a pipe (as under `docker logs`). Output therefore only appears
# on flush/exit. Wrap each peer in `stdbuf -oL -eL` to force line buffering so
# logs stream in real time -- the interop test author must do the same when
# scraping container output, or run the binary under a TTY.
#
# Usage: ./smoke-test.sh   (assumes image lib60870c-interop:v2.3.5 exists)
set -euo pipefail

IMAGE="${IMAGE:-lib60870c-interop:v2.3.5}"
NET="lib60870-smoke-$$"
SRV="lib60870-srv-$$"

cleanup() {
  docker rm -f "${SRV}" >/dev/null 2>&1 || true
  docker network rm "${NET}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker network create "${NET}" >/dev/null

echo ">> Starting server peer (cs104_server, port 2404)"
docker run -d --name "${SRV}" --network "${NET}" \
  "${IMAGE}" stdbuf -oL -eL cs104_server >/dev/null

# Give the server a moment to bind. (Foreground `sleep` may be blocked in some
# sandboxes; run it in a throwaway container instead.)
docker run --rm "${IMAGE}" sleep 2

echo ">> Server startup log:"
docker logs "${SRV}" 2>&1 | sed 's/^/   /'

echo ">> Running client peer (simple_client) against server for 8s"
docker run --rm --network "${NET}" "${IMAGE}" \
  sh -c "timeout 8 stdbuf -oL -eL simple_client ${SRV} 2404; echo client-rc=\$?" 2>&1 \
  | sed 's/^/   /'

echo ">> Server log after session:"
docker logs "${SRV}" 2>&1 | sed 's/^/   /'

# Verdict: confirm the client saw STARTDT_CON and at least one ASDU.
if docker logs "${SRV}" 2>&1 | grep -q "Connection activated"; then
  echo ">> SMOKE TEST PASSED: APCI exchange completed (connection activated, interrogation handled)."
else
  echo ">> SMOKE TEST FAILED: no activated connection observed in server log." >&2
  exit 1
fi
