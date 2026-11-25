How a write treats a file that already exists.

## Append
`APPEND`: adds the content at the end of the file, creating it when it is missing.

## Create
`CREATE`: writes a new file, and fails with `409-20` when the path already exists.

## Replace
`REPLACE`: replaces the whole file, or creates it. Readers see the old content or the new, never a mix.
