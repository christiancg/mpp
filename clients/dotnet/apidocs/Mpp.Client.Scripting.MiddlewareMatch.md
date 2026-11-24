The entrypoints a middleware wraps.

Every criterion set must hold, and a match with none set wraps every entrypoint of the project. An entrypoint is then left out when any `Exclude` rule holds for it.

## MiddlewareMatch()
Creates a match that wraps every entrypoint.

## Entrypoints
The names of the entrypoints to wrap, or null for any. The names need not exist yet.

## Exclude
At most 16 rules that each leave out the entrypoints they hold for, or null for none. Each rule must set at least one criterion.

## Methods
The HTTP methods to wrap, or null for any.

## PathPrefix
The literal path prefix to wrap, such as `/orders`, or null for any. It covers whole segments: `/orders` covers `/orders` and `/orders/{id}` but not `/orders-archive`.
