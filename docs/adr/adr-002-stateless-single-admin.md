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

- **Nessuna invalidazione token**: un token valido rimane tale per tutta la sua durata (default 24h). In caso di compromissione la soluzione è cambiare `jwtSecret` (invalida tutti i token esistenti) e fare `helm upgrade`.
- **Nessun multi-utente**: impossibile dare accesso in sola lettura a terzi senza condividere le credenziali admin.
- **Nessun refresh token**: alla scadenza il client deve ri-autenticarsi.

## Alternative Scartate

- **OAuth2 / OIDC**: appropriato per sistemi multi-utente, aggiunge dipendenza da identity provider esterno (Keycloak, Auth0, ecc.) — over-engineering per l'uso previsto.
- **Spring Security con DB utenti**: richiede un database, migrazioni, gestione password utenti. Non giustificato per un solo utente.
- **Basic Auth**: non stateless, non adatto a SPA o CLI che fanno molte richieste.
