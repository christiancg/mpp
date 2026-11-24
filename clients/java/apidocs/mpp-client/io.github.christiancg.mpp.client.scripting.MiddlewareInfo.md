A middleware as listed.

@param name its name
@param phase `INGRESS` or `EGRESS`
@param order its place within its phase, lower first
@param match the entrypoints it wraps, empty when it wraps every entrypoint of the project
@param procedureName the procedure it runs
@param timeoutMs one run's timeout, 0 for no cap of its own
@param enabled whether it runs
@param version its version
@param definer whose authority it runs with
@param description its description
@param disabledReason why the server disabled it
@param raw the row as the server sent it
