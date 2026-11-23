# ADR-004: Accesso al volume con SecureDirectoryStream

**Stato**: Accettato
**Data**: 2026-11

## Contesto

Il file manager opera sulle cartelle dei server, che sono scritte dal processo Minecraft e dai suoi plugin (codice di terze parti) con lo stesso UID del controller. Il controllo precedente (`normalize()` + `startsWith()` + `toRealPath()` sugli antenati) era corretto in un istante, ma tra il controllo e l'uso un plugin poteva sostituire una cartella con un symlink verso un altro server o verso il resto del volume (TOCTOU). Anche lo stream dei log leggeva `logs/latest.log` seguendo i symlink.

## Decisione

- Nessun path utente viene risolto dal sistema operativo a partire dalla radice. `ServerStorage` apre la cartella del server e poi ogni componente con `SecureDirectoryStream` e `LinkOption.NOFOLLOW_LINKS`; file aperti, cancellati e rinominati relativamente al descrittore della cartella (`openat`/`unlinkat`/`renameat`).
- I symlink non vengono mai seguiti, nemmeno se puntano dentro la cartella del server: si possono vedere, rinominare e cancellare.
- Scritture e upload sono atomici (file temporaneo + rename) e preservano i permessi.
- All'avvio si verifica che il filesystem supporti `SecureDirectoryStream` (Linux, macOS); altrimenti l'applicazione non parte.

## Conseguenze

- Le race con il processo Minecraft non possono portare il controller fuori dalla cartella del server (coperto da `ServerStorageSecurityTest`).
- Symlink creati intenzionalmente dentro un server (raro) non sono più navigabili dal file manager: cambio di comportamento documentato.
- Limite: Java non offre `mkdirat`; la creazione di directory usa il path dopo aver aperto i genitori senza seguire link, poi riapre la directory con NOFOLLOW. Nel caso peggiore una race può creare una directory vuota fuori posto, mai leggere o scrivere file.
- Sviluppo locale su Windows non supportato per lo storage (il container è Linux).
- L'immagine runtime deve essere basata su **glibc** (`eclipse-temurin:21-jre`): su Alpine (musl ≥ 1.2.4) il JDK non trova `openat64`/`fstatat64` e `SecureDirectoryStream` non è disponibile, quindi il controller non si avvierebbe. Verificato con uno smoke test dell'immagine.
