package harness.cli;

import harness.config.HarnessConfig;
import harness.ui.ControlPlane;

import java.nio.file.Paths;
import java.util.Arrays;

/**
 * O control plane SEM janela — mesmo caminho, sem JavaFX.
 *
 * <p>Existe por dois motivos práticos:
 *
 * <ul>
 *   <li><b>prova reproduzível</b>: dá para demonstrar comando → modelo → tool → gate
 *       → trace num terminal, e colar a saída num handoff. Print de janela não é
 *       evidência que outra sessão consiga repetir;
 *   <li><b>diagnóstico</b>: quando algo quebra, isolar "é o agente" de "é a UI"
 *       custa um comando em vez de uma investigação.</li>
 * </ul>
 *
 * <p>É o MESMO {@link ControlPlane} que a janela usa — não um caminho paralelo que
 * funciona aqui e mente lá.
 *
 * <pre>
 *   mvn -q exec:java -Dexec.mainClass=harness.cli.HarnessCli \
 *       -Dexec.args="move a escrivaninha 30 cm para a esquerda"
 * </pre>
 */
public final class HarnessCli {

    private HarnessCli() {
    }

    public static void main(final String[] args) {
        final var config = HarnessConfig.load(Paths.get("."));
        System.out.println("projeto      : " + config.project());
        System.out.println("pipeline     : " + config.pipelineRepo());
        System.out.println("modelo       : " + config.agentModel() + " @ " + config.ollamaUrl());
        System.out.println();

        try (final var plane = new ControlPlane(config)) {
            System.out.println("== serviços ==");
            for (final var health : plane.services().status()) {
                System.out.printf("  %-8s %-5s %s%n", health.id(),
                        health.up() ? "UP" : "DOWN", health.detail());
            }

            System.out.print("\nsubindo o capability host… ");
            final var started = plane.start();
            System.out.println(started ? "ok" : "FALHOU");
            if (!started) {
                System.err.println("sem capability host não há operação. Verifique o venv do pipeline.");
                System.exit(2);
            }

            if (args.length == 0) {
                System.out.println("\nnenhum comando. Uso: HarnessCli \"<comando em português>\"");
                return;
            }

            final var command = String.join(" ", Arrays.asList(args));
            System.out.println("\n== comando ==\n  " + command + "\n");

            final var startedAt = System.nanoTime();
            final var outcome = plane.command(command);
            final var elapsed = (System.nanoTime() - startedAt) / 1_000_000_000.0;

            System.out.printf("== desfecho: %s (%.1fs) ==%n", outcome.status(), elapsed);
            System.out.println("  " + outcome.summary());
            System.out.println("  trace: " + outcome.traceId()
                    + "  ·  desfazível: " + outcome.undoAvailable());

            if (!outcome.actions().isEmpty()) {
                System.out.println("\n== o que rodou de verdade ==");
                for (final var action : outcome.actions()) {
                    System.out.printf("  %s %-16s %s%n", action.ok() ? "OK  " : "FALHA",
                            action.tool(), action.detail());
                }
            }
            for (final var report : outcome.gateResults()) {
                System.out.println("\n== gates ==");
                System.out.println("  " + report.get("roomId") + " -> " + report.get("overall"));
                if (report.get("gates") instanceof java.util.Map<?, ?> gates) {
                    gates.forEach((name, data) -> System.out.println("    " + name + ": "
                            + (data instanceof java.util.Map<?, ?> m ? m.get("result") : data)));
                }
            }
            // Só CLEAN e ANSWERED são desfecho bom. O resto sai diferente de zero de
            // propósito: um script que chama isto precisa poder falhar.
            System.exit(switch (outcome.status()) {
                case CLEAN, ANSWERED -> 0;
                default -> 1;
            });
        }
    }
}
