package inspector.ui;

/**
 * Ponto de entrada do app EMPACOTADO.
 *
 * <p>Existe por um detalhe do JavaFX: quando a classe {@code main} estende
 * {@link javafx.application.Application}, o launcher do JavaFX exige que os modulos
 * venham no <i>module-path</i> e aborta com "JavaFX runtime components are missing"
 * se estiverem no classpath. Uma classe intermediaria que NAO estende Application
 * contorna essa checagem, e e o que permite o {@code jpackage} montar a imagem sem
 * jlink e sem module-path.
 *
 * <p>Nao ha logica aqui de proposito. Se um dia aparecer, ela pertence a outro lugar.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        InspectorApp.main(args);
    }
}
