How `ProjectFiles.list` lists.

@param recursive whether the listing covers the folder's whole subtree rather than its direct children
@param pageSize how many entries one request asks for, at most `MAX_PAGE_SIZE`; empty for the server's default
@param startAfter a path to start after, in path order

## MAX_PAGE_SIZE

The most entries the server answers in one page, 10000.

## defaults()

@return the direct children, in pages of the server's default size, from the first

## builder()

@return a builder holding the defaults
