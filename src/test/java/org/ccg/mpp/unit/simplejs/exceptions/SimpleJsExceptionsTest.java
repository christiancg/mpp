package org.ccg.mpp.unit.simplejs.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ccg.mpp.simplejs.exceptions.UnexpectedCharacterException;
import org.ccg.mpp.simplejs.exceptions.UnexpectedEndOfInputException;
import org.ccg.mpp.simplejs.exceptions.UnexpectedTokenException;
import org.ccg.mpp.simplejs.exceptions.UnterminatedCommentException;
import org.ccg.mpp.simplejs.exceptions.UnterminatedRegexException;
import org.ccg.mpp.simplejs.exceptions.UnterminatedStringException;
import org.ccg.mpp.simplejs.exceptions.UnterminatedTemplateException;
import org.junit.jupiter.api.Test;

public class SimpleJsExceptionsTest {
    @Test
    public void test_unexpected_character_message() {
        assertEquals("Unexpected character @ at position: 3", new UnexpectedCharacterException('@', 3).getMessage());
    }

    @Test
    public void test_unterminated_string_message() {
        assertEquals("Unterminated string starting at position: 5", new UnterminatedStringException(5).getMessage());
    }

    @Test
    public void test_unterminated_comment_message() {
        assertEquals("Unterminated comment starting at position: 2", new UnterminatedCommentException(2).getMessage());
    }

    @Test
    public void test_unterminated_regex_message() {
        assertEquals("Unterminated regular expression starting at position: 7",
                new UnterminatedRegexException(7).getMessage());
    }

    @Test
    public void test_unterminated_template_message() {
        assertEquals("Unterminated template literal starting at position: 0",
                new UnterminatedTemplateException(0).getMessage());
    }

    @Test
    public void test_unexpected_token_message() {
        assertEquals("Unexpected token } at index: 4", new UnexpectedTokenException("}", 4).getMessage());
    }

    @Test
    public void test_unexpected_end_of_input_message() {
        assertEquals("Unexpected end of input", new UnexpectedEndOfInputException().getMessage());
    }

    @Test
    public void test_unexpected_token_line_column_message() {
        assertEquals("Unexpected token } at line: 2, column: 5", new UnexpectedTokenException("}", 2, 5).getMessage());
    }

    @Test
    public void test_unexpected_end_of_input_line_column_message() {
        assertEquals("Unexpected end of input at line: 3, column: 1",
                new UnexpectedEndOfInputException(3, 1).getMessage());
    }
}
