# MiddlewareRule

A set of entrypoint criteria. Every criterion given must hold.

## methods

Optional: the HTTP methods it holds for.

## pathPrefix

Optional: the route prefix it holds for, made of literal segments and covering whole segments, so `/orders` covers
`/orders` and `/orders/{id}` but not `/orders-archive`.

## entrypoints

Optional: the entrypoint names it holds for. They don't need to exist yet.
