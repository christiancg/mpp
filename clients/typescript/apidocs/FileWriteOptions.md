# FileWriteOptions

Options of a file write.

## mode

Optional: how to treat an existing file, `REPLACE` by default.

## createParents

Optional: whether to create missing parent folders, `true` by default.

## ifVersion

Optional: the version the file must still have, or `"0"` when it must not exist; anything else is refused with
`409-23`.
