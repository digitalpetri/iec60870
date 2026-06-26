# SPDX-License-Identifier: GPL-3.0-or-later
#
# lib60870-C (MZ Automation) IEC 60870-5-104 reference peer.
#
# Used by the iec60870-interop module as a separate-process network peer for
# Testcontainers-based interop tests (@Tag("interop")). lib60870-C is GPLv3; it
# is consumed here only as a standalone binary over the network, never linked.
#
# Multi-stage build:
#   1. "build" stage: shallow-clones a PINNED lib60870 tag and builds the
#      library plus the CS104 server (cs104_server) and client (simple_client)
#      example binaries with CMake.
#   2. runtime stage: a slim Debian image carrying only the two binaries.
#
# Pinned ref is passed via the LIB60870_REF build arg (default below). Override
# with: docker build --build-arg LIB60870_REF=v2.3.6 ...

# ---------------------------------------------------------------------------
# Stage 1: build
# ---------------------------------------------------------------------------
FROM debian:bookworm-slim AS build

# Real, recent lib60870 release tag. See git ls-remote --tags on
# https://github.com/mz-automation/lib60870 for available refs.
ARG LIB60870_REF=v2.3.5
ARG LIB60870_REPO=https://github.com/mz-automation/lib60870.git

RUN apt-get update \
    && apt-get install --no-install-recommends -y \
        ca-certificates \
        cmake \
        g++ \
        gcc \
        git \
        make \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src

# Shallow clone of the single pinned tag keeps the build context tiny.
RUN git clone --depth 1 --branch "${LIB60870_REF}" "${LIB60870_REPO}" lib60870 \
    && git -C lib60870 rev-parse HEAD > /lib60870.commit \
    && echo "lib60870 ref ${LIB60870_REF} -> $(cat /lib60870.commit)"

# Build the library + examples (BUILD_EXAMPLES is ON by default).
# Examples are not installed by the library's `make install`, so we build the
# example targets directly and copy the resulting binaries out by hand.
WORKDIR /src/lib60870/lib60870-C/build
RUN cmake -DCMAKE_BUILD_TYPE=Release .. \
    && make -j"$(nproc)" lib60870 cs104_server simple_client

# Collect the two example binaries into a known location. Their build output
# paths differ per example subdir, so locate them by name.
RUN mkdir -p /out \
    && find /src/lib60870/lib60870-C/build -type f -name cs104_server   -perm -u+x -exec cp {} /out/cs104_server \; \
    && find /src/lib60870/lib60870-C/build -type f -name simple_client  -perm -u+x -exec cp {} /out/simple_client \; \
    && cp /lib60870.commit /out/lib60870.commit \
    && ls -l /out

# ---------------------------------------------------------------------------
# Build the CUSTOM interop driver programs against the freshly built static
# lib60870 archive + in-tree headers. These implement INTEROP-CONTRACT.md.
#
# The static archive (target "lib60870" -> liblib60870.a) and the public/
# internal/HAL headers are all in the source tree we just built; we point gcc
# at the same include directories the library's own CMake uses (see the
# top-level CMakeLists include_directories block). pthread is required by the
# threaded CS104 slave/connection.
# ---------------------------------------------------------------------------
ARG LIB_ROOT=/src/lib60870/lib60870-C
COPY interop_server.c interop_client.c interop_cs101.c /interop-src/
COPY cs101-entrypoint.sh /out/cs101-entrypoint.sh
RUN set -eux; \
    LIB_A="$(find ${LIB_ROOT}/build -type f -name 'liblib60870.a' | head -n1)"; \
    test -n "${LIB_A}"; \
    echo "Linking against static archive: ${LIB_A}"; \
    INCS="-I${LIB_ROOT}/config \
          -I${LIB_ROOT}/src/inc/api \
          -I${LIB_ROOT}/src/inc/internal \
          -I${LIB_ROOT}/src/common/inc \
          -I${LIB_ROOT}/src/hal/inc"; \
    gcc -O2 -Wall -Wextra ${INCS} /interop-src/interop_server.c "${LIB_A}" -lpthread -o /out/interop_server; \
    gcc -O2 -Wall -Wextra ${INCS} /interop-src/interop_client.c "${LIB_A}" -lpthread -o /out/interop_client; \
    gcc -O2 -Wall -Wextra ${INCS} /interop-src/interop_cs101.c  "${LIB_A}" -lpthread -o /out/interop_cs101; \
    chmod +x /out/cs101-entrypoint.sh; \
    ls -l /out/interop_server /out/interop_client /out/interop_cs101 /out/cs101-entrypoint.sh

# ---------------------------------------------------------------------------
# Stage 2: runtime
# ---------------------------------------------------------------------------
FROM debian:bookworm-slim AS runtime

LABEL org.opencontainers.image.title="lib60870-C CS104 interop peer" \
      org.opencontainers.image.source="https://github.com/mz-automation/lib60870" \
      org.opencontainers.image.licenses="GPL-3.0-or-later" \
      org.opencontainers.image.description="lib60870-C CS104 server + client example binaries for IEC 60870-5-104 interop testing"

# The examples are statically linked against the in-tree lib60870 .a, so only
# the C/C++ runtime (already present in debian-slim via libc/libstdc++) is
# needed. ca-certificates is kept for completeness / TLS examples if added.
# socat bridges the CS101 peer's PTY to TCP-LISTEN:2404 (see cs101-entrypoint.sh).
RUN apt-get update \
    && apt-get install --no-install-recommends -y ca-certificates socat \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /out/cs104_server   /usr/local/bin/cs104_server
COPY --from=build /out/simple_client  /usr/local/bin/simple_client
COPY --from=build /out/interop_server /usr/local/bin/interop_server
COPY --from=build /out/interop_client /usr/local/bin/interop_client
COPY --from=build /out/interop_cs101  /usr/local/bin/interop_cs101
COPY --from=build /out/cs101-entrypoint.sh /usr/local/bin/cs101-entrypoint.sh
COPY --from=build /out/lib60870.commit /lib60870.commit

# CS104 / IEC 60870-5-104 default TCP port (also the CS101 PTY<->TCP bridge port).
EXPOSE 2404

# Default to the server peer; override the command to run the client.
# e.g. docker run ... <image> simple_client <server-ip> 2404
CMD ["cs104_server"]
