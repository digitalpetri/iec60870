#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Build the lib60870-C CS104 interop peer image.
#
# Usage:
#   ./build.sh                 # builds lib60870c-interop:v2.3.5 (default ref)
#   LIB60870_REF=v2.3.6 ./build.sh
#   IMAGE=myrepo/lib60870c ./build.sh
#
# The Docker build context is this directory; the lib60870 sources are cloned
# INSIDE the build stage (see .dockerignore), so the context stays a few KB.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB60870_REF="${LIB60870_REF:-v2.3.5}"
IMAGE="${IMAGE:-lib60870c-interop}"
TAG="${TAG:-${LIB60870_REF}}"

echo ">> Building ${IMAGE}:${TAG} from lib60870 ref ${LIB60870_REF}"
docker build \
  --build-arg "LIB60870_REF=${LIB60870_REF}" \
  -t "${IMAGE}:${TAG}" \
  "${SCRIPT_DIR}"

echo ">> Built ${IMAGE}:${TAG}"
echo ">> Pinned lib60870 commit:"
docker run --rm "${IMAGE}:${TAG}" cat /lib60870.commit
