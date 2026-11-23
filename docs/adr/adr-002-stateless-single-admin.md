# ADR-002: Autenticazione Stateless con Singolo Account Admin

**Stato**: Accettato  
**Data**: 2025

## Contesto

Il controller gestisce un'infrastruttura personale/familiare di server Minecraft. Bisognava scegliere un modello di autenticazione adeguato allo scopo.

## Decisione

- **Singolo account admin** configurato tramite `application.properties` (o Helm Secret)
- **JWT stateless** (HS256), senza sessioni server-side
- **Nessun database utenti**

Classi: `CustomUserDetailsService`, `JwtComponent`, `JwtAuthFilter`, `SecurityConfig`.

## Motivazioni

- **Complessità appropriata al dominio**: un'infrastruttura personale non necessita di multi-utente, ruoli o audit log.
- **Zero stato server**: con JWT stateless, qualsiasi istanza del controller (es. dopo un rollout restart) può validare i token emessi senza sincronizzazione.
- **Semplicità operativa**: le credenziali vivono nel Secret Kubernetes come gli altri parametri di configurazione. Nessuna migrazione DB, nessun reset password via DB.
- **Sicurezza sufficiente**: la chiave JWT (`jwtSecret`, min 32 byte) garantisce che i token non possano essere forgiati senza conoscere il secret. La password è BCrypt-encoded all'avvio.

## Trade-off e Limitazioni

- **Invalidazione solo per rotazione**: non esiste una revoca del singolo token. Un token resta valido fino alla scadenza (default 24h) a meno che cambino le credenziali (vedi *Aggiornamento 2026*). In caso di compromissione: cambiare password o `jwtSecret` nel Secret e riavviare il controller.
- **Nessun multi-utente**: impossibile dare accesso in sola lettura a terzi senza condividere le credenziali admin.
- **Nessun refresh token**: alla scadenza il client deve ri-autenticarsi.

## Alternative Scartate

- **OAuth2 / OIDC**: appropriato per sistemi multi-utente, aggiunge dipendenza da identity provider esterno (Keycloak, Auth0, ecc.) — over-engineering per l'uso previsto.
- **Spring Security con DB utenti**: richiede un database, migrazioni, gestione password utenti. Non giustificato per un solo utente.
- **Basic Auth**: non stateless, non adatto a SPA o CLI che fanno molte richieste.

## Aggiornamento 2026 — revoca dopo rotazione delle credenziali

**Problema**: cambiare la password dell'admin non invalidava i token già emessi; serviva cambiare anche il `jwtSecret`.

**Decisione**: ogni token contiene il claim `cv` = HMAC-SHA256(jwtSecret, username + password), troncato. La validazione confronta `cv` con il valore calcolato dalle credenziali correnti: dopo un cambio di password o username (e il riavvio che rilegge il Secret) tutti i token precedenti vengono rifiutati con 401 "Credentials changed". Il claim non permette di ricavare password né secret.

Restano invariati: nessun database, nessuna lista di revoca, modello single-admin. Sono stati aggiunti anche issuer obbligatorio, verifica dell'algoritmo HS256, clock skew di 30 secondi e un limite ai login falliti per client (429).

**Alternative scartate**: denylist dei `jti` (richiede stato condiviso o persistente), versione delle credenziali in una ConfigMap (stato da mantenere a mano), token a vita brevissima con refresh token (complessità non giustificata per un solo utente).
