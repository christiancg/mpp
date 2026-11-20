Builds `Permissions`.

## admin(boolean)

@param admin `true` for a superadmin
@return this builder

## global(GlobalPermission...)

@param permissions server-wide permissions to add
@return this builder

## project(String, DataPermission)

@param project the project
@param permission the access
@return this builder

## collection(String, String, DataPermission)

@param project the project
@param collection the collection
@param permission the access
@return this builder

## scripts(String, ScriptPermission)

@param project the project
@param permission the script access
@return this builder

## build()

@return the permissions
