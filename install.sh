#!/usr/bin/env bash
# Install or upgrade the whole stack (controller + router + ingress + shared volume).
# Safe to run again: it is `helm upgrade --install`.
#
#   ./install.sh                                    # BASE_DOMAIN defaults to mc.lorisdemicheli.it
#   BASE_DOMAIN=mc.example.com ./install.sh
#   ADMIN_PASSWORD=<password> ./install.sh          # admin password (chart default: admin, change it before exposing the panel)
#   VALUES=my-values.yaml ./install.sh              # values file (kept out of git)
#   TAG=sha-<commit> ./install.sh                   # a specific build of the CI (controller + console)
#   CHART=oci://ghcr.io/<owner>/charts/minecraft-controller CHART_VERSION=0.5.0 ./install.sh
#                                                   # the chart published by the CI, instead of this folder
#   REG_TOKEN=<PAT> ./install.sh                    # private registry: creates the pull secret
#   ./install.sh --set storage.className=longhorn   # any extra helm flag is passed through
set -euo pipefail
cd "$(dirname "$0")"

NS="${NS:-minecraft}"
RELEASE="${RELEASE:-mc}"
BASE_DOMAIN="${BASE_DOMAIN:-mc.lorisdemicheli.it}"
REGISTRY="${REGISTRY:-ghcr.io}"
REG_USER="${REG_USER:-lorisdemicheli}"
PULL_SECRET="${PULL_SECRET:-regcred}"
CHART="${CHART:-charts/minecraft-controller}"

kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

args=(--namespace "$NS" --set "baseDomain=$BASE_DOMAIN")
[ -n "${ADMIN_PASSWORD:-}" ] && args+=(--set-string "auth.password=$ADMIN_PASSWORD")
[ -n "${VALUES:-}" ] && args+=(-f "$VALUES")
[ -n "${TAG:-}" ] && args+=(--set "controller.image.tag=$TAG" --set "frontend.image.tag=$TAG")
[ -n "${CHART_VERSION:-}" ] && args+=(--version "$CHART_VERSION")

# Pull secret for a private registry: created only when a token is given (never stored here).
if [ -n "${REG_TOKEN:-}" ]; then
  kubectl create secret docker-registry "$PULL_SECRET" -n "$NS" \
    --docker-server="$REGISTRY" --docker-username="$REG_USER" --docker-password="$REG_TOKEN" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
fi
if kubectl get secret "$PULL_SECRET" -n "$NS" >/dev/null 2>&1; then
  args+=(--set "imagePullSecrets[0].name=$PULL_SECRET")
fi

helm upgrade --install "$RELEASE" "$CHART" "${args[@]}" "$@"

echo
echo "Console:   http://console.$BASE_DOMAIN   (API: http://api.$BASE_DOMAIN; https with ingress.tls.enabled)"
echo "Pods:      kubectl get pods -n $NS"
echo "Login:     user admin, password = ADMIN_PASSWORD (chart default: admin)"
echo "Password:  kubectl get secret $RELEASE-minecraft-controller-auth -n $NS -o jsonpath='{.data.password}' | base64 -d; echo"
echo "API test:  kubectl port-forward svc/$RELEASE-minecraft-controller-controller 8080:8080 -n $NS"
echo "Check:     helm test $RELEASE -n $NS"
