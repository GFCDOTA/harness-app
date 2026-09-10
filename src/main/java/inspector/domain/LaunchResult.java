package inspector.domain;

/**
 * O que aconteceu quando o Felipe apertou o botao. Sem retentativa: se falhou, a UI
 * mostra o porque e ele decide — e a decisao continua sendo dele.
 */
public record LaunchResult(
        String serviceId,
        boolean ok,
        Integer exitCode,
        String output,
        String startedAt
) {
    public LaunchResult {
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId e obrigatorio");
        }
        output = output == null ? "" : output;
    }
}
