package inspector.domain;

import java.util.List;
import java.util.Map;

/**
 * Componente -> qual código realmente roda.
 *
 * <p>Tudo aqui foi conferido no repositório do sketchup-mcp antes de ser escrito, e
 * é reconferido em execução pelo {@code SourceVerifier}. Duas coisas que a checagem
 * derrubou e que valem registro, porque eram exatamente o palpite plausível:
 *
 * <ul>
 *   <li><b>Não há classes.</b> O pipeline é FUNCIONAL — {@code tools/reference_db.py}
 *       tem 943 linhas e zero classes. Modelar como classe+método inventaria nomes.</li>
 *   <li><b>Não há {@code qdrant-client} nem {@code shapely}.</b> A conversa com Qdrant
 *       e Ollama é {@code urllib} da stdlib; está no docstring do próprio módulo
 *       ("HTTP PURO via urllib... o CI nunca importa qdrant_client").</li>
 * </ul>
 */
public final class ImplementationCatalog {

    private ImplementationCatalog() {
    }

    private static final String PY = "python";
    private static final String CPY = "CPython (processo do sketchup-mcp)";

    /** Prefixo -> fatos. Ordem importa: o mais específico primeiro. */
    private static final List<Map.Entry<String, ExecutionFacts>> ENTRIES = List.of(
            Map.entry("ollama.nomic-embed-text", new ExecutionFacts(
                    new Implementation(PY, "tools/rag_embed_backend.py", "embed",
                            List.of("urllib"), "Ollama (serviço) + " + CPY),
                    ExecutionFacts.IA, "Adapter", true)),

            Map.entry("ollama.deepseek", new ExecutionFacts(
                    new Implementation(PY, "tools/ollama_bridge.py", "ask",
                            List.of("urllib"), "Ollama (serviço) + " + CPY),
                    ExecutionFacts.IA, "Adapter", true)),

            Map.entry("ollama.", new ExecutionFacts(
                    new Implementation(PY, "tools/ollama_bridge.py", "ask",
                            List.of("urllib"), "Ollama (serviço) + " + CPY),
                    ExecutionFacts.SERVICO, "Adapter", true)),

            Map.entry("qdrant.", new ExecutionFacts(
                    new Implementation(PY, "tools/rag_embed_backend.py", "search",
                            List.of("urllib"), "Qdrant (container) + " + CPY),
                    ExecutionFacts.SERVICO, "Adapter de VectorStore", false)),

            Map.entry("reference_db.retrieve", new ExecutionFacts(
                    new Implementation(PY, "tools/reference_db.py", "retrieve",
                            List.of(), CPY),
                    ExecutionFacts.RAG, "Orquestrador de ports", false)),

            Map.entry("reference_db.faceted", new ExecutionFacts(
                    new Implementation(PY, "tools/reference_db.py", "_faceted_rank",
                            List.of("sqlite3"), CPY),
                    ExecutionFacts.ALGORITMO, "Repository", false)),

            Map.entry("reference_db.embed_recall", new ExecutionFacts(
                    new Implementation(PY, "tools/rag_embed_backend.py", "semantic_recall",
                            List.of("urllib"), CPY),
                    ExecutionFacts.RAG, "Adapter com degradação", false)),

            Map.entry("reference_db.", new ExecutionFacts(
                    new Implementation(PY, "tools/reference_db.py", "retrieve",
                            List.of(), CPY),
                    ExecutionFacts.RAG, "Repository", false)),

            Map.entry("gate.", new ExecutionFacts(
                    new Implementation(PY, "tools/run_deterministic_gates.py", "run_all",
                            List.of(), CPY),
                    ExecutionFacts.DETERMINISTICO, "Specification", false)),

            Map.entry("architect_program", new ExecutionFacts(
                    new Implementation(PY, "tools/interior_studio/architect_program.py",
                            "room_context", List.of(), CPY),
                    ExecutionFacts.RAG, "Builder de contexto", false)),

            Map.entry("correction_loop", new ExecutionFacts(
                    new Implementation(PY, "tools/correction_loop.py", "run_loop",
                            List.of(), CPY),
                    ExecutionFacts.ALGORITMO, "Loop de convergência", false)),

            Map.entry("finding_router", new ExecutionFacts(
                    new Implementation(PY, "tools/correction_loop.py", "run_loop",
                            List.of(), CPY),
                    ExecutionFacts.ALGORITMO, "Strategy", false)),

            Map.entry("correction_fixes", new ExecutionFacts(
                    new Implementation(PY, "tools/correction_loop.py", "run_loop",
                            List.of(), CPY),
                    ExecutionFacts.ALGORITMO, "Command", false)),

            Map.entry("inspector.", new ExecutionFacts(
                    new Implementation(PY, "core/observability/context.py", "run_scope",
                            List.of(), CPY),
                    ExecutionFacts.DETERMINISTICO, "Context manager", false))
    );

    /** Fatos do componente. Desconhecido devolve {@link ExecutionFacts#NONE}, sem chute. */
    public static ExecutionFacts factsFor(String component) {
        String c = component == null ? "" : component;
        for (Map.Entry<String, ExecutionFacts> e : ENTRIES) {
            if (c.startsWith(e.getKey())) return e.getValue();
        }
        return ExecutionFacts.NONE;
    }

    public static int size() {
        return ENTRIES.size();
    }
}
