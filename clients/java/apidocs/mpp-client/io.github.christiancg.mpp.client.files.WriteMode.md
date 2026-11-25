How a write treats a file that already exists.

## REPLACE

Replaces the file's whole content, atomically: a reader sees the old content or the new one. Creates the file when it
is missing.

## CREATE

Creates the file, and fails with `409-20` (`FILE_EXISTS`) when the path already exists.

## APPEND

Adds the content at the end of the file, creating it when it is missing.
