# Flusso: Ciclo di Vita dei Server

## Descrizione

Gestione completa di un server Minecraft: creazione, lettura, aggiornamento, eliminazione, avvio, stop e terminazione immediata. Ogni server corrisponde a una coppia di risorse Kubernetes (StatefulSet + Service).

## Endpoint

| Metodo | Path | Operazione |
|---|---|---|
| `GET` | `/servers` | Lista tutti i server |
| `GET` | `/servers/{name}` | Leggi un server |
| `POST` | `/servers` | Crea un server |
| `PUT` | `/servers/{name}` | Aggiorna un server |
| `DELETE` | `/servers/{name}?deleteData=true` | Elimina un server |
| `POST` | `/servers/{name}/console/start` | Avvia (replicas → 1) |
| `POST` | `/servers/{name}/console/stop` | Stop graceful (replicas → 0) |
| `POST` | `/servers/{name}/console/terminate` | Kill immediato |

---

## Flusso: Creazione Server

```mermaid
sequenceDiagram
    participant C as Client
    participant SC as ServerController
    participant SS as ServerService
    participant SN as ServerNames
    participant ST as ServerStorage
    participant SMF as ServerManifestFactory
    participant KG as KubernetesGateway
    participant K8s as Kubernetes API

    C->>SC: POST /servers {ServerInstanceDto}
    SC->>SS: create(dto)

    SS->>SS: validate(dto, creating=true)
    SS->>SN: requireNew(dto.name)
    alt Nome non valido o riservato
        SN-->>SS: InvalidRequestException / ConflictException
        SS-->>C: 400 / 409
    end

    SS->>ST: freeBytes()
    alt Spazio insufficiente (< minFreeGb)
        SS-->>C: 409 ConflictException
    end

    SS->>ST: createServerDir(name)
    SS->>SMF: service(dto)
    SMF-->>SS: V1Service
    SS->>KG: createService(namespace, service)
    KG->>K8s: POST /api/v1/namespaces/{ns}/services
    alt Già esiste
        K8s-->>KG: 409
        KG-->>SS: ResourceAlreadyExistsException
        SS-->>C: 409
    end

    SS->>SMF: statefulSet(dto)
    SMF-->>SS: V1StatefulSet (replicas=0, config in annotation)
    SS->>KG: createStatefulSet(namespace, statefulSet)
    KG->>K8s: POST /apis/apps/v1/namespaces/{ns}/statefulsets

    alt Creazione StatefulSet fallisce
        K8s-->>KG: errore
        KG-->>SS: eccezione
        SS->>KG: deleteService(namespace, name) ← rollback
        SS-->>C: eccezione originale
    end

    SS->>SS: toDto(sts) → readConfig + ServerStates.of()
    SS-->>SC: ServerInstanceDto (state=STOPPED)
    SC-->>C: 201 Created + Location header
```

**Nota**: il server viene creato con `replicas=0` (fermo). Deve essere esplicitamente avviato.

---

## Flusso: Aggiornamento Server

```mermaid
sequenceDiagram
    participant C as Client
    participant SC as ServerController
    participant SS as ServerService
    participant KG as KubernetesGateway
    participant SMF as ServerManifestFactory
    participant K8s as Kubernetes API

    C->>SC: PUT /servers/{name} {ServerInstanceDto}
    SC->>SS: update(name, dto)
    SS->>SS: dto.setName(name)  ← name immutabile
    SS->>SS: validate(dto, creating=false)
    SS->>KG: getStatefulSet(namespace, name)
    KG->>K8s: GET .../statefulsets/{name}
    K8s-->>KG: V1StatefulSet
    SS->>SMF: apply(sts, dto)
    Note over SMF: Aggiorna solo annotation config,\nenv vars e resource limits.\nSelector e storage immutati.
    SS->>KG: replaceStatefulSet(namespace, name, sts)
    KG->>K8s: PUT .../statefulsets/{name}
    Note over K8s: Se server in esecuzione,\nK8s riavvia il pod automaticamente.
    SS-->>C: 200 ServerInstanceDto aggiornato
```

---

## Flusso: Eliminazione Server

