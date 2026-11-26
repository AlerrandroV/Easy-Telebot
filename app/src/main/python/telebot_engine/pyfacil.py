"""Python facilitado: um pedaço de Python seguro para quem não programa.

O código do dono do bot é lido com ``ast`` e executado por um interpretador
próprio (nada de ``exec``). Só existe o que está aqui: ``if/elif/else``,
``for``, ``while``, ``break``, ``continue``, variáveis, contas, textos, listas,
dicionários, f-strings e as funções do bot. Há limite de passos, de tempo de
processamento, de tamanho de texto/lista e de mensagens enviadas por execução.

Exemplo (os nomes valem em pt, en, es, fr e ru ao mesmo tempo; ver
``pyfacil_names``)::

    if mensagem.tipo == foto:
        arquivo = mensagem.foto.baixar()
        r = termux("node atualizar_foto.js", arquivo, tempo=60)
        if r.ok:
            enviar_texto("Foto atualizada!")
        else:
            enviar_texto("Deu erro: " + r.erro)
        parar()
    else:
        enviar_texto("Isso não é uma foto, envie uma imagem.")

Num bloco "Aguardar resposta", o código roda a cada mensagem recebida e a
espera continua até ``parar()``. Num comando, ``parar()`` encerra os blocos que
viriam depois.

Quem usa: ``run(code, host, ...)`` executa; ``check(code)`` só confere (editor do app).
O ``host`` é quem fala com o Telegram e o Termux (``EngineHost`` no motor, um
falso nos testes).
"""

import ast
import base64 as _b64
import difflib
import functools
import json
import os
import random as _random
import re
import secrets
import shlex
import threading
import time

from . import pyfacil_names as N
from .pyfacil_names import fold

MAX_CODE = 20000
MAX_STEPS = 200000
MAX_SECONDS = 10.0          # processamento puro (não conta envio, Termux, download e pausa)
MAX_SENDS = 20
MAX_TEXT = 100000
MAX_ITEMS = 10000
MAX_ALLOC = 5000000         # soma de tudo o que o código criou de texto/lista numa execução
MAX_DEPTH = 60
MAX_PAUSE = 60
MAX_TERMUX_TIMEOUT = 300
DEFAULT_TERMUX_TIMEOUT = 30
MAX_FILE_TO_TERMUX = 512 * 1024
MAX_VARIABLES = 64 * 1024
ENV_NAME = re.compile(r"^[A-Z][A-Z0-9_]*$")
_BIG_NUMBER = re.compile(r"\d{5,}")


# -- erros ------------------------------------------------------------------------------------

class ScriptError(Exception):
    def __init__(self, code, line=0, **params):
        super().__init__(code)
        self.code = code
        self.line = line
        self.params = params

    def message(self, lang):
        table = N.ERRORS.get(self.code) or N.ERRORS["internal"]
        text = table.get(lang) or table["en"]
        params = dict(self.params)
        params.setdefault("line", self.line)
        params.setdefault("detail", "")
        params.setdefault("hint", "")
        try:
            return text.format(**params)
        except (KeyError, IndexError):
            return text

    def to_dict(self, lang):
        return {"code": self.code, "line": self.line, "message": self.message(lang)}


class _Stop(Exception):
    pass


class _Break(Exception):
    pass


class _Continue(Exception):
    pass


# -- objetos que o código enxerga -------------------------------------------------------------

class Kind(str):
    """Tipo de mensagem: igual a qualquer nome dele em qualquer idioma (``"foto" == "photo"``)."""

    def __new__(cls, canonical, lang="en"):
        obj = super().__new__(cls, N.name_in(N.KINDS, canonical, lang))
        obj.canonical = canonical
        return obj

    def __eq__(self, other):
        if isinstance(other, Kind):
            return self.canonical == other.canonical
        if isinstance(other, Func):
            return other.canonical == self.canonical
        if isinstance(other, str):
            return N.KIND_INDEX.get(fold(other.strip())) == self.canonical
        return False

    def __ne__(self, other):
        return not self.__eq__(other)

    def __hash__(self):
        return hash(self.canonical)


class Func:
    """Função do bot (``enviar_texto``...) ou método de um objeto (``arquivo.baixar``)."""

    def __init__(self, canonical, impl, kwargs=(), label=None):
        self.canonical = canonical
        self.impl = impl
        self.kwargs = kwargs
        self.label = label or canonical

    def __repr__(self):
        return "<%s()>" % self.label


class Proxy:
    """Objeto com atributos traduzidos (mensagem, usuário, chat, arquivo, resultado)."""

    kind = ""

    def attr(self, canonical, interp, line):
        raise NotImplementedError

    def label(self, lang):
        return N.name_in(N.OBJECT_LABELS, self.kind, lang)

    def __eq__(self, other):
        return self is other

    def __hash__(self):
        return id(self)


class FileRef(Proxy):
    kind = "file"

    def __init__(self, info, path=None):
        self.info = info or {}
        self.path = path

    def __str__(self):
        return self.path or self.info.get("file_id", "")

    def attr(self, canonical, interp, line):
        if canonical == "download":
            return Func("download", lambda it, ln: it.download(self, ln), label=interp.local("file", "download"))
        if canonical == "id":
            return self.info.get("file_id", "")
        if canonical == "name":
            return self.info.get("file_name") or os.path.basename(self.path or "") or ""
        if canonical == "size":
            return int(self.info.get("file_size") or (os.path.getsize(self.path) if self.path else 0))
        if canonical == "mime":
            return self.info.get("mime_type") or ("image/jpeg" if self.info.get("_photo") else "")
        if canonical == "path":
            return self.path or ""
        return None


