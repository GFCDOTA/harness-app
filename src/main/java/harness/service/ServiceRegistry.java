package harness.service;

import harness.config.HarnessConfig;
import inspector.domain.ServiceAction;
import inspector.domain.ServiceTarget;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A esteira desta máquina: quais serviços existem, onde sondá-los e como subi-los.
 *
 * <p>Vive aqui, fora do domínio, porque URL e caminho de instalação são
 * configuração de ambiente — não regra. Os endereços saem do
 * {@link HarnessConfig}, então trocar uma porta é trocar uma chave em
 * {@code harness.json}, não recompilar.
 *
 * <p>A lista de COMANDOS é fechada de propósito: é ela que impede um painel web (ou
 * um modelo) de virar um shell. E nenhum deles usa
 * {@code powershell -ExecutionPolicy Bypass} — foi o padrão que o Defender flagou
 * (ThreatID 2147941383) na época do NOC.
 */
public final class ServiceRegistry {

    private ServiceRegistry() {
    }

    public static List<ServiceTarget> targets(final HarnessConfig cfg) {
        return List.of(
                new ServiceTarget("ollama", "Ollama",
                        URI.create(cfg.ollamaUrl() + "/api/tags"),
                        "modelo local — interpreta o comando e escolhe as capabilities"),
                new ServiceTarget("qdrant", "Qdrant",
                        URI.create(cfg.string("qdrantUrl", "http://127.0.0.1:6333") + "/collections"),
                        "banco vetorial — recall semântico do RAG"),
                new ServiceTarget("gpt", "GPT-Docker",
                        URI.create(cfg.string("gptUrl", "http://127.0.0.1:8899") + "/health"),
                        "oráculo — decisão e veredito visual"));
    }

    public static Map<String, ServiceAction> actions(final HarnessConfig cfg) {
        final var pipeline = cfg.pipelineRepo().toString();
        final var actions = new LinkedHashMap<String, ServiceAction>();
        actions.put("docker", new ServiceAction("docker", "Docker Desktop",
                List.of("cmd", "/c", "start", "",
                        cfg.string("dockerExe", "C:/Program Files/Docker/Docker/Docker Desktop.exe")),
                null));
        actions.put("ollama", new ServiceAction("ollama", "Ollama",
                List.of("cmd", "/c", "start", "", "ollama.exe", "serve"), null));
        actions.put("qdrant", new ServiceAction("qdrant", "Qdrant",
                List.of("docker", "compose", "-f", "docker-compose.rag.yml", "up", "-d"),
                pipeline));
        actions.put("gpt", new ServiceAction("gpt", "GPT-Docker",
                List.of("docker", "compose", "up", "-d"),
                cfg.string("gptComposeDir", "E:/Claude/ops/gpt-docker")));
        return Map.copyOf(actions);
    }
}
