Spring Data's metadata about MPP entities: their id property, collection, database and field
names. Conversion itself goes through the client's Jackson codec.

## MppMappingContext(String)

@param defaultDatabase the database of entities that name none, or `null`

## defaultDatabase()

@return the database of entities that name none
