package inspector.domain;

/**
 * PORT — executa UMA acao declarada, uma vez.
 *
 * <p>Nao ha metodo de "garantir que esteja no ar", de proposito: garantir implicaria
 * vigiar e repetir, que e exatamente o que matou o NOC.
 */
public interface ServiceLauncher {

    LaunchResult run(ServiceAction action);
}
