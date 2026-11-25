How `ProjectFiles.write` and `writeText` write.

@param mode what to do when the file exists
@param createParents whether missing parent folders are created; without it a missing parent fails with `404-14`
@param ifVersion the version the file must have now, or the write fails with `409-23` (`FILE_VERSION_CONFLICT`);
`ABSENT` means the file must not exist

## ABSENT

The `ifVersion` of a file that must not exist yet, `"0"`.

## defaults()

@return `REPLACE`, creating missing parents, unconditionally

## builder()

@return a builder holding the defaults
