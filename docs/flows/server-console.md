# Flusso: Console Server

## Descrizione

Accesso live a un server in esecuzione: informazioni runtime (CPU, memoria, giocatori), stream di log, invio comandi alla console Minecraft.

Due stream SSE (Server-Sent Events) per il monitoring in tempo reale:
- **Info stream**: stato + metriche ogni 10 secondi (polling condiviso tra i client)
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
    participant SCS as ServerConsoleService
    participant IS as InfoStream (uno per server)

    C->>SCS: GET /servers/{name}/console/info/stream
    SCS->>SCS: nessuno stream attivo? getStatefulSet → 404 subito
    SCS->>IS: join (dentro ConcurrentHashMap.compute)
    Note over IS: subscribers++, annulla l'eventuale timer di stop,<br/>avvia il polling se non è attivo
    IS-->>C: ultimo valore (replay latest) + aggiornamenti

    loop ogni console.info-poll-interval (10s), un solo polling per server
        IS->>IS: info(name)
        alt successo
            IS-->>C: event: {name} / data: {ServerInstanceInfoDto}
        else server eliminato (404)
            IS-->>C: stream completato, entry rimossa
        else altro errore
            IS->>IS: log debug + metrica, tick saltato (nessuna disconnessione)
        end
    end
    loop ogni console.heartbeat-interval (15s)
        IS-->>C: ":keepalive" (commento SSE)
    end

    C->>IS: disconnessione → leave (compute): subscribers--
    Note over IS: a 0 subscriber: timer di console.info-stream-grace-period (10s)<br/>scaduto senza nuovi viewer → polling fermato, entry rimossa
```

- Più client condividono lo stesso polling; la disconnessione di uno non interrompe gli altri.
- Tutte le modifiche (creazione, join, leave, stop) avvengono in `compute` sulla chiave del server: nessuna race, nessuna entry orfana nella mappa (verificato da `ServerConsoleServiceTest`).
- Il fail-fast non esegue più un `info()` completo (exec + metriche) per ogni client: solo un `getStatefulSet` quando lo stream non è già attivo.
- Metriche: `minecraft_controller_sse_info_streams`, `..._sse_info_subscribers`, `..._sse_log_streams`.

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
    SCS->>SCS: Valida: non vuoto, una sola riga, niente caratteri di controllo, max 1000
    SCS->>KG: getStatefulSet(namespace, name)
    SCS->>SCS: ServerStates.of(sts)

    alt state != RUNNING
        SCS-->>C: 409 ConflictException "Server is not running"
    end

    SCS->>KG: exec(..., "gosu", "minecraft", "mc-send-to-console", "say Hello")
    Note over SCS: con server-security.run-as-non-root: senza gosu
    KG->>K8s: exec nel pod {name}-0, container minecraft
    Note over K8s: deadline unica kubernetes.exec-timeout (20s),<br/>output limitato a exec-max-output-bytes

    alt exitCode != 0
        SCS-->>C: 500 ServerException (stderr abbreviato)
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

    loop Ogni console.log-poll-interval (500ms)
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

- Il file è aperto tramite `ServerStorage.logSource()`: nessun symlink viene seguito (un plugin che sostituisce `logs/latest.log` con un link a file di altri server ottiene uno stream vuoto).
- Righe più lunghe di `console.max-log-line-length` sono troncate con il suffisso ` … [truncated]`; heartbeat `:keepalive` ogni `console.heartbeat-interval`.
- Formato degli eventi invariato: un evento `data:` per riga.

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
| `InvalidRequestException` | 400 | Comando vuoto, multilinea, con caratteri di controllo o troppo lungo |
| `ServerException` | 500 | Exec fallito nel pod (mc-send-to-console) |
| Client disconnect (SSE) | — | `AsyncRequestNotUsableException` / IOException "Broken pipe": silenziosamente ignorato da `GlobalExceptionHandler` |
