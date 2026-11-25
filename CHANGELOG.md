# Changelog

Every notable change to the MPP server, its Docker image and its bundled command-line client. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow [Semantic Versioning](https://semver.org/),
`0.x` for now, so anything may change in a minor release (see [docs/compatibility.md](docs/compatibility.md)).

Each entry names the wire `protocolVersion` and the `storageFormatVersion`, and says when either one moved. A new
storage format means a dump and restore, never just a jar swap. The client libraries under
[`clients/`](clients/README.md) are versioned and released on their own.

## [Unreleased]

Protocol version 2, storage format version 2: both moved.

### Changed

- MPP is now the Micro Project Engine, and the unit a collection lives in is a **project**, no longer a database.
  Everything that named a database names a project:
  - operations `CREATE_PROJECT`, `DROP_PROJECT`, `LIST_PROJECTS`, `SET_PROJECT_OWNERS` and `GET_PROJECT_STATS`, and
    the `projectName` field on every request;
  - the `projects` list of `LIST_PROJECTS`, the users' `projectPermissions` and `ownedProjects`, the
    `project|collection` collection-permission keys and the `CREATE_PROJECT`/`DROP_PROJECT` global permissions;
  - error codes `PROJECT_NOT_FOUND` (`404-4`), `PROJECT_ALREADY_EXISTS` (`409-2`),
    `TOO_MANY_COLLECTIONS_FOR_PROJECT` (`400-40`), `TOO_MANY_PROJECTS` (`400-41`), `ERROR_CREATING_PROJECT`,
    `ERROR_UPDATING_PROJECT_OWNERS`, `ERROR_DROPPING_PROJECT` and `ERROR_LISTING_PROJECTS` (`500-11` to `500-14`);
    the numbers are unchanged;
  - config keys `maxProjects`, `maxCollectionsPerProject`, `maxProceduresPerProject`,
    `maxConcurrentScriptsPerProject` and `scheduleMaxPerProject`;
  - the `mpp_projects` metric, the client's `--projects` dump flag, the scripts' `db.listProjects()`, and the
    `project` field of trigger events, run rows and the run directories;
  - the client libraries' `Project` type, `client.project(...)` and `listProjects()`, and Spring's
    `@MppEntity(project = ...)` and `mpp.project`.
- The `admin` metadata keeps its projects in `admin/projects`. A folder written by an earlier release, stamped or not,
  is refused at startup. A dump taken by 0.1.0 still uses the old operation and field names.

## [0.1.0] - 2026-11-17

The first release. Protocol version 1, storage format version 1.

### Added

- Project files: every project has a file tree of its own in `filesPath` (default `files`, the `/app/files` volume in
  Docker), kept apart from the data folder (`filePath`). Operations `WRITE_FILE` (replace, create or append, conditional on a
  version), `READ_FILE` (windowed), `STAT_FILE`, `LIST_FILES` (paged), `DELETE_FILE`, `MOVE_FILE` and
  `MAKE_DIRECTORY`, gated by the project's `READ` and `READ_WRITE` grants; error codes `400-51`, `400-52`, `404-14`,
  `409-20` to `409-23`, `413-2`, `413-3`, `500-42` and `500-43`; config keys `filesPath`, `filesMaxFileSize`,
  `filesMaxReadBytes`, `filesMaxBytesPerProject` and `filesMaxEntriesPerProject`; `files` in `GET_PROJECT_STATS`.
  Each project's manifest is its commit log, so a crash never leaves half a file. Under clustering one node per
  project coordinates its file changes and replicates them to a majority, and file anti-entropy repairs the rest
  (see [docs/files.md](docs/files.md)).
- Storage: documents as newline-delimited JSON in paged files that reuse freed space and vacuum in place, a
  write-ahead log per collection with group commit, and durable writes (`syncWrites=true`) synced before they are
  acknowledged. Startup replays the log and finishes interrupted work, so a crash or a power loss never needs a manual
  repair.
- Queries: an aggregation pipeline (`FILTER`, `MAP`, `GROUP_BY`, `JOIN`, `SORT`, `DISTINCT`, `REDUCE`, `COUNT`,
  `SKIP`, `LIMIT`) plus `FIND_BY_ID`, single-field indexes that a query can combine, counts answered from the index,
  explain and analyze with index suggestions, opt-in dirty reads and live queries (`LISTEN`).
- Types: dates and times, geo points with `distance` and `within`, and vectors with an approximate `nearest` search
  (`exact:true` scans instead).
- Integrity: JSON Schema (draft 2020-12) validation per collection, transactions across collections, users with
  database and collection permissions, and database owners.
- Scripting: an embedded JavaScript engine for ad-hoc scripts, stored procedures, before and after triggers, scheduled
  procedures and pipeline operators, sandboxed and limited in time and memory.
- Clustering: fully replicated nodes without a master, an owner per collection, quorum writes, anti-entropy,
  transactions across nodes and scripts placed by load, with optional mutual TLS between nodes.
- Operations:
  - `SERVER_INFO` with the release, `protocolVersion` and `storageFormatVersion`.
  - `GET_DATABASE_STATS`, `REINDEX` and standard error codes.
  - An opt-in Prometheus `/metrics` endpoint (`metricsPort`) with request counts and latencies per operation, error
    codes, connections, fsyncs, cache, queues and data totals.
  - Caching bounded in memory and sized from the heap.
  - TLS for clients.
  - Graceful shutdown.
- Backups: point-in-time snapshots (`START_SNAPSHOT`) that don't block writes, which `--dump` reads through.
- Command-line client: the jar is also its own client, with `--shell`, `--exec`, `--dump` and `--restore` against a
  local or remote server, with or without TLS.
- Storage format stamp: `{filePath}/format.json` records the storage format, and the server refuses to start on a
  folder written in another one instead of misreading it.
- A wire protocol reference for client authors, [docs/protocol.md](docs/protocol.md), with the framing, the request
  and response envelopes, how replies are matched to requests, the retry rules, and every operation, permission and
  error code.
- An operations guide, [docs/operations.md](docs/operations.md), with a deployment checklist, every configuration key,
  sizing, security hardening, metrics and suggested alerts, the log lines that need action, backups and restore
  drills, and a recovery runbook.
- A Docker image for `linux/amd64` and `linux/arm64`, with a trimmed JRE and a health check, published to
  `ghcr.io/christiancg/mpp`.
- Windows support: the jar runs on Windows as well as Linux and macOS, with the same data files and wire protocol
  (lines end in a line feed everywhere). See [On Windows](docs/operations.md#on-windows).

[Unreleased]: https://github.com/christiancg/mpp/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/christiancg/mpp/releases/tag/v0.1.0
