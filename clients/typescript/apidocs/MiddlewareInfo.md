# MiddlewareInfo

A middleware, as `Middlewares.list` returns it.

## name

Its name.

## phase

`INGRESS` or `EGRESS`.

## order

Its place within its phase.

## match

The entrypoints it wraps; missing when it wraps every one.

## procedureName

The procedure it runs.

## timeoutMs

Its time limit in milliseconds; `0` means no limit of its own.

## enabled

Whether it runs.

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
