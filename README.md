# Minecraft Controller

Gestione di server Minecraft su Kubernetes: un'API REST (Spring Boot) crea, avvia, ferma e cancella server [itzg/minecraft-server](https://github.com/itzg/docker-minecraft-server) come StatefulSet, ne mostra console e log in tempo reale (SSE) e gestisce i loro file su un volume condiviso. [itzg/mc-router](https://github.com/itzg/mc-router) instrada `<server>.<dominio>` sulla porta 25565.

| Cartella | Contenuto |
|---|---|
| `backend/` | API Spring Boot 4 / Java 21 |
| `ui/` | console web Angular 21 (SSR su Node), con `Dockerfile` |
| `charts/minecraft-controller/` | chart Helm |
| `docs/` | architettura, flussi, ADR, CI/CD |

## Installazione

```bash
kubectl create namespace minecraft
kubectl -n minecraft create secret generic minecraft-controller-auth \
  --from-literal=password="$(openssl rand -base64 18)" \
  --from-literal=jwt-secret="$(openssl rand -base64 48)"

helm upgrade --install minecraft-controller ./charts/minecraft-controller \
  --namespace minecraft --create-namespace \
  --set baseDomain=mc.example.com \
  --set auth.existingSecret=minecraft-controller-auth
```

Il chart installa tutto: controller, console web (`console.<baseDomain>`), router e volume. La console prende l'indirizzo dell'API (`api.<baseDomain>`) all'avvio del container, non dal build. Per solo l'API: `--set frontend.enabled=false`.

Dal chart pubblicato dalla CI (GHCR, senza clonare il repository):

```bash
helm upgrade --install minecraft-controller oci://ghcr.io/<owner>/charts/minecraft-controller \
  --version 0.5.0 --namespace minecraft --create-namespace \
  --set baseDomain=mc.example.com --set auth.existingSecret=minecraft-controller-auth
```

Configurazione completa, DNS, TLS, storage, backup e troubleshooting: [charts/minecraft-controller/README.md](charts/minecraft-controller/README.md). Esempio di values: [values-example.yaml](charts/minecraft-controller/values-example.yaml).

Script di comodo: `./install.sh` (helm upgrade --install, crea namespace e pull secret; la password di `admin` è `admin` di default, `ADMIN_PASSWORD=...` la cambia) e `./build.sh` (build e push delle immagini locali: backend e console).

## Sviluppo

```bash
# backend (richiede un kubeconfig: kubernetes.client.file=/path/kubeconfig)
cd backend && ./mvnw -B -ntp verify
IT_LORISDEMICHELI_MINECRAFTSERVERS_SECURITY_PASSWORD=dev \
IT_LORISDEMICHELI_MINECRAFTSERVERS_SECURITY_JWTSECRET=dev-secret-dev-secret-dev-secret-123 \
IT_LORISDEMICHELI_MINECRAFTSERVERS_BASEDOMAIN=localhost \
  ./mvnw spring-boot:run

# frontend (l'API è attesa su http://localhost:9696, vedi ui/src/environments/environment.ts)
cd ui && npm ci && npm test -- --watch=false && npm start

# immagine della console (l'indirizzo dell'API si passa all'avvio)
docker build -t minecraft-console:dev ui/
docker run --rm -p 4000:4000 -e API_BASE_URL=http://localhost:9696 -e NG_ALLOWED_HOSTS=localhost minecraft-console:dev
```

API documentata con OpenAPI su `/swagger-ui/index.html`. Health e metriche su `:8081/actuator/health`, `:8081/actuator/prometheus`.

## CI/CD

Su ogni push su `main` GitHub Actions compila e testa backend e frontend, valida il chart e pubblica le immagini `ghcr.io/<owner>/minecraft-controller` e `ghcr.io/<owner>/minecraft-console` (`sha-<commit>`, `main`, `latest`) e il chart Helm come pacchetto OCI (`ghcr.io/<owner>/charts/minecraft-controller`, una volta per ogni `version` di `Chart.yaml`). Dettagli: [docs/ci-cd.md](docs/ci-cd.md).

## Documentazione

- [Architettura e configurazione](docs/architecture.md)
- [Modello di dominio](docs/domain-model.md)
- Flussi: [autenticazione](docs/flows/authentication.md), [ciclo di vita](docs/flows/server-lifecycle.md), [console](docs/flows/server-console.md), [file](docs/flows/file-management.md)
- ADR: [001 config in annotation](docs/adr/adr-001-k8s-annotation-as-config-store.md), [002 admin singolo stateless](docs/adr/adr-002-stateless-single-admin.md), [003 compensazione del ciclo di vita](docs/adr/adr-003-lifecycle-compensation.md), [004 accesso sicuro al volume](docs/adr/adr-004-secure-directory-stream.md)
