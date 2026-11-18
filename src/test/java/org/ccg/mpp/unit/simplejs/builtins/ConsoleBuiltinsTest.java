package org.ccg.mpp.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.ccg.mpp.simplejs.builtins.ConsoleBuiltins;
import org.ccg.mpp.simplejs.internal.Interpreter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class ConsoleBuiltinsTest {
    private final List<String> captured = new ArrayList<>();

    @AfterEach
    public void resetSink() {
        ConsoleBuiltins.setSink(System.out::println);
    }

    @Test
    public void test_console_log() {
        ConsoleBuiltins.setSink(captured::add);
        Interpreter.run("console.log('a', 1, true)");
        assertEquals(List.of("a 1 true"), captured);
    }

    @Test
    public void test_console_levels() {
        ConsoleBuiltins.setSink(captured::add);
        Interpreter.run("console.error('e'); console.warn('w'); console.info('i');");
        assertEquals(List.of("e", "w", "i"), captured);
    }
}
