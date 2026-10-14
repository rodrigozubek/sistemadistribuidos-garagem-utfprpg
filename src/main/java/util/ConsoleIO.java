package util;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Configura a entrada e a saída conforme a codificação do console. */
public final class ConsoleIO {
    private ConsoleIO() { }

    public static BufferedReader initialize() {
        Console console = System.console();
        String encoding = System.getProperty("garage.consoleEncoding");
        Charset charset;
        if ("native".equalsIgnoreCase(encoding)) {
            charset = Charset.forName(System.getProperty("native.encoding", Charset.defaultCharset().name()));
        } else if (encoding != null) {
            charset = Charset.forName(encoding);
        } else {
            charset = console == null ? StandardCharsets.UTF_8 : console.charset();
        }
        System.setOut(new PrintStream(System.out, true, charset));
        System.setErr(new PrintStream(System.err, true, charset));
        return new BufferedReader(new InputStreamReader(System.in, charset));
    }
}
