package inspector.domain;

/**
 * PORT — como se pergunta a um serviço se ele está vivo.
 *
 * <p>O port representa o MECANISMO (uma sonda), não o produto. Qdrant, Ollama e o
 * oráculo GPT não são três estratégias diferentes: são três alvos HTTP. Um adapter
 * por produto seria arquitetura artificial.
 */
public interface HealthProbe {

    ServiceHealth probe(ServiceTarget target);
}
