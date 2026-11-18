Creates the mapping context (scanning the application's packages for `@MppEntity`), the
`MppTemplate` and the `MppTransactionManager`, and turns on `@Transactional`.

## TEMPLATE_BEAN

The template bean name, `mppTemplate`.

## TRANSACTION_MANAGER_BEAN

The transaction manager bean name, `mppTransactionManager`.

## mppMappingContext(MppProperties, BeanFactory)

@param properties supplies the default database
@param beanFactory supplies the packages to scan
@return the mapping context

## mppTemplate(MppClient, MppMappingContext)

@param client the client
@param mappingContext the mapping context
@return the template

## mppTransactionManager(MppClient)

@param client the client
@return the transaction manager
