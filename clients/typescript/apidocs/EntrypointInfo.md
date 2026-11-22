# EntrypointInfo

An entrypoint, as `Entrypoints.list` returns it.

## name

Its name.

## method

The HTTP method it answers, upper case.

## path

Its route template.

## url

The path to request on the server's `httpPort`.

## procedureName

The procedure it runs.

## timeoutMs

Its time limit in milliseconds; `0` means the server's default.

## enabled

Whether it answers.

## version

Its version.

## definer

The user it runs as.

## description

Its description.

## disabledReason

Why the server disabled it, when it did.

## raw

The row as the server sent it.
