One window of a file, as `ProjectFiles.readRange` and `readTextRange` read it.

## FileRange(String, String, long, long, byte[], boolean)

@param path the file's normalised path
@param version the file's version when the window was read
@param size the whole file's size in bytes
@param offset where the window starts, in bytes
@param content the window's bytes, copied
@param eof whether the window reaches the end of the file

## path()

@return the file's normalised path

## version()

@return the file's version when the window was read

## size()

@return the whole file's size in bytes

## offset()

@return where the window starts, in bytes

## content()

@return a copy of the window's bytes; empty when the offset is past the end

## length()

@return how many bytes the window holds

## text()

@return the window's bytes decoded as UTF-8

## eof()

@return whether the window reaches the end of the file
