"""Texto das mensagens: variáveis (``{nome}``) + formatação estilo Markdown.

Tudo é enviado com ``parse_mode=HTML``. A pessoa escreve uma marcação simples
(ou usa os botões do editor) e nós geramos o HTML, escapando ``&``, ``<``,
``>`` e ``"`` tanto no texto quanto nos valores das variáveis: um nome com ``<``
não pode quebrar a mensagem.

Marcação aceita::

    *negrito*  **negrito**  _itálico_  __sublinhado__  ~riscado~
    ||spoiler||  `código`  ```bloco de código```  [texto](link)
    > citação (no começo da linha)
"""

import html
import re

VARIABLES = ("nome", "sobrenome", "usuario", "id", "grupo", "args", "data", "hora", "bot")
# Os mesmos valores com nomes em inglês (valem em qualquer idioma do app).
ALIASES = {"name": "nome", "last_name": "sobrenome", "username": "usuario", "group": "grupo",
           "date": "data", "time": "hora"}

_VAR = re.compile(r"\{([A-Za-z_][A-Za-z0-9_]*)\}")
# Variável com argumento, como {preço:BTC} ou {clima:São Paulo} (dados da internet, netvars.py).
_ANYVAR = re.compile(r"\{([^\W\d]\w*)(?::([^{}\n]{1,80}))?\}")
_PH = re.compile("\x00(\\d+)\x00")


def escape(text):
    return (
        str(text)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def unknown_variables(text):
    """Nomes entre chaves que não são variáveis conhecidas (para avisar no editor)."""
    seen = []
    for name in _VAR.findall(text or ""):
        if name not in VARIABLES and name not in ALIASES and name not in seen:
            seen.append(name)
    return seen


def normalize_url(url):
    """Aceita https://..., tg://..., t.me/..., @canal. ``None`` se não for um link válido."""
    url = (url or "").strip()
    if not url:
        return None
    if url.startswith("@") and re.match(r"^@[A-Za-z0-9_]{4,}$", url):
        return "https://t.me/" + url[1:]
    low = url.lower()
    if low.startswith(("https://", "http://", "tg://")):
        return url
    if low.startswith(("t.me/", "telegram.me/", "www.")):
        return "https://" + url
    if re.match(r"^[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+(/.*)?$", url):
        return "https://" + url
    return None


def build_context(message, args, bot_username, now_date, now_time):
    user = message.get("from") or {}
    chat = message.get("chat") or {}
    first = user.get("first_name") or ""
    last = user.get("last_name") or ""
    username = user.get("username")
    group = ""
    if chat.get("type") in ("group", "supergroup", "channel"):
        group = chat.get("title") or ""
    ctx = {
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
    for alias, name in ALIASES.items():
        ctx[alias] = ctx[name]
    return ctx


# -- marcação -------------------------------------------------------------------

_INLINE = [
    (re.compile(r"\*\*(?=\S)(.+?)(?<=\S)\*\*"), "b"),
    (re.compile(r"(?<![\w*])\*(?=[^\s*])(.+?)(?<=[^\s*])\*(?![\w*])"), "b"),
    (re.compile(r"(?<![\w_])__(?=\S)(.+?)(?<=\S)__(?![\w_])"), "u"),
    (re.compile(r"(?<![\w_])_(?=[^\s_])(.+?)(?<=[^\s_])_(?![\w_])"), "i"),
    (re.compile(r"(?<![\w~])~(?=\S)(.+?)(?<=\S)~(?![\w~])"), "s"),
    (re.compile(r"\|\|(?=\S)(.+?)(?<=\S)\|\|"), "tg-spoiler"),
]
_LINK = re.compile(r"\[([^\]\n]+)\]\(([^)\s]+)\)")


def _inline(text):
    for pattern, tag in _INLINE:
        text = pattern.sub(lambda m, t=tag: "<%s>%s</%s>" % (t, m.group(1), t), text)
    return text


def markdown_to_html(text):
    """Converte a marcação simples em HTML do Telegram. ``text`` ainda NÃO escapado."""
    protected = []

    def protect(fragment):
        protected.append(fragment)
        return "\x01%d\x01" % (len(protected) - 1)

    text = escape(text)
    # Código primeiro: nada dentro dele é formatado.
    text = re.sub(r"```(?:[A-Za-z0-9_+-]*\n)?(.+?)```", lambda m: protect("<pre>%s</pre>" % m.group(1)),
                  text, flags=re.S)
    text = re.sub(r"`([^`\n]+)`", lambda m: protect("<code>%s</code>" % m.group(1)), text)

    def link(m):
        url = normalize_url(html.unescape(m.group(2)))
        if not url:
            return m.group(0)
        return protect('<a href="%s">%s</a>' % (escape(url), _inline(m.group(1))))

    text = _LINK.sub(link, text)
    text = _inline(text)

    # Citação: linhas seguidas começando com "> ".
    lines, out, quote = text.split("\n"), [], []
    for line in lines + [None]:
        if line is not None and (line.startswith("&gt; ") or line == "&gt;"):
            quote.append(line[5:])
            continue
        if quote:
            out.append("<blockquote>%s</blockquote>" % "\n".join(quote))
            quote = []
        if line is not None:
            out.append(line)
    text = "\n".join(out)

    while "\x01" in text:
        text = re.sub("\x01(\\d+)\x01", lambda m: protected[int(m.group(1))], text)
    return text


def render(text, context, lang=None):
    """Formata o texto e troca as variáveis conhecidas. Desconhecidas ficam como estão."""
    values = []

    def var(m):
        name, arg = m.group(1), m.group(2)
        if arg is not None:
            from . import netvars
            value = netvars.resolve(name, arg, lang)
            values.append(escape(value) if value is not None else escape(m.group(0)))
        else:
            values.append(escape(context[name]) if name in context else escape(m.group(0)))
        return "\x00%d\x00" % (len(values) - 1)

    body = markdown_to_html(_ANYVAR.sub(var, text or ""))
    return _PH.sub(lambda m: values[int(m.group(1))], body)


def html_to_plain(text):
    """Plano B se o Telegram recusar o HTML: tira as tags e desescapa."""
    return html.unescape(re.sub(r"<[^>]+>", "", text))


def plain_length(text):
    return len(html_to_plain(text))
