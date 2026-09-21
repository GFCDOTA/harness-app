package harness.agent.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A tabela de capabilities do lado do Harness, e o PORTÃO por onde toda chamada
 * passa.
 *
 * <p>Os schemas vêm do capability host — fonte ÚNICA. Duplicar a declaração aqui
 * criaria dois contratos que divergem em silêncio (a dívida que o envelope do
 * Inspector já pagou uma vez entre repositórios).
 *
 * <p>O que este objeto acrescenta é governança: tool desconhecida não vira comando,
 * e risco HIGH não roda sem confirmação humana.
 */
public final class ToolRegistry {

    private final Map<String, ToolSpec> tools = new LinkedHashMap<>();
    private final List<UnsupportedCapability> unsupported;

    public ToolRegistry(final List<ToolSpec> specs, final List<UnsupportedCapability> unsupported) {
        for (final var spec : specs) {
            this.tools.put(spec.name(), spec);
        }
        this.unsupported = unsupported == null ? List.of() : List.copyOf(unsupported);
    }

    public List<ToolSpec> all() {
        return List.copyOf(this.tools.values());
    }

    public List<UnsupportedCapability> unsupported() {
        return this.unsupported;
    }

    public Optional<ToolSpec> find(final String name) {
        return Optional.ofNullable(this.tools.get(name));
    }

    public boolean isEmpty() {
        return this.tools.isEmpty();
    }

    /**
     * Decide o que fazer com uma chamada ANTES de executá-la.
     *
     * @param autoApproveHigh política do ambiente: {@code true} só quando o Felipe
     *                        já disse "pode" para esta operação específica.
     */
    public Admission admit(final ToolCall call, final boolean autoApproveHigh) {
        final var spec = this.tools.get(call.tool());
        if (spec == null) {
            final var hint = this.unsupported.stream()
                    .filter(candidate -> candidate.name().equals(call.tool()))
                    .map(candidate -> " (" + candidate.reason() + ")")
                    .findFirst().orElse("");
            // Sugestão DIRIGIDA antes da lista. Despejar 35 nomes não ajuda: o
            // modelo ignora a lista e chuta de novo — rodando o app ele tentou
            // `open_skp` e `save_skp` quatro vezes, sendo que a real é
            // `open_skp_in_sketchup`. Um "você quis dizer X?" ele usa.
            final var close = closestTools(call.tool());
            final var didYouMean = close.isEmpty() ? ""
                    : " Você quis dizer: " + String.join(", ", close) + "?";
            return Admission.rejected(
                    "UNKNOWN_TOOL",
                    "capability '" + call.tool() + "' não existe" + hint + "."
                            + didYouMean
                            + " Disponíveis: " + String.join(", ", this.tools.keySet()));
        }
        if (!spec.implemented()) {
            final var reason = this.unsupported.stream()
                    .filter(candidate -> candidate.name().equals(call.tool()))
                    .map(UnsupportedCapability::reason)
                    .findFirst().orElse("capability ainda nao implementada");
            return Admission.rejected("CAPABILITY_MISSING",
                    "capability '" + call.tool() + "' ainda nao esta implementada: " + reason);
        }
        if (spec.needsConfirmation() && !autoApproveHigh) {
            return Admission.needsConfirmation(spec,
                    "'" + spec.name() + "' é risco HIGH e precisa de confirmação humana");
        }
        return Admission.allowed(spec);
    }

    /** Veredito do portão. */
    public record Admission(Verdict verdict, ToolSpec spec, String code, String message) {

        public enum Verdict { ALLOWED, REJECTED, NEEDS_CONFIRMATION }

        static Admission allowed(final ToolSpec spec) {
            return new Admission(Verdict.ALLOWED, spec, null, null);
        }

        static Admission rejected(final String code, final String message) {
            return new Admission(Verdict.REJECTED, null, code, message);
        }

        static Admission needsConfirmation(final ToolSpec spec, final String message) {
            return new Admission(Verdict.NEEDS_CONFIRMATION, spec, "NEEDS_CONFIRMATION", message);
        }

        public boolean allowed() {
            return this.verdict == Verdict.ALLOWED;
        }
    }

    /**
     * Tools registradas mais parecidas com o nome que o modelo inventou.
     *
     * <p>Critério deliberadamente simples e determinístico: nome registrado que
     * CONTÉM o chute, ou cujo chute contém o registrado. `open_skp` acha
     * `open_skp_in_sketchup`; `save_skp` acha `apply_to_skp` por prefixo comum.
     * Nada de distância de edição — sugestão errada confunde mais que a lista.
     */
    private List<String> closestTools(final String guess) {
        if (guess == null || guess.isBlank()) return List.of();
        final var needle = guess.toLowerCase();
        final var byContainment = this.tools.keySet().stream()
                .filter(name -> name.contains(needle) || needle.contains(name))
                .limit(3)
                .toList();
        if (!byContainment.isEmpty()) return byContainment;
        // senão: mesmo prefixo até o primeiro `_` (save_skp -> save_snapshot)
        final var head = needle.contains("_") ? needle.substring(0, needle.indexOf('_')) : needle;
        if (head.length() < 3) return List.of();
        return this.tools.keySet().stream()
                .filter(name -> name.startsWith(head + "_"))
                .limit(3)
                .toList();
    }
}
