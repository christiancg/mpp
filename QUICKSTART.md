# Quickstart

This gets an MPP server running on your machine, stores a document and reads it back, first with the client that
ships inside the server and then from your own code. It takes about five minutes.

MPP is experimental. Try it and build things with it, but don't put data in it that you can't afford to lose.

## 1. Start the server

Pick one of the three ways below. All of them listen on port `8989`.

The server won't start without a password for its admin user, which is called `admin`, so each option sets one. Use at
least 8 characters and avoid common passwords and the username, because the server refuses those too (see
[Bootstrap](docs/operations.md#bootstrap)).

### With the published Docker image

```bash
docker run -d --name mpp -p 8989:8989 \
  -e MPP_ADMIN_PASSWORD=<choose-a-strong-password> \
  -v mpp-data:/app/db \
  ghcr.io/christiancg/mpp:latest
```

### With Docker, built from source

You only need Git and Docker. The image builds the jar itself.

```bash
git clone https://github.com/christiancg/mpp.git
cd mpp
docker build -t mpp .
docker run -d --name mpp -p 8989:8989 \
  -e MPP_ADMIN_PASSWORD=<choose-a-strong-password> \
  -v mpp-data:/app/db \
  mpp
```

### With the jar

You need Java 27 or newer. Download `mpp-<version>.jar` from the
[latest release](https://github.com/christiancg/mpp/releases/latest), rename it to `mpp.jar`, and put the
password in an `mpp.cfg` next to it:

```bash
echo "defaultAdminPassword=<choose-a-strong-password>" > mpp.cfg
java -XX:+UseG1GC -XX:G1PeriodicGCInterval=60000 -Djdk.nio.maxCachedBufferSize=262144 -jar mpp.jar
```

To build the jar yourself, run `mvn clean package -DskipTests` in a clone (you need Maven and JDK 27). The jar ends
up in `target/mpp.jar`.

The data goes into `./db` and the logs into `./logs`, both relative to the folder you started it from.

### Check that it is up

The server is ready in about a second. With Docker, `docker ps` shows the container as `healthy` once the health check
has run. With the jar, ask the server directly. This needs no password and exits with `0` once the server is `READY`:

```bash
java -jar mpp.jar --health
```

## 2. Try it from the command line

The server jar is also a client. When its first argument starts with `--`, it connects to a server instead of starting
one, and it reads the password from `MPP_PASSWORD`:

```bash
export MPP_PASSWORD=<the-password-you-chose>
java -jar mpp.jar \
  --exec '{"type":"CREATE_PROJECT","projectName":"my_project"}' \
  --exec '{"type":"CREATE_COLLECTION","projectName":"my_project","collectionName":"people"}' \
  --exec '{"type":"SAVE","projectName":"my_project","collectionName":"people","object":{"_id":"user-1","name":"Alice","age":31}}' \
  --exec '{"type":"AGGREGATE","projectName":"my_project","collectionName":"people","aggregationSteps":[{"type":"FILTER","operator":{"fieldOperatorType":"GREATER_THAN","field":"age","value":30}}]}'
```

With Docker the client is already in the container, so replace `java -jar mpp.jar` with
`docker exec -e MPP_PASSWORD=<the-password-you-chose> mpp java -jar app.jar`.

Each `--exec` sends one request and prints its reply on its own line. The client stops at the first reply that is not
`OK`. For an interactive prompt use `--shell` instead (see [Client](README.md#client-shell-exec-dumprestore)).

Before you go further:

- A collection has to exist before you write to it. Saving into a missing one fails with `404-11`.
- `SAVE` inserts or updates. Saving a document whose `_id` already exists replaces it.
- An `_id` is a string of 1 to 64 letters, digits, `_` or `-`. Leave it out and the server generates one.
- Every query except a lookup by `_id` is an [aggregation pipeline](docs/protocol.md#aggregate) of steps such as `FILTER`,
  `SORT`, `LIMIT` and `GROUP_BY`.

## 3. Use it from your code

There are client libraries for Java, TypeScript/JavaScript, Python and .NET. Each one manages a pool of connections and
gives you a query builder, transactions and live queries. Here is the same example in two of them.

Python 3.11 or newer, `pip install mpp-client`:

```python
import os

from mpp import ClientSettings, Credentials, MppClient, Order, field

settings = ClientSettings(credentials=Credentials("admin", os.environ["MPP_PASSWORD"]))

with MppClient.connect(settings) as client:
    people = client.project("my_project").collection("people")
    people.save({"_id": "user-2", "name": "Bob", "age": 45})
    adults = people.query().filter(field("age") > 30).sort("age", Order.DESC).to_list()
    print(adults)
```

TypeScript or JavaScript on Node.js 22.12 or newer, `npm install mpp-client`:

```ts
import { field, MppClient, Order } from "mpp-client";

await using client = await MppClient.connect({
    credentials: { username: "admin", password: process.env.MPP_PASSWORD ?? "" },
});
const people = client.project("my_project").collection("people");
await people.save({ _id: "user-2", name: "Bob", age: 45 });
const adults = await people.query().filter(field("age").gt(30)).sort("age", Order.DESC).toArray();
console.log(adults);
```

Each library has its own guide, with installation, settings, typed collections, transactions and error handling:

| Language | Package | Guide |
|---|---|---|
| Java 17+ (and Spring Boot) | `io.github.christiancg:mpp-client` | [clients/java](clients/java/README.md) |
| TypeScript / JavaScript | `mpp-client` on npm | [clients/typescript](clients/typescript/README.md) |
| Python 3.11+ | `mpp-client` on PyPI | [clients/python](clients/python/README.md) |
| .NET 10+ | `Mpp.Client` on NuGet | [clients/dotnet](clients/dotnet/README.md) |

No library for your language? Any TCP client works: send one JSON request per line and read one JSON reply per line.
Authenticate first with [`AUTHENTICATE`](docs/protocol.md#authenticate).

## 4. Next steps

- [Configuration](docs/operations.md#configuration): memory, page size, timeouts and limits, all in `mpp.cfg`.
- [Indexes](docs/protocol.md#create_index): queries scan the collection until the fields they filter on are indexed.
- [Users and permissions](docs/protocol.md#users--permissions): create a user for your application instead of using `admin`.
- [TLS](docs/operations.md#tls--secure-connections): encrypt connections before exposing the server beyond your machine.
- [Backups](README.md#client-shell-exec-dumprestore): `--dump` and `--restore`. A dump reads each project from one
  consistent snapshot, so you can take it while your application keeps writing.
- [Guarantees](README.md#guarantees): what survives a crash or a power loss, and what doesn't.
- [Upgrading](docs/compatibility.md): when a new release can use your data folder as it is, and when it needs a dump
  and restore.
- [Scripting](docs/simplejs.md) and [clustering](docs/clustering.md), once the basics work for you.

## Stopping and cleaning up

```bash
docker stop mpp && docker rm mpp
docker volume rm mpp-data
```

The second line deletes your data. With the jar, stop the server with `Ctrl+C` (it shuts down cleanly) and delete the
`db` and `logs` folders to start over.
