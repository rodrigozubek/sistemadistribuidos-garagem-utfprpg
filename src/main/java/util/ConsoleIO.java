package util;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** O painel da IDE usa UTF-8; terminais nativos informam sua codificação. */
public final class ConsoleIO {
    private ConsoleIO() { }

    public static BufferedReader initialize() {
        Console console = System.console();
        Charset charset = console == null ? StandardCharsets.UTF_8 : console.charset();
        System.setOut(new PrintStream(System.out, true, charset));
        System.setErr(new PrintStream(System.err, true, charset));
        return new BufferedReader(new InputStreamReader(System.in, charset));
    }
}
