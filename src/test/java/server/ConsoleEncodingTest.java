package server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ConsoleEncodingTest {
    private String run(String mainClass, String input, String... args) throws Exception {
        return runWithEncoding(mainClass, input, null, StandardCharsets.UTF_8, args);
    }

    private String runWithEncoding(String mainClass, String input, String consoleEncoding,
                                   Charset charset, String... args) throws Exception {
        return runWithProperty(mainClass, input, "garage.consoleEncoding", consoleEncoding, charset, args);
    }

    private String runWithProperty(String mainClass, String input, String consoleProperty, String consoleEncoding,
                                   Charset charset, String... args) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        // Simula a saída Windows que causava caracteres inválidos no painel UTF-8.
        command.add("-Dstdout.encoding=windows-1252");
        command.add("-Dstderr.encoding=windows-1252");
        if (consoleEncoding != null) command.add("-D" + consoleProperty + "=" + consoleEncoding);
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(mainClass);
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            try (var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(charset));
            }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Processo não terminou");
            assertEquals(0, process.exitValue());
            return new String(process.getInputStream().readAllBytes(), charset);
        } finally {
            process.destroyForcibly();
        }
    }

    @Test void clientMenuUsesUtf8EvenWithWindowsOutputEncoding() throws Exception {
        String output = run("client.Client", "9\n0\n", "127.0.0.1", "5000");
        assertTrue(output.contains("Cadastrar usuário"), output);
        assertTrue(output.contains("Opção: "), output);
        assertTrue(output.contains("Opção inválida."), output);
        assertFalse(output.contains("\uFFFD"), output);
    }

    @Test void serverErrorsUseUtf8EvenWithWindowsOutputEncoding() throws Exception {
        String output = run("server.Server", "", "0");
        assertTrue(output.contains("Porta inválida"), output);
        assertFalse(output.contains("\uFFFD"), output);
    }

    @Test void serverErrorsMatchNativeConsoleEncoding() throws Exception {
        Charset nativeCharset = Charset.forName(System.getProperty("native.encoding", Charset.defaultCharset().name()));
        String output = runWithProperty("server.Server", "", "garage.server.consoleEncoding", "native", nativeCharset, "0");
        assertTrue(output.contains("Porta inválida"), output);
    }

    @Test void clientMenuSupportsAnExplicitConsoleEncoding() throws Exception {
        String output = runWithEncoding("client.Client", "9\n0\n", "windows-1252",
                Charset.forName("windows-1252"), "127.0.0.1", "5000");
        assertTrue(output.contains("Cadastrar usuário"), output);
        assertTrue(output.contains("Opção inválida."), output);
    }

    @Test void serverEncodingDoesNotChangeClientMenu() throws Exception {
        for (String mainClass : java.util.List.of("client.Client",
                "br.edu.utfpr.sistemasdistribuidos.sistemagaragem.SistemaGaragem")) {
            String output = runWithProperty(mainClass, "9\n0\n", "garage.server.consoleEncoding", "native",
                    StandardCharsets.UTF_8, "127.0.0.1", "5000");
            assertTrue(output.contains("Cadastrar usuário"), output);
            assertTrue(output.contains("Opção: "), output);
            assertTrue(output.contains("Opção inválida."), output);
            assertFalse(output.contains("\uFFFD"), output);
        }
    }
}
