Builds `ListOptions`.

## recursive(boolean)

@param recursive whether to list the whole subtree, `false` by default
@return this builder

## pageSize(int)

@param pageSize how many entries one request asks for, 1 to `ListOptions.MAX_PAGE_SIZE`
@return this builder

## startAfter(String)

@param path the path to start after, such as the last one a previous listing returned
@return this builder

## build()

@return the options
@throws IllegalArgumentException when the page size is out of range
