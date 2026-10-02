#!/usr/bin/env bash
# Build the backend image, push it to the private registry and, if the app is
# already installed, restart it so it pulls the new image.
#   ./build.sh            -> lorisdemicheli/minecraft-controller:dev
#   TAG=0.1.0 ./build.sh  -> another tag (then: ./install.sh --set controller.image.tag=0.1.0)
set -euo pipefail
cd "$(dirname "$0")"

IMAGE="${IMAGE:-lorisdemicheli/minecraft-controller}"
TAG="${TAG:-dev}"
NS="${NS:-minecraft}"
RELEASE="${RELEASE:-mc}"

docker build -t "$IMAGE:$TAG" backend
docker push "$IMAGE:$TAG"

if kubectl get deploy "$RELEASE-minecraft-controller-controller" -n "$NS" >/dev/null 2>&1; then
  kubectl rollout restart "deploy/$RELEASE-minecraft-controller-controller" -n "$NS"
  kubectl rollout status  "deploy/$RELEASE-minecraft-controller-controller" -n "$NS"
fi
echo "OK: $IMAGE:$TAG"
