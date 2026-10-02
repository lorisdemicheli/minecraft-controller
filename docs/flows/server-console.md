# Flusso: Console Server

## Descrizione

Accesso live a un server in esecuzione: informazioni runtime (CPU, memoria, giocatori), stream di log, invio comandi alla console Minecraft.

Due stream SSE (Server-Sent Events) per il monitoring in tempo reale:
- **Info stream**: stato + metriche ogni 10 secondi
- **Log stream**: tail live di `logs/latest.log` ogni 500ms

Entrambi leggono direttamente dal **volume condiviso** o tramite **exec nel pod**, quindi funzionano indipendentemente dallo stato del server (anche se fermo per i log storici).

## Endpoint

| Metodo | Path | Tipo risposta | Descrizione |
|---|---|---|---|
| `GET` | `/servers/{name}/console/info` | `application/json` | Snapshot info (one-shot) |
| `GET` | `/servers/{name}/console/info/stream` | `text/event-stream` | SSE info ogni 10s |
| `POST` | `/servers/{name}/console/commands` | — | Invia comando Minecraft |
| `GET` | `/servers/{name}/console/logs` | `text/event-stream` | SSE log live |
| `GET` | `/servers/{name}/console/logs/history` | `application/json` | Log storici (paginati) |

---

## Flusso: Info Snapshot (`GET /console/info`)

```mermaid
sequenceDiagram
    participant C as Client
    participant CC as ServerConsoleController
    participant SCS as ServerConsoleService
    participant KG as KubernetesGateway
    participant SMF as ServerManifestFactory
    participant K8s as Kubernetes API

    C->>CC: GET /servers/{name}/console/info
    CC->>SCS: info(name)

    SCS->>KG: getStatefulSet(namespace, name)
    KG->>K8s: GET .../statefulsets/{name}
    K8s-->>KG: V1StatefulSet
    SCS->>SCS: ServerStates.of(sts) → state

    SCS->>SCS: new ServerInstanceInfoDto(state)
    SCS->>SMF: readConfig(sts)
    SMF-->>SCS: Optional<ServerConfig>
    Note over SCS: Se config presente: imposta maxCpu, maxMemory

    alt state == RUNNING
        SCS->>SCS: readStatus(name, info)
        Note over SCS: exec mc-monitor status --json nel pod\nParsa: version, players, description, icon

        SCS->>SCS: readUsage(name, info)
        Note over SCS: Kubernetes Metrics API\nSomma CPU e memory di tutti i container
    end

    SCS-->>CC: ServerInstanceInfoDto
    CC-->>C: 200 JSON
```

### Dettaglio `readStatus`

Esegue `mc-monitor status --host localhost --port 25565 --json` nel container `minecraft` del pod `{name}-0`.

Parsing del JSON di risposta (`server_info`):
- `version` → `ServerVersionDto` (name, protocol)
- `players.online`, `players.max`, `players.Samples[]` → `ServerPopulationDto`
- `description` → `ServerDescriptionDto` (struttura MOTD Minecraft)
- `favicon` → `String` base64

Fallimenti (pod non raggiungibile, exit code != 0, JSON malformato) sono loggati a DEBUG e ignorati: l'info DTO viene restituito con i campi live a null.

### Dettaglio `readUsage`

Usa `GenericKubernetesApi` per `metrics.k8s.io/v1beta1/pods/{name}-0`.

Se `metrics-server` non è installato o non ha ancora dati, restituisce `Optional.empty()` e i campi `cpuUsage`/`memoryUsage` restano null (best-effort).

Conversioni:
- CPU: somma `BigDecimal` cores → milli-CPU (`* 1000`, arrotondamento `HALF_UP`)
- Memory: somma byte → MB (`/ 1024*1024`)

---

## Flusso: Info Stream SSE (`GET /console/info/stream`)

```mermaid
sequenceDiagram
    participant C as Client
    participant CC as ServerConsoleController
    participant SCS as ServerConsoleService

    C->>CC: GET /servers/{name}/console/info/stream\nAccept: text/event-stream
    CC->>SCS: info(name)  ← fail-fast 404 check
    CC->>SCS: infoStream(name)
    SCS->>SCS: ConcurrentHashMap.computeIfAbsent(name, ...)
    Note over SCS: Se non esiste: crea Flux.interval(0, 10s)\nsu Schedulers.boundedElastic()\n.replay(1).refCount(1, 10s)
    SCS-->>CC: Flux<ServerSentEvent<ServerInstanceInfoDto>>
    CC-->>C: SSE stream aperto

    loop Ogni 10 secondi
        SCS->>SCS: info(name)
        alt Successo
            SCS-->>C: event: {name}\ndata: {ServerInstanceInfoDto JSON}
        else ResourceNotFoundException (server eliminato)
            SCS-->>C: stream terminato
        else Altro errore
            SCS->>SCS: log.debug() + segnale vuoto (no disconnect)
        end
    end

    Note over SCS: Quando l'ultimo subscriber si disconnette:\n10 secondi di cooldown → stream rimosso dalla cache
```

