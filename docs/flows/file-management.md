# Flusso: Gestione File

## Descrizione

API di file manager per la cartella dati di un server. Tutte le operazioni accedono direttamente al volume condiviso tramite `ServerStorage`, indipendentemente dallo stato del server (funziona anche con il server fermo).

La sicurezza è gestita da `ServerStorage.resolve()`: ogni path viene validato per evitare directory traversal (`..`) e symlink escape.

## Endpoint

| Metodo | Path | Query params | Descrizione |
|---|---|---|---|
| `GET` | `/servers/{name}/files` | `path=/` | Lista directory |
| `GET` | `/servers/{name}/files/download` | `path=<file>` | Download file (streaming) |
| `POST` | `/servers/{name}/files` | `destPath=<dir>`, `file=<multipart>` | Upload file (max 1 GB) |
| `POST` | `/servers/{name}/files/directory` | `path=<dir>` | Crea directory |
| `POST` | `/servers/{name}/files/touch` | `path=<file>` | Crea file vuoto / aggiorna mtime |
| `DELETE` | `/servers/{name}/files` | `path=<path>` | Elimina file o directory |
| `GET` | `/servers/{name}/files/content` | `path=<file>` | Leggi testo (max 2 MB) |
| `PUT` | `/servers/{name}/files/content` | `path=<file>` | Scrivi testo |
| `POST` | `/servers/{name}/files/rename` | `path=<path>`, `newName=<name>` | Rinomina |
| `POST` | `/servers/{name}/files/copy` | `sourcePath=<path>`, `destPath=<path>` | Copia |
| `GET` | `/servers/{name}/files/usage` | — | Spazio disco usato / disponibile |

---

## Layout Volume

```
<dataDir>/
└── <server-name>/          ← root del server (IRRIMUOVIBILE via API)
    ├── logs/
    │   └── latest.log      ← letto anche da ServerConsoleService
    ├── world/
    ├── server.properties
    └── ...
```

---

## Diagramma: Lista e Navigazione

```mermaid
sequenceDiagram
    participant C as Client
    participant FC as ServerFileController
    participant ST as ServerStorage
    participant Vol as Volume

    C->>FC: GET /servers/{name}/files?path=/world
    FC->>ST: list(name, "/world")
    ST->>ST: resolve(name, "/world", symlink=false)
    Note over ST: Valida path: null byte, escape, symlink
    ST->>Vol: Files.list(resolved)
    Vol-->>ST: Stream<Path>
    ST->>ST: describe(path) per ogni entry
    ST->>ST: sort: directory prima, poi A-Z case-insensitive
    ST-->>FC: List<StoredFile>
    FC->>FC: .map(FileEntry::from) → LocalDateTime
    FC-->>C: 200 [{name, type, sizeBytes, lastModified}]
```

---

## Diagramma: Download File

```mermaid
sequenceDiagram
    participant C as Client
    participant FC as ServerFileController
    participant ST as ServerStorage
    participant Vol as Volume

    C->>FC: GET /servers/{name}/files/download?path=/world/level.dat
    FC->>ST: stat(name, path)
    ST-->>FC: StoredFile(name, FILE, size, mtime)
    alt Non è un file regolare
        FC-->>C: 400 InvalidRequestException "Not a regular file"
    end
    FC->>ST: openRead(name, path)
    ST->>Vol: Files.newInputStream(resolved)
    Vol-->>ST: InputStream
    FC-->>C: 200 application/octet-stream\nContent-Disposition: attachment\nContent-Length: <size>\n(StreamingResponseBody → trasferimento diretto)
```

**Nota**: `StreamingResponseBody` evita buffering in memoria: adatto per file di qualsiasi dimensione.

---

## Diagramma: Upload File

