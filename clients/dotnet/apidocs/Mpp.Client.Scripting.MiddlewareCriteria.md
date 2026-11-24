An exclusion rule of a `MiddlewareMatch`: it holds for an entrypoint when every criterion set holds.

At least one criterion must be set, since an empty rule would exclude every entrypoint.

## MiddlewareCriteria()
Creates a rule with no criterion set.

## Entrypoints
The names of the entrypoints the rule holds for, or null for any. Excluding by name is the narrowest rule, and a name stays excluded for an entrypoint saved later under it.

## Methods
The HTTP methods the rule holds for, or null for any.

## PathPrefix
The literal path prefix the rule holds for, or null for any. It also covers routes saved under it later.
