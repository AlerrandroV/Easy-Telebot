"""Internet sem Termux: requisições HTTP com limites, o bloco "Requisição HTTP" e testes do app.

``fetch(url, method, headers, body, timeout)`` devolve ``{"ok", "status", "text", "json",
"error"}``. Só http(s), até ``MAX_BYTES`` de resposta, ``MAX_TIMEOUT`` segundos.

Bloco HTTP (na resposta de um comando ou gatilho)::

    {"type": "http", "method": "GET", "url": "https://api.exemplo.com/preco?item={args}",
     "headers": "Authorization: Bearer abc", "body": "", "body_type": "json",
     "variable": "cotacao", "path": "data.price", "timeout": 15}

Os valores das variáveis entram na URL já codificados (``{args}`` = "São Paulo" vira
``S%C3%A3o%20Paulo``). O resultado vira ``{cotacao}`` nas mensagens seguintes, e
``{cotacao_status}`` traz o código HTTP. ``path`` escolhe um pedaço do JSON:
``data.price``, ``items.0.title`` ou ``[0].name``.
"""

import json
import re
import ssl
import urllib.error
import urllib.parse
import urllib.request

MAX_BYTES = 1024 * 1024
MAX_TIMEOUT = 30
DEFAULT_TIMEOUT = 15
METHODS = ("GET", "POST", "PUT", "PATCH", "DELETE")
BODY_TYPES = ("json", "form", "text")
USER_AGENT = "EasyTelebot/1.0 (+https://github.com/AlerrandroV/Easy-Telebot)"
_SSL = ssl.create_default_context()
_VAR = re.compile(r"\{([^\W\d]\w*)\}")
_VAR_NAME = re.compile(r"^[a-z_][a-z0-9_]{0,31}$")


def _open(request, timeout):
    """Separado para os testes trocarem por um falso."""
    return urllib.request.urlopen(request, timeout=timeout, context=_SSL)


def fetch(url, method="GET", headers=None, body=None, timeout=DEFAULT_TIMEOUT):
    url = str(url or "").strip()
    if not re.match(r"^https?://", url, re.I):
        return {"ok": False, "status": 0, "text": "", "json": None, "error": "url"}
    method = method.upper() if str(method).upper() in METHODS else "GET"
    hdrs = {"User-Agent": USER_AGENT, "Accept": "application/json, text/plain, */*"}
    hdrs.update({str(k): str(v) for k, v in (headers or {}).items()})
    data = None
    if body is not None and method != "GET":
        data = body if isinstance(body, bytes) else str(body).encode("utf-8")
    try:
        timeout = max(1, min(int(timeout or DEFAULT_TIMEOUT), MAX_TIMEOUT))
    except (TypeError, ValueError):
        timeout = DEFAULT_TIMEOUT
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    status, raw, error = 0, b"", None
    try:
        with _open(req, timeout) as resp:
            status = resp.status
            raw = resp.read(MAX_BYTES + 1)
    except urllib.error.HTTPError as exc:
        status = exc.code
        try:
            raw = exc.read(MAX_BYTES + 1)
        except Exception:  # noqa: BLE001
            raw = b""
    except Exception as exc:  # noqa: BLE001 - sem internet, DNS, tempo esgotado...
        error = "%s: %s" % (type(exc).__name__, getattr(exc, "reason", exc))
    if len(raw) > MAX_BYTES:
        raw = raw[:MAX_BYTES]
        error = error or "too_big"
    text = raw.decode("utf-8", "replace")
    try:
        parsed = json.loads(text) if text.strip()[:1] in ("{", "[") else None
    except ValueError:
        parsed = None
    return {"ok": error is None and 200 <= status < 300, "status": status, "text": text, "json": parsed,
            "error": error}


def extract(data, path):
    """``"data.items.0.title"`` ou ``"[0].name"`` dentro de um JSON. ``None`` se não existir."""
    if not path:
        return data
    cur = data
    for part in re.findall(r"[^.\[\]]+", path):
        if isinstance(cur, list):
            try:
                cur = cur[int(part)]
            except (ValueError, IndexError):
                return None
        elif isinstance(cur, dict):
            if part not in cur:
                return None
            cur = cur[part]
        else:
            return None
    return cur


