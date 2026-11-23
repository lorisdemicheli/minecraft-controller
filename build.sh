#!/usr/bin/env bash
# Build the backend and console images, push them and, if the release is installed, point it to them.
# The CI publishes the official images on every push to main: this script is for local builds.
#   ./build.sh                        -> ghcr.io/lorisdemicheli/minecraft-controller:dev (+ minecraft-console:dev)
#   TAG=0.1.0 ./build.sh              -> another tag
#   IMAGE=docker.io/me/mc ./build.sh  -> another registry for the backend (docker login first)
#   CONSOLE_IMAGE=docker.io/me/ui ./build.sh -> another registry for the console
set -euo pipefail
cd "$(dirname "$0")"

IMAGE="${IMAGE:-ghcr.io/lorisdemicheli/minecraft-controller}"
CONSOLE_IMAGE="${CONSOLE_IMAGE:-ghcr.io/lorisdemicheli/minecraft-console}"
TAG="${TAG:-dev}"
NS="${NS:-minecraft}"
RELEASE="${RELEASE:-mc}"

docker build -t "$IMAGE:$TAG" backend
docker build -t "$CONSOLE_IMAGE:$TAG" ui
docker push "$IMAGE:$TAG"
docker push "$CONSOLE_IMAGE:$TAG"

if helm status "$RELEASE" -n "$NS" >/dev/null 2>&1; then
  helm upgrade "$RELEASE" charts/minecraft-controller -n "$NS" --reuse-values \
    --set "controller.image.repository=$IMAGE" --set "controller.image.tag=$TAG" \
    --set controller.image.pullPolicy=Always \
    --set "frontend.image.repository=$CONSOLE_IMAGE" --set "frontend.image.tag=$TAG" \
    --set frontend.image.pullPolicy=Always
  # same tag pushed again: force the new pull
  kubectl rollout restart "deploy/$RELEASE-minecraft-controller-controller" -n "$NS"
  kubectl rollout status  "deploy/$RELEASE-minecraft-controller-controller" -n "$NS"
  if kubectl get "deploy/$RELEASE-minecraft-controller-console" -n "$NS" >/dev/null 2>&1; then
    kubectl rollout restart "deploy/$RELEASE-minecraft-controller-console" -n "$NS"
    kubectl rollout status  "deploy/$RELEASE-minecraft-controller-console" -n "$NS"
  fi
fi
echo "OK: $IMAGE:$TAG, $CONSOLE_IMAGE:$TAG"
