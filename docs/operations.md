# MPP operations guide

How to run an MPP server for other people: what to set before the first start, how much memory and disk it needs,
how to secure, watch, back up and stop it, what its log lines mean and how to recover when something goes wrong.

To try it on your own machine first, follow the [Quickstart](../QUICKSTART.md). The wire protocol is in
[protocol.md](protocol.md), clusters are in [clustering.md](clustering.md) and upgrades in
[compatibility.md](compatibility.md). MPP is still experimental (see the [README](../README.md#status)), so this
guide describes what the server does today, not a promise that it is ready for data you can't afford to lose.

- [Deployment checklist](#deployment-checklist)
- [Running the server](#running-the-server)
- [Bootstrap](#bootstrap)
- [Configuration](#configuration)
- [Sizing](#sizing)
- [Security hardening](#security-hardening)
- [Monitoring](#monitoring)
- [Logs](#logs)
- [Backups](#backups)
- [Shutdown and restart](#shutdown-and-restart)
- [Durability](#durability)
- [Upgrades](#upgrades)
- [Recovery runbook](#recovery-runbook)
- [Running a cluster](#running-a-cluster)

## Deployment checklist

| Item | What to set | Why |
|---|---|---|
| Runtime | JDK 27 or newer, or the Docker image | The build requires it |
| Admin password | `defaultAdminPassword` for the first start, then a second admin with [`CREATE_USER`](protocol.md#create_user-admin-only) | The server won't start without one, and a lost admin password can't be reset ([Bootstrap](#bootstrap), [Recovery](#a-lost-admin-password)) |
| Data folder | `filePath` on a local disk that honours `fsync`, with `syncWrites=true` | Otherwise a power loss can lose acknowledged writes ([Durability](#durability)) |
| File modes | `chmod 700` on `filePath` and `logPath`, `chmod 600` on `mpp.cfg` and the keystore | They hold password hashes, data and secrets ([File permissions](#file-permissions)) |
| Network | `bindAddress` set to the interface clients use | Blank binds every interface |
| HTTP entrypoints | `httpPort=0` if you don't use [entrypoints](protocol.md#http-entrypoints), otherwise `httpBindAddress` and a firewall rule for `8990` | The listener is on by default on every interface, HTTPS-only with the client port's keystore when `tlsEnabled=true` |
| TLS | `tlsEnabled=true`, a CA-issued keystore and your own `tlsKeystorePassword` | Without it passwords and documents travel in plaintext ([TLS](#tls--secure-connections)) |
| Limits | Every protective limit above `0` | `0` turns a limit off ([Security hardening](#security-hardening)) |
| Script fetch | `scriptFetchAllowlist` narrowed to the hosts your scripts call, or `scriptFetchEnabled=false` | The shipped `*` lets any script call any public host |
| Shutdown | `shutdownTimeoutMs` below the supervisor's kill grace period | Docker kills after 10 s, the default budget is 15 s ([Shutdown and restart](#shutdown-and-restart)) |
| Memory | A container memory limit or `-Xmx`, and `maxMemory=auto` unless you measured | [Sizing](#sizing) |
| Monitoring | `metricsPort`, a `metricsToken` if it leaves loopback, and the alerts below | [Monitoring](#monitoring) |
| Backups | A scheduled `--dump`, a script that recreates users and grants, and a restore drill | A dump carries no users ([Backups](#backups)) |
| Logs | `logPath` with room for `(maxLogFiles + 1) x 2 x maxLogFileSize` | [Logs](#logs) |
| Health check | The image's `HEALTHCHECK`, or `--health` from your supervisor | [Health checks](#health-checks) |

## Running the server

The server ships without an admin password, so put one in the `mpp.cfg` of the working directory before the first
start:

```bash
mvn clean package -DskipTests
echo "defaultAdminPassword=<choose-a-strong-password>" >> mpp.cfg
java -XX:+UseG1GC -XX:G1PeriodicGCInterval=60000 -Djdk.nio.maxCachedBufferSize=262144 -jar target/mpp.jar
```

Keep those three JVM flags, they are explained in [Sizing](#sizing).

With Docker, set `MPP_ADMIN_PASSWORD` and the entrypoint writes it into the container's config. There is no build
argument for it, because a password given to `docker build` stays in the image layers. For anything else, including
the TLS and cluster secrets, mount your own `mpp.cfg` over the image's (and then put the password in that file, the
variable can't write into a read-only mount):

```bash
docker build -t mpp .
docker run -p 8989:8989 -p 8990:8990 -v ./db:/app/db -v ./mpp.cfg:/app/mpp.cfg:ro mpp
```

Port `8990` is the [HTTP entrypoint](protocol.md#http-entrypoints) listener. Leave out `-p 8990:8990` if you don't use
entrypoints.

The image runs as the unprivileged `mpp` user, so a host folder mounted over `/app/db`, `/app/logs` or `/app/certs`
must be writable by its uid (`docker run --rm --entrypoint id mpp` prints it). It also has a `HEALTHCHECK`, see
[Health checks](#health-checks).

### On Windows

The jar runs unchanged on Windows with a JDK 27 for its architecture (Temurin for x64, Zulu or Liberica for ARM64). Start
it from PowerShell with the same command. The data folder and dumps use line feeds everywhere, so they move between
Windows, Linux and macOS as they are. What differs:

- Java can't sync a folder to disk on Windows, so the server logs a `DURABILITY WARNING` and runs with
  `syncWrites=false` ([Durability](#durability)). Set `syncWrites=false` yourself to silence it.
- The owner-only file modes are POSIX only. On NTFS put `filePath`, `logPath` and the keystore under a folder only the
  server's account can read.
- Windows Defender Firewall blocks `java.exe` until you allow it:
  `New-NetFirewallRule -DisplayName mpp -Direction Inbound -Protocol TCP -LocalPort 8989 -Action Allow`.
- Ctrl+C in the server's console runs the normal shutdown. `Stop-Process` or Task Manager is a hard kill, and the next
  start recovers as after a crash.
- Each connection holds a platform thread on Windows, so keep that in mind when you size `maxConnections`.
- Windows PowerShell 5.1 strips the double quotes inside `--exec '<json>'`. Put the requests in a file instead:
  `cmd /c "java -jar mpp.jar --exec < commands.jsonl"`.

## Bootstrap

On the first start, when no admin user exists, the server creates one from `defaultAdminUsername` and
`defaultAdminPassword`. It refuses to start, with one `FATAL` line and no port opened, when:

- `defaultAdminPassword` is `administrator` (the old default), one of the most common passwords (`password123`,
  `changeme`, `12345678` and so on) or the same as the username, in any letter case. This applies even when an admin
  already exists.
- `defaultAdminPassword` is blank or shorter than 8 characters and no admin exists yet.

Once the admin exists both keys are ignored and you can remove the password from `mpp.cfg`. The server only checks
the configured value, never the stored hash, so if your admin was created with `administrator` in an older setup,
change its password with [`SET_PASSWORD`](protocol.md#set_password).

## Configuration

The server reads `mpp.cfg` from its working directory. It only needs the keys you want to change, every missing key
takes the default below. Lines starting with `#` are comments. Every value is checked at startup, and any invalid one
stops the server with a `FATAL` line listing all of the problems.

Sizes are written like `64Kb`, `2Mb` or `1Gb`, times are in milliseconds. For the protective limits, `0` turns the
limit off and the server names it in a `SECURITY WARNING` at every start.

A typical production `mpp.cfg`:

```
bindAddress=10.0.0.5
defaultAdminPassword=<choose-a-strong-password>
tlsEnabled=true
tlsKeystorePath=certs/mpp.p12
tlsKeystorePassword=<your-keystore-password>
# the metrics port leaves loopback, so it needs a token
metricsPort=9100
metricsBindAddress=0.0.0.0
metricsToken=<a-long-random-token>
# only the hosts scripts really call
scriptFetchAllowlist=api.example.com
# below Docker's 10 s kill grace period
shutdownTimeoutMs=8000
```

### Network and connections

| Key | Default | What it does |
|---|---|---|
| `port` | `8989` | Client port |
| `bindAddress` | blank | Interface the client port binds to. Blank binds all of them, `127.0.0.1` only accepts local clients |
| `maxConnections` | `100` | Client connections open at once. `0` means unlimited |
| `maxUnauthenticatedConnectionsPerAddress` | `16` | Connections one address may hold before logging in, then `503-14`. Clients behind one NAT share it |
| `maxUnauthenticatedInvalidRequests` | `5` | Bad requests a connection may send before logging in, then it is closed |
| `authTimeoutMs` | `10000` | Time a new connection has to log in, then `408-4` and it is closed |
| `maxRequestSize` | `64Mb` | Largest request line once logged in (64 KB before), then `400-22` and the connection is closed |
| `clientOutboundQueueMaxBytes` | `16Mb` | Unread responses and LISTEN updates one connection may pile up before it is closed with `413-1` |
| `maxUnsentResponseBytes` | `0` | Unsent responses the whole server may hold before large reads answer `503-17`. `0` means a quarter of the heap |
| `clientWriteStallTimeoutMs` | `30000` | A client that reads less than 64 KB of its responses in this time is disconnected |

### Authentication

| Key | Default | What it does |
|---|---|---|
| `defaultAdminUsername` | `admin` | Name of the first admin, 3-64 characters from `A-Z a-z 0-9 _ -` |
| `defaultAdminPassword` | blank | Password of the first admin, see [Bootstrap](#bootstrap) |
| `maxFailedAuthAttempts` | `5` | Failed logins one connection may make. The last one answers `401-4` and closes it |
| `authFailureDelayBaseMs` | `250` | Delay after a failed login for the same user and address, doubled on each failure |
| `authFailureDelayMaxMs` | `30000` | Longest delay |
| `maxConcurrentPasswordChecks` | `0` | Password hashes computed at once. `0` means one per processor |
| `authVerifierCacheEnabled` | `true` | Lets a repeated correct login skip the slow hash. The password itself is never kept |

### TLS

| Key | Default | What it does |
|---|---|---|
| `tlsEnabled` | `false` | Encrypt client connections and refuse plaintext ones |
| `tlsKeystorePath` | `certs/mpp.p12` | PKCS12 keystore with the key and certificate. A self-signed one is generated here if missing |
| `tlsKeystorePassword` | `change_it` | Password of the keystore |

### Metrics endpoint

| Key | Default | What it does |
|---|---|---|
| `metricsPort` | `0` | Port of the [Prometheus endpoint](#metrics). `0` turns it off |
| `metricsBindAddress` | `127.0.0.1` | Interface the metrics port binds to |
| `metricsToken` | blank | Bearer token a scrape must send. Blank asks for none |

### Storage and durability

| Key | Default | What it does |
|---|---|---|
| `filePath` | `db` | Data folder, created `0700` if missing |
| `maxPageSize` | `2Mb` | Size of a page file, larger than `maxEntrySize` |
| `maxEntrySize` | `1Mb` | Largest document |
| `syncWrites` | `true` | Force every write to disk before answering, see [Durability](#durability) |
| `walCheckpointBytes` | `8Mb` | Write-ahead log size that starts a checkpoint. Bigger means fewer checkpoints and a longer replay after a crash |
| `walIdleCheckpointMs` | `60000` | Checkpoint a log that has been idle this long. `0` turns it off |
| `shutdownTimeoutMs` | `15000` | Time budget of a graceful shutdown |

### Logging

| Key | Default | What it does |
|---|---|---|
| `logPath` | `logs` | Log folder, created `0700` if missing. Only expired `yyyy-MM-dd.log` files are ever deleted from it |
| `logLevel` | `INFO` | Lowest severity written: `FATAL`, `ERROR`, `WARNING` or `INFO` |
| `maxLogFiles` | `7` | Days of logs kept |
| `maxLogFileSize` | `100Mb` | Size at which the day's file rolls over to `yyyy-MM-dd.1.log` |

### Memory and caches

| Key | Default | What it does |
|---|---|---|
| `maxMemory` | `auto` | Document and index cache. `auto` is 40% of the heap, a size is capped at 60%, `0` is unlimited and `-1` turns caching off |
| `transientLoadPermits` | `4` | Index loads too big for the cache that may run at once. The rest wait |
| `metadataCacheMaxBytes` | `72Mb` | Cache of procedures, schemas, schedules and entrypoints, on top of `maxMemory` |
| `metadataCacheMaxEntries` | `4096` | Cache of trigger lists and "not found" answers, by count. `0` reads from disk every time |
| `procedureCacheSize` | `128` | Compiled procedures kept. `0` compiles on every call |
| `procedureCacheMaxBytes` | `16Mb` | Heap the compiled procedures and scripts may hold |
| `schemaPatternCacheMaxBytes` | `32Mb` | Heap the compiled schema regular expressions may hold |

### Background work

| Key | Default | What it does |
|---|---|---|
| `backgroundProcessingThreads` | `10` | Workers that update the field indexes and admin data after a write |
| `backgroundQueueMaxEvents` | `200000` | Background events queued. When full, writes wait and then answer `503-16` with nothing written |
| `backgroundBacklogWaitMs` | `5000` | How long such a write waits for room. `0` refuses at once |

### Queries

| Key | Default | What it does |
|---|---|---|
| `indexScanFallbackPercent` | `25` | Share of a collection of 4096+ documents an index match may cover before the query scans instead. `100` always uses the index |
| `maxFilterOperands` | `10000` | Values in an `IN`/`NOT_IN` and vertices of a `within` polygon, then `400-27` |
| `maxVectorDimensions` | `16384` | Components of a `#vector(...)` value |
| `aggregateTimeoutMs` | `300000` | Time budget of an `AGGREGATE` or a `LISTEN`'s first run, then `408-6` |
| `aggregateMaxResultBytes` | `auto` | Memory one `AGGREGATE` may build, then `400-36`. `auto` is a quarter of the heap |
| `aggregateTotalHeldBytes` | `auto` | Memory all running pipelines may hold together, then `503-20`. `auto` is a quarter of the heap |
| `deleteMaxMatches` | `100000` | Documents a `DELETE` with a `condition` may delete, then `400-44` and nothing is deleted |
| `updateMaxMatches` | `100000` | Documents a `SAVE` with a `condition` may update, then `400-45` and nothing is written |

### Transactions and snapshots

| Key | Default | What it does |
|---|---|---|
| `transactionLockTimeoutMs` | `5000` | How long a transactional write waits for a busy collection before the transaction aborts with `409-5` |
| `transactionIdleTimeoutMs` | `30000` | A transaction idle this long is rolled back, then `409-16` |
| `transactionMaxAgeMs` | `600000` | Oldest a transaction may get before it is rolled back with `408-5` |
| `transactionMaxOps` | `100000` | Operations one transaction may buffer, then `400-26` |
| `transactionMaxBytes` | `256Mb` | Document bytes one transaction may buffer, then `400-26` |
| `transactionTotalMaxBytes` | `auto` | Document bytes all open transactions may buffer together, then `503-21`. `auto` is a quarter of the heap |
| `snapshotMaxBytes` | `1Gb` | Old document versions one [snapshot](protocol.md#snapshots-start_snapshot-read_snapshot-release_snapshot) may keep, then `409-17` |
| `snapshotMaxOpen` | `4` | Snapshots open at once, then `503-22` |
| `snapshotIdleTimeoutMs` | `300000` | A snapshot unread this long is released |

### LISTEN

| Key | Default | What it does |
|---|---|---|
| `maxListensPerConnection` | `64` | Registrations per connection, then `400-25` |
| `maxListensPerUser` | `256` | Registrations per user across connections, then `400-38` |
| `maxListens` | `10000` | Registrations on the server, then `400-25` |
| `listenMaxRequestBytes` | `1Mb` | Largest `LISTEN` request, then `400-37` |
| `listenMaxRetainedBytes` | `64Mb` | Request bytes all registrations keep together, then `503-15` |
| `listenRerunTimeoutMs` | `10000` | Time budget of a re-run after a write. A late one pushes nothing |
| `listenThreads` | `2` | Workers that re-run the queries after a write |

### Schemas and object limits

| Key | Default | What it does |
|---|---|---|
| `schemaValidationBudget` | `5000000` | Validation steps one write may spend on its schema, then `400-23`. A big `BULK_SAVE` may need more |
| `schemaMaxPatternLength` | `4096` | Longest regular expression in a schema, then `400-31`. Keep it equal on every node |
| `schemaMaxPatterns` | `256` | Regular expressions in one schema, then `400-39`. Keep it equal on every node |
| `maxProjects` | `1000` | User projects, then `400-41` |
| `maxCollectionsPerProject` | `1000` | Collections per project, then `400-40` |
| `maxIndexesPerCollection` | `64` | Field indexes per collection, then `400-42` |
| `maxProceduresPerProject` | `500` | Stored procedures per project, then `400-34` |

### Scripts

What scripts can do is in [simplejs.md](simplejs.md).

| Key | Default | What it does |
|---|---|---|
| `scriptsEnabled` | `true` | Allow scripts, stored procedures and [script operators](protocol.md#script-operators-simplejs-in-the-pipeline). Off answers `403-2` |
| `scriptInstructionBudget` | `10000000` | Instructions per run, then `400-11` |
| `scriptTimeoutMs` | `5000` | Time per run, then `408-1` |
| `scriptMaxDepth` | `200` | Call depth, then `400-11` |
| `scriptMaxSourceBytes` | `256Kb` | Largest source, then `400-10` |
| `scriptMaxMemoryBytes` | `64Mb` | Memory for long strings and big arrays per run, then `400-12` |
| `scriptHeapCeilingPercent` | `75` | Heap use, in percent, above which a script asking for more memory is stopped |
| `scriptMaxResultBytes` | `16Mb` | Largest returned value, then `400-15`. Use `db.cursor` for more |
| `scriptMaxArrayLength` | `16777216` | Longest array a single native call may expand, then `400-30` |
| `scriptCursorBatchSize` | `500` | Default batch of `db.cursor` |
| `scriptCursorMaxBatchSize` | `5000` | Largest batch a script may ask `db.cursor` for |
| `scriptMaxLogLines` | `1000` | `console` lines returned, newest kept |
| `scriptMaxLogLineChars` | `4096` | Characters kept per `console` line |
| `scriptTextImportEnabled` | `false` | Allow `importText`, which runs a string as a module |
| `scriptProcedureImportEnabled` | `true` | Allow importing a stored procedure of the same project as a module |
| `scriptTimeZone` | `UTC` | Time zone scripts see, so every node answers the same |
| `scriptLocale` | `en-US` | Locale scripts format and compare with |
| `aggregationScriptInstructionBudget` | `1000000` | Instructions all script operators of one pipeline may run, then `400-11` |
| `aggregationScriptTimeoutMs` | `2000` | Time for the same, then `408-1` |
| `aggregationScriptMaxSourceBytes` | `16Kb` | Largest script operator, then `400-10` |
| `maxConcurrentScripts` | `64` | Client script runs at once on the node, then `503-6` |
| `maxConcurrentScriptsPerUser` | `32` | Script runs at once per user, triggers, schedules and entrypoints included |
| `maxConcurrentScriptsPerProject` | `48` | Script runs at once per project. The last slot is kept for owners and admins |
| `scriptQueueWaitMs` | `250` | How long a run waits for a free slot before `503-6` |

### Script fetch

| Key | Default | What it does |
|---|---|---|
| `scriptFetchEnabled` | `true` | Allow `fetch` from scripts, procedures, triggers and schedules. Needs the server running from its jar |
| `scriptFetchAllowlist` | `*` | Hosts scripts may call: exact hosts, `*.example.com`, or `*` for every public host. Private addresses need an exact entry, an empty list denies all |
| `scriptFetchMaxConcurrent` | `8` | Fetches one run may have in flight |
| `scriptFetchMaxResponseBytes` | `1Mb` | Largest response body |
| `scriptFetchTimeoutMs` | `5000` | Time budget of one fetch |

### Script run history

| Key | Default | What it does |
|---|---|---|
| `scriptRunHistoryEnabled` | `true` | Record finished runs in each project's `script_runs` collection, best effort |
| `scriptRunHistoryKinds` | `CALL_PROCEDURE,TRIGGER,SCHEDULE` | Kinds recorded. `RUN_SCRIPT`, `ENTRYPOINT` and `BEFORE_HOOK` (failures only) can be added |
| `scriptRunHistoryIncludeLogs` | `false` | Keep the run's console output on its row |
| `scriptRunHistoryMaxErrorChars` | `2000` | Characters of an error message kept |
| `scriptRunHistoryMaxQueued` | `10000` | Rows waiting to be written. More are dropped and counted |
| `scriptRunHistoryMaxRows` | `100000` | Rows kept per project. `0` means no cap |
| `scriptRunHistoryRetentionMs` | `604800000` | How long a row is kept (7 days) |
| `scriptRunHistorySweepMs` | `3600000` | How often old rows are deleted |

### Triggers

| Key | Default | What it does |
|---|---|---|
| `triggersEnabled` | `false` | Fire triggers on committed writes |
| `triggerThreads` | `2` | Trigger workers |
| `triggerQueueSize` | `10000` | Trigger runs queued. When full the oldest is dropped and counted |
| `triggerQueueMaxBytes` | `64Mb` | The same queue, in bytes |
| `triggerMaxPerCollection` | `50` | Triggers per collection, then `400-32` |
| `triggerMaxDepth` | `3` | How deep a chain of trigger-fired writes may go |
| `triggerTimeoutMs` | `1000` | Time per trigger run |
| `triggerRunLogEnabled` | `true` | Record a run before it starts, so a crash doesn't lose it. The record stays on the node |
| `triggerRunRetentionMs` | `86400000` | How long an orphaned pending run is kept (1 day) |
| `triggerMaxAttempts` | `3` | Attempts before a failing run is [dead-lettered](#dead-lettered-trigger-runs) |
| `triggerRetryBackoffMs` | `1000` | Pause before a retry, doubled each time |
| `triggerRetryMaxBackoffMs` | `60000` | Longest pause between retries |
| `triggerDeadLetterRetentionMs` | `604800000` | How long a dead-lettered run is kept (7 days) |
| `triggerDeadLetterMaxRuns` | `10000` | Dead-lettered runs kept per project. `0` means no cap |
| `beforeHookInstructionBudget` | `200000` | Instructions all the before triggers of one request may run |
| `beforeHookTimeoutMs` | `200` | Time for the same. Past it the write is refused with `408-1` |

### Schedules

| Key | Default | What it does |
|---|---|---|
| `schedulesEnabled` | `true` | Fire schedules. Off answers `403-2` to the schedule operations |
| `scheduleThreads` | `2` | Schedule workers |
| `scheduleQueueSize` | `100` | Due runs waiting. When full the oldest is dropped |
| `scheduleTickMs` | `1000` | How often due schedules are checked, the finest a schedule can fire |
| `scheduleRefreshMs` | `60000` | How often the schedule list is reloaded from disk |
| `scheduleTimeoutMs` | `30000` | Default time per run |
| `scheduleMaxTimeoutMs` | `300000` | Largest `timeoutMs` a schedule may ask for, then `400-29` |
| `scheduleMaxPerProject` | `100` | Schedules per project |
| `scheduleMaxTotalPerUser` | `500` | Schedules one user defines across all projects, then `400-33` |
| `scheduleMinIntervalMs` | `1000` | Smallest `intervalMs`, below it `400-35` |

### HTTP entrypoints

What an entrypoint does is in [protocol.md](protocol.md#http-entrypoints).

| Key | Default | What it does |
|---|---|---|
| `httpPort` | `8990` | Port of the HTTP entrypoint listener, different from `port`, `metricsPort` and `clusterPort`. `0` turns it off. HTTPS when `tlsEnabled=true` |
| `httpBindAddress` | blank | Interface the entrypoint listener binds to. Blank binds all of them |
| `httpMaxConnections` | `10000` | HTTP connections open at once, then `503` and the connection is closed. `0` means no cap |
| `httpMaxRequestBytes` | `64Mb` | Largest request body, then `413` without reading it |
| `httpReadTimeoutMs` | `10000` | Time the request head, and then its body, has to arrive, then `408` and the connection is closed |
| `httpIdleTimeoutMs` | `5000` | Time a kept-alive connection may wait for its next request |
| `entrypointTimeoutMs` | `30000` | Default time per run |
| `entrypointMaxTimeoutMs` | `300000` | Largest `timeoutMs` an entrypoint may ask for, then `400-48` |
| `maxEntrypointsPerProject` | `1000` | Entrypoints per project, then `400-47` |

### Cluster

How a cluster behaves is in [clustering.md](clustering.md).

| Key | Default | What it does |
|---|---|---|
| `clusterEnabled` | `false` | Run as a cluster node |
| `clusterPort` | `9990` | Node-to-node port, different from `port` |
| `clusterBindAddress` | `0.0.0.0` | Interface the cluster port binds to |
| `clusterAdvertisedAddress` | `127.0.0.1` | Address the other nodes use to reach this one |
| `clusterSeeds` | blank | Comma-separated `host:port` of nodes to join through. Empty on the first node |
| `nodeId` | blank | Stable node id. Blank generates one and keeps it in `filePath/cluster/node.id` |
| `clusterExpectedSize` | `1` | Node count the write quorum is based on until membership settles |
| `clusterSecret` | blank | Shared secret of the cluster port, at least 32 characters. Required in a cluster |
| `clusterTlsEnabled` | `false` | Mutual TLS between nodes, with one keystore shared by all of them |
| `clusterMaxFrameSize` | `256Mb` | Largest node-to-node message, at least twice `maxRequestSize` |
| `clusterHandshakeTimeoutMs` | `5000` | Time a new peer connection has to introduce itself |
| `clusterMaxClockSkewMs` | `3600000` | A version further than this in the future is refused. Keep it above the real clock skew |
| `gossipIntervalMs` | `1000` | Heartbeat interval |
| `suspectTimeoutMs` | `5000` | Silence before a node is SUSPECT |
| `deadTimeoutMs` | `15000` | Silence before a node is DEAD, more than `suspectTimeoutMs` |
| `deadEvictionMs` | `86400000` | Time a DEAD node still counts for the quorum before it is dropped. Longer than any outage you expect to come back from |
| `deadProbeIntervalMs` | `10000` | How often a SUSPECT or DEAD node is probed, so two sides of a healed partition find each other |
| `replicationAckTimeoutMs` | `5000` | Longest wait for the replication quorum |
| `virtualNodesPerNode` | `128` | Points per node on the hash ring |
| `readFallbackToLocal` | `true` | Serve a read locally when the owner can't be reached |
| `antiEntropyIntervalMs` | `60000` | How often a node reconciles its collections with its peers |
| `tombstoneRetentionMs` | `86400000` | How long delete markers are kept. Longer than any node downtime you expect |
| `scriptRoutingEnabled` | `true` | Let a script run on a less loaded node. The script settings must match on every node |
| `scriptLocalityWeight` | `50` | 0-100, how much script placement prefers a node owning the project's collections over load |

### Testing only

| Key | Default | What it does |
|---|---|---|
| `faultInjectionEnabled` | `false` | Lets an admin pause or halt the node over the wire. Never in production |
| `powerLossShadowPath` | blank | Folder for the power-loss test harness. Needs `faultInjectionEnabled`, outside `filePath` |

## Sizing

The heap holds four things:

- the document and index cache, `maxMemory` (40% of the heap with `auto`);
- the metadata caches, `metadataCacheMaxBytes` (72 MB) plus the procedure and schema caches;
- scripts, up to `maxConcurrentScripts x scriptMaxMemoryBytes` (64 x 64 MB) when every slot runs a greedy script, though
  `scriptHeapCeilingPercent` stops scripts once the heap passes 75%;
- everything else: the collector's headroom, requests and responses in flight, open transactions and running
  aggregates (the last two capped at a quarter of the heap each).

The image gives the JVM 70% of the container's memory limit, so in Docker the only number to pick is that limit. Change
the share with `-e JDK_JAVA_OPTIONS=-XX:MaxRAMPercentage=50` (or `-Xmx`). The startup log warns when the budgets don't
fit the heap. A small cached document takes about 3 KB of heap, against under 200 bytes on disk, so plan the cache
around the data you query most. The README's [performance section](../README.md#performance) shows what the server
does with 100,000 documents in 4 GB.

### Memory management

`maxMemory` is the cache's own estimate of the heap it holds, checked every 5 seconds. When it goes over, the least
used collections and indexes are evicted, documents first and primary keys last. `0` never evicts and `-1` reads
everything from disk (admin data is always cached). With `auto` nothing needs sizing. With an explicit value, give the
heap about twice `maxMemory` plus the metadata and script budgets, and the startup log tells you when that's off.

A primary key or field index too big for `maxMemory` is never cached and the log says so once. It still works, but
reads and writes on it go to disk, so raise `maxMemory` above the size the warning gives if you query it.

The process takes more memory than the heap: metaspace, compiled code and unused committed heap are outside it.

### JVM flags

- `-XX:+UseG1GC`: ZGC needed about 21% more heap for the same data and 2.5 times the process memory. The flag is
  explicit because the JVM picks SerialGC by itself in a container under 2 CPUs or 1792 MB.
- `-XX:G1PeriodicGCInterval=60000`: lets an idle server give back the heap it no longer uses. Without it the process
  stays at its peak after a burst.
- `-Djdk.nio.maxCachedBufferSize=262144`: stops the JDK from caching a native buffer per thread for large file writes,
  outside the heap. Without it, restoring a dump with large documents on a 512 MB container ran out of memory.

On a glibc host (the image uses Alpine), the same server uses about 185 MB more native memory. With `java -jar` there,
set `MALLOC_ARENA_MAX=2`, consider `-XX:TrimNativeHeapInterval=60000`, and keep the heap nearer the JVM's default
quarter of memory than the image's 70%.

### Entrypoint traffic

Every [HTTP entrypoint](protocol.md#http-entrypoints) request runs a script, so the three script-concurrency keys are
what size entrypoint throughput: `maxConcurrentScriptsPerUser` (32, counted against the entrypoint's definer, so all
traffic to one user's entrypoints shares those slots), `maxConcurrentScriptsPerProject` (48) and `maxConcurrentScripts`
(64). A request past them answers `503` with `Retry-After: 1`. Raise them for a busy endpoint, or set one to `0` to
lift it, and keep the script memory worst case above in mind. The body is read only once a run has its slot, so request
bodies in memory are bounded by `maxConcurrentScripts x httpMaxRequestBytes`, not by the open connections.

Each open connection holds a file descriptor. `httpMaxConnections=10000` needs a limit above it (`ulimit -n`) plus room
for the client port and the data files. Docker's default is usually enough, a bare Linux login shell's 1024 is not.

### Disk

Besides the documents, each collection takes:

| What | Up to |
|---|---|
| Dead space in its pages | Updates and deletes leave the old copy as blank space until the page is compacted, about a third more than the live data |
| Its primary key index, `pk.idx` | One short row per write since the last compaction |
| Its write-ahead log, `{collection}.wal` | `walCheckpointBytes` (8 MB), twice that under heavy writes |
| Each field index | The `.idx` file plus a change log, folded back once the log reaches the size of the `.idx` (at least 64 KB), and at most 4 times that |

And the server as a whole:

| What | Up to |
|---|---|
| Snapshots while a dump runs | `snapshotMaxBytes` (1 GB) for each of up to `snapshotMaxOpen` (4), deleted when released |
| Script run history | `scriptRunHistoryMaxRows` (100,000) rows per project, for `scriptRunHistoryRetentionMs` (7 days) |
| Dead-lettered trigger runs | `triggerDeadLetterMaxRuns` (10,000) per project |
| Cluster tombstones | One line per deleted document, for `tombstoneRetentionMs` (1 day) |
| Logs | `(maxLogFiles + 1) x 2 x maxLogFileSize`, 1.6 GB with the defaults |

`mpp_live_bytes` and [`GET_PROJECT_STATS`](protocol.md#get_project_stats) give the live document bytes. Keep free
at least the size of the biggest collection's files, plus twice `walCheckpointBytes` for every collection written at
once, plus `snapshotMaxBytes` while dumps run. On a full disk the server stays up and recovers once space is back
([Disk full](#disk-full)), but every write fails until then.

## Security hardening

The server refuses to start with a weak or blank `defaultAdminPassword` ([Bootstrap](#bootstrap)), and with
`scriptFetchEnabled=true` when it doesn't run from its jar. Everything else is a warning at every start, so aim for a
clean startup log:

| Startup line | Remove it with |
|---|---|
| `SECURITY WARNING: tlsEnabled=false, so client connections are plaintext` | `tlsEnabled=true` and a CA-issued keystore ([TLS](#tls--secure-connections)) |
| `SECURITY WARNING: tlsKeystorePassword is still the shipped value` | Your own `tlsKeystorePassword` |
| `SECURITY WARNING: these limits are set to 0, which turns them off` | A positive value for each key it names, unless something in front of the server enforces it |
| `SECURITY WARNING: the data folder (filePath) at ... is accessible by group or other users` (also the log folder, the keystore and `mpp.cfg`) | The `chmod` the line prints ([File permissions](#file-permissions)) |
| `SECURITY WARNING: the metrics endpoint on port ... without a metricsToken` | `metricsToken`, or `metricsBindAddress=127.0.0.1` |
| `SECURITY WARNING: clusterTlsEnabled=false` | `clusterTlsEnabled=true` with one keystore shared by every node |
| `SECURITY WARNING: scriptFetchAllowlist names ... exactly` | Removing that internal host from `scriptFetchAllowlist`, unless scripts must reach it |
| `scriptFetchAllowlist is '*'` | Listing the hosts scripts call, or `scriptFetchEnabled=false` |
| `SECURITY WARNING: faultInjectionEnabled=true` | `faultInjectionEnabled=false`, it is for tests only |
| `DURABILITY WARNING: syncWrites=false` | `syncWrites=true` ([Durability](#durability)) |
| `DURABILITY WARNING: syncWrites=true, but this platform cannot sync a directory` | Running on a platform that can |

Beyond that:

- Give each application its own user with the narrowest grant that works ([permission model](protocol.md#permission-model)),
  not the admin.
- Leave `triggersEnabled` off unless you use triggers, and turn `scriptsEnabled` off if nobody runs scripts.
- Every [HTTP entrypoint](protocol.md#http-entrypoints) is public, and its procedure runs with its definer's
  permissions. Anyone who can reach `httpPort` can do through it whatever the procedure does with those grants. Save
  entrypoints as a user that holds only the grants they need, never as an admin or an owner, check what they accept
  inside the procedure, and set `httpPort=0` or `httpBindAddress` when nothing outside should reach them.
- Remove the admin password from `mpp.cfg` once the first admin exists.

### TLS / secure connections

With `tlsEnabled=true` the same protocol runs over TLS and a plaintext client is dropped at the handshake. The same
setting makes the [HTTP entrypoint](protocol.md#http-entrypoints) listener HTTPS-only, with the same keystore and
protocols, so one TLS config covers both listeners and there is nothing more to set. The key and
certificate come from the PKCS12 keystore at `tlsKeystorePath`, unlocked with `tlsKeystorePassword`. Use a CA-issued
certificate in production.

When the keystore doesn't exist the server generates a self-signed one there and keeps it across restarts. That's for
development only, clients won't trust it. A loaded certificate that has expired or expires within 30 days shows up as a
`SECURITY WARNING`.

Without TLS, passwords and documents cross the network in plaintext, so keep such a server on localhost. Change
`change_it` too: anyone who can read the keystore file can extract the private key with it.

### File permissions

On POSIX filesystems every folder the server creates (data, logs, the keystore's folder) is `0700` and a generated
keystore is `0600`. The server never changes the mode of something that already exists. Instead it warns about the data
folder, the log folder, the keystore and `mpp.cfg` (which holds the admin, keystore and cluster secrets) when group
or other users can read them, and prints the `chmod` that fixes it. A Docker volume is often created `0755`, so expect
that warning until you tighten it.

Log cleanup only deletes old `yyyy-MM-dd.log` files, never anything else in `logPath`, and dropping a project or
collection never follows a symbolic link.

## Monitoring

### Metrics

With `metricsPort` set the server answers `GET /metrics` in the Prometheus text format. It is plain HTTP, separate
from the client port and its TLS, and binds `127.0.0.1` by default. Keep it on loopback, or set `metricsToken` and put
a TLS proxy in front before exposing it. At most four scrapes are served at once.

```yaml
scrape_configs:
  - job_name: mpp
    static_configs:
      - targets: ["db-host:9100"]
    authorization:
      credentials: "<metricsToken>"
```

In Docker, set `metricsBindAddress=0.0.0.0` and a `metricsToken`, and publish the port (`-p 9100:9100`).

Every metric starts with `mpp_`. A scrape takes no lock, so it answers while a collection is busy. Totals cover all
user projects, never one collection.

| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `build_info` | gauge (always 1) | `version`, `protocol_version`, `storage_format_version` | What `SERVER_INFO` reports |
| `uptime_seconds` | gauge | | Seconds since the start |
| `requests_total` | counter | `operation`, `status`, `code` | Requests answered. `code` is the error code, empty on success |
| `request_duration_seconds` | histogram | `operation` | Time to answer a request, without sending it |
| `connections` | gauge | | Open client connections |
| `connections_refused_total` | counter | `reason` (`max_connections`, `unauthenticated_per_address`) | Connections refused (`503-1`, `503-14`) |
| `in_flight_requests` | gauge | | Requests being served |
| `unsent_response_bytes`, `unsent_response_limit_bytes` | gauge | | Responses waiting to be sent, and `maxUnsentResponseBytes` |
| `cache_bytes`, `cache_limit_bytes` | gauge | | What the cache holds, and `maxMemory` (`+Inf` unlimited, `0` off) |
| `heap_used_bytes`, `heap_max_bytes` | gauge | | JVM heap |
| `gc_collections_total`, `gc_seconds_total` | counter | `gc` | Garbage collections and their time, per collector |
| `background_queued` | gauge | `queue` (`index`, `maintenance`, `history`) | Background work waiting |
| `triggers_total` | counter | `outcome` (`fired`, `failed`, `dropped`, `retried`, `dead_lettered`) | Trigger runs |
| `trigger_queue`, `schedule_queue` | gauge | | Trigger and schedule runs waiting |
| `schedules_total` | counter | `outcome` (`fired`, `failed`, `skipped`, `dropped`) | Scheduled runs |
| `scripts_running`, `scripts_rejected_total` | gauge, counter | | Scripts running, and scripts refused for lack of a slot |
| `in_doubt_transactions` | gauge | | Distributed transactions prepared here and not resolved yet |
| `fsync_total`, `fsync_failures_total` | counter | | Forces to disk, and the ones that failed |
| `fsync_duration_seconds` | histogram | | Time of one force |
| `projects`, `collections`, `documents`, `live_bytes` | gauge | | Totals across all user projects |

The histogram buckets go from 0.5 ms to 10 s.

### Health checks

- The image runs the jar's `--health` client every 30 seconds after a 60-second start period, so `docker ps` shows
  `healthy` once the server is `READY`.
- Anywhere else, run `java -jar mpp.jar --health --host <host> --port <port>` (add `--tls`, and `--insecure` for a
  self-signed certificate). It needs no password and exits `0` only when the server is `READY`.
- A load balancer can send [`SERVER_INFO`](protocol.md#server_info) without logging in. `SYNCING` is a cluster node
  still catching up and `SHUTTING_DOWN` answers everything with `503-13`. Only route to `READY`.

### Suggested alerts

Starting points in PromQL. Tune the windows to your traffic.

| Condition | Means | First action |
|---|---|---|
| `up{job="mpp"} == 0` | The endpoint doesn't answer | Check the process and the [startup log](#logs) |
| `increase(mpp_fsync_failures_total[5m]) > 0` | The disk refused to persist a write | [A failed fsync](#a-failed-fsync) |
| `histogram_quantile(0.99, sum by (le) (rate(mpp_fsync_duration_seconds_bucket[5m]))) > 0.05` | Forces take over 50 ms, and every write waits for them | Check the disk's latency and what else writes to it |
| `mpp_in_doubt_transactions > 0` for 10 minutes | A distributed transaction lost its coordinator | [In-doubt distributed transactions](#in-doubt-distributed-transactions) |
| `increase(mpp_triggers_total{outcome="dead_lettered"}[1h]) > 0` | A trigger kept failing and was set aside | [Dead-lettered trigger runs](#dead-lettered-trigger-runs) |
| `increase(mpp_triggers_total{outcome="dropped"}[1h]) > 0`, or the same for `mpp_schedules_total` | A full queue dropped a run | Raise the queue size or slow down what fires it |
| `deriv(mpp_background_queued{queue="index"}[15m]) > 0` for 15 minutes | Index updates fall behind the writes | Writes will start answering `503-16`, check CPU and disk |
| `mpp_heap_used_bytes / mpp_heap_max_bytes > 0.9` for 15 minutes | The heap is nearly full | [Sizing](#sizing) |
| `mpp_unsent_response_bytes / mpp_unsent_response_limit_bytes > 0.8` | Clients read slower than they ask | Reads will start answering `503-17`, find the slow client |
| `rate(mpp_connections_refused_total[5m]) > 0` | Connections hit a cap | Raise it, or find the client that leaks connections |
| `sum by (code) (rate(mpp_requests_total{code=~"5.."}[5m])) > 0` | Server-side errors | Look the code up in the [error table](protocol.md#error-codes) |
| `rate(mpp_scripts_rejected_total[5m]) > 0` | Every script slot was busy | Raise `maxConcurrentScripts` or find the slow script in `script_runs` |

## Logs

Every entry goes to `logPath` and to standard output, so `docker logs` shows it too. There is one file per day,
`yyyy-MM-dd.log`, rolled over to `yyyy-MM-dd.1.log` once it reaches `maxLogFileSize`. Files older than `maxLogFiles` days
are deleted.

A line looks like `14:02:11.348519-INFO-org.ccg.mpp.ServerMain - MPP 0.1.0 starting`. An exception adds ` -> `
with its class and message, then one stack frame per line, then each cause the same way after `Caused by: `. A message
is always one line, so `grep` finds whole entries.

The lines below need someone to act. Search for the text before the first `...`.

### The server refused to start

Every refusal is one `FATAL` line and exit status `1`. Nothing on disk changes, so fix the cause and start again.

| Line starts with | Cause and action |
|---|---|
| `Configuration validation failed, the application will not start:` | Every invalid key follows, separated by a literal `\n`. Fix `mpp.cfg` ([Configuration](#configuration)). This includes `httpPort (...) must be different from port`, `metricsPort` or `clusterPort` |
| `Insecure configuration, the application will not start:` | A weak or missing `defaultAdminPassword` ([Bootstrap](#bootstrap)), or script fetch outside the jar. The line says which |
| `The server will not start: .../format.json says this folder holds storage format` | The data folder belongs to another release. The line says which one to run ([compatibility.md](compatibility.md#the-format-stamp)) |
| `The server will not start: could not bind the HTTP entrypoint listener to port` | `httpPort` is taken, or can't be bound on `httpBindAddress`. Pick a free port, or `httpPort=0` to turn the listener off |
| `The server will not start:` followed by anything else | The server can't prepare `filePath` or `logPath`, for example a missing permission. The line names the path |
| `I/O error while starting server on port` | The client port is taken, or can't be bound on `bindAddress` |
| `Failed to start the cluster server` | The cluster port couldn't open. The exception that follows says why |

### Startup warnings that need action

| Line starts with | Action |
|---|---|
| `The following configs are missing and will be using defaults:` | Nothing, those keys take the defaults in the [table](#configuration) |
| `SECURITY WARNING:` / `DURABILITY WARNING:` | See [Security hardening](#security-hardening) |
| `These collections stopped with field-index work outstanding` | `REINDEX` each collection it names ([A stale or suspect index](#a-stale-or-suspect-index)) |
| `These field indexes were left half-built or half-dropped` | `REINDEX` or `DROP_INDEX` each field it names. Until then queries on it scan |
| `The configured memory budgets total ... but JVM -Xmx is only` | Lower the budgets or raise the heap ([Sizing](#sizing)) |
| `maxMemory=... is more than 60% of the JVM heap` | The cache was capped. Raise the heap, lower `maxMemory`, or use `auto` |
| `JVM -Xmx (...) is more than 2x the configured maxMemory` | Raise `maxMemory` towards half the heap, or use `auto` |
| `The primary-key index of ... needs at least` | That collection's keys don't fit in `maxMemory`, so keyed access reads from disk. Raise `maxMemory` |
| `These names share one on-disk path and collide on a case-insensitive filesystem` | Two names differ only in case on a filesystem that ignores case. Move the data to a case-sensitive one |
| `Project '...' is stored in the folder this node keeps its cluster state in` | Copy that project's data elsewhere before enabling clustering |

### Errors while running

| Line starts with | Action |
|---|---|
| `Failed to seed the write clock from disk` and the other `Could not seed the write clock` lines | Versions may go backwards after this restart, which matters in a cluster. Fix the file the exception names and restart |
| `Failed to recover in-doubt transactions at startup` | [In-doubt distributed transactions](#in-doubt-distributed-transactions) |
| `Failed to load the schedule definitions` | Some schedules didn't load. The exception names the file |
| `Could not checkpoint ... in the background` and `Still cannot checkpoint` | Usually a full disk. The server keeps going and retries, so free some space ([Disk full](#disk-full)) |
| `Trigger queue full; dropped` / `Schedule queue full; dropped` | A run was dropped, see the alerts above |
| `The shutdown budget ran out with ... write-ahead log(s) not checkpointed` | Nothing is lost, the next start replays them. Raise `shutdownTimeoutMs` if it happens every time |
| `... request(s) still running at the end of the shutdown budget` / `Shutdown step '...' failed` | Check the next start for the index warnings above |

After a crash, `Repaired the field indexes of project|coll for N document(s) in X ms` is the healthy line: the start
finished the index work the crash interrupted.

## Backups

A dump is the backup. [`--dump`](../README.md#client-shell-exec-dumprestore) reads each project from one snapshot
while writes go on, and writes plain requests that `--restore` replays. The README describes the flags and the format.

### A scheduled dump

Run it as the admin so every project is included, and keep the password in `MPP_PASSWORD`, not on the command line:

```sh
#!/bin/sh
set -eu
out=/backups/mpp-$(date +%F).ldump.gz
java -jar /opt/mpp/mpp.jar --dump --compress --host db.internal --tls > "$out.partial"
mv "$out.partial" "$out"
find /backups -name 'mpp-*.ldump.gz' -mtime +14 -delete
```

The client exits with `1` when the dump stops and `3` when it can't connect or log in, so `set -e` leaves only the
`.partial` file and the job fails visibly. A dump also fails, instead of mixing two points in time, when its snapshot
passes `snapshotMaxBytes` (`409-17`), when too many snapshots are open (`503-22`) or when a collection it reads is
dropped. Run it again.

### What a dump does not carry

Users, grants and project owners never leave the server: password hashes aren't exported, and
[`CREATE_USER`](protocol.md#create_user-admin-only) only takes a plaintext password. Keep a script that recreates them
with `CREATE_USER`, [`CHANGE_PERMISSIONS`](protocol.md#change_permissions-admin-only) and
[`SET_PROJECT_OWNERS`](protocol.md#set_project_owners-admin-only), and run it after a restore.
[`LIST_USERS`](protocol.md#list_users-admin-only) shows what it needs to cover. Restored procedures, triggers,
schedules and entrypoints belong to the user who ran the restore, and run with that user's permissions.

Script run history, pending trigger runs and in-doubt transactions aren't dumped either.

### A restore drill

A backup is only as good as its last restore. Restore into a scratch server on another port and folder, then compare:

```sh
mkdir -p /tmp/drill && cd /tmp/drill
printf 'port=9899\ndefaultAdminPassword=%s\n' "$DRILL_PASSWORD" > mpp.cfg
java -XX:+UseG1GC -jar /opt/mpp/mpp.jar &
until java -jar /opt/mpp/mpp.jar --health --port 9899 > /dev/null 2>&1; do sleep 1; done
MPP_PASSWORD="$DRILL_PASSWORD" java -jar /opt/mpp/mpp.jar --restore --port 9899 < /backups/mpp-2026-11-12.ldump.gz
MPP_PASSWORD="$DRILL_PASSWORD" java -jar /opt/mpp/mpp.jar --exec '{"type":"GET_PROJECT_STATS"}' --port 9899
```

Compare each collection's `entryCount` with production at the time of the dump, then stop the scratch server and
delete `/tmp/drill`. A restore merges into what the server already has. To replace a collection, drop it first.

### Copying the data folder

A copy of `filePath` is only a valid backup when the server is stopped, and only as a whole: the `.wal` files,
`format.json` and everything under `admin/` belong to it. Copying a running server's folder isn't supported. Restore
such a copy only into the same release.

## Shutdown and restart

`SIGTERM` (what `docker stop` sends) or Ctrl+C starts a graceful shutdown. The log shows
`Shutting down; up to 15000ms to finish outstanding work` and, at the end, `Shutdown complete`.

In that time the server stops accepting connections, rolls back open transactions, lets the HTTP entrypoint requests in
flight finish (closing idle HTTP connections), finishes the queued trigger and index work, releases snapshots and
checkpoints the write-ahead logs. Whatever is still pending when `shutdownTimeoutMs` runs out is dropped with a warning.
If that was index work, run `REINDEX` on the collections it names. Keep `shutdownTimeoutMs` below your supervisor's kill
grace period (Docker's is 10 s, so lower the budget or raise `--stop-timeout`).

A `SIGKILL` skips all of that and is still safe: the next start replays the logs ([Durability](#durability)), it just
takes longer.

## Durability

With `syncWrites=true` a write is answered only once its record in the collection's write-ahead log
(`{collection}.wal`) is forced to disk. The page files are written after that and forced later, at a checkpoint. After a
crash or a power loss the start replays the log, so an acknowledged write is redone and an unacknowledged one leaves
nothing behind. A checkpoint runs in the background when the log passes `walCheckpointBytes`, when it has been idle for
`walIdleCheckpointMs`, and at shutdown.

Concurrent `SAVE`s and `DELETE`s on the same collection share one force, so the disk's force latency is what limits the
write rate of a single collection. Different collections write in parallel. As a reference, one force took about
0.25 ms on a Docker volume on Linux and about 4 ms on an Apple SSD under macOS, and the benchmark did 4-5k synced
inserts or updates and 7-8k deletes per second with 8 clients. A dirty read can see a write before its force.

With `syncWrites=false` writes are answered once the OS has them, which is faster, but a power loss can lose the most
recent ones. The logs and the generated keystore are never forced.

All of this relies on a disk that really persists forced data. Some network filesystems and disks with a volatile
write cache don't.

## Upgrades

The rules are in [compatibility.md](compatibility.md): compare the new release's `storageFormatVersion` with what
[`SERVER_INFO`](protocol.md#server_info) reports. If it's the same, stop the server, replace the jar or image and start
it on the same folder. If it's different, [dump](#backups), start the new release on an empty folder, recreate the
users and restore.

## Recovery runbook

### After a crash or `kill -9`

Nothing to do. The start replays the write-ahead logs, rebuilds its page bookkeeping and finishes the interrupted index
work. Expect `Repaired the field indexes of ...` lines and a slower start than usual.

### After a power loss

With `syncWrites=true`, the same as a crash. With `syncWrites=false`, writes acknowledged shortly before may be gone,
and nothing tells you which.

### Disk full

Writes fail, reads keep working and the server stays up. Once you free space everything resumes without a restart.
While full:

- A write answered `500-34` was logged but not applied yet. It will take effect, so check with
  [`FIND_BY_ID`](protocol.md#find_by_id) before resending it.
- A `COMMIT_TRANSACTION` answered `500-33` did commit. Its connection keeps finishing it and its collections answer
  `503-18` until then.
- A read answered `503-19` hit a collection with such a write still pending. Retry once space is back.
- Any other error means the write didn't happen.

Free space under `filePath` (and `logPath` if it's the same disk), then watch the `Could not checkpoint` lines stop.

### A failed fsync

`mpp_fsync_failures_total` goes up and the write that needed the force fails. The server never trusts a file whose
force failed and rebuilds it from the write-ahead log at the next checkpoint. Find out why the disk refused (full,
failing, a network filesystem that lost its server) before relying on the node again.

### A stale or suspect index

When the startup log names collections with index work outstanding, or a query through an index misses documents a
scan finds, rebuild the indexes from the documents:

```json
{"type":"REINDEX","projectName":"shop","collectionName":"orders"}
```

`fieldNames` limits it to some fields ([`REINDEX`](protocol.md#reindex)).

### A damaged primary-key index

When every request to one collection fails with an error about its `pk.idx`, or a document that's on disk isn't found
by id, rebuild it from the page files:

```json
{"type":"REINDEX","projectName":"shop","collectionName":"orders","primaryKey":true}
```

If it answers `409-15`, restart the server and run it again.

### Dead-lettered trigger runs

A trigger that fails `triggerMaxAttempts` times is set aside with its last error.
[`LIST_TRIGGER_RUNS`](protocol.md#list_trigger_runs-admin-only) lists them. Fix the cause, then replay or discard each
one with [`RESOLVE_TRIGGER_RUN`](protocol.md#resolve_trigger_run-admin-only). They are deleted after
`triggerDeadLetterRetentionMs`, or past `triggerDeadLetterMaxRuns` per project.

### In-doubt distributed transactions

When a cluster transaction's coordinator dies, the other nodes resolve it if any of them knows the outcome. When none
does, `mpp_in_doubt_transactions` stays above zero and the transaction keeps its locks. Bring the coordinator back
if you can. Otherwise find it with [`LIST_TRANSACTIONS`](protocol.md#list_transactions-admin-only), decide the outcome
from its per-node status and force it with [`RESOLVE_TRANSACTION`](protocol.md#resolve_transaction-admin-only)
([clustering.md](clustering.md#transactions)).

### A refused start

See [The server refused to start](#the-server-refused-to-start).

### A lost admin password

There is no reset. The server only reads `defaultAdminPassword` while no admin exists. Another admin can set a new one
with [`SET_PASSWORD`](protocol.md#set_password), which is why the [checklist](#deployment-checklist) asks for a second
admin. Without one, dump the data as any user who can read it, start a new server on an empty folder with a new
`defaultAdminPassword` and restore.

## Running a cluster

A cluster node runs like a single server, plus what [clustering.md](clustering.md#operations-runbook) describes:

- every node runs the same release, with the same `clusterSecret` and one shared keystore for cluster TLS;
- `clusterExpectedSize` is the steady node count;
- `--dump` against a node reads what that node has applied;
- move a node's traffic away before removing it, since its peers wait `deadTimeoutMs` before taking over its
  collections.
