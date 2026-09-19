package inspector.domain;

import java.util.List;

/**
 * O catálogo do Learning Mode: componente -> o que ele É.
 *
 * <p>Casa por PREFIXO, do mais específico para o mais geral, e a primeira regra que
 * bate vence. Componente desconhecido devolve um perfil {@code catalogued=false} com
 * o que dá para derivar do prefixo — nunca uma explicação inventada.
 *
 * <p><b>Sobre “externo”:</b> a UI antes dizia “HTTP externo” para Ollama e Qdrant.
 * Estava errado como comunicação. No código, “externo” queria dizer apenas “fora do
 * processo Python”; na tela, lia-se “chamou uma API na internet”. Ollama e Qdrant
 * rodam NA MÁQUINA. Agora o rótulo diz onde a coisa roda de verdade, e existe
 * {@code HTTP_INTERNET} separado para quando a chamada realmente sair daqui.
 */
public final class ComponentCatalog {

    private ComponentCatalog() {
    }

    private static final List<ComponentProfile> PROFILES = List.of(
            new ComponentProfile("ollama.nomic-embed-text", true,
                    "Gerar embedding",
                    ComponentProfile.HTTP_LOCAL, "HTTP local",
                    "HTTP em localhost", "Ollama — serviço nativo na sua máquina",
                    "localhost:11434",
                    "transformar texto em vetor numérico",
                    "o texto da consulta",
                    "um vetor de 768 números que representa o sentido do texto",
                    "Sem isto não há como comparar SIGNIFICADO. A busca cairia para casar "
                            + "palavra por palavra, e “sofá” não acharia “estofado”.",
                    "tools/rag_embed_backend.py",
                    List.of("Embedding", "Modelo de embedding", "Ollama")),

            new ComponentProfile("ollama.deepseek", true,
                    "Executar o LLM",
                    ComponentProfile.LLM_LOCAL, "LLM local",
                    "HTTP em localhost", "Ollama — modelo deepseek-r1:14b na sua máquina",
                    "localhost:11434",
                    "gerar texto a partir do contexto montado",
                    "o prompt com o contexto recuperado",
                    "texto gerado, com contagem de tokens de entrada e saída",
                    "É a parte que raciocina. O RAG existe para alimentar ISTO com o "
                            + "contexto certo; sem contexto, ele responde do que sabe de fábrica.",
                    "tools/ollama_bridge.py",
                    List.of("LLM", "Prompt", "Tokens", "Inferência local")),

            new ComponentProfile("ollama.", true,
                    "Chamar modelo no Ollama",
                    ComponentProfile.HTTP_LOCAL, "HTTP local",
                    "HTTP em localhost", "Ollama — serviço nativo na sua máquina",
                    "localhost:11434",
                    "rodar um modelo local",
                    "depende do modelo",
                    "depende do modelo",
                    "O Ollama é o servidor que hospeda os modelos locais.",
                    "tools/ollama_bridge.py",
                    List.of("Ollama", "Inferência local")),

            new ComponentProfile("qdrant.", true,
                    "Buscar no banco vetorial",
                    ComponentProfile.DOCKER_LOCAL, "Docker local",
                    "HTTP em localhost", "Qdrant — container Docker na sua máquina",
                    "localhost:6333",
                    "achar os trechos semanticamente mais próximos da consulta",
                    "o vetor produzido pelo Ollama",
                    "os trechos mais parecidos, com uma nota de similaridade",
                    "É o que troca “procurar palavra igual” por “procurar sentido "
                            + "parecido”. Se cair, o pipeline degrada para busca estruturada.",
                    "tools/rag_embed_backend.py",
                    List.of("Vector Database", "Similaridade de cosseno", "Collection", "RAG")),

            new ComponentProfile("reference_db.retrieve", true,
                    "Buscar referências",
                    ComponentProfile.CODIGO_LOCAL, "código local",
                    "chamada de função, no mesmo processo",
                    "processo Python do sketchup-mcp", "",
                    "decidir COMO recuperar contexto e orquestrar quem faz o quê",
                    "a intenção da busca e o backend pedido",
                    "os trechos selecionados e o rótulo honesto do que realmente aconteceu",
                    "É a camada de decisão do retrieval. NÃO é o banco vetorial — ele chama "
                            + "o Ollama e o Qdrant, e decide o que fazer quando algo falha.",
                    "tools/reference_db.py",
                    List.of("RAG", "Adapter", "Orquestração", "Fallback")),

            new ComponentProfile("reference_db.faceted", true,
                    "Busca estruturada (o plano B)",
                    ComponentProfile.CODIGO_LOCAL, "código local",
                    "consulta em SQLite, no mesmo processo",
                    "processo Python do sketchup-mcp", "",
                    "achar trechos por atributo — estilo, cômodo, material — sem vetor",
                    "os filtros da consulta",
                    "trechos que casam os filtros; nesta execução, nenhum",
                    "É o que sobra quando o vetorial cai. Continua sendo RAG: recupera e "
                            + "aumenta o contexto, só que sem similaridade semântica.",
                    "tools/reference_db.py",
                    List.of("Fallback", "Degradação", "RAG estruturado")),

            new ComponentProfile("reference_db.embed_recall", true,
                    "Recall semântico",
                    ComponentProfile.CODIGO_LOCAL, "código local",
                    "chamada de função, no mesmo processo",
                    "processo Python do sketchup-mcp", "",
                    "coordenar embedding + busca vetorial e reportar se degradou",
                    "a consulta em texto",
                    "trechos recuperados, ou a declaração de que a infra caiu",
                    "É quem percebe que o Qdrant não respondeu e marca a execução como "
                            + "degradada em vez de fingir que deu certo.",
                    "tools/rag_embed_backend.py",
                    List.of("Circuit degradation", "Fallback", "Observabilidade honesta")),

            new ComponentProfile("reference_db.", true,
                    "Camada de recuperação",
                    ComponentProfile.CODIGO_LOCAL, "código local",
                    "chamada de função, no mesmo processo",
                    "processo Python do sketchup-mcp", "",
                    "recuperar contexto para o RAG", "a consulta", "trechos de contexto",
                    "É a camada lógica de retrieval, não o banco.",
                    "tools/reference_db.py",
                    List.of("RAG", "Adapter")),

            new ComponentProfile("gate.", true,
                    "Gate determinístico",
                    ComponentProfile.CODIGO_LOCAL, "código local",
                    "chamada de função, no mesmo processo",
                    "processo Python do sketchup-mcp", "",
                    "verificar uma regra dura — sem IA, sem opinião",
                    "o modelo geométrico gerado",
                    "PASS ou FAIL, com as contagens que sustentam o veredito",
                    "É o contrapeso do LLM. O modelo sugere; o gate mede. Sem isto, erro "
                            + "de geometria passaria porque a resposta pareceu boa.",
                    "tools/run_deterministic_gates.py",
                    List.of("Gate determinístico", "Invariante", "Validação")),

            new ComponentProfile("architect_program", true,
                    "Montar o contexto",
                    ComponentProfile.CODIGO_LOCAL, "código local",
                    "chamada de função, no mesmo processo",
                    "processo Python do sketchup-mcp", "",
                    "juntar system prompt + estilo + projeto no prompt final",
                    "os trechos recuperados e as regras do projeto",
                    "o prompt montado, com a proporção de cada origem",
                    "É o A de RAG — Augmented. Recuperar não serve de nada se o contexto "
                            + "não for montado e não couber no limite do modelo.",
                    "tools/interior_studio/architect_program.py",
                    List.of("RAG", "Context window", "Prompt engineering")),

            new ComponentProfile("correction_loop", true,
                    "Loop de correção",
                    ComponentProfile.CODIGO_LOCAL, "código local",
                    "chamada de função, no mesmo processo",
                    "processo Python do sketchup-mcp", "",
                    "detectar problema, classificar, corrigir e re-checar até fechar",
                    "os achados dos gates",
                    "as correções aplicadas e o estado terminal do ciclo",
                    "É o harness da APLICAÇÃO — o laço que o próprio pipeline roda. Não "
                            + "confundir com o agente que conduz a sessão.",
                    "tools/correction_loop.py",
                    List.of("Harness", "Loop de correção", "Convergência")),

            new ComponentProfile("finding_router", true,
                    "Classificar o achado",
                    ComponentProfile.CODIGO_LOCAL, "código local",
                    "chamada de função, no mesmo processo",
                    "processo Python do sketchup-mcp", "",
                    "decidir se um problema é auto-corrigível, precisa de visão ou de humano",
                    "os achados dos gates",
                    "a rota de cada achado",
                    "É o que impede o loop de tentar consertar sozinho o que só uma pessoa "
                            + "pode decidir.",
                    "tools/correction_loop.py",
                    List.of("Harness", "Roteamento de decisão")),

            new ComponentProfile("correction_fixes", true,
                    "Aplicar a correção",
                    ComponentProfile.CODIGO_LOCAL, "código local",
                    "chamada de função, no mesmo processo",
                    "processo Python do sketchup-mcp", "",
                    "executar o conserto escolhido — mover móvel, ajustar folga",
                    "o achado e a rota",
                    "a mudança aplicada no modelo",
                    "É a mão que mexe. O gate aponta; isto conserta.",
                    "tools/correction_loop.py",
                    List.of("Harness", "Auto-fix")),

            new ComponentProfile("inspector.", true,
                    "Marcador da execução",
                    ComponentProfile.CODIGO_LOCAL, "código local",
                    "chamada de função, no mesmo processo",
                    "processo Python do sketchup-mcp", "",
                    "marcar início e fim da run",
                    "nada", "os limites da execução",
                    "É o par de marcos que define a run. Sem eles não há duração total nem "
                            + "estado terminal.",
                    "core/observability/context.py",
                    List.of("Observabilidade", "Span", "Trace"))
    );

