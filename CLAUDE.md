# Minecraft Controller — AI Agent Context

Questo file è il punto d'ingresso primario per agenti AI che lavorano su questo repository.
Leggi questa sezione prima di esplorare il codice.

## Stack Tecnologico

| Layer | Tecnologia | Versione |
|---|---|---|
| Linguaggio | Java | 21 |
| Framework | Spring Boot | 4.0.1 |
| Build | Maven | 3.9 |
| Kubernetes client | client-java | 24.0.0 |
| Health / metriche | Spring Boot Actuator + Micrometer Prometheus | gestite da BOM |
| Auth | JJWT (HS256) | 0.11.5 |
| API Docs | SpringDoc OpenAPI | 3.0.0 |
| Reactive | Reactor Core | gestita da BOM |
| Utility | Lombok | gestita da BOM |
| Runtime image | eclipse-temurin:21-jre (glibc: non usare Alpine, vedi ADR-004) | — |
| Build image | maven:3.9-eclipse-temurin-21 | — |
| Frontend | Angular (SSR) + Material, test Vitest | 21 |
| Deploy | Helm chart | 0.5.0 |
| CI | GitHub Actions (solo `main`) | — |

Il modulo `client-java-spring-integration` non va reintrodotto: registra un'autoconfigurazione che richiede Prometheus simpleclient e impediva l'avvio del contesto.

Virtual threads abilitati (`spring.threads.virtual.enabled=true`): le chiamate bloccanti al Kubernetes API client sono convenienti su virtual thread.

## Struttura del Repository

```
minecraft-controller/
├── backend/                  ← unico modulo Maven (Spring Boot)
│   ├── src/main/java/...
│   ├── src/main/resources/application.properties
│   ├── Dockerfile
│   └── pom.xml
├── ui/                       ← console Angular 21 + Dockerfile (npm test / npm run build)
├── charts/minecraft-controller/  ← Helm chart di deploy
│   ├── values.yaml, values.schema.json, values-example.yaml, README.md
│   ├── ci/                   ← values usati da lint/template nella CI
│   └── templates/
├── .github/workflows/ci.yml  ← build, test, helm, immagini GHCR (controller + console), chart OCI (solo push su main)
├── build.sh                  ← build + push delle immagini locali (backend + console), aggiorna la release
└── install.sh                ← helm upgrade --install
```

Il **frontend** è in `ui/` (con `Dockerfile`): la CI lo compila, lo testa e ne pubblica l'immagine `minecraft-console`; il chart lo installa di default (`frontend.enabled: true`). L'indirizzo dell'API non è nel bundle: il server Node serve `/config.js` da `API_BASE_URL` (il chart lo imposta), con `environment.apiBasePath` come ripiego per lo sviluppo. `NG_ALLOWED_HOSTS` elenca gli host serviti dal rendering lato server (altri → 400).

## Architettura ad Alto Livello

```
REST Client (browser / frontend)
         │  Bearer JWT
         ▼
┌─────────────────────────────────────────┐
│              Spring Boot API            │
│  AuthController      /login             │
│  ServerController    /servers           │
│  ServerConsoleController /servers/*/console │
│  ServerFileController    /servers/*/files   │
└────────────┬──────────────┬────────────┘
             │              │
             ▼              ▼
      ServerService   ServerConsoleService
             │              │
   ServerLifecycleManager   │
             └──────┬───────┘
                    ▼
           KubernetesGateway  ──►  Kubernetes API
           ServerManifestFactory    (StatefulSet, Service, Exec, Metrics)
                    │
                    ▼
           ServerStorage  ──►  Shared Volume (PVC)
                               └── <dataDir>/<server>/
                               └── <dataDir>/.trash/
```

Componenti schedulati: `TrashSweeper` (ogni ora purga `.trash`, retention 0 = mai) e `ServerLifecycleManager.reconcile()` (ogni minuto completa cancellazioni pendenti e rimuove Service orfani).

## Convenzioni di Codice

