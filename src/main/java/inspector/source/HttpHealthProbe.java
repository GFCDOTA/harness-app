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
    public ServiceHealth probe(final ServiceTarget target) {
        final var startedAt = System.nanoTime();
        final var now = Instant.now().toString();
        try {
            final var request = HttpRequest.newBuilder(target.probeUri())
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            final var response = this.client.send(request, HttpResponse.BodyHandlers.ofString());
            final var elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            final var up = response.statusCode() >= 200 && response.statusCode() < 400;
            return new ServiceHealth(target.id(), target.label(), target.role(), up,
                    response.statusCode(), elapsedMs, peek(response.body()), now);
        } catch (final Exception ex) {
            final var elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            // Causa raiz e mais util que a classe da excecao: "Connection refused"
            // diz que o container esta parado; timeout diz outra coisa.
            var root = (Throwable) ex;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            final var why = root.getMessage() == null
                    ? root.getClass().getSimpleName() : root.getMessage();
            return new ServiceHealth(target.id(), target.label(), target.role(), false,
                    null, elapsedMs, why, now);
        }
    }

    private static String peek(final String body) {
        if (body == null) return "";
        final var flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= BODY_PEEK ? flat : flat.substring(0, BODY_PEEK - 1) + "…";
    }
}
