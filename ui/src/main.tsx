import { createRoot } from "react-dom/client";
import "@xyflow/react/dist/style.css";
import "./styles.css";
import { App } from "./App";
import { installBase } from "./bridge";

installBase();
createRoot(document.getElementById("root")!).render(<App />);
