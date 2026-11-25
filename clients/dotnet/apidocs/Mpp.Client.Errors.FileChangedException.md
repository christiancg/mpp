A file kept changing while `ProjectFiles.ReadAsync` or `ProjectFiles.DownloadAsync` read it window by window: its version moved between two windows on the first read and on every restart, or once when the destination stream cannot seek back. The client raises it; nothing is wrong with the server.

## FileChangedException()
Creates an exception with a default message.

## FileChangedException(string)
Creates an exception with a message.

@param message the message

## FileChangedException(string, Exception)
Creates an exception with a message and the exception that caused it.

@param message the message
@param innerException the cause, or null

## FileChangedException(string, string, Status?, string, Exception)
Creates an exception from a server refusal.

@param message the message
@param rawCode the server's error code such as `404-4`, or null
@param status the reply's status, or null
@param operation the operation that was refused, or null
@param innerException the cause, or null
