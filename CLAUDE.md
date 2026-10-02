# Minecraft Controller — AI Agent Context

Questo file è il punto d'ingresso primario per agenti AI che lavorano su questo repository.
Leggi questa sezione prima di esplorare il codice.

## Stack Tecnologico

| Layer | Tecnologia | Versione |
|---|---|---|
| Linguaggio | Java | 21 |
| Framework | Spring Boot | 4.0.1 |
| Build | Maven | 3.9 |
| Kubernetes client | client-java + spring-integration | 24.0.0 |
| Auth | JJWT (HS256) | 0.11.5 |
| API Docs | SpringDoc OpenAPI | 3.0.0 |
| Reactive | Reactor Core | gestita da BOM |
| Utility | Lombok | gestita da BOM |
| Runtime image | eclipse-temurin:21-jre-alpine | — |
| Build image | maven:3.9-eclipse-temurin-21 | — |
| Deploy | Helm chart | 0.3.0 |

Virtual threads abilitati (`spring.threads.virtual.enabled=true`): le chiamate bloccanti al Kubernetes API client sono convenienti su virtual thread.

## Struttura del Repository

```
minecraft-controller/
├── backend/                  ← unico modulo Maven (Spring Boot)
│   ├── src/main/java/...
│   ├── src/main/resources/application.properties
│   ├── Dockerfile
│   └── pom.xml
├── charts/minecraft-controller/  ← Helm chart di deploy
│   ├── values.yaml
│   └── templates/
├── build.sh                  ← build Docker image + rollout restart
└── install.sh                ← helm upgrade --install
```

Il **frontend** (Angular SSR / Node) è referenziato in `values.yaml` ma il suo codice non è presente in questo repository. `frontend.enabled: false` per default.

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

Componente schedulato: `TrashSweeper` — ogni ora purga i dati in `.trash` scaduti.

## Convenzioni di Codice

- **Architettura a layer**: Controller → Service → Gateway/Storage. Nessun Controller chiama direttamente il Kubernetes client.
- **Lombok**: `@Slf4j` per il logger `log`, `@RequiredArgsConstructor` per DI by constructor, `@Getter`/`@Setter` per DTOs, `@StandardException` per le exception custom. **Importante**: Lombok deve essere in `<annotationProcessorPaths>` del `maven-compiler-plugin` (già presente in `backend/pom.xml`).
- **Eccezioni**: tutte le eccezioni custom sono in `exception/` e usano `@StandardException`. La mappatura HTTP→applicazione avviene in `KubernetesGateway.translate()`. Il `GlobalExceptionHandler` (`@RestControllerAdvice`) converte le eccezioni in risposte JSON strutturate.
- **SSE / Streaming**: i due stream SSE (log e info) usano `Flux<T>` di Reactor. Il filtro JWT `shouldNotFilterAsyncDispatch()` restituisce `false` per lasciare passare i chunk SSE.
- **Config**: `MinecraftServerOptions` (`@ConfigurationProperties`) aggrega tutta la configurazione applicativa. Non esistono valori di configurazione sparsi nell'applicazione.
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
| [docs/adr/adr-002-stateless-single-admin.md](docs/adr/adr-002-stateless-single-admin.md) | Singolo admin JWT stateless |

## Comandi Utili per lo Sviluppo

```bash
# Build + push + rollout restart (se deploy già presente)
./build.sh

# Deploy iniziale (crea namespace, secret, helm install)
./install.sh

# Build locale senza Docker
cd backend && mvn -B package -DskipTests

# Build Docker locale
docker build -t minecraft-controller:dev backend/

# Recupero password generata dal chart
kubectl get secret mc-minecraft-controller-secret -n minecraft \
  -o jsonpath='{.data.password}' | base64 -d
```

## Prerequisiti e Configurazione Richiesta

I seguenti parametri **non hanno default** e causano il fallimento dell'avvio se assenti:

| Proprietà | Scopo |
|---|---|
| `it.lorisdemicheli.minecraft-servers.security.password` | Password dell'admin |
| `it.lorisdemicheli.minecraft-servers.security.jwt-secret` | Chiave HMAC-SHA256 (min 32 char) |

Forniti tramite l'Helm Secret (`charts/minecraft-controller/templates/secret.yaml`) o `existingSecret`.