- **Architettura a layer**: Controller → Service → Gateway/Storage. Nessun Controller chiama direttamente il Kubernetes client. Le operazioni multi-step (create/update/delete/start/stop) passano da `ServerLifecycleManager` (lock per nome, rollback, idempotenza, vedi ADR-003); `ServerService` fa validazione e mapping.
- **Kubernetes**: solo `KubernetesGateway`. Retry (`KubernetesRetry`) solo per chiamate ripetibili ed errori transitori; create/replace mai ritentati alla cieca. Delete restituiscono `boolean` (false = già assente).
- **Filesystem**: solo `ServerStorage`, sempre tramite `SecureDirectoryStream` + `NOFOLLOW_LINKS` (ADR-004). Non usare `Files.*` con path costruiti da input utente.
- **Lombok**: `@Slf4j` per il logger `log`, `@RequiredArgsConstructor` per DI by constructor, `@Getter`/`@Setter` per DTOs, `@StandardException` per le exception custom. **Importante**: Lombok deve essere in `<annotationProcessorPaths>` del `maven-compiler-plugin` (già presente in `backend/pom.xml`).
- **Eccezioni**: tutte le eccezioni custom sono in `exception/` e usano `@StandardException`. La mappatura Kubernetes→applicazione avviene in `KubernetesGateway.translate()` (usa `V1Status.reason`). `GlobalExceptionHandler` produce il formato unico di `ApiErrors`; i messaggi delle eccezioni applicative sono mostrati al client, quindi non devono contenere dettagli interni. 503 = `KubernetesUnavailableException`, 429 = `TooManyRequestsException`.
- **SSE / Streaming**: i due stream SSE (log e info) usano `Flux<ServerSentEvent<T>>` di Reactor con heartbeat `:keepalive`. L'info stream è condiviso per server con conteggio esplicito dei subscriber (`InfoStream`, mutazioni solo in `ConcurrentHashMap.compute`). Il filtro JWT `shouldNotFilterAsyncDispatch()` restituisce `false` per lasciare passare i chunk SSE.
- **Config**: `MinecraftServerOptions` (`@ConfigurationProperties`) aggrega tutta la configurazione applicativa ed è validata all'avvio (`validate()`): aggiungendo una proprietà aggiungere anche il controllo e la riga nella tabella di `docs/architecture.md`.
- **Logging**: `@Slf4j`, messaggi con `server=<nome>`; mai password, token, contenuti di file o testo dei comandi console.
- **Test**: JUnit 5 + Mockito senza cluster (`KubernetesGateway` mockato), `@TempDir` per lo storage, `VirtualTimeScheduler` per gli stream, `TestOptions.valid()` per le opzioni. Frontend: Vitest via `ng test`.
- **Nomi server**: validati da `ServerNames` (DNS-1035 label, max 40 char, solo `a-z0-9-`). Il nome è usato simultaneamente come nome risorsa K8s, hostname DNS e nome cartella sul volume.
- **Configurazione server su K8s**: salvata come JSON nell'annotation `it.lorisdemicheli/config` dello StatefulSet (no DB). Vedi [ADR-001](docs/adr/adr-001-k8s-annotation-as-config-store.md).
- **Autenticazione**: singolo admin, JWT stateless. Vedi [ADR-002](docs/adr/adr-002-stateless-single-admin.md).

## Indice Documentazione `/docs/`

| File | Contenuto |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Componenti, diagramma Mermaid, configurazione |
| [docs/domain-model.md](docs/domain-model.md) | Entità, DTO, regole di business |
| [docs/flows/authentication.md](docs/flows/authentication.md) | Flusso login JWT |
| [docs/flows/server-lifecycle.md](docs/flows/server-lifecycle.md) | CRUD server + start/stop/terminate |
| [docs/flows/server-console.md](docs/flows/server-console.md) | Info SSE, log stream, comandi |
| [docs/flows/file-management.md](docs/flows/file-management.md) | Gestione file sul volume condiviso |
| [docs/adr/adr-001-k8s-annotation-as-config-store.md](docs/adr/adr-001-k8s-annotation-as-config-store.md) | Config server in annotation K8s |
| [docs/adr/adr-002-stateless-single-admin.md](docs/adr/adr-002-stateless-single-admin.md) | Singolo admin JWT stateless, revoca dopo rotazione |
| [docs/adr/adr-003-lifecycle-compensation.md](docs/adr/adr-003-lifecycle-compensation.md) | Compensazione e recupero di create/delete |
| [docs/adr/adr-004-secure-directory-stream.md](docs/adr/adr-004-secure-directory-stream.md) | Accesso al volume senza seguire symlink |
| [docs/ci-cd.md](docs/ci-cd.md) | Pipeline GitHub Actions, immagini GHCR, Trivy |
| [charts/minecraft-controller/README.md](charts/minecraft-controller/README.md) | Installazione, parametri Helm, backup |

## Comandi Utili per lo Sviluppo

```bash
# Build + push + rollout restart (se deploy già presente)
./build.sh

# Deploy iniziale (crea namespace, secret, helm install)
./install.sh

# Test e build backend
cd backend && ./mvnw -B -ntp verify

# Test e build frontend
cd ui && npm ci && npm test -- --watch=false && npm run build

# Validazione chart
helm lint charts/minecraft-controller -f charts/minecraft-controller/ci/default-values.yaml

# Build Docker locale
docker build -t minecraft-controller:dev backend/
docker build -t minecraft-console:dev ui/

# Recupero password generata dal chart
kubectl get secret mc-minecraft-controller-auth -n minecraft \
  -o jsonpath='{.data.password}' | base64 -d
```

## Prerequisiti e Configurazione Richiesta

I seguenti parametri **non hanno default** e causano il fallimento dell'avvio se assenti:

| Proprietà | Scopo |
|---|---|
| `it.lorisdemicheli.minecraft-servers.security.password` | Password dell'admin |
| `it.lorisdemicheli.minecraft-servers.security.jwt-secret` | Chiave HMAC-SHA256 (min 32 byte) |
| `it.lorisdemicheli.minecraft-servers.base-domain` | Dominio dei server (il chart lo imposta da `baseDomain`) |

Forniti tramite l'Helm Secret (`charts/minecraft-controller/templates/secret.yaml`) o `existingSecret`.
