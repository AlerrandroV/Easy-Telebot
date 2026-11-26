"""Leitura do ``config.json`` de um bot (o app grava; o motor só lê).

Formato atual (versão 2)::

    {
      "version": 2,
      "auto_menu": true,
      "commands": [
        {
          "id": "abc",
          "triggers": ["start", "iniciar"],
          "description": "Começar",
          "show_in_menu": true,
          "admin_level": null,          (ou 1..10: só admins dessa classe ou acima)
          "messages": [
            {
              "text": "Olá, *{nome}*!",
              "media": {"type": "photo", "file": "media/x.jpg", "name": "loja.jpg"},
              "buttons": [[{"text": "Site", "url": "https://..."}]],
              "link_preview": false
            }
          ]
        }
      ]
    }

A versão 1 (``command`` + ``response``) é convertida automaticamente.
"""

import json

from . import antispam, automations, flows, i18n, pyfacil, termux, webfetch
from .shop import catalog as shop_catalog

MEDIA_TYPES = ("photo", "video", "animation", "audio", "voice", "document", "sticker")


def _clean_trigger(value):
    return str(value or "").strip().lstrip("/").lower()


# user/channel/group são links (t.me/... ou @nome); o tipo só muda o ícone no app.
LINK_TYPES = ("url", "user", "channel", "group")
BUTTON_TYPES = LINK_TYPES + ("copy", "node", "webapp", "callback")


def normalize_button(b):
    """``{"text", "type", "value"}``. Tipos: ``url`` (link ou @), ``copy`` (copiar texto),
    ``node`` (ir para uma etapa de fluxo), ``webapp`` (mini app) e ``callback`` (dado
    entregue aos scripts do Termux). Botões antigos ``{"text", "url"}`` viram ``url``."""
    if not isinstance(b, dict) or not str(b.get("text") or "").strip():
        return None
    kind = b.get("type") if b.get("type") in BUTTON_TYPES else "url"
    value = str(b.get("value") if b.get("value") is not None else b.get("url") or "").strip()
    if not value:
        return None
    out = {"text": str(b["text"]), "type": kind, "value": value}
    if kind in LINK_TYPES:
        out["url"] = value  # compatibilidade com quem lê "url"
    if kind == "callback" and isinstance(b.get("reaction"), dict):
        # Botão "pasta": o que o bot faz quando tocam nele (blocos, inclusive outros botões).
        r = b["reaction"]
        out["reaction"] = {"blocks": _block_list(r.get("blocks")), "edit": bool(r.get("edit", True))}
    return out


def _message(item):
    if not isinstance(item, dict):
        return None
    media = item.get("media")
    if isinstance(media, dict) and media.get("type") in MEDIA_TYPES and (media.get("file") or media.get("file_id")):
        media = {"type": media["type"], "file": str(media.get("file") or ""), "name": str(media.get("name") or ""),
                 "file_id": media.get("file_id")}
    else:
        media = None
    rows = []
    for row in item.get("buttons") or []:
        if isinstance(row, dict):
            row = [row]
        clean = []
        for b in row or []:
            button = normalize_button(b)
            if button:
                clean.append(button)
        if clean:
            rows.append(clean)
    text = str(item.get("text") or "")
    if not text.strip() and media is None:
        return None
    return {
        "text": text,
        "media": media,
        "buttons": rows,
        "link_preview": bool(item.get("link_preview", False)),
        "reply_keyboard": _reply_keyboard(item.get("reply_keyboard")),
    }


def _reply_keyboard(kb):
    """Teclado fixo: linhas de teclas (texto, pedir contato ou pedir localização) ou "remover"."""
    if not isinstance(kb, dict):
        return None
    rows = []
    for row in kb.get("rows") or []:
        keys = []
        for k in row if isinstance(row, list) else []:
            k = {"text": k} if isinstance(k, str) else (k if isinstance(k, dict) else {})
            text = str(k.get("text") or "").strip()[:64]
            if text:
                keys.append({"text": text, "request": k.get("request") if k.get("request") in ("contact", "location") else ""})
        if keys:
            rows.append(keys[:8])
    if rows:
        return {"rows": rows[:12], "remove": False}
    return {"rows": [], "remove": True} if kb.get("remove") else None


BLOCK_TYPES = ("message", "termux", "flow")


WAIT_FIELDS = ("any", "type", "text")
WAIT_OPS = ("is", "is_not", "equals", "contains", "starts", "ends", "number", "email", "phone")


def _condition(c):
    c = c if isinstance(c, dict) else {}
    field = c.get("field") if c.get("field") in WAIT_FIELDS else "any"
    op = c.get("op") if c.get("op") in WAIT_OPS else ("is" if field == "type" else "contains")
    return {"field": field, "op": op, "value": str(c.get("value") or "")[:200]}


def _block_list(raw):
    """Lista de blocos (recursiva: botões callback e regras de espera têm os seus)."""
    out = []
    for b in raw if isinstance(raw, list) else []:
        if not isinstance(b, dict):
            continue
        kind = b.get("type")
        if kind == "message":
            m = _message(b)
            if m:
                out.append({"type": "message", "message": m})
        elif kind == "termux":
            sc = termux.normalize_script(b)
            if sc:
                out.append({"type": "termux", "script": sc})
        elif kind == "code":
            code = str(b.get("code") or "")[:pyfacil.MAX_CODE]
            if code.strip():
                out.append({"type": "code", "code": code})
        elif kind == "http":
            h = webfetch.normalize_block(b)
            if h:
                out.append(h)
        elif kind == "flow" and b.get("node"):
            out.append({"type": "flow", "node": str(b["node"])})
        elif kind == "wait":
            var = str(b.get("variable") or "").strip().lower()
            out.append({
                "type": "wait",
                "variable": var if flows.VAR_NAME.match(var) else "",
                "rules": [{"condition": _condition(r.get("condition")), "blocks": _block_list(r.get("blocks")),
                           "keep": bool(r.get("keep", False))}
                          for r in b.get("rules") or [] if isinstance(r, dict)],
                "otherwise": _block_list(b.get("otherwise")),
                "otherwise_keep": bool(b.get("otherwise_keep", True)),
                "code": code if (code := str(b.get("code") or "")[:pyfacil.MAX_CODE]).strip() else "",
            })
    return out


