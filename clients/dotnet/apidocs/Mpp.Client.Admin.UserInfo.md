A user as `LIST_USERS` describes it.

@param Username the user name
@param IsAdmin whether the user is a superadmin
@param GlobalPermissions the server-wide permissions
@param ProjectPermissions the access per project
@param CollectionPermissions the access per `project|collection`
@param ScriptPermissions the script access per project
@param OwnedProjects the projects the user owns
@param Raw the row as the server sent it

## UserInfo(string, bool, IReadOnlyList<string>, Document, Document, Document, IReadOnlyList<string>, Document)
Creates the description.

@param Username the user name
@param IsAdmin whether the user is a superadmin
@param GlobalPermissions the server-wide permissions
@param ProjectPermissions the access per project
@param CollectionPermissions the access per `project|collection`
@param ScriptPermissions the script access per project
@param OwnedProjects the projects the user owns
@param Raw the row as the server sent it

## CollectionPermissions
The access per `project|collection`.

## ProjectPermissions
The access per project.

## GlobalPermissions
The server-wide permissions.

## IsAdmin
Whether the user is a superadmin.

## OwnedProjects
The projects the user owns.

## Raw
The row as the server sent it.

## ScriptPermissions
The script access per project.

## Username
The user name.
