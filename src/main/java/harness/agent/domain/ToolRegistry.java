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
            return Admission.rejected(
                    "UNKNOWN_TOOL",
                    "capability '" + call.tool() + "' não existe" + hint
                            + ". Disponíveis: " + String.join(", ", this.tools.keySet()));
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
}
