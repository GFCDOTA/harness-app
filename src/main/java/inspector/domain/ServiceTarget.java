package inspector.domain;

import java.net.URI;

/**
 * Um serviço que o Inspector OBSERVA. Ele nunca sobe, derruba ou reinicia nada —
 * quem ressuscita serviço é o Docker e o serviço do Ollama, não este app. A lição
 * do NOC foi que watchdog caseiro custa mais do que entrega.
 */
public record ServiceTarget(String id, String label, URI probeUri, String role) {

    public ServiceTarget {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id é obrigatório");
        if (probeUri == null) throw new IllegalArgumentException("probeUri é obrigatória");
    }
}
