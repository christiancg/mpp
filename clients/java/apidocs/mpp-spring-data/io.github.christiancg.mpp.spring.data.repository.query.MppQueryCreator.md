Turns a derived query method's parts into an MPP filter.

## MppQueryCreator(PartTree, ParameterAccessor, MppOperations, Class)

@param tree the parsed method name
@param parameters the call's arguments
@param operations resolves field paths
@param type the entity class

## validate(PartTree, String, MppOperations, Class)

Refuses, when the repository is created, a method MPP cannot answer.

@param tree the parsed method name
@param methodName the method
@param operations resolves field paths
@param type the entity class
