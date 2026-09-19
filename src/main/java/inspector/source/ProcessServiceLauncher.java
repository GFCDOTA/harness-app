package inspector.source;

import inspector.domain.LaunchResult;
import inspector.domain.ServiceAction;
import inspector.domain.ServiceLauncher;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * ADAPTER — roda o comando declarado com ProcessBuilder.
 *
 * <p>Nao existe caminho aqui que aceite string de comando vinda da UI: o
 * {@link ServiceAction} chega pronto, montado no codigo. Isso e o que impede a
 * pagina de virar um shell.
 *
 * <p>Tambem NAO usa {@code powershell -ExecutionPolicy Bypass}: foi exatamente esse
 * padrao que o Defender flagou (ThreatID 2147941383) e que virou relaunch fantasma
 * na epoca do NOC.
 */
public final class ProcessServiceLauncher implements ServiceLauncher {

    private static final int TIMEOUT_SECONDS = 90;
    private static final int OUTPUT_CAP = 4000;

    @Override
    public LaunchResult run(final ServiceAction action) {
        final var startedAt = Instant.now().toString();
        try {
            ProcessBuilder pb = new ProcessBuilder(action.command());
            if (action.workingDir() != null && !action.workingDir().isBlank()) {
                pb.directory(new File(action.workingDir()));
            }
            pb.redirectErrorStream(true);
            Process p = pb.start();

            String output;
            try (var in = p.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            final var done = p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!done) {
                p.destroy();
                return new LaunchResult(action.serviceId(), false, null,
                        "passou de " + TIMEOUT_SECONDS + "s e foi interrompido:\n" + cap(output), startedAt);
            }
            final var code = p.exitValue();
            return new LaunchResult(action.serviceId(), code == 0, code, cap(output), startedAt);
        } catch (final IOException ex) {
            return new LaunchResult(action.serviceId(), false, null,
                    "nao consegui executar: " + ex.getMessage(), startedAt);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new LaunchResult(action.serviceId(), false, null, "interrompido", startedAt);
        }
    }

    private static String cap(final String text) {
        if (text == null) return "";
        final var trimmed = text.strip();
        return trimmed.length() <= OUTPUT_CAP
                ? trimmed : trimmed.substring(0, OUTPUT_CAP - 1) + "…";
    }
}
