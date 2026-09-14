# diaries-responder

`diaries-responder` is the Java responder/server for the Diaries application.

It listens for MQTT RPC requests from `diaries-client`, validates and executes those requests, stores durable state in PostgreSQL, publishes retained MQTT topic-tree updates, and serves static diary/image files.

This project is one part of the wider Diaries system:

```text id="d49vvh"
diaries/
  diaries-client/       Angular browser client
  diaries-responder/    Java MQTT responder/server
```

For the system-wide design, see the top-level `ARCHITECTURE.md` in the parent `diaries` repository.

## Responsibilities

The responder is the authoritative server-side component.

It is responsible for:

* connecting to the MQTT broker
* handling MQTT RPC requests from the client
* authenticating and authorising requests
* managing access and refresh tokens
* enforcing locking rules
* creating, updating, deleting, locking, and unlocking fragments and marquees
* updating diary and page metadata
* uploading, listing, and deleting files
* storing persistent state in PostgreSQL using JPA/Hibernate
* publishing retained MQTT topic-tree objects
* synchronising retained MQTT state with the database on startup
* releasing stale fragment locks
* serving static diary and uploaded file content

The client may prevent invalid actions for usability, but the responder must enforce correctness.

## Technology

The responder uses:

* Java
* Gradle
* Gradle application plugin
* Shadow JAR plugin
* Eclipse project support
* PostgreSQL JDBC driver
* JPA/Hibernate
* Eclipse Paho MQTT v5 client
* MQTT RPC responder libraries
* Jackson
* JJWT
* SLF4J / Log4j2
* JUnit Jupiter

## Prerequisites

Install or provide:

* Java matching the Gradle toolchain requirement
* Gradle wrapper from the parent/top-level project or a compatible Gradle installation
* PostgreSQL
* MQTT broker, for example Mosquitto
* a responder configuration JSON file
* filesystem directories for diary pages and uploaded files

## Build

From the responder project directory:

```bash id="7u5l4x"
../gradlew build
```

Or, from the top-level `diaries` repository:

```bash id="gmxjkt"
./gradlew :diaries-responder:build
```

On Windows:

```bat id="ltvq6z"
gradlew.bat :diaries-responder:build
```

The normal build also creates a Shadow/fat JAR.

### Local Docker image

The responder owns the multi-stage `Dockerfile` used by the
`local-docker-build` mode. Because the Gradle wrapper and shared build files
belong to the parent project, build it from the top-level `diaries` directory:

```bash
docker build -f diaries-responder/Dockerfile -t diaries-responder:local .
```

`compose.local-docker-build.yaml` uses the same Dockerfile and parent build
context. The production pipeline continues to use its runtime-only image recipe
for packaging an already published responder artifact.

## Useful Gradle commands

```bash id="ifm5rs"
../gradlew clean
../gradlew build
../gradlew test
../gradlew shadowJar
../gradlew installDist
```

The `installDist` task creates the application launcher and its runtime libraries
under `build/install/diaries-responder/`.

## Run

The responder requires a configuration file.

A typical command is:

```bash id="2c3kcn"
java -jar build/libs/diaries-responder-fat.jar --config config.json
```

Or, when running through Gradle:

```bash id="uank1l"
../gradlew run --args="--config config.json"
```

The responder expects the `--config` / `-c` argument to identify the configuration JSON file.

## Configuration

Configuration is read from a JSON file.

The main configuration sections are:

```text id="ha5vtv"
mqtt
db
diaries
refreshPeriod
refreshExpiration
secret
normaliseOnStartup
fragmentLockTtlSeconds
```

### MQTT configuration

The MQTT configuration identifies the broker and MQTT user:

```json id="qoo37i"
{
  "mqtt": {
    "host": "localhost",
    "port": 1883,
    "user": {
      "username": "responder",
      "password": "password"
    }
  }
}
```

The responder connects to the broker using a TCP MQTT URL constructed from the host and port.

### Database configuration

