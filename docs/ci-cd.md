# CI/CD

Workflow: [`.github/workflows/ci.yml`](../.github/workflows/ci.yml).

## Quando parte

- **push su `main`**;
- **manualmente** (`workflow_dispatch`, tab *Actions → CI → Run workflow*). Se lanciato su un altro branch esegue i controlli ma **non** pubblica immagini (il job `docker` ha `if: github.ref == 'refs/heads/main'`).

Nessun trigger su `pull_request`, `develop` o altri branch.

## Job (in ordine, ognuno parte solo se il precedente è riuscito)

| # | Job | Cosa fa | Artifact |
|---|---|---|---|
| 1 | `backend` | Temurin 21, cache Maven, `./mvnw -B -ntp verify` (compilazione + tutti i test JUnit/Mockito/integrazione) | `backend-jar` (il JAR prodotto); report surefire se fallisce |
| 2 | `frontend` | Node 22, cache npm su `ui/package-lock.json`, `npm ci`, `npm test -- --watch=false` (Vitest), `npm run build` | `frontend-dist` |
| 3 | `helm` | Helm 3.16, `helm lint --strict` con ogni file `ci/*.yaml`, verifica che i valori obbligatori/vietati vengano rifiutati, `helm template` (default, `values-example.yaml`, ogni `ci/*.yaml`), **kubeconform** `-strict` (Kubernetes 1.30), `helm package` | `helm-chart` (pacchetto `.tgz` + manifest renderizzati) |
| 4 | `docker` | Una leg per immagine (matrice): `controller` (`backend/Dockerfile`, context `backend/`) e `console` (`ui/Dockerfile`, context `ui/`). Build, **scansione Trivy**, push su GHCR | immagini |
| 5 | `chart` | Pubblica il chart come pacchetto OCI su GHCR, **una volta per ogni `version`** di `Chart.yaml` (se esiste già non la sovrascrive) | `ghcr.io/<owner>/charts/minecraft-controller` |

Il job `helm` verifica anche che la console riceva `API_BASE_URL` (http di default, https con TLS) e che `frontend.enabled` senza immagine venga rifiutato.

Configurazioni Helm verificate (`charts/minecraft-controller/ci/`): default (console inclusa), console con immagine propria, solo API (`frontend.enabled=false`), `existingClaim`, TLS con host/certificati dedicati, RBAC e ServiceAccount disabilitati, `existingSecret`, tutte le funzionalità opzionali insieme (NetworkPolicy, HPA, PDB, backup, NodePort, log JSON, server non-root).

## Immagini pubblicate

`ghcr.io/<owner>/minecraft-controller` (backend) e `ghcr.io/<owner>/minecraft-console` (console web), owner in minuscolo, con i tag:

| Tag | Uso |
|---|---|
| `sha-<commit completo>` | **immutabile**, da usare nei deployment |
| `main` | ultimo commit di main (tag mobile) |
| `latest` | come `main` |

Le immagini vengono pubblicate **solo** se backend, frontend e Helm sono passati e se la scansione non trova vulnerabilità CRITICAL correggibili. Le due immagini di un commit hanno lo stesso tag `sha-<commit>`: `./install.sh` con `TAG=sha-<commit>` le imposta entrambe. Il chart (job 5) parte solo dopo che entrambe sono state pubblicate.

Il riepilogo del job (`Summary`) riporta digest e comando Helm pronto da copiare.

## Usare un'immagine della CI

```bash
helm upgrade --install minecraft-controller charts/minecraft-controller \
  --namespace minecraft --create-namespace -f my-values.yaml \
  --set controller.image.repository=ghcr.io/OWNER/minecraft-controller \
  --set controller.image.tag=sha-COMMIT \
  --set controller.image.pullPolicy=IfNotPresent
```

Il package GHCR eredita la visibilità del repository; se è privato serve un pull secret (README del chart). Nessuna credenziale è salvata nel repository e la pipeline **non** esegue deploy su cluster.

## Sicurezza della pipeline

- `permissions: contents: read` a livello di workflow; solo il job `docker` ha `packages: write`.
- Autenticazione a GHCR con il `GITHUB_TOKEN` del run (nessun secret aggiuntivo).
- Action fissate a SHA di commit (con la versione in commento); kubeconform scaricato con verifica SHA-256.
- `actions/checkout` con `persist-credentials: false`.
- Cache: Maven/npm via `setup-java`/`setup-node` (chiave dai file di lock/pom), layer Docker via cache GitHub Actions (`type=gha`), entrambe con scope del repository.
- Build riproducibile: `SOURCE_DATE_EPOCH` = data del commit.

### Politica Trivy

| Severità | Effetto |
|---|---|
| CRITICAL con fix disponibile | **blocca** la pubblicazione |
| CRITICAL senza fix | riportata (`ignore-unfixed`), non blocca |
| HIGH | riportata nel log, non blocca |

Motivazione: le vulnerabilità senza correzione (tipicamente nell'immagine base `eclipse-temurin:21-jre`) non sono risolvibili dal progetto e bloccherebbero ogni rilascio. Vanno riviste periodicamente nel log del job; per accettarne una in modo esplicito si può aggiungere un file `.trivyignore` con motivazione.

## Versionamento

- **Applicazione**: `backend/pom.xml` `<version>` = `Chart.yaml` `appVersion` (oggi `1.1.0`), aggiornate a mano.
- **Chart**: `Chart.yaml` `version` (semver), incrementata a mano quando cambiano template o values, non a ogni build. Pubblicazione OCI opzionale: vedi README del chart, *Rilascio del chart*.

## Verifica locale equivalente

```bash
(cd backend && ./mvnw -B -ntp verify)
(cd ui && npm ci && npm test -- --watch=false && npm run build)
helm lint charts/minecraft-controller -f charts/minecraft-controller/ci/default-values.yaml
helm template t charts/minecraft-controller -f charts/minecraft-controller/ci/full-values.yaml | kubeconform -strict -summary
docker build -t minecraft-controller:dev backend/
```
