"""Variáveis no texto de resposta: ``Olá, {nome}!``.

Tudo é enviado com ``parse_mode=HTML``. Por isso escapamos ``&``, ``<`` e ``>``
tanto no texto digitado pela pessoa quanto nos valores das variáveis: um nome
com ``<`` não pode quebrar a mensagem.
"""

import re

VARIABLES = ("nome", "sobrenome", "usuario", "id", "grupo", "args", "data", "hora", "bot")

_PATTERN = re.compile(r"\{([A-Za-z_][A-Za-z0-9_]*)\}")


def escape(text):
    return (
        str(text)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    )


def unknown_variables(text):
    """Nomes entre chaves que não são variáveis conhecidas (para avisar no editor)."""
    seen = []
    for name in _PATTERN.findall(text or ""):
        if name not in VARIABLES and name not in seen:
            seen.append(name)
    return seen


def build_context(message, args, bot_username, now_date, now_time):
    user = message.get("from") or {}
    chat = message.get("chat") or {}
    first = user.get("first_name") or ""
    last = user.get("last_name") or ""
    username = user.get("username")
    group = ""
    if chat.get("type") in ("group", "supergroup", "channel"):
        group = chat.get("title") or ""
    return {
        "nome": first,
        "sobrenome": last,
        "usuario": "@" + username if username else first,
        "id": str(user.get("id", "")),
        "grupo": group,
        "args": args,
        "data": now_date,
        "hora": now_time,
        "bot": "@" + bot_username if bot_username else "",
    }


def render(text, context):
    """Escapa o texto e troca as variáveis conhecidas. Desconhecidas ficam como estão."""

    def repl(match):
        name = match.group(1)
        if name in context:
            return escape(context[name])
        return escape(match.group(0))

    out = []
    last = 0
    for match in _PATTERN.finditer(text or ""):
        out.append(escape(text[last:match.start()]))
        out.append(repl(match))
        last = match.end()
    out.append(escape((text or "")[last:]))
    return "".join(out)
