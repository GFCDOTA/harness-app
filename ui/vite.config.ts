import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  // './' e obrigatorio: o JavaFX WebView carrega por file://, e caminho absoluto
  // ('/assets/...') apontaria para a raiz do disco.
  base: "./",
  build: {
    // Sai direto como recurso Maven, entao ./mvnw javafx:run funciona sem npm.
    outDir: "../src/main/resources/web",
    emptyOutDir: true,
    sourcemap: false
  }
});
