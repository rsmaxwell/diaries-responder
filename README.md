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
imageFragmentWritesEnabled
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
`change-control/complete/0024-FEAT - introduce reusable persistent Image catalogue/migration/README.md`.

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

For development-infrastructure on Windows, use
`diaries-responder/scripts/windows/migration0024ImageCatalogue.bat` from the
top-level project. It uses `%USERPROFILE%\.diaries\responder.json`, matching
`diaries-responder/scripts/windows/run-responder.bat`, and accepts
`DIARIES_RESPONDER_CONFIG_FILE` as an explicit override. Paths are relative to
the caller's directory. The only required argument is the mode:

```bat
diaries-responder\scripts\windows\migration0024ImageCatalogue.bat dry-run
diaries-responder\scripts\windows\migration0024ImageCatalogue.bat apply
```

The script creates or uses `%USERPROFILE%\temp\dry-run` and
`%USERPROFILE%\temp\apply`. Dry-run writes its reviewed plan to
`%USERPROFILE%\temp\dry-run\0024-create-plan.json`; apply selects that file
automatically. The directory for the requested mode must be empty. Archive and
empty both evidence directories before starting another complete reconciliation.
An optional 0022 candidate CSV may follow the mode; when supplied for dry-run,
supply the same unchanged file for apply.

The three local modes use the same database data directory when `local.env`
sets `DIARIES_DB_DATA_DIR=./data/database/common`, and their responder
configurations address the same physical NAS Files tree. Run the local
reconciliation once through development-infrastructure. Do not apply it again in
local-docker-build or local-published-smoke. When either Docker mode starts, its
responder reads the shared Image rows and replays the catalogue into that mode's
own retained MQTT tree. The modes must not run their PostgreSQL containers
concurrently against the shared data directory.

A reconciliation plan is bound to its database identity and Files-root identity.
The development configuration sees the NAS through a Windows path, while the
Docker configurations see `/data/files`; consequently, a development plan must
not be applied from a Docker container.

For production, the Diaries playbook installs
`migration0024ImageCatalogue.sh` under the production project's `scripts`
directory. It accepts the same mode and optional candidate arguments as the
Windows script and writes evidence beneath `${HOME}/temp/dry-run` and
`${HOME}/temp/apply`; `DIARIES_MIGRATION0024_EVIDENCE_ROOT` can override that
root. The script runs the migration in a disposable container using the deployed
responder service's image, `/config/responder.json`, database network and Files
mounts. The database service must remain running. Apply refuses to run while the
normal responder service is running, preventing normal responder writes from
racing the reviewed migration. The live production run remains Phase 11 work.

The final candidate CSV is optional, but use the same file for dry-run and apply
when cross-referencing 0022. Review the dry-run plan and conflicts before apply.
Each script runs only the requested mode and does not restart the responder.
Arrange a writer-free maintenance window for apply; after it commits, normal
responder startup replays the retained Image topics.
Reconciliation is idempotent for unchanged inputs: a second dry-run plans zero
new rows, and a same-plan apply accepts matching rows without inserting them
again. A changed file, configuration or unrelated catalogue row requires a fresh
dry-run and review. Archive the prior evidence before emptying the fixed output
directories for another run.

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
[0024 Phase 8 evidence](../change-control/complete/0024-FEAT%20-%20introduce%20reusable%20persistent%20Image%20catalogue/evidence/phase-08-reconciliation/README.md).

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
[Phase 9 evidence and coverage](../change-control/complete/0024-FEAT%20-%20introduce%20reusable%20persistent%20Image%20catalogue/evidence/phase-09-validation/README.md).

## Image deletion service primitives (0030 Step 3)

`ImageCatalogueService.delete(relativePath, tombstone)` provides internal semantic
Image deletion. It shares upload/reconciliation locking, stages the existing
file privately, commits deletion through the existing repository, invokes the
supplied tombstone callback, and then removes the backup. It returns immutable
Image identity/path information. The callback must publish the real retained
Image tombstone and throw on failure; a no-op callback is only suitable for tests.

The JPA adapter locks and verifies the expected row before deletion. Definitive
rollback restores bytes without replacing an external file; unknown commits or
failed recovery/publication preserve the backup and expose a typed internal
recovery exception. These internal filesystem paths must not appear in RPC
errors. Step 5 registers the authenticated `deleteImage` handler; generic `DeleteFile`
continues to reject catalogue-owned paths. See the parent 0030 Step 3 evidence
for the initial validation. Step 4 adds deterministic staging, restoration and
cleanup failure tests and internal recovery diagnostics; its results are in
the parent 0030 `evidence/Step 4` directory.