The database configuration identifies the PostgreSQL server, database, admin user, application users, and JDBC settings.

Conceptually:

```json id="7zy6mo"
{
  "db": {
    "host": "localhost",
    "port": 5432,
    "database": "diaries",
    "jdbc": {
      "dbms": "postgresql"
    },
    "admin": {
      "username": "postgres",
      "password": "password"
    },
    "users": []
  }
}
```

Do not commit real passwords or production secrets to git.

### Diaries/static file configuration

The `diaries` section identifies the root filesystem location and the child directories used for diary page images and uploaded files.

Conceptually:

```json id="iptj28"
{
  "diaries": {
    "root": "/path/to/diaries/root",
    "baseUrl": "http://localhost:8081",
    "diaries": "diaries",
    "files": "files"
  }
}
```

At startup, the responder creates the configured directories if needed.

The built-in static file server listens on port `8081` and serves:

```text id="7szl96"
/diaries    diary/page image files
/files      uploaded files
```

## MQTT RPC handlers

The responder registers handlers for operations such as:

```text id="i86z9v"
health
register
signin
refreshToken
normaliseDiaries
normalisePages
normaliseFragments
updatePage
updateDiary
addFragment
addMarquee
updateMarquee
updateFragment
lockFragment
unlockFragment
deleteFragment
deleteMarquee
uploadFile
listFiles
deleteFile
quit
```

The client sends MQTT RPC messages to request these operations. The responder validates the request, performs any required database work, publishes retained state where appropriate, and replies to the client.

The `health` operation is reserved for responder readiness checks. It does not require a Diaries access token, but Mosquitto authentication and the narrowly scoped `diaries-health` ACL protect its transport. A successful response contains only `{"status":"UP"}` and is returned only after a live `SELECT 1` probe succeeds using a separate short-lived `EntityManager`.

## Responder readiness command

The responder fat JAR contains a dedicated MQTT RPC health-check command:

```text
java -cp diaries-responder.jar com.rsmaxwell.diaries.responder.health.ResponderHealthCheck --config /config/responder.json
```

The command reads the MQTT broker host and port from the responder configuration and authenticates its short-lived requestor client with `DIARIES_MQTT_HEALTH_USERNAME` and `DIARIES_MQTT_HEALTH_PASSWORD`. It uses a unique MQTT client ID, subscribes to `diaries/rpc/<client-id>/response`, and exits 0 only after a correlated successful `health` response with an `UP` payload. The request/response transaction has an internal four-second deadline. Compose sets `loglevel=ERROR` only for the health-check subprocess so successful polling remains quiet while command failures still produce one concise standard-error line.

Docker responder health checks use this command rather than the static HTTP server. This proves that the broker, normal long-lived responder listener and publisher, and database are usable together.

## Retained topic-tree model

The responder is responsible for publishing the retained MQTT state observed by the client.

The intended model is:

```text id="xdy5z3"
Client sends RPC request
  -> responder validates request
  -> responder starts database transaction
  -> responder updates database
  -> responder commits transaction
  -> responder publishes retained MQTT state
  -> responder sends RPC reply
```

Retained MQTT messages should reflect committed database state.

The client should use retained topic updates as the live model of the application rather than relying only on RPC replies.

## Locking

The responder enforces fragment locking.

Typical rules are:

* a fragment may be edited only by a caller that owns the lock
* lock ownership should be checked on update/delete operations
* stale locks may be released on responder startup
* unlock operations should be safe and idempotent where practical
* retained fragment/marquee state should be published after lock state changes

Locking behaviour must remain consistent with the Angular client.

## Startup behaviour

On startup, the responder:

1. reads the configuration file
2. starts the static file server
3. opens the database connection
4. creates repository instances
5. builds the `DiaryContext`
6. synchronises retained MQTT state with the database
7. connects MQTT publisher/listener clients
8. releases stale locks
9. waits for MQTT RPC requests

If `normaliseOnStartup` is enabled, startup may also normalise database/topic-tree state.

