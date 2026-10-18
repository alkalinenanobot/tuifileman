package org.ds.tuifileman.cli;

import org.ds.tuifileman.cli.Console.Style;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/** Questions on the terminal. Asked on stderr and answered on stdin, so stdout stays clean for data. */
final class Prompt {
    private static BufferedReader reader;

    private Prompt() { }

    /**
     * Shows the question and returns the trimmed answer, or null at end of input. Escape-code colours come
     * from the stream, so a plain terminal gets plain text.
     */
    static String ask(Console.Out err, String question, String choices) {
        err.write(err.style(Style.BOLD, question) + " " + err.style(Style.MUTED, choices) + " ");
        try {
            if (reader == null) {
                reader = new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset()));
            }
            String line = reader.readLine();
            return line == null ? null : line.strip();
        } catch (IOException e) {
            return null;
        }
    }
}