**Condivisione stream**: il `Flux` è condiviso tra tutti i client che guardano lo stesso server (`.replay(1)` invia subito l'ultimo valore a nuovi subscriber). Questo evita N poll Kubernetes per N client simultanei.

---

## Flusso: Invio Comando (`POST /console/commands`)

```mermaid
sequenceDiagram
    participant C as Client
    participant CC as ServerConsoleController
    participant SCS as ServerConsoleService
    participant KG as KubernetesGateway
    participant K8s as Kubernetes API

    C->>CC: POST /servers/{name}/console/commands\nContent-Type: text/plain\nBody: "say Hello"

    CC->>SCS: sendCommand(name, "say Hello")
    SCS->>SCS: Valida comando non blank
    SCS->>KG: getStatefulSet(namespace, name)
    SCS->>SCS: ServerStates.of(sts)

    alt state != RUNNING
        SCS-->>C: 409 ConflictException "Server is not running"
    end

    SCS->>KG: exec(namespace, pod, container, "gosu", "minecraft", "mc-send-to-console", "say Hello")
    KG->>K8s: exec nel pod {name}-0, container minecraft
    Note over K8s: Timeout: 20 secondi

    alt exitCode != 0
        SCS-->>C: 500 ServerException (stderr incluso)
    else exitCode == 0
        SCS-->>CC: void
        CC-->>C: 204 No Content
    end
```

---

## Flusso: Log Stream SSE (`GET /console/logs`)

```mermaid
sequenceDiagram
    participant C as Client
    participant CC as ServerConsoleController
    participant SCS as ServerConsoleService
    participant LF as LogFile (utility)
    participant Vol as Volume (logs/latest.log)

    C->>CC: GET /servers/{name}/console/logs\nAccept: text/event-stream
    CC->>SCS: logStream(name)
    SCS->>SCS: Verifica che la dir server esista
    SCS->>LF: tail(path, 50)
    LF->>Vol: Legge le ultime 50 righe
    LF-->>SCS: Chunk(lines, position)

    SCS-->>C: SSE: prime 50 righe storiche (batch iniziale)

    loop Ogni 500ms (Schedulers.boundedElastic)
        SCS->>LF: readFrom(path, position)
        LF->>Vol: Legge da offset position
        alt Nuove righe disponibili
            LF-->>SCS: Chunk(newLines, newPosition)
            SCS->>SCS: Aggiorna AtomicLong position
            SCS-->>C: SSE: nuove righe
        else Log rotato (file più piccolo dell'offset)
            LF-->>SCS: Riparte da byte 0
        else UncheckedIOException (rotazione in corso)
            SCS->>SCS: Ignora, riprova al prossimo tick
        end
    end
```

### Dettagli `LogFile`

- `tail(path, n)`: legge a blocchi da 8KB dal fondo del file, raccoglie n righe complete
- `readFrom(path, pos)`: legge max 1 MB di nuovi dati da `pos`; restituisce solo righe complete (terminazione `\n`); strip `\r`
- Rotazione rilevata: se `file.size() < pos` riparte da 0
- Righe lunghe (> 1MB): emesse comunque per evitare stallo

### Log History (`GET /console/logs/history`)

Parametri: `limit` (default 100, max 5000), `skip` (default 0).

`LogFile.window(path, limit, skip)`: finestra di lettura che salta le ultime `skip` righe e restituisce le `limit` righe precedenti.

---

## Gestione Eccezioni

| Eccezione | HTTP | Contesto |
|---|---|---|
| `ResourceNotFoundException` | 404 | Server non trovato (StatefulSet o directory) |
| `ConflictException` | 409 | Comando inviato a server non RUNNING |
| `InvalidRequestException` | 400 | Comando vuoto o blank |
| `ServerException` | 500 | Exec fallito nel pod (mc-send-to-console) |
| Client disconnect (SSE) | — | `AsyncRequestNotUsableException` / IOException "Broken pipe": silenziosamente ignorato da `GlobalExceptionHandler` |
