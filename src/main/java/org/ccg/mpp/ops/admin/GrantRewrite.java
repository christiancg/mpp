package org.ccg.mpp.ops.admin;

import org.ccg.mpp.ops.AdminOperationHelper.RewriteVersion;

public sealed interface GrantRewrite permits GrantRewrite.Keep, GrantRewrite.Restamp {
    GrantRewrite KEEP = new Keep();

    RewriteVersion version();

    record Keep() implements GrantRewrite {
        @Override
        public RewriteVersion version() {
            return RewriteVersion.KEEP;
        }
    }

    record Restamp(RestampedRecords into) implements GrantRewrite {
        @Override
        public RewriteVersion version() {
            return RewriteVersion.RESTAMP;
        }
    }
}
