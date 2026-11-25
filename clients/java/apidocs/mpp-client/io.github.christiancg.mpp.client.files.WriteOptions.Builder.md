Builds `WriteOptions`.

## mode(WriteMode)

@param mode what to do when the file exists, `REPLACE` by default
@return this builder

## createParents(boolean)

@param createParents whether missing parent folders are created, `true` by default
@return this builder

## ifVersion(String)

@param version the version the file must have now, as a write, a stat or a listing answered it
@return this builder

## ifAbsent()

The same as `ifVersion(WriteOptions.ABSENT)`: the file must not exist yet.

@return this builder

## build()

@return the options