```mermaid
sequenceDiagram
    participant C as Client
    participant FC as ServerFileController
    participant ST as ServerStorage
    participant Vol as Volume

    C->>FC: POST /servers/{name}/files\nmultipart: file=<binary>, destPath=/plugins
    FC->>ST: upload(name, "/plugins", "plugin.jar", InputStream)
    ST->>ST: resolve(name, "/plugins")
    alt /plugins è directory esistente
        ST->>ST: target = /plugins/plugin.jar
    else /plugins è un path file
        ST->>ST: target = /plugins (path completo)
    end
    ST->>ST: Valida target non sia directory
    ST->>ST: Valida parent directory esiste
    ST->>Vol: Files.copy(InputStream, target, REPLACE_EXISTING)
    FC-->>C: 204 No Content
```

**Limite**: `spring.servlet.multipart.max-file-size=1GB` (configurato in `application.properties`). File più grandi ricevono 413.

---

## Diagramma: Lettura / Scrittura Testo

```mermaid
sequenceDiagram
    participant C as Client
    participant FC as ServerFileController
    participant ST as ServerStorage
    participant Vol as Volume

    C->>FC: GET /servers/{name}/files/content?path=/server.properties
    FC->>ST: readText(name, path, maxBytes=2MB)
    ST->>ST: requireRegularFile(resolved)
    ST->>Vol: Files.size(file)
    alt Dimensione > 2 MB
        ST-->>FC: InvalidRequestException "File too large to edit"
        FC-->>C: 400 (scaricare il file invece di aprirlo nell'editor)
    end
    ST->>Vol: Files.readAllBytes(file)
    Vol-->>ST: byte[]
    ST-->>FC: String (UTF-8)
    FC-->>C: 200 text/plain

    C->>FC: PUT /servers/{name}/files/content?path=/server.properties\nBody: "contenuto modificato"
    FC->>ST: writeText(name, path, content)
    ST->>ST: Valida non è directory
    ST->>Vol: Files.writeString(CREATE|TRUNCATE|WRITE)
    FC-->>C: 204 No Content
```

---

## Regole di Sicurezza e Validazione

Tutte le operazioni passano per `ServerStorage.resolve(server, rel, leafMayBeLink)`:

1. **Null byte**: path con `\0` → `InvalidRequestException "Invalid path"`
2. **Backslash**: normalizzati in forward slash
3. **Escape lessicale**: `normalize()` dopo `resolve()` → se il risultato non parte dalla dir del server → `InvalidRequestException "Path escapes the server directory"`
4. **Symlink escape**: il sistema risolve `toRealPath()` sugli antenati esistenti del path e verifica che stiano dentro la dir del server → previene symlink che puntano fuori
5. **Root directory**: cancellazione e rinomina della root del server sono rifiutate
6. **Rename**: nessun path separator (`/` o `\`) nel nuovo nome
7. **Copy directory-in-itself**: rilevata e rifiutata

---

## Spazio Disco (`GET /files/usage`)

```mermaid
sequenceDiagram
    participant C as Client
    participant FC as ServerFileController
    participant ST as ServerStorage
    participant Vol as Volume

    C->>FC: GET /servers/{name}/files/usage
    FC->>ST: usedBytes(name)
    ST->>Vol: Files.walkFileTree (somma size file regolari)
    ST->>ST: freeBytes() → FileStore.getUsableSpace()
    ST->>ST: totalBytes() → FileStore.getTotalSpace()
    FC->>FC: new DiskUsageDto(used, free, total)
    FC-->>C: 200 {usedBytes, volumeFreeBytes, volumeTotalBytes}
```

---

## Gestione Eccezioni

| Eccezione | HTTP | Causa |
|---|---|---|
| `ResourceNotFoundException` | 404 | Directory/file non trovato, parent non trovato |
| `InvalidRequestException` | 400 | Path non valido, non è file regolare, file troppo grande, nome con separator |
| `ConflictException` | 409 | Directory già esiste (mkdir), file già esiste (rename/copy) |
| `MaxUploadSizeExceededException` | 413 | File upload > 1 GB |