class Result(Proxy):
    kind = "result"

    def __init__(self, ok, output="", error="", code=0, timed_out=False):
        self.values = {"ok": ok, "output": output, "error": error, "code": code, "timed_out": timed_out}

    def __str__(self):
        return self.values["output"]

    def __bool__(self):
        return bool(self.values["ok"])

    def attr(self, canonical, interp, line):
        return self.values.get(canonical)


class UserRef(Proxy):
    kind = "user"

    def __init__(self, user, is_admin=False):
        self.user = user or {}
        self.is_admin = is_admin

    def __str__(self):
        return self.user.get("first_name") or ""

    def attr(self, canonical, interp, line):
        u = self.user
        return {
            "id": u.get("id"), "name": u.get("first_name") or "", "last_name": u.get("last_name") or "",
            "username": u.get("username") or "", "language": u.get("language_code") or "",
            "admin": self.is_admin,
        }.get(canonical)


class ChatRef(Proxy):
    kind = "chat"

    def __init__(self, chat):
        self.chat = chat or {}

    def __str__(self):
        return str(self.chat.get("id", ""))

    def attr(self, canonical, interp, line):
        c = self.chat
        return {
            "id": c.get("id"), "type": c.get("type") or "",
            "title": c.get("title") or c.get("first_name") or "", "private": c.get("type") == "private",
        }.get(canonical)


MEDIA_ORDER = ("animation", "video_note", "sticker", "voice", "audio", "video", "document")


def message_kind(msg):
    if msg.get("_button") is not None:
        return "button"
    if msg.get("photo"):
        return "photo"
    for key in MEDIA_ORDER:
        if msg.get(key):
            return key
    for key in ("location", "contact", "poll"):
        if msg.get(key):
            return key
    if msg.get("text") is not None:
        return "text"
    return "other"


class MessageRef(Proxy):
    kind = "message"

    def __init__(self, msg, user_ref, chat_ref):
        self.msg = msg or {}
        self.user_ref = user_ref
        self.chat_ref = chat_ref

    def __str__(self):
        return self.msg.get("text") or self.msg.get("caption") or ""

    def file_info(self):
        m = self.msg
        if m.get("photo"):
            info = dict(max(m["photo"], key=lambda p: p.get("file_size") or p.get("width", 0)))
            info["_photo"] = True
            info.setdefault("file_name", "foto.jpg")
            return info
        for key in MEDIA_ORDER:
            if isinstance(m.get(key), dict) and m[key].get("file_id"):
                return m[key]
        return None

    def attr(self, canonical, interp, line):
        m = self.msg
        if canonical == "type":
            return Kind(message_kind(m), interp.lang)
        if canonical == "text":
            return m.get("text") or m.get("caption") or ""
        if canonical == "photo":
            return FileRef(self.file_info()) if m.get("photo") else None
        if canonical == "file":
            info = self.file_info()
            return FileRef(info) if info else None
        if canonical == "data":
            return m.get("_button") or ""
        if canonical == "contact":
            return (m.get("contact") or {}).get("phone_number") or ""
        if canonical in ("latitude", "longitude"):
            return (m.get("location") or {}).get(canonical)
        if canonical == "id":
            return m.get("message_id")
        if canonical == "user":
            return self.user_ref
        if canonical == "chat":
            return self.chat_ref
        return None


class Variables(dict):
    """``variaveis["cidade"] = ...``: fica guardado por pessoa (vale também como ``{cidade}`` nas mensagens)."""


# -- métodos permitidos dos tipos do Python ---------------------------------------------------

SAFE_METHODS = {
    str: {"lower", "upper", "strip", "lstrip", "rstrip", "split", "rsplit", "splitlines", "replace",
          "startswith", "endswith", "find", "count", "index", "isdigit", "isnumeric", "isalpha", "isalnum",
          "isspace", "islower", "isupper", "title", "capitalize", "join", "removeprefix", "removesuffix"},
    list: {"append", "pop", "insert", "remove", "index", "count", "reverse", "sort", "copy", "extend", "clear"},
    dict: {"get", "keys", "values", "items", "pop", "update", "copy", "setdefault", "clear"},
    tuple: {"index", "count"},
    int: set(), float: {"is_integer"}, bool: set(), type(None): set(),
}

BINOPS = {
    ast.Add: lambda a, b: a + b, ast.Sub: lambda a, b: a - b, ast.Mult: lambda a, b: a * b,
    ast.Div: lambda a, b: a / b, ast.FloorDiv: lambda a, b: a // b, ast.Mod: lambda a, b: a % b,
    ast.Pow: lambda a, b: a ** b,
}
CMPOPS = {
    ast.Eq: lambda a, b: a == b, ast.NotEq: lambda a, b: a != b, ast.Lt: lambda a, b: a < b,
    ast.LtE: lambda a, b: a <= b, ast.Gt: lambda a, b: a > b, ast.GtE: lambda a, b: a >= b,
    ast.In: lambda a, b: a in b, ast.NotIn: lambda a, b: a not in b,
    ast.Is: lambda a, b: a is b or (a is None and b is None), ast.IsNot: lambda a, b: a is not b,
}

ALLOWED = (
    ast.Module, ast.Expr, ast.Assign, ast.AugAssign, ast.If, ast.For, ast.While, ast.Break, ast.Continue,
    ast.Pass, ast.BoolOp, ast.And, ast.Or, ast.BinOp, ast.UnaryOp, ast.Not, ast.USub, ast.UAdd,
    ast.Compare, ast.Call, ast.keyword, ast.Name, ast.Load, ast.Store, ast.Attribute, ast.Constant,
    ast.JoinedStr, ast.FormattedValue, ast.List, ast.Tuple, ast.Dict, ast.Set, ast.Subscript, ast.Slice,
    ast.IfExp,
) + tuple(BINOPS) + tuple(CMPOPS)


