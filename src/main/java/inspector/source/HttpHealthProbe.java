package inspector.source;

import inspector.domain.HealthProbe;
import inspector.domain.ServiceHealth;
import inspector.domain.ServiceTarget;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * ADAPTER — sonda HTTP. Serve Qdrant, Ollama e o oráculo GPT igualmente, porque os
 * três são alvos HTTP; o que muda é a URL, não o mecanismo. Um adapter por produto
 * seria arquitetura artificial.
 *
 * <p>Timeout curto de propósito: um painel que trava esperando um serviço morto é
 * pior que um painel que diz "morto" em 2 s.
 */
public final class HttpHealthProbe implements HealthProbe {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final int BODY_PEEK = 160;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public ServiceHealth probe(ServiceTarget target) {
        long t0 = System.nanoTime();
        String now = Instant.now().toString();
        try {
            HttpRequest req = HttpRequest.newBuilder(target.probeUri())
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            boolean up = res.statusCode() >= 200 && res.statusCode() < 400;
            return new ServiceHealth(target.id(), target.label(), target.role(), up,
                    res.statusCode(), ms, peek(res.body()), now);
        } catch (Exception e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            // Causa raiz e mais util que a classe da excecao: "Connection refused"
            // diz que o container esta parado; timeout diz outra coisa.
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            String why = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
            return new ServiceHealth(target.id(), target.label(), target.role(), false,
                    null, ms, why, now);
        }
    }

    private static String peek(String body) {
        if (body == null) return "";
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= BODY_PEEK ? flat : flat.substring(0, BODY_PEEK - 1) + "…";
    }
}