Recovery errors log the Image ID, canonical path, database outcome and phase,
plus internal target/backup paths. Keep any `.delete-backup` file until an
administrator has stopped writers and checked the actual database row, file
identity and retained topic. `UNKNOWN` means the database outcome must be
established before restoring or deleting anything. `ROLLED_BACK` means the row
was retained (or no delete was attempted); a staging failure may leave either
the original target or the backup holding the bytes. `COMMITTED` means the row
was deleted: a publication failure needs retained-state reconciliation, while
a cleanup failure may leave redundant bytes after the tombstone was published.
Never overwrite an unexpected replacement file or blindly retry deletion as
recovery. Preserve the backup on uncertainty. These diagnostics are for operators,
not RPC responses; automatic recovery after process/power failure is not supplied.
## Eclipse and the packaged inspection probe

`src/test/resources/image-inspection/PackagedInspectionProbe.java` is standalone
source data used to check the packaged responder JAR. It deliberately has no
package declaration. The Gradle Eclipse model excludes that file from JDT
compilation while Gradle still copies it as a test resource. After importing or
changing this configuration, use **Gradle > Refresh Gradle Project**, then
**Project > Clean** if Eclipse retains an old package-mismatch marker.

### Image deletion RPC (0030 Step 5)

The registered `deleteImage` handler accepts a required basename `name` and an
optional `subdir` (default root), using the shared Image path policy. It requires
an active access token with EDITOR or stronger role. Success returns the committed
Image id, canonical relativePath and deleted=true. Invalid input returns 400,
authorization failure 401, no catalogue row 404, missing/nonregular bytes 409,
and deletion/recovery failure 500 without exposing internal recovery paths.

After durable deletion, `ImagePublishDTO.removeAndAwait` publishes an empty QoS 1
retained payload to `diaries/images/<id>` and waits up to ten seconds for broker
acknowledgement. Rejection, timeout or publication failure preserves the recovery
backup and returns 500. The generic DTO removal method remains unchanged for
existing callers. Client wrapper and UI integration are subsequent 0030 steps.
Validation and disposable database/broker runner: parent feature `evidence/Step 5`.

## 0025 Fragment Image persistence prerequisite

The Fragment repository now reads/writes nullable `fragment.image_id`. Before running
this responder against an existing database, apply the reviewed
[0025 Step 2 migration](../change-control/in-progress/0025-FEAT%20-%20add%20responder%20ImageFragment%20persistence%20and%20RPC/migration/README.md)
using its backup/preflight procedure. Restoring a pre-0025 backup also requires this
migration before startup; do not rely on Hibernate schema update to install the FK,
index and type constraint. The Step 3 integration runner restores the frozen backup
and applies both the 0024 Image schema and the 0025 migration to a disposable database.

Step 5 publishes `FragmentPublishDTO.imageId` on both `diaries/fragments/{id}` and
`diaries/dates/{year}/{month}/{day}/{id}`. It is always present: a numeric Image ID
when referenced, otherwise explicit JSON null. `marqueeId` remains for compatibility;
IMAGE fragments without a Marquee publish `marqueeId: null`. Database replay
reconstructs the same payload on both aliases. Retained QoS 1 and empty-payload
tombstones are unchanged.
Step 4 provides `ResolvedFragmentState`, `saveMarqueeFragment` and `saveImageFragment`.
Creation helpers own their transaction and return committed copies; Image attachment
uses a transaction-scoped `PESSIMISTIC_READ` lookup. Read-side resolution preserves
unresolved Image IDs for repair, while writer validation rejects invalid shapes.
The addImageFragment creation RPC is registered in Step 6. Image-aware update
semantics are implemented in Step 7; reference-aware Image deletion is implemented in Step 9. This intermediate implementation is not a production
ImageFragment authoring release.

### addImageFragment (0025 Step 6)

Requires an active EDITOR or stronger access token. Arguments: positive `pageId`,
`year`, `month`, `day`, `sequence`, string `text`, and optional positive `imageId`.
Omitting `imageId` or passing null creates an IMAGE Fragment with no Image reference.
Caller-supplied type/id/marquee fields cannot override the operation's IMAGE identity.
Dates must be calendar-valid, text is limited to 4096 characters, and sequence uses
the existing NUMERIC(10,4) format with no rounding or immediate renumbering.
This shares the existing Fragment chronology, not a separate Image sequence space.