# -- funções nativas do Python liberadas ------------------------------------------------------

def _sized(interp, line, value):
    return interp.check_size(value, line)


def _builtin(fn, sized=False):
    def call(interp, line, *args, **kwargs):
        for a in args:
            if isinstance(a, range) and len(a) > MAX_ITEMS:
                raise ScriptError("too_big", line, n=MAX_ITEMS)
        out = fn(*args, **kwargs)
        return interp.check_size(out, line) if sized else out
    return call


def _range(interp, line, *args):
    r = range(*args)
    if len(r) > MAX_ITEMS:
        raise ScriptError("too_big", line, n=MAX_ITEMS)
    return r


def _to_text(interp, line, value=""):
    if isinstance(value, Proxy):
        return str(value)
    return interp.check_size(str(value), line)


PY_BUILTINS = {
    "len": _builtin(len), "str": _to_text, "int": _builtin(int), "float": _builtin(float),
    "bool": _builtin(bool), "round": _builtin(round), "abs": _builtin(abs), "min": _builtin(min),
    "max": _builtin(max), "sum": _builtin(sum), "range": _range, "list": _builtin(list, True),
    "dict": _builtin(dict, True), "tuple": _builtin(tuple, True), "sorted": _builtin(sorted, True),
    "reversed": _builtin(lambda x: list(reversed(x)), True), "enumerate": _builtin(lambda *a: list(enumerate(*a)), True),
    "zip": _builtin(lambda *a: list(zip(*a)), True), "any": _builtin(any), "all": _builtin(all),
    "set": _builtin(set, True),
}


# -- análise (sem executar) -------------------------------------------------------------------

@functools.lru_cache(maxsize=64)
def _parse(code):
    if len(code) > MAX_CODE:
        raise ScriptError("too_long", 0, n=MAX_CODE)
    try:
        tree = ast.parse(code, mode="exec")
    except SyntaxError as exc:
        raise ScriptError("syntax", exc.lineno or 0, detail=(exc.msg or "").strip()) from None
    except (ValueError, RecursionError, MemoryError) as exc:
        raise ScriptError("syntax", 0, detail=str(exc)) from None
    _validate(tree)
    return tree


def _validate(tree):
    def walk(node, depth):
        if depth > MAX_DEPTH:
            raise ScriptError("too_deep", getattr(node, "lineno", 0))
        if not isinstance(node, ALLOWED):
            name = type(node).__name__
            raise ScriptError("not_allowed", getattr(node, "lineno", 0),
                              what=N.FORBIDDEN_LABELS.get(name, name))
        if isinstance(node, ast.Attribute) and node.attr.startswith("_"):
            raise ScriptError("not_allowed", node.lineno, what="." + node.attr)
        if isinstance(node, ast.Name) and node.id.startswith("__"):
            raise ScriptError("not_allowed", node.lineno, what=node.id)
        if isinstance(node, ast.Constant) and isinstance(node.value, bytes):
            raise ScriptError("not_allowed", node.lineno, what="bytes")
        if isinstance(node, (ast.Assign, ast.AugAssign)):
            for t in (node.targets if isinstance(node, ast.Assign) else [node.target]):
                for sub in ast.walk(t):
                    if isinstance(sub, ast.Attribute) and isinstance(sub.ctx, ast.Store):
                        raise ScriptError("read_only", node.lineno, name=ast.unparse(sub))
        if isinstance(node, ast.Dict) and any(k is None for k in node.keys):
            raise ScriptError("not_allowed", node.lineno, what="**")
        if isinstance(node, ast.Call) and any(k.arg is None for k in node.keywords):
            raise ScriptError("not_allowed", node.lineno, what="**")
        for child in ast.iter_child_nodes(node):
            walk(child, depth + 1)
    walk(tree, 0)


def _assigned_names(tree):
    names = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Name) and isinstance(node.ctx, ast.Store):
            names.add(node.id)
    return names


def detect_language(code, default="pt"):
    """Idioma mais provável do código (pelos nomes usados). Serve para escolher a língua dos erros."""
    try:
        tree = ast.parse(code)
    except (SyntaxError, ValueError, RecursionError, MemoryError):
        return default
    votes = {lang: 0 for lang in N.LANGS}
    for node in ast.walk(tree):
        words = []
        if isinstance(node, ast.Name):
            words.append((N.GLOBALS, node.id))
            words.append((N.KINDS, node.id))
        elif isinstance(node, ast.Attribute):
            for table in N.ATTRS.values():
                words.append((table, node.attr))
        elif isinstance(node, ast.keyword) and node.arg:
            words.append((N.KWARGS, node.arg))
        hit = set()
        for table, word in words:
            hit |= N.languages_of(table, fold(word))
        if hit and len(hit) < len(N.LANGS):
            for lang in hit:
                votes[lang] += 1
    best = max(votes.values())
    if best == 0:
        return default
    winners = [lang for lang in N.LANGS if votes[lang] == best]
    return default if default in winners else winners[0]


def _known_globals():
    names = set(N.GLOBAL_INDEX) | set(N.KIND_INDEX) | set(PY_BUILTINS)
    return names


