#!/usr/bin/env bash
# Install or upgrade the whole stack (controller + router + ingress + shared volume).
# Safe to run again: it is `helm upgrade --install`.
#   ./install.sh                                  # first time asks for the Docker Hub token
#   ./install.sh --set baseDomain=other.example   # any extra helm flag is passed through
set -euo pipefail
cd "$(dirname "$0")"

NS="${NS:-minecraft}"
RELEASE="${RELEASE:-mc}"
REG_USER="${REG_USER:-lorisdemicheli}"

kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

# Pull secret for the private registry: only asked if it does not exist yet.
if ! kubectl get secret regcred -n "$NS" >/dev/null 2>&1; then
  if [ -z "${REG_TOKEN:-}" ]; then
    read -rsp "Docker Hub token for $REG_USER: " REG_TOKEN; echo
  fi
  kubectl create secret docker-registry regcred -n "$NS" \
    --docker-username="$REG_USER" --docker-password="$REG_TOKEN"
fi

helm upgrade --install "$RELEASE" charts/minecraft-controller -n "$NS" "$@"

echo
echo "Pods:      kubectl get pods -n $NS"
echo "Password:  kubectl get secret $RELEASE-minecraft-controller-auth -n $NS -o jsonpath='{.data.password}' | base64 -d; echo"
echo "API test:  kubectl port-forward svc/$RELEASE-minecraft-controller-controller 8080:8080 -n $NS"
