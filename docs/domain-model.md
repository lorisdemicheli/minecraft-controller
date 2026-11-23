# Domain Model

## Glossario

| Termine | Definizione |
|---|---|
| **Server** | Un'istanza di server Minecraft gestita dal controller, identificata da un nome univoco. |
| **ServerState** | Stato derivato del server, calcolato dalle informazioni del StatefulSet Kubernetes. |
| **ServerConfig** | Configurazione immutabile (dal punto di vista del K8s) di un server: tipo, risorse, parametri. Persiste come JSON nell'annotation del StatefulSet. |
| **Namespace** | Namespace Kubernetes dove vivono le risorse dei server (default: `minecraft-servers`). |
| **Volume condiviso** | PersistentVolumeClaim unico montato da tutti i pod server (subPath per server) e dal controller. |
| **Trash** | Cartella `.trash/` del volume dove vengono spostati i dati dei server eliminati (soft-delete). |

---

## Entità Principali

### `ServerInstanceDto`

DTO principale usato per creare, leggere e aggiornare un server.

| Campo | Tipo | Default | Validazione / Note |
|---|---|---|---|
| `name` | `String` | — | DNS-1035: `[a-z][a-z0-9-]{0,38}[a-z0-9]?`, max 40 char. Riservati: `console`, `api`. Immutabile dopo la creazione. |
| `type` | `SmartServerTypeDto` | — | **Obbligatorio**. Determina il `TYPE` env var e i parametri specifici. |
| `cpu` | `int` | `1000` | Milli-CPU. Vincolo: `[100, 64000]`. |
| `memory` | `int` | `1024` | Megabyte. Vincolo: `[512, 262144]`. |
| `eula` | `boolean` | `false` | Minecraft EULA acceptance. |
| `version` | `String` | — | Versione Minecraft (usata da VANILLA, PLUGIN, MOD). |
| `modrinthProjectId` | `String` | — | **Obbligatorio** se `type = MODRINTH`. |
| `curseforgePageUrl` | `String` | — | **Obbligatorio** se `type = CURSEFORGE`. Richiede anche `curseForgeApiKey` configurata. |
| `state` | `ServerState` | — | **Read-only** (ignorato su create/update, popolato dal server). |

### `ServerConfig` (record)

Sottoinsieme di `ServerInstanceDto` persistito nell'annotation K8s. Esclude `name` e `state` (derivabili da K8s).

```java
record ServerConfig(SmartServerTypeDto type, int cpu, int memory, boolean eula,
    String version, String modrinthProjectId, String curseforgePageUrl)
```

Conversioni: `ServerConfig.from(dto)` → annotation JSON; `config.toDto(name)` → DTO con state = null.

### `ServerInstanceInfoDto`

Snapshot live di un server in esecuzione (risposta di `/console/info`).

| Campo | Tipo | Descrizione |
|---|---|---|
| `state` | `ServerState` | Stato corrente (costruttore required). |
| `cpuUsage` | `Long` | Milli-CPU usati (da Kubernetes Metrics API). |
| `memoryUsage` | `Long` | MB usati (da Kubernetes Metrics API). |
| `maxCpu` | `Long` | Limite CPU configurato in milli-CPU. |
| `maxMemory` | `Long` | Limite memoria configurato in MB. |
| `version` | `ServerVersionDto` | Versione server (da `mc-monitor status`). |
| `population` | `ServerPopulationDto` | Online/max/lista giocatori. |
| `description` | `ServerDescriptionDto` | MOTD del server. |
| `icon` | `String` | Favicon base64 del server. |

---

## Enumerazioni

### `ServerState`

Deriva dallo stato del StatefulSet K8s (solo `spec.replicas` e `status.readyReplicas`).

```mermaid
stateDiagram-v2
    [*] --> STOPPED : create() (replicas=0)
    STOPPED --> STARTING : start() → replicas=1
    STARTING --> RUNNING : readyReplicas=1
    RUNNING --> SHUTDOWN : stop() → replicas=0
    SHUTDOWN --> STOPPED : pod terminato
    RUNNING --> STOPPED : terminate() (force delete pod)
```

| Valore | Condizione K8s |
|---|---|
| `STOPPED` | desired=0, current=0 |
| `SHUTDOWN` | desired=0, current>0 (pod in terminazione) |
| `STARTING` | desired≥1, readyReplicas=0 |
| `RUNNING` | desired≥1, readyReplicas≥1 |

### `SmartServerTypeDto`

Enum con behavior: ogni valore sa quali variabili d'ambiente generare per il pod `itzg/minecraft-server`.

| Valore | `TYPE` env | Env aggiuntivi | Requisiti |
|---|---|---|---|
| `VANILLA` | `VANILLA` | `VERSION` | — |
| `PLUGIN` | `PAPER` | `VERSION` | — |
| `MOD` | `FORGE` | `VERSION` | — |
| `MODRINTH` | `MODRINTH` | `MODRINTH_PROJECT` | `modrinthProjectId` obbligatorio |
| `CURSEFORGE` | `AUTO_CURSEFORGE` | `CF_API_KEY`, `CF_PAGE_URL` | `curseforgePageUrl` + API key configurata |

---

## DTOs di Supporto

### `ServerPopulationDto`
```java
int online;        // giocatori connessi
int max;           // slot massimi
List<PlayerDto> players;
```

### `PlayerDto`
```java
String id;    // UUID
String name;
```

### `ServerVersionDto`
```java
String name;    // es. "1.21.4"
int protocol;
```

### `ServerDescriptionDto`
```java
String text;
boolean bold, italic, underlined, strikethrough, obfuscated;
String color;
Object extra;   // struttura MOTD raw di Minecraft
```