def check(code, lang=None, default_lang="pt"):
    """Confere o código sem rodar. ``{"ok", "lang", "errors": [{"code", "line", "message"}]}``."""
    lang = lang or detect_language(code or "", default_lang)
    try:
        tree = _parse(code or "")
    except ScriptError as exc:
        return {"ok": False, "lang": lang, "errors": [exc.to_dict(lang)]}
    errors = []
    user_names = _assigned_names(tree)
    known = _known_globals()
    for node in ast.walk(tree):
        if isinstance(node, ast.Name) and isinstance(node.ctx, ast.Load):
            if node.id in user_names or fold(node.id) in known or node.id in PY_BUILTINS:
                continue
            errors.append(ScriptError("unknown_name", node.lineno, name=node.id,
                                      hint=_hint(node.id, user_names, lang)).to_dict(lang))
        elif isinstance(node, ast.Attribute) and isinstance(node.value, ast.Name):
            base = node.value.id
            if base in user_names:
                continue
            obj = N.GLOBAL_INDEX.get(fold(base))
            if obj in N.ATTRS and fold(node.attr) not in N.ATTR_INDEX[obj]:
                errors.append(ScriptError("unknown_attr", node.lineno, obj=base, attr=node.attr,
                                          options=_options(N.ATTRS[obj], lang)).to_dict(lang))
        elif isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id not in user_names:
            canonical = N.GLOBAL_INDEX.get(fold(node.func.id))
            spec = FUNCTIONS.get(canonical)
            if spec is None:
                continue
            for kw in node.keywords:
                if N.KWARG_INDEX.get(fold(kw.arg)) not in spec[1]:
                    errors.append(ScriptError("unknown_kwarg", node.lineno, func=node.func.id, name=kw.arg,
                                              options=", ".join(N.name_in(N.KWARGS, k, lang) for k in spec[1]) or "-")
                                  .to_dict(lang))
    seen, unique = set(), []
    for e in sorted(errors, key=lambda e: e["line"]):
        key = (e["line"], e["message"])
        if key not in seen:
            seen.add(key)
            unique.append(e)
    return {"ok": not unique, "lang": lang, "errors": unique[:20]}


def _options(table, lang):
    return ", ".join(N.name_in(table, k, lang) for k in table)


def _hint(name, user_names, lang):
    candidates = set(user_names)
    for table in (N.GLOBALS, N.KINDS):
        for k in table:
            candidates.add(N.name_in(table, k, lang))
    candidates |= set(PY_BUILTINS)
    close = difflib.get_close_matches(name, sorted(candidates), n=1, cutoff=0.7)
    return N.HINT.get(lang, N.HINT["en"]).format(name=close[0]) if close else ""


# -- funções do bot ---------------------------------------------------------------------------

def _rows(interp, line, buttons):
    """``botoes=[...]``: lista de botões (um por linha) ou lista de linhas (listas)."""
    if buttons is None:
        return None
    if not isinstance(buttons, (list, tuple)):
        buttons = [buttons]
    rows = []
    for item in buttons:
        row = item if isinstance(item, (list, tuple)) else [item]
        out = [_button_dict(interp, line, b) for b in row]
        if out:
            rows.append(out)
    return {"inline_keyboard": rows[:20]} if rows else None


def _button_dict(interp, line, b):
    if isinstance(b, dict) and b.get("text"):
        return b
    text = str(b)
    return {"text": text[:64], "callback_data": interp.host.callback_prefix + _slug(text)}


def _slug(text):
    s = re.sub(r"[^a-z0-9_]+", "_", fold(text)).strip("_")
    return (s or "botao")[:50]


def f_send_text(interp, line, text, buttons=None, to=None):
    return interp.send(line, "sendMessage", {"text": text}, buttons, to=to)


def f_reply(interp, line, text, buttons=None):
    return interp.send(line, "sendMessage", {"text": text}, buttons, reply=True)


def f_send_photo(interp, line, photo, caption="", buttons=None, to=None):
    return interp.send(line, "sendPhoto", {"caption": caption}, buttons, media=("photo", photo), to=to)


def f_send_file(interp, line, file, caption="", buttons=None, to=None):
    return interp.send(line, "sendDocument", {"caption": caption}, buttons, media=("document", file), to=to)


def f_button(interp, line, text, link=None, data=None, copy=None):
    text = str(text)[:64]
    if not text.strip():
        raise ScriptError("bad_call", line, func=interp.local_global("button"), detail="texto vazio")
    if link:
        return {"text": text, "url": str(link)}
    if copy is not None:
        return {"text": text, "copy_text": {"text": str(copy)[:256]}}
    value = _slug(text) if data is None else re.sub(r"\s+", "_", str(data))
    raw = interp.host.callback_prefix + value
    if len(raw.encode("utf-8")) > 64:
        raise ScriptError("bad_call", line, func=interp.local_global("button"), detail="dado > 61 bytes")
    return {"text": text, "callback_data": raw}


