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

## Useful Gradle commands

```bash id="ifm5rs"
../gradlew clean
../gradlew build
../gradlew test
../gradlew shadowJar
../gradlew getDeps
```

The `getDeps` task copies runtime dependencies into the `runtime/` directory.

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
