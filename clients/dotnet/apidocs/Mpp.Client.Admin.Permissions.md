What a user may do. Used to create a user and to replace a user's permissions.

An empty `Permissions` grants nothing. `Admin` makes the user a superadmin, who may do everything.

## Permissions()
Creates permissions that grant nothing.

## CollectionKey(string, string)
Builds the key of a collection in `Collections`, which is `project|collection`.

@param project the project name
@param collection the collection name
@returns the key

## Admin
True for a superadmin, who is not limited by the other permissions.

## Collections
Access per collection, keyed by `Permissions.CollectionKey`.

## Projects
Access per project, keyed by the project name.

## Global
The server-wide permissions.

## Scripts
Script access per project, keyed by the project name.
