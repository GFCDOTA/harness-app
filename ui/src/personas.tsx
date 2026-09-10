import type { JSX } from "react";

/**
 * Os "bonecos": um por PAPEL do passo, nao um por componente. Sao icones de 40 px
 * dentro do no — ensinam de relance o que aquela etapa e, sem virar decoracao.
 *
 * O mapeamento e uma decisao de APRESENTACAO e por isso vive aqui, no React, e nao
 * no dominio Java: o Java diz category/componentFamily; quem escolhe a cara e a UI.
 */
export type PersonaKey =
  | "librarian"
  | "embedding"
  | "vectordb"
  | "llm"
  | "architect"
  | "gatekeeper"
  | "mechanic"
  | "observer";

export const PERSONA_LABEL: Record<PersonaKey, string> = {
  librarian: "bibliotecario — busca estruturada",
  embedding: "tradutor — texto vira vetor",
  vectordb: "banco vetorial — busca por similaridade",
  llm: "modelo de linguagem — gera texto",
  architect: "arquiteto — monta o contexto",
  gatekeeper: "fiscal — gate deterministico",
  mechanic: "mecanico — corrige e re-checa",
  observer: "observador — marcador da run"
};

export function personaFor(category: string | null, family: string): PersonaKey {
  if (category === "LLM") return "llm";
  if (category === "DETERMINISTIC") return "gatekeeper";
  if (category === "RAG") {
    if (family === "ollama") return "embedding";
    if (family === "qdrant") return "vectordb";
    return "librarian";
  }
  if (category === "HARNESS") {
    return family.startsWith("architect") ? "architect" : "mechanic";
  }
  return "observer";
}

const S = { fill: "none", stroke: "currentColor", strokeWidth: 1.6, strokeLinecap: "round" as const, strokeLinejoin: "round" as const };

export function PersonaIcon({ persona, size = 40 }: { persona: PersonaKey; size?: number }): JSX.Element {
  const common = { width: size, height: size, viewBox: "0 0 24 24", "aria-hidden": true as const };
  switch (persona) {
    case "llm":
      return (
        <svg {...common}>
          <rect x="4" y="7" width="16" height="12" rx="3" {...S} />
          <path d="M12 4v3M8 12h.01M16 12h.01M9 16h6" {...S} />
        </svg>
      );
    case "embedding":
      return (
        <svg {...common}>
          <path d="M3 7h6M3 11h4" {...S} />
          <path d="M11 9h4" {...S} />
          <circle cx="18" cy="6" r="1.6" {...S} />
          <circle cx="20" cy="12" r="1.6" {...S} />
          <circle cx="17" cy="17" r="1.6" {...S} />
        </svg>
      );
    case "vectordb":
      return (
        <svg {...common}>
          <ellipse cx="11" cy="6" rx="7" ry="2.6" {...S} />
          <path d="M4 6v6c0 1.4 3.1 2.6 7 2.6" {...S} />
          <path d="M4 12v4c0 1.4 3.1 2.6 7 2.6" {...S} />
          <circle cx="17" cy="15" r="3.4" {...S} />
          <path d="M19.6 17.6L22 20" {...S} />
        </svg>
      );
    case "librarian":
      return (
        <svg {...common}>
          <path d="M5 5h6a2 2 0 012 2v12a2 2 0 00-2-2H5z" {...S} />
          <path d="M19 5h-6a2 2 0 00-2 2v12a2 2 0 012-2h6z" {...S} />
        </svg>
      );
    case "architect":
      return (
        <svg {...common}>
          <path d="M4 19L14 4l6 15z" {...S} />
          <path d="M8 19h9M11 13h5" {...S} />
        </svg>
      );
    case "gatekeeper":
      return (
        <svg {...common}>
          <path d="M12 3l7 3v6c0 4.2-3 7.4-7 9-4-1.6-7-4.8-7-9V6z" {...S} />
          <path d="M9 12l2.2 2.2L15.5 10" {...S} />
        </svg>
      );
    case "mechanic":
      return (
        <svg {...common}>
          <path d="M14.5 4a4.5 4.5 0 00-3.9 6.8l-6 6a2 2 0 102.8 2.8l6-6A4.5 4.5 0 1014.5 4z" {...S} />
          <path d="M6.5 17.5h.01" {...S} />
        </svg>
      );
    default:
      return (
        <svg {...common}>
          <path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6-10-6-10-6z" {...S} />
          <circle cx="12" cy="12" r="2.6" {...S} />
        </svg>
      );
  }
}
