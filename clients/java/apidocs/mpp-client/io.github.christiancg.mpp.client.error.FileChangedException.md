A file kept changing while `ProjectFiles.read` read it, or changed while `ProjectFiles.download` wrote it to a stream.
It carries no server code: every request succeeded, but the windows belong to different versions of the file.

## FileChangedException(String, int)

@param path the file
@param attempts how many times it was read before giving up

## path()

@return the file
