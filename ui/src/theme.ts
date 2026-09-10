export type Theme = "dark" | "light";

const KEY = "harness.theme";

/**
 * Escuro por padrao: e um app de observabilidade, usado ao lado de terminal e editor.
 *
 * O WebView do JavaFX nao acompanha o tema do Windows de forma confiavel, entao a
 * escolha e explicita e persistida — nada de prefers-color-scheme aqui.
 */
export function readTheme(): Theme {
  try {
    const v = localStorage.getItem(KEY);
    if (v === "light" || v === "dark") return v;
  } catch {
    // storage bloqueado nao pode impedir o app de abrir
  }
  return "dark";
}

export function applyTheme(t: Theme): void {
  document.documentElement.setAttribute("data-theme", t);
  try {
    localStorage.setItem(KEY, t);
  } catch {
    // preferencia nao persistiu; a sessao atual continua valendo
  }
}
