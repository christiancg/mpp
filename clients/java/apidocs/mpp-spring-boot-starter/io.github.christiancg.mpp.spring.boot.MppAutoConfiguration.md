Creates the `ClientSettings` and the `MppClient` (closed with the context) unless the application
defines its own.

## mppClientSettings(MppProperties, ObjectProvider, ObjectProvider)

@param properties the `mpp.*` properties
@param objectMapper Boot's Jackson mapper, when there is exactly one
@param customizers applied in order
@return the settings

## mppClient(ClientSettings)

@param settings the settings
@return the connected client
@throws org.springframework.dao.DataAccessResourceFailureException when the server cannot be reached or refuses the login
