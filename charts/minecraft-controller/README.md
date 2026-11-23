# minecraft-controller — Helm chart

Installa in un namespace:

- **controller** (backend Spring Boot): API REST, gestione dei server Minecraft come StatefulSet;
- **router** ([itzg/mc-router](https://github.com/itzg/mc-router)): instrada `<server>.<baseDomain>` al server giusto;
- **volume dati condiviso** (un PVC, una cartella per server);
- **Ingress** per l'API (e per la console web, opzionale);
- opzionali: NetworkPolicy, PodDisruptionBudget/HPA del router, backup periodico.

## Prerequisiti

| Requisito | Note |
|---|---|
| Kubernetes ≥ 1.25 | |
| Helm ≥ 3.8 | |
| StorageClass **ReadWriteMany** | consigliata (Longhorn+NFS, CephFS, NFS provisioner…). Con ReadWriteOnce tutti i pod che usano il volume devono girare sullo stesso nodo |
| Ingress controller | traefik (default k3s) o ingress-nginx |
| LoadBalancer o NodePort | per la porta Minecraft (25565) del router |
| metrics-server | opzionale: CPU/RAM dei server nella console |
| cert-manager | opzionale: certificati TLS |

## Installazione rapida

```bash
# 1. credenziali (consigliato: Secret creato da te, mai nei values versionati)
kubectl create namespace minecraft
kubectl -n minecraft create secret generic minecraft-controller-auth \
  --from-literal=password="$(openssl rand -base64 18)" \
  --from-literal=jwt-secret="$(openssl rand -base64 48)"
# opzionale, solo per i modpack CurseForge:
#   --from-literal=curseforge-api-key=...

# 2. installazione
helm upgrade --install minecraft-controller ./charts/minecraft-controller \
  --namespace minecraft --create-namespace \
  --set baseDomain=mc.example.com \
  --set auth.existingSecret=minecraft-controller-auth

# 3. verifica
helm test minecraft-controller -n minecraft
```

Oppure con un file di valori (partendo da [`values-example.yaml`](values-example.yaml)):

```bash
helm upgrade --install minecraft-controller ./charts/minecraft-controller \
  --namespace minecraft --create-namespace -f my-values.yaml
```

### Installare una versione specifica dell'immagine

La CI pubblica `ghcr.io/<owner>/minecraft-controller` con i tag `sha-<commit>` (immutabile), `main` e `latest`.

```bash
helm upgrade --install minecraft-controller ./charts/minecraft-controller \
  --namespace minecraft --create-namespace \
  -f my-values.yaml \
  --set controller.image.repository=ghcr.io/OWNER/minecraft-controller \
  --set controller.image.tag=sha-COMMIT \
  --set controller.image.pullPolicy=IfNotPresent \
  --set frontend.image.repository=ghcr.io/OWNER/minecraft-console \
  --set frontend.image.tag=sha-COMMIT
```

Se il package GHCR è privato crea un pull secret e passalo al chart:

```bash
kubectl -n minecraft create secret docker-registry ghcr-pull \
  --docker-server=ghcr.io --docker-username=OWNER --docker-password=<PAT con read:packages>
helm upgrade ... --set imagePullSecrets[0].name=ghcr-pull
```

## Aggiornamento e disinstallazione

```bash
helm upgrade minecraft-controller ./charts/minecraft-controller -n minecraft --reuse-values \
  --set controller.image.tag=sha-NUOVOCOMMIT
helm rollback minecraft-controller -n minecraft          # torna alla revisione precedente

helm uninstall minecraft-controller -n minecraft
```

Il controller usa la strategia `Recreate` (mai due istanze contemporaneamente): durante un
aggiornamento l'API è indisponibile per qualche secondo, i server Minecraft continuano a girare.

`helm uninstall` **non** cancella il PVC dei dati (`helm.sh/resource-policy: keep`) né i server
Minecraft creati dal controller (StatefulSet e Service con label `managed-by=minecraft-controller`).
Per rimuovere tutto:

```bash
kubectl -n minecraft delete statefulset,service -l managed-by=minecraft-controller
kubectl -n minecraft delete pvc minecraft-controller-data   # IRREVERSIBILE: cancella i mondi
```

## Credenziali

| Modalità | Quando |
|---|---|
| `auth.existingSecret` | produzione, GitOps (Argo CD/Flux), `helm template` |
| `auth.password` / `auth.jwtSecret` via `--set-string` o file non versionato | test, installazioni semplici |
| niente (`auth.jwtSecret` vuoto) | il chart genera la chiave JWT alla prima installazione e la riusa agli upgrade (`lookup`; non funziona con `helm template`/GitOps) |

**La password di default è `admin`** (`auth.password`): cambiala prima di esporre il pannello.

```bash
helm upgrade --install ... --set-string auth.password='una-password-lunga'   # la scegli tu
helm upgrade --install ... --set-string auth.password=                       # casuale, generata una volta e riusata agli upgrade
```

Cambiare la password invalida i token già emessi: dopo l'`upgrade` rifai il login.

Chiavi del Secret: `password`, `jwt-secret` (≥ 32 caratteri), `curseforge-api-key` (opzionale).

```bash
kubectl -n minecraft get secret minecraft-controller-auth -o jsonpath='{.data.password}' | base64 -d; echo
```

**Rotazione**: modifica il Secret e riavvia il controller
(`kubectl -n minecraft rollout restart deploy/minecraft-controller-controller`). Cambiando la password
**o** la chiave JWT tutti i token emessi prima vengono rifiutati (vedi ADR-002).

La chiave CurseForge non compare mai in chiaro: i pod dei server la leggono dallo stesso Secret
(`secretKeyRef`) e la ricerca dei modpack passa dal backend.

## Storage

```yaml
storage:
  size: 100Gi
  className: longhorn
  accessMode: ReadWriteMany
  existingClaim: ""        # oppure il nome di un PVC già esistente nel namespace
  uid: 1000                # utente dei file (default dell'immagine itzg)
  gid: 1000
  initPermissions: true    # init container root che fa chown della radice del volume
  trashRetentionHours: 24  # 0 = il cestino non viene mai svuotato automaticamente
  minFreeGb: 2
```

Layout del volume: `<server>/` (dati di un server), `.trash/<server>-<timestamp>/` (server cancellati),
`.pending-deletion/` (cancellazioni in attesa che il pod si fermi).

Con un namespace con Pod Security `restricted` imposta `storage.initPermissions=false` e prepara i
permessi del volume in altro modo (l'init container gira come root).

## Dominio e DNS

Con `baseDomain: mc.example.com`:

| Record | Punta a |
|---|---|
| `*.mc.example.com` | IP esterno del Service `<release>-router` (TCP 25565) |
| `api.mc.example.com` | Ingress controller (oppure `ingress.api.host`) |
| `console.mc.example.com` | Ingress controller (console web, attiva di default; `ingress.console.host`) |

I record specifici di `api`/`console` hanno precedenza sul wildcard. Non creare server chiamati `api` o `console`.

## TLS

```yaml
ingress:
  className: nginx
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-prod   # cert-manager crea i certificati
  tls:
    enabled: true
    secretName: minecraft-controller-tls              # oppure un certificato wildcard esistente
  api:
    tlsSecretName: ""                                 # certificato dedicato all'API (opzionale)
```

Le annotation di default `ingress.api.annotations` configurano ingress-nginx per gli stream SSE
(buffering disattivato, timeout lunghi) e gli upload fino a 1 GB; con traefik non servono.

## Sicurezza

- RBAC solo con `Role`/`RoleBinding` nel namespace (nessun `ClusterRole`). Controller: StatefulSet, Service,
  `pods` (get/delete), `pods/exec`, metriche. Router: lettura dei Service (+ scale con `router.autoScale`).
  Con `rbac.create=false` crea Role e binding equivalenti a mano.
- Controller: non root, `readOnlyRootFilesystem`, nessuna capability, seccomp `RuntimeDefault`,
  `/tmp` su `emptyDir` (`controller.tmpSizeLimit`).
- Router: non root (UID 65532), root filesystem read-only.
- Server Minecraft: niente token del service account, `allowPrivilegeEscalation: false`, seccomp;
  con `servers.runAsNonRoot=true` girano come `storage.uid` senza capability (verifica la compatibilità
  della tua immagine/modpack prima di attivarlo).
- `networkPolicy.enabled=true`: API raggiungibile solo dall'ingress controller, server Minecraft solo dal router.
- Health e metriche sono su una porta separata (`controller.managementPort`, default 8081) non esposta dall'Ingress.

## Health check e metriche

| Probe | Endpoint | Dipende da |
|---|---|---|
| startup / liveness | `/actuator/health/liveness` | solo il processo (un problema dell'API Kubernetes non causa riavvii) |
| readiness | `/actuator/health/readiness` | volume dati montato e scrivibile |

Metriche Prometheus: `http://<release>-controller:8081/actuator/prometheus`
(`controller.metrics.scrapeAnnotations=true` aggiunge le annotation `prometheus.io/*`). Principali:
`minecraft_controller_servers{state}`, `minecraft_controller_operations_seconds{operation,outcome}`,
`minecraft_controller_kubernetes_errors_total{code}`, `minecraft_controller_sse_*`,
`minecraft_controller_pending_deletions`.

## Backup

Il PVC contiene i mondi: fai backup regolari. Strategie, dalla più consigliata:

1. **Snapshot CSI** (`VolumeSnapshot`), se la StorageClass li supporta (Longhorn, Ceph, molti driver cloud):
   istantanei e coerenti a livello di blocco. Con Longhorn si possono pianificare dalla sua UI.
2. **Velero** (con snapshot o file-system backup) per backup off-cluster e ripristino dell'intero namespace.
3. **CronJob del chart** (`backup.enabled=true`): archivio `tar.gz` del volume su un secondo PVC, con
   verifica (`gzip -t`, `sha256`) e retention (`backup.retentionDays`). Il volume dati è montato in sola lettura.
   I file sono copiati mentre i server girano: per una copia coerente esegui `save-off`/`save-all` dalla
   console prima, o ferma i server.

Ripristino selettivo di un server da un archivio del CronJob:

```bash
# server fermo; il pod del backup monta /backup
tar -xzf /backup/minecraft-20260101T040000Z.tar.gz -C /data ./survival
```

La cartella `.trash` è esclusa dal backup di default (`backup.includeTrash`) ed è una rete di sicurezza
temporanea, non un backup.

## Frontend

La console (Angular SSR su Node, cartella `ui/`) è installata di default su `console.<baseDomain>`,
con l'immagine `ghcr.io/lorisdemicheli/minecraft-console` che la CI pubblica insieme a quella del controller.
Per installare solo l'API: `--set frontend.enabled=false`.

L'immagine non contiene l'indirizzo dell'API: il chart lo passa all'avvio nelle variabili d'ambiente del pod.

| Variabile | Valore impostato dal chart | Per cambiarlo |
|---|---|---|
| `API_BASE_URL` | `http(s)://api.<baseDomain>` (https con `ingress.tls.enabled`) | `frontend.apiBaseUrl` |
| `NG_ALLOWED_HOSTS` | host dell'Ingress della console e `localhost` | `frontend.allowedHosts` (host extra) |

Il rendering lato server risponde `400` a ogni altro `Host` (protezione di Angular dagli header falsificati).
Il controller accetta richieste del browser solo dall'origine della console (`controller.corsAllowedOrigins`
vuoto = URL della console): se usi un host diverso da `console.<baseDomain>` imposta `ingress.console.host`.

Senza Ingress, per provarla: `kubectl port-forward svc/<release>-minecraft-controller-console 4000:80 -n minecraft`,
ma l'API deve essere raggiungibile dal browser all'indirizzo di `API_BASE_URL`.

Build locale dell'immagine: `docker build -t minecraft-console:dev ui/`.

## Parametri

| Parametro | Default | Descrizione |
|---|---|---|
| `baseDomain` | `""` | **Obbligatorio.** Dominio della piattaforma |
| `imagePullSecrets` | `[]` | Pull secret per immagini private |
| `storage.size` | `50Gi` | Dimensione del volume condiviso |
| `storage.className` | `""` | StorageClass (vuoto = default del cluster) |
| `storage.accessMode` | `ReadWriteMany` | `ReadWriteMany`, `ReadWriteOnce`, `ReadWriteOncePod` |
| `storage.existingClaim` | `""` | PVC esistente da usare |
| `storage.annotations` | `{}` | Annotation del PVC creato |
| `storage.uid` / `storage.gid` | `1000` | Proprietario dei file |
| `storage.initPermissions` | `true` | chown della radice del volume all'avvio |
| `storage.initImage` | `busybox:1.37` | Immagine dell'init container (e di `helm test`) |
| `storage.trashRetentionHours` | `24` | Permanenza nel cestino (0 = mai svuotato) |
| `storage.minFreeGb` | `2` | Spazio minimo per creare server e caricare file |
| `auth.username` | `admin` | Utente admin |
| `auth.existingSecret` | `""` | Secret con `password`, `jwt-secret`, `curseforge-api-key` |
| `auth.password` | `admin` | Password dell'admin (vuoto = casuale, generata una volta). Cambiala |
| `auth.jwtSecret` | `""` | Chiave JWT (vuoto = generata) |
| `auth.jwtExpirationMs` | `86400000` | Durata dei token (1 min – 30 giorni) |
| `auth.curseForgeApiKey` | `""` | Chiave CurseForge (senza existingSecret) |
| `auth.loginMaxAttempts` / `auth.loginWindow` | `10` / `5m` | Limite di login falliti per client |
| `rbac.create` | `true` | Crea Role e RoleBinding |
| `serviceAccount.create` | `true` | Crea i ServiceAccount |
| `serviceAccount.controllerName` / `routerName` | `""` | Nomi dei ServiceAccount |
| `serviceAccount.annotations` | `{}` | Annotation dei ServiceAccount |
| `controller.image.repository` | `ghcr.io/lorisdemicheli/minecraft-controller` | Immagine |
| `controller.image.tag` | `main` | Tag (`sha-<commit>` per installazioni riproducibili) |
| `controller.image.pullPolicy` | `Always` | |
| `controller.replicas` | `1` | Deve restare 1 |
| `controller.port` / `managementPort` | `8080` / `8081` | API / health e metriche |
| `controller.resources` | 100m, 384Mi–768Mi | Risorse del controller |
| `controller.javaOpts` | `-XX:MaxRAMPercentage=75 …` | Opzioni JVM |
| `controller.logFormat` | `text` | `text` o `json` (ECS) |
| `controller.maxUploadSize` | `1GB` | File più grande caricabile |
| `controller.tmpSizeLimit` | `2Gi` | emptyDir `/tmp` (buffer degli upload) |
| `controller.corsAllowedOrigins` | `[]` | Origini CORS (vuoto = URL della console) |
| `controller.metrics.scrapeAnnotations` | `false` | Annotation Prometheus |
| `controller.extraEnv` | `[]` | Variabili aggiuntive (es. altre proprietà `it.lorisdemicheli.minecraft-servers.*`) |
| `controller.podAnnotations` / `podLabels` | `{}` | |
| `controller.podSecurityContext` / `securityContext` | `{}` | Sovrascrivono i default restrittivi |
| `controller.nodeSelector` / `tolerations` / `affinity` / `topologySpreadConstraints` / `priorityClassName` | vuoti | Scheduling |
| `servers.image` | `itzg/minecraft-server` | Immagine dei server Minecraft |
| `servers.terminationGracePeriodSeconds` | `120` | Tempo per salvare il mondo allo stop |
| `servers.resources.cpuRequestPercent` | `100` | Request CPU in % della CPU scelta |
| `servers.resources.cpuLimitEnabled` | `true` | Limite CPU |
| `servers.resources.memoryRequestPercent` | `100` | Request memoria in % del limite |
| `servers.resources.memoryOverheadPercent` / `memoryOverheadMinMb` | `15` / `256` | Memoria oltre l'heap |
| `servers.runAsNonRoot` | `false` | Server senza root e senza capability |
| `frontend.enabled` | `true` | Console web (`false` = solo API) |
| `frontend.image.repository` / `tag` / `pullPolicy` | `ghcr.io/lorisdemicheli/minecraft-console` / `main` / `Always` | Usa `sha-<commit>` per installazioni riproducibili |
| `frontend.apiBaseUrl` | `""` | URL pubblico dell'API per il browser (vuoto = Ingress dell'API) |
| `frontend.allowedHosts` | `[]` | Host extra serviti dal rendering lato server |
| `frontend.replicas`, `port`, `resources`, `extraEnv`, scheduling | | |
| `router.image.repository` / `tag` | `itzg/mc-router` / `latest` | |
| `router.replicas` | `1` | Ignorato con l'HPA |
| `router.autoScale.up` / `down` | `false` | Avvio/arresto automatico dei server |
| `router.autoscaling.*` | disabilitato | HorizontalPodAutoscaler |
| `router.podDisruptionBudget.*` | disabilitato | PDB (utile con più repliche) |
| `router.service.type` / `port` / `nodePort` / `externalTrafficPolicy` / `annotations` | `LoadBalancer` / `25565` | |
| `router.resources`, `podSecurityContext`, `securityContext`, scheduling | | |
| `ingress.enabled` / `className` / `annotations` | `true` / `traefik` / `{}` | |
| `ingress.tls.enabled` / `secretName` | `false` / `""` | |
| `ingress.api.host` / `annotations` / `tlsSecretName` | `api.<baseDomain>` | Ingress dell'API |
| `ingress.console.host` / `annotations` / `tlsSecretName` | `console.<baseDomain>` | Ingress della console |
| `networkPolicy.enabled` | `false` | NetworkPolicy |
| `networkPolicy.ingressControllerNamespace` | `kube-system` | Namespace dell'ingress controller |
| `networkPolicy.monitoringNamespaces` | `[]` | Namespace che possono leggere health/metriche |
| `backup.enabled` / `schedule` / `retentionDays` / `includeTrash` | `false` / `0 4 * * *` / `7` / `false` | CronJob di backup |
| `backup.image` | `alpine:3.20` | |
| `backup.storage.*` | 100Gi RWO | PVC dei backup (o `existingClaim`) |

Altre proprietà del backend (timeout Kubernetes, retry, intervalli SSE…) si impostano con
`controller.extraEnv`, ad esempio:

```yaml
controller:
  extraEnv:
    - name: IT_LORISDEMICHELI_MINECRAFTSERVERS_KUBERNETES_EXECTIMEOUT
      value: 30s
```

L'elenco completo è in [`docs/architecture.md`](../../docs/architecture.md#configurazione).

## Validazione del chart

```bash
helm lint charts/minecraft-controller -f charts/minecraft-controller/ci/default-values.yaml
for f in charts/minecraft-controller/ci/*.yaml; do
  helm template t charts/minecraft-controller -f "$f" > /dev/null || echo "FAIL $f"
done
```

La CI esegue anche kubeconform sui manifest generati.

## Rilascio del chart

- `version` in `Chart.yaml` segue semver e va incrementata a mano quando cambiano template o values
  (non a ogni build); `appVersion` è la versione dell'applicazione (`backend/pom.xml`).
- La CI produce il pacchetto `minecraft-controller-<version>.tgz` come artifact di ogni run.
- Su `main` la CI lo **pubblica** come pacchetto OCI su GHCR, **una volta per ogni `version`**: se quella
  versione esiste già non la sovrascrive (i tag GHCR sono mutabili). Per rilasciare modifiche al chart
  incrementa `version` in `Chart.yaml`.

```bash
# installazione dal registry (senza clonare il repository)
helm upgrade --install minecraft-controller oci://ghcr.io/OWNER/charts/minecraft-controller --version 0.5.0 -n minecraft -f my-values.yaml
helm show values oci://ghcr.io/OWNER/charts/minecraft-controller --version 0.5.0   # parametri
```

Il package `charts/minecraft-controller` nasce **privato**: alla prima pubblicazione impostane la visibilità
(o collegalo al repository) da GitHub → Packages → Package settings. Se resta privato serve
`helm registry login ghcr.io` con un token `read:packages`.

Pubblicazione manuale (con un token `write:packages`), se serve:

```bash
helm package charts/minecraft-controller
echo "$GHCR_TOKEN" | helm registry login ghcr.io -u OWNER --password-stdin
helm push minecraft-controller-0.5.0.tgz oci://ghcr.io/OWNER/charts
```

## Troubleshooting

| Sintomo | Causa probabile |
|---|---|
| `Error: ... 'baseDomain' is required` | imposta `baseDomain` |
| Pod in `ContainerCreating` con `Multi-Attach error` | volume ReadWriteOnce usato da pod su nodi diversi: usa ReadWriteMany |
| Controller in `CrashLoopBackOff`, log `Invalid configuration` | il messaggio elenca le proprietà errate (es. `jwt-secret` troppo corta) |
| `ImagePullBackOff` sul controller | package GHCR privato: crea un pull secret (`imagePullSecrets`) |
| API 401 dopo un cambio password | atteso: i vecchi token sono revocati, rifai il login |
| API 503 | l'API Kubernetes non risponde: il controller ritenta da solo, riprova |
| Cancellazione di un server con risposta 202 | i file andranno nel cestino quando il pod si sarà fermato (fino ad allora il nome resta riservato) |
| Console senza CPU/RAM | metrics-server non installato |
| Stream di log/console che si interrompono dietro nginx | verifica le annotation SSE in `ingress.api.annotations` |

```bash
kubectl -n minecraft logs deploy/minecraft-controller-controller
kubectl -n minecraft get events --sort-by=.lastTimestamp
kubectl -n minecraft port-forward svc/minecraft-controller-controller 8081:8081   # /actuator/health
```
