package harness.agent.domain;

import java.util.List;

/**
 * PORT — quem realmente executa as capabilities.
 *
 * <p>O adapter de produção é um processo filho em Python (onde o pipeline vive); o
 * de teste é um dublê em memória. O runtime não sabe a diferença, e é por isso que
 * a suíte roda sem SketchUp, sem Ollama e sem Qdrant (missão §29).
 */
public interface CapabilityHost {

    /** A tabela de capabilities publicada pelo host. Fonte ÚNICA dos schemas. */
    List<ToolSpec> describeTools();

    /** Capabilities que a missão nomeia e que ainda não existem, com o motivo. */
    List<UnsupportedCapability> unsupported();

    /** Executa UMA chamada já validada. */
    ToolResult invoke(ToolCall call);

    /** {@code true} se o host está atendendo agora. */
    boolean isAlive();
}
