# FileWriteMode

How a write treats an existing file: `REPLACE` swaps it atomically, `CREATE` refuses to overwrite it (`409-20`) and
`APPEND` adds to its end, creating it when missing.