The 0024 Image model is registered with the EntityManager factory and its
repository is installed in `DiaryContext` during startup. Apply the explicit
0024 Phase 2 schema migration before running with Hibernate schema validation;
keep Hibernate DDL configuration at `validate` or `none`, not automatic schema
creation/update. The migration runbook is in the parent repository's
`change-control/in-progress/0024-FEAT - introduce reusable persistent Image catalogue/migration/README.md`.

`DiaryContext.inflateImage` loads independent Image metadata. `saveImage`
returns a committed copy without changing the caller's candidate; `updateImage`
persists the caller-supplied version and returns the affected-row count. These
helpers own their transactions and reject an already-active transaction.
Callers managing a wider transaction use the Image repository directly. The
context and its EntityManager retain their existing single-thread usage model.
Startup replay includes every Image row, even with no Fragment references, at
`diaries/images/{id}`. Its ten-field payload is metadata only: id, version,
relativePath, mimeType, originalFilename, width, height, checksum, caption and
altText. It contains no file bytes or resolved URL/absolute storage path.
The synchroniser waits for retained replay before comparing with database
state, publishes differences in topic order with QoS 1 and retain, and removes
stale topics using empty retained payloads. Unchanged topics are not republished.
Its temporary snapshot subscriber uses one `diaries/#` subscription at QoS 0
to avoid Mosquitto's finite queue for QoS 1/2 replay. A unique, non-retained
`diaries/diaries/_sync/{run}/{checkpoint}` marker confirms that the stream has
drained; missing markers and disconnected snapshots fail startup. These markers
use the existing responder ACL and are excluded from the snapshot. Canonical
object publications and ordinary subscribers keep their existing QoS contract.
The shared local broker ACL grants only the responder read/write access to
`diaries/images/+`; reload that ACL before starting the updated responder.
Production ACL deployment remains part of 0024 Phase 11. Client and web do not
subscribe to the Image catalogue yet. Upload orchestration is implemented in
Phase 6; the registration helpers themselves do not publish or touch files.

0024 Phase 5 adds shared services for the subsequent handler integration:

* `ImagePathPolicy` normalizes NFC and separators, preserves case, rejects
  traversal/absolute/URI/non-portable names and existing case or Unicode aliases,
  and rejects descendant symlinks/reparse points before filesystem operations.
* `ImageMetadataInspector` decodes JPEG, PNG, GIF and WebP from their bytes,
  including animated GIF/WebP. WebP uses TwelveMonkeys ImageIO 3.14.0. The defaults
  are 20 MiB encoded bytes, 40 million decoded pixels across frames and 256
  frames. Bad containers, decoder warnings, invalid dimensions, MIME mismatch
  and checksum mismatch fail inspection. Extensions do not cause renaming.
* `ResolvedUpload` carries immutable paths and inspected metadata from staging.
  `ImageCatalogueService` hashes while staging, checks database ownership,
  promotes with atomic no-replace hard-link creation, inserts with a fresh
  EntityManager, commits and then invokes its publication callback. Generic
  octet-stream content creates no Image row. The callback must acknowledge MQTT
  delivery or throw so the caller can report a publication failure.
* Definitive insert rollback removes the promoted file and restores an
  uncatalogued overwrite backup. An uncertain commit or failed compensation
  preserves recovery files and throws `RecoveryRequiredException`. A publication
  failure preserves the committed row/file and supplies the DTO for replay.