Success returns the committed Fragment payload (200) and publishes both existing
retained Fragment aliases with `imageId` and `marqueeId: null`. No Marquee is created.
Authentication failures return 401; invalid arguments or unresolved Page/Image
references return 400; persistence/publication failures return 500. Image lookup and
PESSIMISTIC_READ locking occur inside the creation transaction. A synchronous
publication failure reports the committed Fragment ID: do not blindly retry creation,
as it is not idempotent. QoS 1 publication follows the existing asynchronous publisher
convention; the RPC does not wait for a broker acknowledgement.

This is intermediate 0025 source capability, not production authoring approval.
Complete the remaining 0025/0026 verification and deployment safeguards before
production IMAGE creation is enabled. This step changes no deployment configuration.

### updateFragment Image selection (0025 Step 7)

The existing edit RPC accepts optional `imageId`. For IMAGE fragments, omission
preserves the current reference, explicit null clears it, and a positive integer
attaches/replaces it with an existing Image. Missing Images or invalid IDs return 400.
MARQUEE (and legacy null-type) fragments accept absent/null imageId only. Supplied
`pageId` and `type` must match stored ownership; different values return 400.
Existing clients may continue omitting these fields.

The handler locks the Fragment row before reading/checking its version and editing
lock. Attachment/replacement uses the transaction-scoped Image lock. The caller must
own the Fragment lock and submit its current version (existing stale-version 400 and
wrong-owner 409 conventions remain). Success clears the edit lock. A text/Image-only
edit increments the version once without renumbering; date/sequence changes retain
the existing normaliser and its version bumps for renumbered rows. All changes commit
or roll back together. After commit, reloaded state is published; a date move removes
the previous alias. The response remains the Fragment ID, not a new edit protocol.

Full requests still include the existing date/sequence/text fields; this is not a
partial-update RPC for those fields. Client image-selection UI remains later work.

### Mixed Fragment lifecycle (0025 Step 8)

Lock/unlock operate on either Fragment type and publish the optional Image/Marquee
state. They lock only the Fragment row for editing; they do not acquire an edit lock
on the reusable Image. Sequence normalisation sorts all types together by date,
sequence and ID, preserving Image references and using the common state resolver
for publication.

`deleteFragment` resolves associations inside its transaction. A MARQUEE Fragment
loses its optional Marquee plus Fragment, with both sets of retained aliases removed.
An IMAGE Fragment loses only its Fragment and aliases: the Image row, file and topic
remain even after its last reference is removed. An invalid IMAGE+Marquee pair
returns conflict; remove its Marquee with `deleteMarquee` first.

`addMarquee` and `updateMarquee` require a MARQUEE-compatible Fragment without an
Image reference. Legacy null type remains compatible; AddMarquee retains its
existing migration behaviour of assigning MARQUEE ownership. `deleteMarquee` is an
explicit repair path: with the existing caller-owned Fragment lock, it can remove
an invalid Marquee, clear the edit lock and publish the remaining Fragment without
changing its type, Page or Image reference. Existing DeleteFragment authorization
is unchanged; this step does not add a new version/edit-lock request requirement.


### Reference-aware deleteImage (0025 Step 9)

The existing `deleteImage({subdir?, name})` request and success reply are unchanged.
Any Fragment reference now produces 409 with a deliberate `ImageReferencedException`;
the handler does not parse foreign-key error messages. The existing client handles
this as an image-in-use conflict and leaves its Files list intact.

Under the existing catalogue/filesystem lock, an advisory reference check avoids
staging a file already known to be referenced. The authoritative check repeats inside
the database deletion transaction after `PESSIMISTIC_WRITE` locks the Image row.
Fragment attachment uses `PESSIMISTIC_READ` on the same row until commit. An attachment
that commits first makes deletion conflict; deletion winning first makes the waiting
attachment fail its Image lookup. The schema's non-cascading FK remains final protection.

0030 recovery is preserved: a reference arriving after the advisory check causes
rollback, restoration of the staged file and backup cleanup, then 409 without a
tombstone. Failed restoration/cleanup still reports administrator recovery. Unknown
database outcomes preserve the backup and report 500. Successful deletion commits
before the acknowledged retained tombstone and final backup cleanup. Removing the
last Fragment reference does not itself delete the Image. Generic DeleteFile guards
remain unchanged. No additional migration or deployment configuration is needed.


### Cross-type regression coverage (0025 Step 10)

