package org.ccg.mpp.simplejs.host;

public interface ModuleResolver {
    ResolvedModule resolve(String specifier, String referrer);
}
