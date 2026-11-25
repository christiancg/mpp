How `ProjectFiles.ListAsync` walks a folder.

## FileListOptions()
Creates options that list the folder's direct children from the start, in pages of the server's default size.

## After
The path to resume after, as a previous listing returned it, or null to start from the beginning.

## PageSize
How many entries each `LIST_FILES` request asks for, between 1 and 10000, or null for the server's default of 10000. The enumeration still walks every page.

## Recursive
Whether the folders below the listed one are walked too. False by default.
