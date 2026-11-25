# FileWriteMode

How `ProjectFiles.write` treats an existing file: `REPLACE` swaps it atomically, `CREATE` refuses it, `APPEND` adds to
its end (and creates a missing file).
