The scheduled procedures of one project. Obtained from `Project.schedules()`.

## Schedules(ClientContext, String)

Internal: use `Project.schedules()`.

@param context the client's internal context
@param project the project

## save(ScheduleDefinition)

@param schedule the schedule
@return its new version

## delete(String)

@param name the schedule

## list()

@return the schedules, without their arguments

## list(boolean)

@param includeArgs whether to include each schedule's arguments
@return the schedules