The Files root must be writable only by trusted server processes. Staging is
reserved under `.image-staging`, with owner-only POSIX permissions (Windows
inherits the server account's directory ACL). File operations use a process
critical section and a shared filesystem lock. The filesystem must support
hard links, atomic moves and reliable locks; unsupported operations fail closed.
Verify these properties on the deployment mount before activation. Arbitrary
external writers must not mutate the root or staging area during operations.
Phase 6.1 wires UploadFile to shared staging: bounded basic-base64 decoding,
one streaming SHA-256 calculation, size/checksum/content validation and cleanup
before promotion. The five existing response fields remain
`name`, `subdir`, `size`, `path`, `url`; normalized subdirectories use `/`.
UploadFile no longer logs names, uploaded bytes or the configured storage path.
Phase 6.3 enables full catalogue completion. A supported image commits one
Image row, then publishes its metadata at `diaries/images/{id}` with QoS 1 and
retain. UploadFile waits up to ten seconds for acknowledgement and rejects
broker failure reason codes. The response adds `imageId` and the ten-field
`image` DTO. Generic non-image octet-stream uploads have explicit null values
for both fields and create no row or topic. Only the outer compatibility
response contains an absolute path; it never enters Image persistence/replay.
Publication failure returns an internal error identifying the committed Image
and replay requirement. It preserves the file and row; retry conflicts instead
of duplicating them. Definitive insert failure compensates the file change.
Unknown commit outcomes retain recovery files and require administrator review.

Phase 6.2 checks catalogue ownership using the PostgreSQL path
identity before staging, then repeats that check under the shared filesystem
lock before promotion. Catalogued paths (including NFC/case/separator aliases
and missing backing files) reject both overwrite modes with conflict status.
An unavailable catalogue fails closed. Uncatalogued files retain ordinary
overwrite behaviour, through atomic no-replace promotion and backup restoration
in the shared service. UploadFile uses its full completion mode so the backup
survives until Image creation commits.

Phase 7 makes generic DeleteFile catalogue-aware. It queries the repository's
exact-or-descendant guard before resolving filesystem aliases or returning
not-found success, then repeats the guard under the same filesystem lock as
upload promotion/commit. Catalogued files and directory prefixes remain
protected even if backing files/directories are missing. SQL `%`, `_` and `!`
characters are literal in the prefix lookup; only slash-delimited descendants
count. Conflicts return status 409 and the normalized relative path, without
an absolute host path. An unavailable catalogue fails closed.

Uncatalogued missing paths remain idempotent and create no directories.
Uncatalogued regular files and empty directories can be deleted; deletion is
non-recursive, and nonempty directories return conflict. Successful replies
retain `name`, `subdir` and the legacy absolute `path`, with `/` separators in
the subdirectory. Generic deletion never writes Image rows or publishes
tombstones. Catalogue removal remains administrator-controlled; there is no
public DeleteImage RPC. Phase 8 provides the administrative reconciliation command described below; deployment remains a later phase.

ListFiles hides reserved staging entries, and ListFiles/DeleteFile and both
responder HTTP static routes reject reserved paths and symlink aliases through
the shared path policy. Before staging, UploadFile probes hard links, atomic
moves and locking using disposable private files on its configured mount;
failures reject the upload. Existing POSIX staging must have mode 0700.
An operator must still verify that root ownership/Windows ACLs allow only
trusted writers and that any external static server also blocks staging before
deployment. The source workspace contains no separately managed production
nginx/Ansible configuration to update. No live NAS probe was performed.

Phase 5 tests cover path attacks, content detection, concurrent uploads,
rollback/backup restoration, uncertain commits and post-commit publication.
`src/test/resources/image-inspection/PackagedInspectionProbe.java` additionally
checks decoder discovery and filesystem operations against the fat JAR on
Windows and Linux. Evidence is recorded in the parent change-control package
`evidence/phase-05-services`.

## Static file server

The responder includes a simple static file server.

It serves only `GET` and `HEAD` requests, rejects path traversal, returns `404` for missing files, and uses detected content types where possible.

The static file server is intended for diary page images and uploaded file content. Large binary data should not be sent through MQTT messages.

## Logging

Logging uses SLF4J with Log4j2.

When debugging, consider the responder log together with:

* browser console log
* MQTT RPC request/reply messages
* retained MQTT topic state
* PostgreSQL rows
* static file URLs

Many bugs involve both client and responder behaviour, especially around locking, deletion, retained publications, and selected client state.

Avoid logging secrets such as database passwords, MQTT passwords, JWT secrets, or tokens.

## Development notes

When changing responder behaviour, check that the client remains consistent.

Pay particular attention to:

* RPC operation names
* request argument names
* reply payload shapes
* retained topic names
* DTO JSON shapes
* transaction boundaries
* lock ownership checks
* idempotent delete/unlock behaviour
* publication after commit
* static file URL conventions

## Project structure

Typical source layout:

```text id="d3wks2"
src/main/java/com/rsmaxwell/diaries/responder/
  Responder.java
  config/
  dto/
  handlers/
  model/
  repository/
  repositoryImpl/
  sync/
  utilities/
```

Typical responsibilities:

| Area             | Responsibility                                           |
| ---------------- | -------------------------------------------------------- |
| `Responder.java` | main entry point, MQTT setup, static file server startup |
| `config`         | JSON configuration model and time parsing                |
| `handlers`       | MQTT RPC operation handlers                              |
| `model`          | domain model objects                                     |
| `dto`            | database/API transfer objects                            |
| `repository`     | repository interfaces                                    |
| `repositoryImpl` | JPA/Hibernate repository implementations                 |
| `sync`           | database/topic-tree synchronisation                      |
| `utilities`      | shared server-side helper code                           |

## Relationship to diaries-client

`diaries-responder` should remain consistent with `diaries-client`.

Both sides must agree on:

* MQTT broker configuration
* MQTT topic names
* RPC operation names
* request and reply JSON shapes
* retained object JSON shapes
* authentication and token rules
* lock/unlock behaviour
* fragment and marquee lifecycle rules
* file upload/list/delete semantics
* image and file URL conventions

The responder should remain the authoritative component for validation and persistence.

## Further documentation

See also:

```text id="bw8bqz"
../README.md
../ARCHITECTURE.md
../diaries-client/README.md
```


### Existing-file Image reconciliation (0024 Phase 8)

Run `:diaries-responder:migration0024ImageCatalogue` with `-PmigrationConfig`
and a new empty `-PmigrationOutput` directory. It defaults to dry-run and emits
a two-way file/database inventory, conflicts, a create plan and hashed evidence.
For apply, also supply `-PmigrationMode=apply` and
`-PmigrationPlan=<reviewed-dry-run/0024-create-plan.json>`. Optional
`-Pmigration0022Candidates` cross-references frozen legacy candidates without
editing Fragment content or deciding conversion.

Apply checks reviewed input identities and fingerprints, locks normal catalogue
file operations and Image writes, and inserts safe missing rows in one database
transaction. It never repairs images or overwrites existing metadata. Stop
external writers during reconciliation. Normal responder startup subsequently
replays committed Image topics. Use a separate output directory for every run.

The actual-copy proof and operational details, including excluded legacy images
and retry/failure handling, are recorded in
[0024 Phase 8 evidence](../change-control/in-progress/0024-FEAT%20-%20introduce%20reusable%20persistent%20Image%20catalogue/evidence/phase-08-reconciliation/README.md).

### Image catalogue automated validation (0024 Phase 9)

From `diaries`, run in PowerShell 7 with Docker, the Java/Node build prerequisites,
installed client dependencies and Chrome/Edge available:

```powershell
.\scripts\windows\validation\test-image-catalogue.ps1 `
  -BackupFile .\data\database-backups\development-infrastructure\diaries-development-20260912-203528.dump `
  -EvidenceDirectory .\diaries-responder\build\image-validation-new-run
```

Use a new evidence directory for every run. The script creates disposable SQL,
JPA and MQTT fixtures, restores the supplied backup only into those fixtures,
enables database/broker integration tests, and runs responder/web tests and
builds plus Angular tests and its production build. It rejects failed or skipped
tests, checks diffs, stops its own containers, and hashes the resulting evidence.
No live configuration or Files root is accepted. See the
[Phase 9 evidence and coverage](../change-control/in-progress/0024-FEAT%20-%20introduce%20reusable%20persistent%20Image%20catalogue/evidence/phase-09-validation/README.md).