    /** Perfil do componente. Desconhecido devolve o que dá para derivar, sem inventar. */
    public static ComponentProfile profileFor(final String component) {
        final var c = component == null ? "" : component;
        for (final ComponentProfile p : PROFILES) {
            if (c.startsWith(p.component())) {
                return new ComponentProfile(c, true, p.humanName(), p.kind(), p.kindLabel(),
                        p.transport(), p.host(), p.endpoint(), p.role(), p.input(),
                        p.output(), p.why(), p.code(), p.concepts());
            }
        }
        return unknown(c);
    }

    /**
     * Componente fora do catálogo. Deriva só o que o nome permite e diz claramente que
     * não conhece o resto — numa ferramenta de estudo, um vazio honesto vale mais que
     * uma explicação plausível e errada.
     */
    private static ComponentProfile unknown(final String component) {
        final var pareceServico = component.contains(".") && !component.startsWith("gate.");
        return new ComponentProfile(component, false, component,
                pareceServico ? ComponentProfile.HTTP_LOCAL : ComponentProfile.CODIGO_LOCAL,
                pareceServico ? "não catalogado" : "código local",
                "", "", "", "", "", "", "", "", List.of());
    }

    /** Quantos componentes o catálogo conhece. */
    public static int size() {
        return PROFILES.size();
    }
}
