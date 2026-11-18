package org.ccg.mpp.simplejs.nodes;

public class ImportNamespaceSpecifier extends JsNode {
    private final Identifier local;

    public ImportNamespaceSpecifier(Identifier local) {
        this.local = local;
    }

    public Identifier getLocal() {
        return local;
    }

    @Override
    public NodeType getType() {
        return NodeType.IMPORT_NAMESPACE_SPECIFIER;
    }
}