def f_termux(interp, line, command, *args, timeout=DEFAULT_TERMUX_TIMEOUT, wait=True, env=None):
    parts = [str(command)]
    files = []
    for a in args:
        if isinstance(a, FileRef):
            ref = a if a.path else interp.download(a, line)
            size = os.path.getsize(ref.path)
            if size > MAX_FILE_TO_TERMUX:
                raise ScriptError("file_big", line, size=size // 1024, limit=MAX_FILE_TO_TERMUX // 1024)
            ext = os.path.splitext(ref.path)[1][:8]
            name = "telebot-%s%s" % (secrets.token_hex(4), ext)
            files.append({"src": ref.path, "name": name})
            parts.append('"$TELEBOT_FILES_DIR"/' + name)
        elif a is None:
            raise ScriptError("no_file", line)
        else:
            parts.append(shlex.quote(str(a)))
    extra = {}
    for k, v in (env or {}).items():
        if not ENV_NAME.match(str(k)):
            raise ScriptError("env_name", line, name=k)
        extra[str(k)] = str(v)
    try:
        seconds = int(timeout)
    except (TypeError, ValueError):
        raise ScriptError("bad_call", line, func="termux", detail="timeout") from None
    seconds = max(1, min(seconds, MAX_TERMUX_TIMEOUT))
    command_line = " ".join(parts)
    if not wait:
        threading.Thread(target=interp.host.termux, args=(command_line, extra, seconds, files),
                         name="pyfacil-termux", daemon=True).start()
        return None
    raw = interp.external(interp.host.termux, command_line, extra, seconds, files)
    raw = raw or {}
    if not raw.get("ok"):
        err = raw.get("error") or {}
        return Result(False, "", (err.get("detail") or err.get("code") or "termux").strip(), -1)
    code = int(raw.get("exit_code") or 0)
    return Result(code == 0, (raw.get("stdout") or "").rstrip("\n"), (raw.get("stderr") or "").strip(),
                  code, timed_out=code == 124)


def f_stop(interp, line, *args):
    raise _Stop()


def f_log(interp, line, *values):
    interp.host.log(" ".join(str(v) for v in values)[:2000])


def f_pause(interp, line, seconds):
    try:
        seconds = float(seconds)
    except (TypeError, ValueError):
        raise ScriptError("bad_call", line, func=interp.local_global("pause"), detail=str(seconds)) from None
    seconds = max(0.0, min(seconds, MAX_PAUSE - interp.paused))
    interp.paused += seconds
    if seconds:
        interp.external(interp.host.sleep, seconds)


def f_base64(interp, line, value):
    if isinstance(value, FileRef):
        ref = value if value.path else interp.download(value, line)
        with open(ref.path, "rb") as fh:
            data = fh.read(MAX_TEXT * 10)
        out = _b64.b64encode(data).decode("ascii")
    else:
        out = _b64.b64encode(str(value).encode("utf-8")).decode("ascii")
    interp.alloc(len(out), line, limit=MAX_TEXT * 14)
    return out


def f_random(interp, line, a=1, b=100):
    return _random.randint(int(a), int(b))


def f_choice(interp, line, options):
    options = list(options)
    return _random.choice(options) if options else None


def f_now(interp, line):
    return interp.host.now()


def f_remind(interp, line, when, text=None):
    """``lembrar("amanhã 9h comprar pão")`` ou ``lembrar(30, "tirar o bolo")`` (minutos).
    Devolve quando vai lembrar ("27/11 às 09:00") ou ``""`` se não entendeu."""
    if interp.chat_id is None:
        raise ScriptError("no_chat", line)
    out = interp.external(interp.host.remind, when if isinstance(when, (int, float)) else str(when),
                          None if text is None else str(text))
    return out[0] if out else ""


def f_cancel_reminders(interp, line):
    return interp.external(interp.host.cancel_reminders)


# canônico → (função, argumentos nomeados aceitos)
FUNCTIONS = {
    "send_text": (f_send_text, ("buttons", "to")),
    "reply": (f_reply, ("buttons",)),
    "send_photo": (f_send_photo, ("caption", "buttons", "to")),
    "send_file": (f_send_file, ("caption", "buttons", "to")),
    "button": (f_button, ("link", "data", "copy")),
    "termux": (f_termux, ("timeout", "wait", "env")),
    "stop": (f_stop, ()),
    "log": (f_log, ()),
    "pause": (f_pause, ()),
    "base64": (f_base64, ()),
    "random": (f_random, ()),
    "choice": (f_choice, ()),
    "now": (f_now, ()),
    "remind": (f_remind, ()),
    "cancel_reminders": (f_cancel_reminders, ()),
}


# -- interpretador ----------------------------------------------------------------------------

class Interpreter:
    def __init__(self, host, lang, message=None, user=None, chat=None, args="", variables=None, is_admin=False):
        self.host = host
        self.lang = lang
        self.vars = {}
        self.steps = 0
        self.sends = 0
        self.paused = 0.0
        self.allocated = 0
        self.external_time = 0.0
        self.started = time.monotonic()
        self.reply_to = None
        user_ref = UserRef(user or (message or {}).get("from"), is_admin)
        chat_ref = ChatRef(chat or (message or {}).get("chat"))
        self.objects = {
            "message": MessageRef(message or {}, user_ref, chat_ref),
            "user": user_ref,
            "chat": chat_ref,
            "variables": variables if variables is not None else Variables(),
            "args": args or "",
            "true": True,
            "false": False,
        }
        self.chat_id = chat_ref.chat.get("id")
        if message and message.get("message_id") and message.get("_button") is None:
            self.reply_to = {"message_id": message["message_id"], "allow_sending_without_reply": True}

    # nomes no idioma do código (para mensagens de erro)
    def local(self, obj, canonical):
        return N.name_in(N.ATTRS[obj], canonical, self.lang)

    def local_global(self, canonical):
        return N.name_in(N.GLOBALS, canonical, self.lang)

    # -- limites -------------------------------------------------------------------------------
    def tick(self, node):
        self.steps += 1
        if self.steps > MAX_STEPS:
            raise ScriptError("steps", getattr(node, "lineno", 0), n=MAX_STEPS)
        if self.steps % 200 == 0:
            if time.monotonic() - self.started - self.external_time > MAX_SECONDS:
                raise ScriptError("slow", getattr(node, "lineno", 0), n=int(MAX_SECONDS))
            if self.host.stopping:
                raise _Stop()

    def alloc(self, n, line, limit=MAX_TEXT):
        if n > limit:
            raise ScriptError("too_big", line, n=limit)
        self.allocated += n
        if self.allocated > MAX_ALLOC:
            raise ScriptError("too_big", line, n=MAX_ALLOC)

    def check_size(self, value, line):
        if isinstance(value, str):
            self.alloc(len(value), line)
        elif isinstance(value, (list, tuple, dict, set)):
            self.alloc(len(value), line, MAX_ITEMS)
        return value

    def external(self, fn, *args):
        """Chamada que espera algo de fora (rede, Termux): não conta como processamento."""
        t = time.monotonic()
        try:
            return fn(*args)
        finally:
            self.external_time += time.monotonic() - t

    # -- execução ------------------------------------------------------------------------------
    def run(self, tree):
        self.exec_block(tree.body)

    def exec_block(self, body):
        for stmt in body:
            self.exec_stmt(stmt)

    def exec_stmt(self, node):
        self.tick(node)
        if isinstance(node, ast.Expr):
            self.eval(node.value)
        elif isinstance(node, ast.Assign):
            value = self.eval(node.value)
            for target in node.targets:
                self.assign(target, value)
        elif isinstance(node, ast.AugAssign):
            t, line = node.target, node.lineno
            if isinstance(t, ast.Name):
                self.assign(t, self.binop(node.op, self.lookup(t.id, line), self.eval(node.value), line))
            else:  # x[chave] += ...
                container, key = self.eval(t.value), self.eval(t.slice)
                current = self.subscript(container, key, line)
                self.set_item(container, key, self.binop(node.op, current, self.eval(node.value), line), line,
                              ast.unparse(t.value))
        elif isinstance(node, ast.If):
            self.exec_block(node.body if self.truth(self.eval(node.test)) else node.orelse)
        elif isinstance(node, ast.While):
            broke = False
            while self.truth(self.eval(node.test)):
                self.tick(node)
                try:
                    self.exec_block(node.body)
                except _Break:
                    broke = True
                    break
                except _Continue:
                    continue
            if not broke:
                self.exec_block(node.orelse)
        elif isinstance(node, ast.For):
            iterable = self.eval(node.iter)
            if isinstance(iterable, Variables):
                iterable = list(iterable)
            if not isinstance(iterable, (list, tuple, str, dict, range, set)):
                raise ScriptError("type", node.lineno, detail="for ... in %s" % _type_name(iterable))
            broke = False
            for item in list(iterable):
                self.tick(node)
                self.assign(node.target, item)
                try:
                    self.exec_block(node.body)
                except _Break:
                    broke = True
                    break
                except _Continue:
                    continue
            if not broke:
                self.exec_block(node.orelse)
        elif isinstance(node, ast.Break):
            raise _Break()
        elif isinstance(node, ast.Continue):
            raise _Continue()
        elif isinstance(node, ast.Pass):
            pass
        else:  # _validate já barrou; só por garantia
            raise ScriptError("not_allowed", node.lineno, what=type(node).__name__)

    def assign(self, target, value):
        line = getattr(target, "lineno", 0)
        if isinstance(target, ast.Name):
            if fold(target.id) in N.GLOBAL_INDEX and N.GLOBAL_INDEX[fold(target.id)] == "variables":
                raise ScriptError("read_only", line, name=target.id)
            self.vars[target.id] = value
        elif isinstance(target, (ast.Tuple, ast.List)):
            items = list(value) if isinstance(value, (list, tuple, str)) else None
            if items is None or len(items) != len(target.elts):
                raise ScriptError("value", line, detail="%d != %s" % (len(target.elts),
                                                                      len(items) if items is not None else "?"))
            for t, v in zip(target.elts, items):
                self.assign(t, v)
        elif isinstance(target, ast.Subscript):
            self.set_item(self.eval(target.value), self.eval(target.slice), value, line, ast.unparse(target.value))
        else:
            raise ScriptError("read_only", line, name=ast.unparse(target))

    def set_item(self, container, key, value, line, label):
        if not isinstance(container, (list, dict)):
            raise ScriptError("read_only", line, name=label)
        try:
            container[key] = value
        except IndexError:
            raise ScriptError("index", line, detail=repr(key)) from None
        except TypeError as exc:
            raise ScriptError("type", line, detail=str(exc)) from None
        if len(container) > MAX_ITEMS:
            raise ScriptError("too_big", line, n=MAX_ITEMS)

    @staticmethod
    def truth(value):
        return bool(value)

    def eval(self, node):
        self.tick(node)
        line = getattr(node, "lineno", 0)
        if isinstance(node, ast.Constant):
            return node.value
        if isinstance(node, ast.Name):
            return self.lookup(node.id, line)
        if isinstance(node, ast.Attribute):
            return self.get_attr(self.eval(node.value), node.attr, line, ast.unparse(node.value))
        if isinstance(node, ast.Call):
            return self.call(node)
        if isinstance(node, ast.BinOp):
            return self.binop(node.op, self.eval(node.left), self.eval(node.right), line)
        if isinstance(node, ast.UnaryOp):
            v = self.eval(node.operand)
            if isinstance(node.op, ast.Not):
                return not self.truth(v)
            try:
                return -v if isinstance(node.op, ast.USub) else +v
            except TypeError as exc:
                raise ScriptError("type", line, detail=str(exc)) from None
        if isinstance(node, ast.BoolOp):
            is_and = isinstance(node.op, ast.And)
            value = None
            for v in node.values:
                value = self.eval(v)
                if self.truth(value) != is_and:
                    return value
            return value
        if isinstance(node, ast.Compare):
            left = self.eval(node.left)
            for op, comp in zip(node.ops, node.comparators):
                right = self.eval(comp)
                try:
                    if isinstance(op, (ast.In, ast.NotIn)) and isinstance(right, Proxy):
                        right = str(right)
                    if not CMPOPS[type(op)](left, right):
                        return False
                except TypeError as exc:
                    raise ScriptError("type", line, detail=str(exc)) from None
                left = right
            return True
        if isinstance(node, ast.IfExp):
            return self.eval(node.body) if self.truth(self.eval(node.test)) else self.eval(node.orelse)
        if isinstance(node, ast.List):
            return self.check_size([self.eval(e) for e in node.elts], line)
        if isinstance(node, ast.Tuple):
            return tuple(self.eval(e) for e in node.elts)
        if isinstance(node, ast.Set):
            try:
                return {self.eval(e) for e in node.elts}
            except TypeError as exc:
                raise ScriptError("type", line, detail=str(exc)) from None
        if isinstance(node, ast.Dict):
            try:
                return {self.eval(k): self.eval(v) for k, v in zip(node.keys, node.values)}
            except TypeError as exc:
                raise ScriptError("type", line, detail=str(exc)) from None
        if isinstance(node, ast.Subscript):
            return self.subscript(self.eval(node.value), self.eval(node.slice), line)
        if isinstance(node, ast.Slice):
            return slice(*(None if p is None else self.eval(p) for p in (node.lower, node.upper, node.step)))
        if isinstance(node, ast.JoinedStr):
            out = "".join(self.format_part(v) for v in node.values)
            return self.check_size(out, line)
        if isinstance(node, ast.FormattedValue):
            return self.format_part(node)
        raise ScriptError("not_allowed", line, what=type(node).__name__)

    def format_part(self, node):
        if isinstance(node, ast.Constant):
            return str(node.value)
        value = self.eval(node.value)
        line = getattr(node, "lineno", 0)
        if node.conversion == ord("r"):
            value = repr(value)
        elif isinstance(value, Proxy) or node.conversion == ord("s"):
            value = str(value)
        spec = self.eval(node.format_spec) if node.format_spec is not None else ""
        if _BIG_NUMBER.search(spec):
            raise ScriptError("too_big", line, n=MAX_TEXT)
        try:
            return format(value, spec)
        except (TypeError, ValueError) as exc:
            raise ScriptError("value", line, detail=str(exc)) from None

    def lookup(self, name, line):
        if name in self.vars:
            return self.vars[name]
        key = fold(name)
        canonical = N.GLOBAL_INDEX.get(key)
        if canonical is not None:
            if canonical in self.objects:
                return self.objects[canonical]
            fn, kwargs = FUNCTIONS[canonical]
            return Func(canonical, fn, kwargs, label=name)
        kind = N.KIND_INDEX.get(key)
        if kind is not None:
            return Kind(kind, self.lang)
        if name in PY_BUILTINS:
            return Func(name, PY_BUILTINS[name], None, label=name)
        raise ScriptError("unknown_name", line, name=name, hint=_hint(name, set(self.vars), self.lang))

    def get_attr(self, obj, attr, line, base_label):
        if isinstance(obj, Proxy):
            table = N.ATTR_INDEX[obj.kind]
            canonical = table.get(fold(attr))
            if canonical is None:
                raise ScriptError("unknown_attr", line, obj=base_label, attr=attr,
                                  options=_options(N.ATTRS[obj.kind], self.lang))
            return obj.attr(canonical, self, line)
        if obj is None and fold(attr) in N.ATTR_INDEX["file"]:
            raise ScriptError("no_file", line)
        if obj is None:
            raise ScriptError("type", line, detail="%s = None (.%s)" % (base_label, attr))
        allowed = SAFE_METHODS.get(type(obj))
        if allowed is None:
            for t, names in SAFE_METHODS.items():
                if isinstance(obj, t):
                    allowed = names
                    break
        if not allowed or attr not in allowed:
            raise ScriptError("unknown_attr", line, obj=base_label, attr=attr,
                              options=", ".join(sorted(allowed or [])) or "-")
        method = getattr(obj, attr)
        return Func(attr, self._method(obj, attr, method), None, label=attr)

    def _method(self, obj, attr, method):
        def call(interp, line, *args, **kwargs):
            if isinstance(obj, str) and attr == "replace" and len(args) >= 2:
                estimate = len(obj) + obj.count(str(args[0])) * len(str(args[1])) if args[0] else len(obj) * 2
                interp.alloc(estimate, line)
            if isinstance(obj, str) and attr == "join" and args:
                parts = list(args[0])
                interp.alloc(sum(len(str(p)) for p in parts) + len(obj) * len(parts), line)
            result = method(*args, **kwargs)
            if isinstance(obj, list) and len(obj) > MAX_ITEMS:
                raise ScriptError("too_big", line, n=MAX_ITEMS)
            if isinstance(result, (type({}.keys()), type({}.values()), type({}.items()))):
                result = list(result)
            return interp.check_size(result, line)
        return call

    def subscript(self, obj, key, line):
        if isinstance(obj, Proxy):
            raise ScriptError("type", line, detail="%s[...]" % obj.label(self.lang))
        try:
            return self.check_size(obj[key], line)
        except IndexError:
            raise ScriptError("index", line, detail=repr(key)) from None
        except KeyError:
            raise ScriptError("key", line, detail=repr(key)) from None
        except TypeError as exc:
            raise ScriptError("type", line, detail=str(exc)) from None

    def binop(self, op, a, b, line):
        if isinstance(a, Proxy) and isinstance(b, str):
            a = str(a)
        if isinstance(b, Proxy) and isinstance(a, str):
            b = str(b)
        kind = type(op)
        if kind is ast.Mult:
            for seq, n in ((a, b), (b, a)):
                if isinstance(seq, (str, list, tuple)) and isinstance(n, int):
                    self.alloc(len(seq) * max(n, 0), line, MAX_TEXT if isinstance(seq, str) else MAX_ITEMS)
        elif kind is ast.Add and isinstance(a, (str, list, tuple)) and isinstance(b, (str, list, tuple)):
            self.alloc(len(a) + len(b), line, MAX_TEXT if isinstance(a, str) else MAX_ITEMS)
        elif kind is ast.Pow and isinstance(b, (int, float)) and isinstance(a, (int, float)):
            if abs(b) > 1000 or (abs(a) > 1 and abs(b) * len(str(int(abs(a)))) > 4000):
                raise ScriptError("too_big", line, n=4000)
        elif kind is ast.Mod and isinstance(a, str) and _BIG_NUMBER.search(a):
            raise ScriptError("too_big", line, n=MAX_TEXT)
        try:
            return BINOPS[kind](a, b)
        except ZeroDivisionError:
            raise ScriptError("zero", line) from None
        except TypeError as exc:
            raise ScriptError("type", line, detail="%s %s %s" % (_type_name(a), _op_symbol(op), _type_name(b))) \
                from exc
        except (ValueError, OverflowError) as exc:
            raise ScriptError("value", line, detail=str(exc)) from None

    def call(self, node):
        line = node.lineno
        func = self.eval(node.func)
        label = ast.unparse(node.func)
        if not isinstance(func, Func):
            raise ScriptError("not_callable", line, name=label)
        args = [self.eval(a) for a in node.args]
        kwargs = {}
        for kw in node.keywords:
            name = kw.arg
            if func.kwargs is not None:
                canonical = N.KWARG_INDEX.get(fold(name))
                if canonical not in func.kwargs:
                    raise ScriptError("unknown_kwarg", line, func=label, name=name,
                                      options=", ".join(N.name_in(N.KWARGS, k, self.lang) for k in func.kwargs) or "-")
                name = canonical
            kwargs[name] = self.eval(kw.value)
        try:
            return func.impl(self, line, *args, **kwargs)
        except (ScriptError, _Stop, _Break, _Continue):
            raise
        except TypeError as exc:
            raise ScriptError("bad_call", line, func=label, detail=_clean(exc)) from None
        except ValueError as exc:
            raise ScriptError("value", line, detail=_clean(exc)) from None
        except (IndexError, KeyError) as exc:
            raise ScriptError("index" if isinstance(exc, IndexError) else "key", line, detail=_clean(exc)) from None
        except ZeroDivisionError:
            raise ScriptError("zero", line) from None

    # -- ações -----------------------------------------------------------------------------
    def send(self, line, method, params, buttons, reply=False, media=None, to=None):
        chat_id = self.chat_id if to is None else (to if isinstance(to, int) or str(to).startswith("@") else _int(to, line))
        if chat_id is None:
            raise ScriptError("no_chat", line)
        if self.sends >= MAX_SENDS:
            raise ScriptError("sends", line, n=MAX_SENDS)
        params = {k: v for k, v in params.items() if v not in (None, "")}
        for key in ("text", "caption"):
            if key in params:
                text = str(params[key])
                params[key] = text[:4096 if key == "text" else 1024]
        if method == "sendMessage" and not params.get("text", "").strip():
            return False
        kb = _rows(self, line, buttons)
        if kb:
            params["reply_markup"] = kb
        if reply and self.reply_to and to is None:
            params["reply_parameters"] = self.reply_to
        files = None
        if media:
            key, value = media
            if isinstance(value, FileRef):
                if value.path:
                    files = {key: value.path}
                else:
                    params[key] = value.info.get("file_id", "")
            else:
                value = str(value)
                if os.path.isfile(value):
                    files = {key: value}
                else:
                    params[key] = value
        self.sends += 1
        return bool(self.external(self.host.send, chat_id, method, params, files))

    def download(self, ref, line):
        if not isinstance(ref, FileRef) or not ref.info.get("file_id"):
            raise ScriptError("no_file", line)
        if ref.path:
            return ref
        try:
            path = self.external(self.host.download, ref.info)
        except ScriptError:
            raise
        except Exception as exc:  # noqa: BLE001
            raise ScriptError("download", line, detail=_clean(exc)) from None
        ref.path = path
        return ref


def _int(value, line):
    try:
        return int(str(value).strip())
    except ValueError:
        raise ScriptError("value", line, detail=str(value)) from None


def _type_name(v):
    if isinstance(v, Proxy):
        return v.kind
    return type(v).__name__


def _op_symbol(op):
    return {ast.Add: "+", ast.Sub: "-", ast.Mult: "*", ast.Div: "/", ast.FloorDiv: "//", ast.Mod: "%",
            ast.Pow: "**"}.get(type(op), "?")


def _clean(exc):
    return str(exc).strip() or type(exc).__name__


# -- execução ---------------------------------------------------------------------------------

def run(code, host, message=None, user=None, chat=None, args="", variables=None, lang=None,
        default_lang="pt", is_admin=False):
    """Executa. ``{"ok", "stopped", "sent", "error": {...} | None, "lang"}``.

    ``variables`` (dict) é alterado no lugar; quem chama guarda depois.
    """
    lang = lang or detect_language(code or "", default_lang)
    interp = Interpreter(host, lang, message, user, chat, args,
                         Variables(variables or {}) if not isinstance(variables, Variables) else variables,
                         is_admin)
    outcome = {"ok": True, "stopped": False, "sent": 0, "error": None, "lang": lang,
               "variables": interp.objects["variables"]}
    try:
        tree = _parse(code or "")
        interp.run(tree)
    except _Stop:
        outcome["stopped"] = True
    except (_Break, _Continue):
        pass
    except ScriptError as exc:
        outcome["ok"] = False
        outcome["error"] = exc.to_dict(lang)
    except RecursionError:
        outcome["ok"] = False
        outcome["error"] = ScriptError("too_deep", 0).to_dict(lang)
    except Exception as exc:  # noqa: BLE001 - nunca derruba o bot
        outcome["ok"] = False
        outcome["error"] = ScriptError("internal", 0, detail="%s: %s" % (type(exc).__name__, exc)).to_dict(lang)
    outcome["sent"] = interp.sends
    return outcome


def storable(variables):
    """Valida o que vai para o disco (só JSON simples, até ``MAX_VARIABLES``)."""
    try:
        raw = json.dumps(dict(variables), ensure_ascii=False)
    except (TypeError, ValueError):
        return None
    if len(raw.encode("utf-8")) > MAX_VARIABLES:
        return None
    return json.loads(raw)