def as_text(value):
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    if isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False)
    return str(value)


# -- bloco "Requisição HTTP" ----------------------------------------------------------------

def normalize_block(b):
    url = str(b.get("url") or "").strip()[:2000]
    if not url:
        return None
    var = str(b.get("variable") or "").strip().lower()
    try:
        timeout = max(1, min(int(b.get("timeout") or DEFAULT_TIMEOUT), MAX_TIMEOUT))
    except (TypeError, ValueError):
        timeout = DEFAULT_TIMEOUT
    return {
        "type": "http",
        "method": b.get("method") if b.get("method") in METHODS else "GET",
        "url": url,
        "headers": str(b.get("headers") or "")[:4000],
        "body": str(b.get("body") or "")[:20000],
        "body_type": b.get("body_type") if b.get("body_type") in BODY_TYPES else "json",
        "variable": var if _VAR_NAME.match(var) else "http",
        "path": str(b.get("path") or "").strip()[:200],
        "timeout": timeout,
    }


def _fill(text, context, encode=None):
    def var(m):
        if m.group(1) not in context:
            return m.group(0)
        value = as_text(context[m.group(1)])
        return encode(value) if encode else value
    return _VAR.sub(var, text or "")


def _json_fill(text, context):
    """No corpo JSON, cada valor vira texto JSON seguro (aspas e barras escapadas)."""
    def var(m):
        if m.group(1) not in context:
            return m.group(0)
        return json.dumps(as_text(context[m.group(1)]), ensure_ascii=False)[1:-1]
    return _VAR.sub(var, text or "")


def prepare(block, context):
    """URL, cabeçalhos e corpo com as variáveis preenchidas."""
    url = _fill(block["url"], context, encode=lambda v: urllib.parse.quote(v, safe=""))
    headers = {}
    for line in _fill(block["headers"], context).splitlines():
        name, sep, value = line.partition(":")
        if sep and name.strip():
            headers[name.strip()] = value.strip()
    body = None
    if block["method"] != "GET" and block["body"].strip():
        kind = block["body_type"]
        if kind == "json":
            body = _json_fill(block["body"], context)
            headers.setdefault("Content-Type", "application/json")
        elif kind == "form":
            body = _fill(block["body"], context, encode=lambda v: urllib.parse.quote_plus(v))
            headers.setdefault("Content-Type", "application/x-www-form-urlencoded")
        else:
            body = _fill(block["body"], context)
            headers.setdefault("Content-Type", "text/plain; charset=utf-8")
    return url, headers, body


def run_block(block, context):
    """Faz a requisição. Devolve ``(variáveis novas, resultado)``."""
    url, headers, body = prepare(block, context)
    res = fetch(url, block["method"], headers, body, block["timeout"])
    if block["path"] and res["json"] is not None:
        value = extract(res["json"], block["path"])
    elif block["path"]:
        value = None
    else:
        value = res["json"] if res["json"] is not None else res["text"].strip()
    text = as_text(value)[:4000]
    var = block["variable"]
    return {var: text, var + "_status": str(res["status"])}, res


def test_block(block_json, lang=None):
    """Para o botão "Testar" do app: roda o bloco com valores de exemplo nas variáveis."""
    from . import i18n
    try:
        raw = json.loads(block_json)
    except ValueError:
        raw = {}
    block = normalize_block(raw if isinstance(raw, dict) else {})
    if block is None:
        return json.dumps({"ok": False, "status": 0, "value": "", "preview": "", "error": "url"})
    sample = {"args": i18n.t("http.sample_args", lang), "nome": "Ana", "name": "Ana", "id": "123456789",
              "usuario": "@ana", "username": "@ana"}
    vars_, res = run_block(block, sample)
    return json.dumps({"ok": res["ok"], "status": res["status"], "value": vars_[block["variable"]][:2000],
                       "preview": res["text"][:3000], "error": res["error"] or ""}, ensure_ascii=False)
