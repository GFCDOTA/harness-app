package inspector.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import inspector.domain.GptConsult;
import inspector.domain.ServiceHealth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FRONTEIRA do painel do oraculo: saude dos servicos + historico de consultas ao GPT.
 * Mesma direcao unica do trace — dominio vira JSON, e a UI so consome.
 */
public final class OracleProjection {

    private final ObjectMapper mapper = new ObjectMapper();

    public String toJson(final List<ServiceHealth> health, final List<GptConsult> consults, final String consultsDir) {
        try {
            return this.mapper.writeValueAsString(toMap(health, consults, consultsDir));
        } catch (final JsonProcessingException ex) {
            throw new IllegalStateException("nao consegui serializar o painel do oraculo", ex);
        }
    }

    Map<String, Object> toMap(final List<ServiceHealth> health, final List<GptConsult> consults, final String consultsDir) {
        final var services = new ArrayList<Map<String, Object>>(health.size());
        for (final var service : health) {
            final var row = new LinkedHashMap<String, Object>();
            row.put("id", service.id());
            row.put("label", service.label());
            row.put("role", service.role());
            row.put("up", service.up());
            row.put("httpStatus", service.httpStatus());
            row.put("latencyMs", service.latencyMs());
            row.put("detail", service.detail());
            row.put("checkedAt", service.checkedAt());
            services.add(row);
        }

        final var rows = new ArrayList<Map<String, Object>>(consults.size());
        for (final var consult : consults) {
            final var row = new LinkedHashMap<String, Object>();
            row.put("id", consult.id());
            row.put("title", consult.title());
            row.put("when", consult.when());
            row.put("declaredWhen", consult.declaredWhen());
            row.put("sizeBytes", consult.sizeBytes());
            row.put("question", consult.question());
            row.put("answer", consult.answer());
            row.put("bothSides", consult.hasBothSides());
            rows.add(row);
        }

        final var out = new LinkedHashMap<String, Object>();
        out.put("health", services);
        out.put("consults", rows);
        out.put("consultsDir", consultsDir);
        out.put("upCount", health.stream().filter(ServiceHealth::up).count());
        out.put("serviceCount", health.size());
        return out;
    }
}
