How `ProjectFiles.WriteAsync` and `ProjectFiles.WriteTextAsync` write a file.

## FileWriteOptions()
Creates options that replace the file and create its missing parent folders.

## CreateParents
Whether the missing parent folders are created. True by default; false fails with `404-14` when the parent folder does not exist.

## IfVersion
The version the file must have for the write to go ahead, `"0"` for a file that must not exist yet, or null to write whatever is there. A mismatch fails with `409-23`.

## Mode
How a file that already exists is treated. `Replace` by default.
