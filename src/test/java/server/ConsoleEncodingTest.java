package server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ConsoleEncodingTest {
    private String run(String mainClass, String input, String... args) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        // Simula a saída Windows que causava caracteres inválidos no painel UTF-8.
        command.add("-Dstdout.encoding=windows-1252");
        command.add("-Dstderr.encoding=windows-1252");
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(mainClass);
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            try (var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Processo não terminou");
            assertEquals(0, process.exitValue());
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
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
}