def _index(commands):
    """Botões callback e esperas de todos os comandos, para o motor achar na hora.

    Cada espera ganha um id pelo caminho (``comando/0/r1/2``); cada callback leva a classe
    de admin do comando de origem (quem não pode usar o comando também não usa os botões).
    """
    callbacks, waits = {}, {}

    def walk(blocks, path, level):
        for i, b in enumerate(blocks):
            here = "%s/%d" % (path, i)
            if b["type"] == "message":
                for row in b["message"]["buttons"]:
                    for btn in row:
                        if btn["type"] == "callback" and "reaction" in btn and btn["value"] not in callbacks:
                            callbacks[btn["value"]] = dict(btn["reaction"], admin_level=level, text=btn["text"])
                            walk(btn["reaction"]["blocks"], "%s/cb:%s" % (here, btn["value"]), level)
            elif b["type"] == "wait":
                b["id"] = here
                waits[here] = dict(b, admin_level=level)
                for j, r in enumerate(b["rules"]):
                    walk(r["blocks"], "%s/r%d" % (here, j), level)
                walk(b["otherwise"], here + "/else", level)

    for c in commands:
        walk(c["blocks"], c["id"], c["admin_level"])
    return callbacks, waits


def _blocks(item, legacy_messages):
    """A resposta do comando: blocos executados em ordem.

    ``[{"type": "message", ...mensagem}, {"type": "termux", "command", "timeout"},
    {"type": "flow", "node"}]``. Comandos antigos (``messages``/``script``/``flow``)
    viram blocos equivalentes.
    """
    if isinstance(item.get("blocks"), list):
        return _block_list(item["blocks"])
    if item.get("flow"):
        return [{"type": "flow", "node": str(item["flow"])}]
    script = termux.normalize_script(item.get("script"))
    if script:
        return [{"type": "termux", "script": script}]
    return [{"type": "message", "message": m} for m in legacy_messages]


def _level(item):
    """Classe de admin exigida (``None`` = qualquer pessoa)."""
    level = item.get("admin_level")
    if level is None and item.get("admin_only"):
        level = 1
    if level is None:
        return None
    try:
        return max(1, min(10, int(level)))
    except (TypeError, ValueError):
        return 1


def normalize(data):
    data = data if isinstance(data, dict) else {}
    commands, autos = [], []
    for i, item in enumerate(data.get("commands") or []):
        if not isinstance(item, dict):
            continue
        kind = item.get("kind") if item.get("kind") in automations.KINDS else "command"
        if kind != "command":
            spec = automations.normalize_spec(kind, item.get("trigger"))
            if spec is not None and item.get("enabled", True):
                autos.append({
                    "id": str(item.get("id") or "auto%d" % i),
                    "kind": kind,
                    "trigger": spec,
                    "admin_level": _level(item),
                    "description": str(item.get("description") or "").strip(),
                    "blocks": _block_list(item.get("blocks")),
                })
            continue
        if "triggers" in item:
            triggers = [_clean_trigger(t) for t in item.get("triggers") or []]
            messages = [m for m in (_message(x) for x in item.get("messages") or []) if m]
        else:  # versão 1
            triggers = [_clean_trigger(item.get("command"))]
            messages = [m for m in [_message({"text": item.get("response")})] if m]
        triggers = [t for t in dict.fromkeys(triggers) if t]
        if not triggers:
            continue
        level = _level(item)
        commands.append({
            "id": str(item.get("id") or "cmd%d" % i),
            "admin_level": level,
            "triggers": triggers,
            "description": str(item.get("description") or "").strip(),
            "show_in_menu": bool(item.get("show_in_menu", True)),
        })
        blocks = _blocks(item, messages)
        commands[-1].update({
            "blocks": blocks,
            # Atalhos para quem só precisa de uma parte (menu, botões de script, fluxos...).
            "messages": [b["message"] for b in blocks if b["type"] == "message"],
            "script": next((b["script"] for b in blocks if b["type"] == "termux"), None),
            "flow": next((b["node"] for b in blocks if b["type"] == "flow"), None),
        })
    callbacks, waits = _index(commands + autos)
    b = data.get("broadcast") if isinstance(data.get("broadcast"), dict) else {}
    try:
        b_level = max(1, min(10, int(b.get("admin_level", 1))))
    except (TypeError, ValueError):
        b_level = 1
    b_triggers = [_clean_trigger(t) for t in b.get("triggers") or ["post", "publi"]]
    return {
        "version": 2,
        "language": i18n.normalize(data.get("language")),
        "antispam": antispam.normalize_settings(data.get("antispam")),
        "termux": termux.normalize_settings(data.get("termux")),
        "flows": flows.normalize(data.get("flows"), _message),
        "callbacks": callbacks,
        "waits": waits,
        "shop": shop_catalog.normalize(data.get("shop"), _message),
        "auto_menu": bool(data.get("auto_menu", True)),
        "commands": commands,
        "automations": autos,
        "broadcast": {
            "enabled": bool(b.get("enabled", True)),
            "admin_level": b_level,
            "triggers": [t for t in b_triggers if t] or ["post", "publi"],
        },
    }


def load(path):
    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        data = {}
    return normalize(data)
