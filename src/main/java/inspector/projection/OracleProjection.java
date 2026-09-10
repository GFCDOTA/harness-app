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

    public String toJson(List<ServiceHealth> health, List<GptConsult> consults, String consultsDir) {
        try {
            return mapper.writeValueAsString(toMap(health, consults, consultsDir));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("nao consegui serializar o painel do oraculo", e);
        }
    }

    Map<String, Object> toMap(List<ServiceHealth> health, List<GptConsult> consults, String consultsDir) {
        List<Map<String, Object>> hs = new ArrayList<>(health.size());
        for (ServiceHealth h : health) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.id());
            m.put("label", h.label());
            m.put("role", h.role());
            m.put("up", h.up());
            m.put("httpStatus", h.httpStatus());
            m.put("latencyMs", h.latencyMs());
            m.put("detail", h.detail());
            m.put("checkedAt", h.checkedAt());
            hs.add(m);
        }

        List<Map<String, Object>> cs = new ArrayList<>(consults.size());
        for (GptConsult c : consults) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("title", c.title());
            m.put("when", c.when());
            m.put("declaredWhen", c.declaredWhen());
            m.put("sizeBytes", c.sizeBytes());
            m.put("question", c.question());
            m.put("answer", c.answer());
            m.put("bothSides", c.hasBothSides());
            cs.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("health", hs);
        out.put("consults", cs);
        out.put("consultsDir", consultsDir);
        out.put("upCount", health.stream().filter(ServiceHealth::up).count());
        out.put("serviceCount", health.size());
        return out;
    }
}
