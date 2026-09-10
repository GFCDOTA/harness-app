import { createRoot } from "react-dom/client";
import "@xyflow/react/dist/style.css";
import "./styles.css";
import { App } from "./App";
import { installBase } from "./bridge";
import { applyTheme, readTheme } from "./theme";

// Antes do render, para a primeira pintura ja sair no tema certo (sem flash branco).
applyTheme(readTheme());
installBase();
createRoot(document.getElementById("root")!).render(<App />);