### `DiskUsageDto` (record)
```java
record DiskUsageDto(long usedBytes, long volumeFreeBytes, long volumeTotalBytes,
                    boolean partial, int unreadableEntries)
```
`partial=true`: alcune voci non erano leggibili, `usedBytes` è un limite inferiore.

### `ExecResult` (record, interno)
```java
record ExecResult(int exitCode, String stdout, String stderr, boolean truncated)
```
`truncated`: l'output ha superato `kubernetes.exec-max-output-bytes` ed è stato tagliato.

### `ServerLifecycleManager.DeleteOutcome` (enum, interno)
`DELETED` (204), `DATA_KEPT` (204, `deleteData=false`), `DATA_PENDING` (202: dati nel cestino appena il pod si ferma).

### `FileEntry` (record)
```java
record FileEntry(String name, FileType type, long sizeBytes, LocalDateTime lastModified)
enum FileType { FILE, DIRECTORY, OTHER }
```

---

## Regole di Business Fondamentali

### Nomi Server (`ServerNames`)
- Pattern: `[a-z]([a-z0-9-]{0,38}[a-z0-9])?` — subset di DNS-1035 label
- Lunghezza max: 40 caratteri
- Nomi riservati: `console`, `api` (collidono con gli hostname della piattaforma)
- Usato come: nome risorsa K8s + hostname DNS `<name>.<baseDomain>` + directory sul volume

### Validazione Creazione Server
1. Nome valido (non riservato, non già usato, nessuna cancellazione pendente con quel nome)
2. `type` non null
3. `cpu` in [100, 64000] milli-CPU
4. `memory` in [512, 262144] MB
5. `eula = true` (solo alla creazione)
6. `version`, se presente: `LATEST`, `SNAPSHOT`, `1.21`, `1.21.4`, `1.21-pre1`, `24w14a`…
7. `modrinthProjectId` obbligatorio (id o slug) se `type = MODRINTH`
8. `curseforgePageUrl` obbligatorio e della forma `https://www.curseforge.com/minecraft/modpacks/<slug>` se `type = CURSEFORGE`
9. CurseForge API key configurata se `type = CURSEFORGE`
10. Spazio libero sul volume ≥ `minFreeGb` (default 2 GB)

### Aggiornamento Server
- Il `name` è immutabile: viene sovrascritto con il path parameter `{serverName}`
- Vengono aggiornati annotation di config, env, risorse e security context (selector e storage invariati); conflitti di `resourceVersion` ritentati
- Un server in esecuzione si riavvia automaticamente (K8s replace)

### Eliminazione Server
- `deleteData=true` (default): dopo la rimozione di StatefulSet e Service, e solo quando il pod non esiste più, i dati vengono spostati in `.trash/<name>-<millis>` e purgati dopo `trashRetentionHours` (0 = mai). Fino ad allora un marker in `.pending-deletion/` blocca il riuso del nome
- `deleteData=false`: i dati rimangono; un nuovo server con lo stesso nome li adotta

### Sicurezza Filesystem (`ServerStorage`)
- Ogni path relativo è normalizzato lessicalmente (`..` non può uscire dalla cartella) e poi percorso componente per componente con `SecureDirectoryStream` + `NOFOLLOW_LINKS` (ADR-004)
- I symlink non vengono mai seguiti; scritture e upload sono atomici
- Non è possibile cancellare o rinominare la directory root del server
- Il rename non accetta path separator nel nuovo nome

### Risorse Pod
- Heap JVM = `MEMORY` env (`memory` MB)
- Memory limit = heap + max(heap × `memory-overhead-percent`%, `memory-overhead-min-mb`) in MiB (default 15%, minimo 256 MiB)
- CPU limit = `cpu` milli-CPU (disattivabile con `server-resources.cpu-limit-enabled=false`)
- Requests = `cpu-request-percent` / `memory-request-percent` dei limiti (default 100%: requests = limits, QoS Guaranteed, come nelle versioni precedenti, in cui Kubernetes copiava i limits nelle requests)
- Nota: prima il limite era espresso in `M` (10^6 byte) mentre l'heap è in MiB: ora entrambi sono in MiB

---

## Relazioni tra Entità

```mermaid
classDiagram
    class ServerInstanceDto {
        +String name
        +SmartServerTypeDto type
        +int cpu
        +int memory
        +boolean eula
        +String version
        +String modrinthProjectId
        +String curseforgePageUrl
        +ServerState state
    }

    class ServerConfig {
        <<record>>
        +SmartServerTypeDto type
        +int cpu
        +int memory
        +boolean eula
        +String version
        +String modrinthProjectId
        +String curseforgePageUrl
        +from(dto) ServerConfig
        +toDto(name) ServerInstanceDto
    }

    class ServerInstanceInfoDto {
        +ServerState state
        +Long cpuUsage
        +Long memoryUsage
        +Long maxCpu
        +Long maxMemory
        +ServerVersionDto version
        +ServerPopulationDto population
        +ServerDescriptionDto description
        +String icon
    }

    class ServerPopulationDto {
        +int online
        +int max
        +List~PlayerDto~ players
    }

    class SmartServerTypeDto {
        <<enumeration>>
        VANILLA
        PLUGIN
        MOD
        MODRINTH
        CURSEFORGE
        +getEnvs(dto, options) List~V1EnvVar~
    }

    class ServerState {
        <<enumeration>>
        STOPPED
        STARTING
        RUNNING
        SHUTDOWN
    }

    ServerInstanceDto --> SmartServerTypeDto
    ServerInstanceDto --> ServerState
    ServerInstanceDto ..> ServerConfig : from/toDto
    ServerInstanceInfoDto --> ServerState
    ServerInstanceInfoDto --> ServerPopulationDto
    ServerPopulationDto --> PlayerDto
```
