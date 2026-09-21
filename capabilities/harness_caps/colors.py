"""Nome de cor → RGB. Tabela DETERMINÍSTICA, fechada, sem LLM.

Por que uma tabela e não o modelo: "preto" tem que dar o mesmo RGB toda vez, e um
nome que ninguém previu tem que RECUSAR com a lista do que existe — não chutar um
valor plausível. Cor inventada entraria no `.skp` como se fosse pedido, e é
exatamente a classe de erro que a hard rule #3 do repo barra ("gate verde não
valida alteração inventada").

Os nomes são em português porque é a língua em que o Felipe dá o comando; os
equivalentes em inglês existem porque o modelo local às vezes responde em inglês
mesmo com prompt em português.
"""
from __future__ import annotations

#: nome canônico → (r, g, b). Ordem alfabética para a mensagem de erro ser estável.
PALETTE: dict[str, tuple[int, int, int]] = {
    "amarelo": (245, 197, 66),
    "areia": (214, 198, 170),
    "azul": (47, 92, 158),
    "azul-marinho": (28, 42, 74),
    "bege": (222, 205, 180),
    "branco": (245, 245, 242),
    "carvalho": (193, 154, 107),
    "cinza": (138, 138, 138),
    "cinza-claro": (198, 198, 196),
    "cinza-escuro": (74, 74, 76),
    "concreto": (168, 165, 158),
    "dourado": (191, 155, 74),
    "grafite": (54, 56, 60),
    "laranja": (219, 122, 52),
    "madeira-clara": (206, 176, 136),
    "madeira-escura": (94, 66, 45),
    "marrom": (110, 78, 56),
    "nogueira": (89, 62, 43),
    "off-white": (238, 235, 228),
    "preto": (26, 26, 28),
    "rosa": (216, 158, 162),
    "roxo": (108, 76, 148),
    "terracota": (186, 104, 78),
    "verde": (78, 124, 88),
    "verde-escuro": (44, 72, 54),
    "vermelho": (176, 58, 52),
    "vinho": (108, 40, 48),
}

#: sinônimos e formas em inglês → nome canônico. Não são cores novas.
ALIASES: dict[str, str] = {
    "black": "preto", "white": "branco", "gray": "cinza", "grey": "cinza",
    "light gray": "cinza-claro", "light grey": "cinza-claro",
    "dark gray": "cinza-escuro", "dark grey": "cinza-escuro",
    "blue": "azul", "navy": "azul-marinho", "red": "vermelho",
    "green": "verde", "dark green": "verde-escuro", "yellow": "amarelo",
    "orange": "laranja", "purple": "roxo", "pink": "rosa",
    "brown": "marrom", "beige": "bege", "sand": "areia",
    "gold": "dourado", "walnut": "nogueira", "oak": "carvalho",
    "graphite": "grafite", "charcoal": "grafite", "wine": "vinho",
    "burgundy": "vinho", "concrete": "concreto",
    "light wood": "madeira-clara", "dark wood": "madeira-escura",
    "preta": "preto", "branca": "branco", "vermelha": "vermelho",
    "amarela": "amarelo", "cinzento": "cinza",
}
# NÃO mapear "escuro"/"claro" sozinhos: são qualificadores, não cores. "escuro"
# → cinza-escuro fazia "verde-escuro" no comando contar como se o Felipe tivesse
# nomeado cinza-escuro, e a guarda de cor inventada deixaria passar a troca.


class UnknownColor(ValueError):
    """Cor que não existe na tabela. Carrega as disponíveis — não chuta."""

    def __init__(self, name: str):
        super().__init__(
            f"cor '{name}' não existe na tabela. Disponíveis: "
            + ", ".join(sorted(PALETTE))
        )
        self.name = name
        self.available = sorted(PALETTE)


def normalize(name: str) -> str:
    """Forma canônica do nome: minúsculo, sem espaço sobrando, alias resolvido."""
    key = " ".join(str(name).strip().lower().split())
    key = key.replace("_", "-")
    if key in PALETTE:
        return key
    if key in ALIASES:
        return ALIASES[key]
    # "cinza claro" (espaço) e "cinza-claro" (hífen) são a mesma cor
    hyphen = key.replace(" ", "-")
    if hyphen in PALETTE:
        return hyphen
    if hyphen in ALIASES:
        return ALIASES[hyphen]
    raise UnknownColor(name)


def resolve(name: str) -> tuple[str, tuple[int, int, int]]:
    """Nome dado pelo usuário → (nome canônico, RGB). Levanta `UnknownColor`."""
    canonical = normalize(name)
    return canonical, PALETTE[canonical]


def hexcode(rgb: tuple[int, int, int]) -> str:
    """RGB → `rrggbb`. Usado para compor nome de material único e estável."""
    return "%02x%02x%02x" % tuple(int(c) for c in rgb)
