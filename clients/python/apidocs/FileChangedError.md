# FileChangedError

A whole-file read or download gave up because the file kept changing between its windows, or changed while it was being
downloaded into a stream that cannot seek. Raised by the client; it carries no error code.
