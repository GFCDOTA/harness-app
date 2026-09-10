package inspector.domain;

import java.util.List;

/**
 * Uma acao que o FELIPE pode disparar para um servico — sempre por clique, nunca
 * por deteccao automatica.
 *
 * <p>Esta e a linha que preserva a licao do NOC: o que custou caro foi ressurreicao
 * AUTOMATICA (watchdog, Scheduled Task, respawn em loop, PowerShell que o Defender
 * flagava). Um botao que uma pessoa aperta e outra coisa: e deliberado, atendido e
 * acontece uma vez. Este app NAO reinicia nada sozinho, NAO tenta de novo, e NAO
 * observa "caiu" para agir.
 *
 * <p>O comando e DECLARADO no codigo, nunca montado a partir de texto vindo da UI:
 * a pagina so pede um id conhecido.
 */
public record ServiceAction(String serviceId, String label, List<String> command, String workingDir) {

    public ServiceAction {
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId e obrigatorio");
        }
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("comando vazio para " + serviceId);
        }
        command = List.copyOf(command);
    }
}
