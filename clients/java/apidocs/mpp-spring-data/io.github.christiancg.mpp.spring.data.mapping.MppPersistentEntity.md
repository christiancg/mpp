The metadata of one entity class.

@param <T> the entity type

## MppPersistentEntity(TypeInformation, String)

@param information the entity type
@param defaultProject the project used when the class names none

## collection()

@return the collection name
@throws org.springframework.data.mapping.MappingException when the name breaks MPP's naming rules

## project()

@return the project name
@throws org.springframework.data.mapping.MappingException when there is none or it breaks the naming rules

## requiredIdProperty()

@return the id property: the `@Id` one, else the one named `id`
@throws org.springframework.data.mapping.MappingException when the class has none

## idJavaWireName()

@return the JSON name the id property has before it is stored as `_id`

## transientWireNames()

@return the names of the `@Transient` fields, which are never stored
