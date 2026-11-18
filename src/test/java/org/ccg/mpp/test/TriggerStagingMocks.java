package org.ccg.mpp.test;

import static org.mockito.Mockito.mockStatic;

import org.ccg.mpp.ops.StagedTriggerRuns;
import org.ccg.mpp.ops.TriggerHelper;
import org.mockito.MockedStatic;

public final class TriggerStagingMocks {
    private TriggerStagingMocks() {
    }

    public static MockedStatic<TriggerHelper> mockTriggerHelper() {
        return mockStatic(TriggerHelper.class,
                invocation -> invocation.getMethod().getReturnType() == StagedTriggerRuns.class
                        ? StagedTriggerRuns.none()
                        : null);
    }
}