```mermaid
sequenceDiagram
    participant C as Client
    participant SC as ServerController
    participant SS as ServerService
    participant KG as KubernetesGateway
    participant ST as ServerStorage
    participant K8s as Kubernetes API

    C->>SC: DELETE /servers/{name}?deleteData=true
    SC->>SS: delete(name, deleteData=true)
    SS->>KG: deleteStatefulSet(namespace, name)
    KG->>K8s: DELETE .../statefulsets/{name}

    SS->>KG: deleteService(namespace, name)
    KG->>K8s: DELETE .../services/{name}
    Note over KG: ResourceNotFoundException ignorato\n(Service già eliminato)

    alt deleteData=true
        SS->>ST: moveToTrash(name)
        Note over ST: Rinomina data/{name} → data/.trash/{name}-{millis}\nAtomic move se supportato dal filesystem
    end

    SS-->>C: 204 No Content
```

**Purga dati**: `TrashSweeper` controlla ogni ora i dati in `.trash` e rimuove permanentemente quelli con timestamp più vecchio di `trashRetentionHours` (default 24h).

---

## Flusso: Start / Stop / Terminate

```mermaid
sequenceDiagram
    participant C as Client
    participant CC as ServerConsoleController
    participant SS as ServerService
    participant KG as KubernetesGateway
    participant K8s as Kubernetes API

    C->>CC: POST /servers/{name}/console/start
    CC->>SS: start(name)
    SS->>KG: scaleStatefulSet(namespace, name, replicas=1)
    KG->>K8s: GET .../statefulsets/{name}/scale
    KG->>K8s: PUT .../statefulsets/{name}/scale (spec.replicas=1)
    CC-->>C: 204 No Content

    C->>CC: POST /servers/{name}/console/stop
    CC->>SS: stop(name)
    SS->>KG: scaleStatefulSet(namespace, name, replicas=0)
    Note over K8s: Il pod riceve SIGTERM; il terminationGracePeriod\n(default 120s) permette al server di salvare il mondo
    CC-->>C: 204 No Content

    C->>CC: POST /servers/{name}/console/terminate
    CC->>SS: terminate(name)
    SS->>SS: stop(name) → replicas=0
    SS->>KG: deletePodNow(namespace, podName)
    KG->>K8s: DELETE .../pods/{name}-0?gracePeriodSeconds=0
    Note over KG: ResourceNotFoundException ignorato\n(nessun pod in esecuzione)
    CC-->>C: 204 No Content
```

### Differenza Stop vs Terminate

| | Stop | Terminate |
|---|---|---|
| Meccanismo | `replicas=0` | `replicas=0` + delete pod immediato |
| Grace period | Rispettato (120s default) | Ignorato (`gracePeriodSeconds=0`) |
| Rischio dati | Minimo (salvataggio mondo) | Alto (dati non salvati) |
| Uso | Shutdown controllato | Server bloccato / non risponde |

---

## Lettura Stato e Lista

### `GET /servers/{name}` e `GET /servers`

Lo stato (`ServerState`) viene derivato **esclusivamente** dal StatefulSet tramite `ServerStates.of(sts)`:

```
spec.replicas=0, status.replicas=0  → STOPPED
spec.replicas=0, status.replicas>0  → SHUTDOWN (terminando)
spec.replicas≥1, readyReplicas=0    → STARTING
spec.replicas≥1, readyReplicas≥1    → RUNNING
```

La configurazione del server viene letta dall'annotation JSON `it.lorisdemicheli/config` dello StatefulSet (`ServerManifestFactory.readConfig()`). StatefulSet senza annotation valida (creati da versioni precedenti) vengono **ignorati** silenziosamente nella lista, con un `log.warn`.

---

## Gestione Eccezioni

| Eccezione | HTTP | Causa |
|---|---|---|
| `ResourceNotFoundException` | 404 | Server non trovato nel namespace K8s |
| `ResourceAlreadyExistsException` | 409 | Server già esistente (creazione duplicata) |
| `ConflictException` | 409 | Spazio insufficiente, CurseForge API key mancante, config annotation corrotta |
| `InvalidRequestException` | 400 | Validazione fallita (nome, CPU, memory, campi obbligatori) |
| `ServerException` | 500 | Errore imprevisto del Kubernetes API |
