package org.ccg.mpp.simplejs.host;

import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.CompiledProcedureCache;
import org.ccg.mpp.ops.req.validations.NameValidations;
import org.ccg.mpp.simplejs.ModuleReferences;

public final class ProcedureModuleResolver implements ModuleResolver {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final CompiledProcedureCache compiledProcedures = IocContainer.get(CompiledProcedureCache.class);
    public static final String SPECIFIER_PREFIX = ModuleReferences.PROCEDURE_SPECIFIER_PREFIX;

    private final String scopedDatabase;
    private final boolean revealsSource;

    public ProcedureModuleResolver(String scopedDatabase) {
        this(scopedDatabase, true);
    }

    public ProcedureModuleResolver(String scopedDatabase, boolean revealsSource) {
        this.scopedDatabase = scopedDatabase;
        this.revealsSource = revealsSource;
    }

    @Override
    public ResolvedModule resolve(String specifier, String referrer) {
        final var name = ModuleReferences.procedureNameOf(specifier);
        if (scopedDatabase == null || !NameValidations.isValidProcedureName(name)) {
            return null;
        }
        final var definition = cache.getProcedure(scopedDatabase, name);
        if (definition == null || !definition.isEnabled() || definition.getSource() == null) {
            return null;
        }
        final var version = definition.getVersion();
        final var source = definition.getSource();
        if (!revealsSource) {
            return ResolvedModule.withHiddenSource(moduleId(name, version), source, SPECIFIER_PREFIX + name);
        }
        return new ResolvedModule(moduleId(name, version), source,
                compiledProcedures.get(scopedDatabase, name, version, source), SPECIFIER_PREFIX + name);
    }

    private String moduleId(String name, long version) {
        return "procedure:" + scopedDatabase + Globals.COLL_IDENTIFIER_SEPARATOR + name
                + Globals.COLL_IDENTIFIER_SEPARATOR + version;
    }
}
