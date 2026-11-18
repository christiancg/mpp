package org.ccg.mpp.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.condition.EnabledIf;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@EnabledIf(value = "org.ccg.mpp.test.PlatformSupport#directorySync", disabledReason = "this platform cannot force a"
        + " folder to the device, so the server runs it with syncWrites=false and never takes this synced path")
public @interface RequiresDirectorySync {
}
