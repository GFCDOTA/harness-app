package inspector.domain;

/**
 * O que se sabe de um serviço num instante. {@code up=false} com {@code detail}
 * dizendo o porquê — nunca um "ok" otimista quando a sonda não conseguiu falar.
 */
public record ServiceHealth(
        String id,
        String label,
        String role,
        boolean up,
        Integer httpStatus,
        Long latencyMs,
        String detail,
        String checkedAt
) {
    public ServiceHealth {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id é obrigatório");
    }
}