The normal Gradle test task includes `ResolvedFragmentStateTest` (complete writer
invariant matrix), `AddFragmentContractTest` (legacy Marquee creation contract), and
`FragmentLifecycleContractTest` (mixed-type locks, reference-preserving deletion and
cross-type handler rejection). They require no database or broker connection and
complement the existing Image creation/update, retained DTO, repository and recovery
tests. Database concurrency and constraints remain separately verified integration
concerns. See [Step 10 evidence](../change-control/in-progress/0025-FEAT%20-%20add%20responder%20ImageFragment%20persistence%20and%20RPC/evidence/Step%2010/README.md)
for the coverage matrix and recorded results.


### Database integration fixture (0025 Step 11)

`ImageWiringIntegrationTest.imageFragmentDatabaseLifecycleWithRealRetainedTopics`
creates an isolated Diary/Page, two Images and mixed Fragments, then verifies real
PostgreSQL constraints and handler lifecycle behavior with actual retained Mosquitto
messages. The Step 11 runner also executes both attach/delete race orderings, uses
Hibernate schema validation, and checks restored database row hashes after cleanup.
Use the [Step 11 runner and evidence](../change-control/in-progress/0025-FEAT%20-%20add%20responder%20ImageFragment%20persistence%20and%20RPC/evidence/Step%2011/README.md)
for disposable setup; ordinary test runs skip these environment-gated tests. Live RPC
request dispatch and restart replay verification remain Step 12.


### Live ImageFragment RPC verification (0025 Step 12)

The disposable Step 12 fixture verifies create/edit/delete through real MQTT requests
and replies, then recreates persistence/connection state and runs production startup
reconciliation. See [Step 12 evidence](../change-control/in-progress/0025-FEAT%20-%20add%20responder%20ImageFragment%20persistence%20and%20RPC/evidence/Step%2012/README.md).

mqtt-rpc 0.0.8 rejects null values in Request.args. The responder's scoped
ImageFragmentMessageHandler adapter preserves explicit `imageId:null` for
addImageFragment/updateFragment so clearing works over the wire. Authentication and
handler validation remain authoritative; other requests use the existing dispatcher.
Remove this adapter after upgrading to a verified null-preserving dependency.
Step 13's production authoring gate defaults to disabled; enable only after the reader/client rollout prerequisites below.


### ImageFragment authoring gate (0025 Step 13)

Add the following top-level property to the responder JSON used by `--config`:

```json
"imageFragmentWritesEnabled": false
```

Missing or null is also disabled. An active editor receives **403 Forbidden** when
calling `addImageFragment` while disabled, with or without an initial Image selection.
`updateFragment` rejects changes to an IMAGE Fragment's `imageId`, including attachment,
replacement and explicit-null clearing. Rejection rolls back the request and preserves
its lock, version, text, database relationship and retained topics. Authentication and
existing type/Page/version checks still apply.

Omitted imageId preserves the reference. An explicit value equal to the current ID
(including null-to-null) is not a mutation and remains allowed. Text/date/sequence
edits, reading, replay, lock/unlock, normalisation and Fragment deletion remain available.
The Image catalogue/upload/delete operations retain their existing reference safeguards.
The creation service also enforces the gate; it is not solely a UI restriction.

Only enable `"imageFragmentWritesEnabled": true` deliberately in disposable development
or integration configuration. Keep production false until the 0025 responder is
validated, the 0026-capable web reader is deployed/verified and 0027 authoring rollout
is explicitly approved. Changing the JSON requires responder restart; this is not a
hot-reloaded flag. Disabling it does not remove existing IMAGE data.

Configuration location by mode:

- development-infrastructure: the JSON passed to the directly launched responder's
  `--config` argument.
- local-docker-build and local-published-smoke: the external JSON selected by
  `DIARIES_RESPONDER_DOCKER_CONFIG_FILE`, mounted read-only at `/config/responder.json`.
- production: the responder JSON generated/mounted by the external Ansible deployment.
  Its template should emit `"imageFragmentWritesEnabled": false` (or omit the property).
  This source change does not edit that separate repository or any private runtime file.

There is no new environment-variable override: all modes use the same responder JSON
property. The disposable integration fixture explicitly enables it; its gate test then
exercises missing/false values over live MQTT. See [Step 13 evidence](../change-control/in-progress/0025-FEAT%20-%20add%20responder%20ImageFragment%20persistence%20and%20RPC/evidence/Step%2013/README.md).
