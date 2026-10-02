# ADR-001: Configurazione Server come Annotation Kubernetes

**Stato**: Accettato  
**Data**: 2025

## Contesto

Ogni server Minecraft ha una configurazione (tipo, CPU, memoria, versione, ecc.) che deve essere persistita e associata al server. Le opzioni considerate erano:

1. **Database relazionale** (es. H2, PostgreSQL)
2. **ConfigMap Kubernetes**
3. **Annotation sul StatefulSet**

## Decisione

La configurazione è serializzata come **JSON in un'annotation** dello StatefulSet Kubernetes (`it.lorisdemicheli/config`).

Classe di persistenza: `ServerConfig` (Java record).  
Factory di lettura/scrittura: `ServerManifestFactory.readConfig()` / `toJson()`.

## Motivazioni

- **Zero dipendenze esterne**: nessun DB da gestire, provisioning o backup. La configurazione vive dove vive il server stesso.
- **Atomicità K8s**: la creazione del StatefulSet (con config nell'annotation) è atomica: non è possibile avere un pod senza config o una config senza pod.
- **Disaster recovery semplice**: chiunque abbia accesso al cluster può leggere la config con `kubectl get sts -o jsonpath='{.metadata.annotations}'`.
- **Coerenza**: l'annotation viene aggiornata insieme al StatefulSet in `replaceStatefulSet()` — una sola operazione PUT.

## Limitazioni e Trade-off

- **Etichette vs Annotation**: i label Kubernetes hanno un limite di 63 caratteri e non accettano URL; per questo la config completa va in un'annotation (limite ~256 KB), non in label. Le label sono usate solo per il selector (`managed-by`, `server-name`).
- **Nessuna storia**: le modifiche alla config non sono versionate (solo lo stato corrente in K8s).
- **StatefulSet senza annotation**: versioni precedenti del controller potrebbero aver creato StatefulSet senza questa annotation. `ServerService.list()` li logga come warning e li salta.
- **Dimensione**: per configurazioni molto complesse (futuri parametri) potrebbe diventare ingombrante, ma per i campi attuali è ampiamente sufficiente.

## Alternative Scartate

- **Database**: aggiunge un componente infrastrutturale da gestire, backup e sincronizzazione con lo stato K8s. Per il numero di server gestito (decine) è eccessivo.
- **ConfigMap**: richiede un'ulteriore risorsa K8s per server, complicando la creazione (transazionalità tra StatefulSet + Service + ConfigMap) e la cancellazione.
