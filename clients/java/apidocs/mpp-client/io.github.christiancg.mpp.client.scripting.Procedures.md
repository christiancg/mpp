The stored procedures of one project. Obtained from `Project.procedures()`.

## Procedures(ClientContext, String)

Internal: use `Project.procedures()`.

@param context the client's internal context
@param project the project

## save(ProcedureDefinition)

@param procedure the procedure
@return its new version

## delete(String)

@param name the procedure

## list()

@return the procedures, without their source

## list(boolean)

@param includeSource whether to include each procedure's source
@return the procedures

## call(String)

@param name the procedure
@return its result and logs

## call(String, Document)

@param name the procedure
@param args the value it imports as `args`
@return its result and logs
