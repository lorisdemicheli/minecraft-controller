# ADR-003: Operazioni compensative e recupero per il ciclo di vita dei server

**Stato**: Accettato
**Data**: 2026-11

## Contesto

Creare o cancellare un server tocca tre sistemi senza transazione comune: la cartella sul volume, il Service e lo StatefulSet su Kubernetes. Il codice precedente creava la cartella e il Service e, se lo StatefulSet falliva, cancellava solo il Service (lasciando la cartella); la cancellazione spostava subito i dati nel cestino, anche con il pod ancora in esecuzione, e falliva con 404 se lo StatefulSet era già stato rimosso da un tentativo precedente. Un errore di rete durante una create non permetteva di sapere se l'oggetto fosse stato creato.

## Decisione

Una classe dedicata, `ServerLifecycleManager`, esegue le operazioni multi-step; `ServerService` resta responsabile di validazione e mapping DTO.

1. **Lock per nome** in memoria (`NamedLocks`): operazioni sullo stesso server sono serializzate.
2. **Passi idempotenti**: delete che tollerano "non trovato", scale ripetibili, Service orfani adottati.
3. **Compensazione conservativa**: il rollback di una create rimuove il Service e la cartella solo se creata in quella chiamata e vuota, e mai se esiste uno StatefulSet con quel nome. Gli errori di compensazione sono loggati, contati (`minecraft_controller_rollback_failures_total`) e allegati come *suppressed* all'errore originale.
4. **Esiti incerti** (timeout, 5xx): create e replace non vengono ritentati alla cieca; lo stato reale viene riletto e confrontato con la configurazione voluta.
5. **Cancellazione in due fasi** con marker persistito sul volume (`.pending-deletion/<server>`): i dati vanno nel cestino solo quando il pod non esiste più (più un ritardo di sicurezza per i pod cancellati forzatamente). Il marker sopravvive ai riavvii e blocca la ricreazione dello stesso nome fino al completamento.
6. **Riconciliazione periodica**: completa le cancellazioni pendenti e rimuove i Service senza StatefulSet dopo un periodo di grazia.

## Conseguenze

- Le operazioni interrotte si completano ripetendo la richiesta o automaticamente entro un minuto.
- I lock e la riconciliazione coordinano un solo processo: il controller deve restare a **una replica** (il chart lo impone e usa la strategia `Recreate`). Per più repliche servirebbe un lock distribuito (es. Lease di Kubernetes con leader election): non introdotto perché non necessario oggi.
- `DELETE /servers/{name}` può rispondere 202 (dati in attesa di spostamento) invece di 204.
- Servono i permessi `services get/list` e `pods get` in più nel Role del controller.

## Alternative scartate

- **Un operator con CRD** (`MinecraftServer` + reconcile loop): modello più robusto ma riscrittura completa, CRD da installare e gestire; sproporzionato.
- **Database o ConfigMap come journal delle operazioni**: lo stato necessario (marker di cancellazione) sta già bene sul volume condiviso, vedi ADR-001.
- **Owner reference Service → StatefulSet** (garbage collection di Kubernetes): risolve gli orfani in cancellazione ma non in creazione, e cambia la struttura delle risorse esistenti.
