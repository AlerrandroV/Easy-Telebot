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

from . import antispam, flows, termux
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
    }


BLOCK_TYPES = ("message", "termux", "flow")


def _blocks(item, legacy_messages):
    """A resposta do comando: blocos executados em ordem.

    ``[{"type": "message", ...mensagem}, {"type": "termux", "command", "timeout"},
    {"type": "flow", "node"}]``. Comandos antigos (``messages``/``script``/``flow``)
    viram blocos equivalentes.
    """
    raw = item.get("blocks")
    out = []
    if isinstance(raw, list):
        for b in raw:
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
            elif kind == "flow" and b.get("node"):
                out.append({"type": "flow", "node": str(b["node"])})
        return out
    if item.get("flow"):
        return [{"type": "flow", "node": str(item["flow"])}]
    script = termux.normalize_script(item.get("script"))
    if script:
        return [{"type": "termux", "script": script}]
    return [{"type": "message", "message": m} for m in legacy_messages]


def normalize(data):
    data = data if isinstance(data, dict) else {}
    commands = []
    for i, item in enumerate(data.get("commands") or []):
        if not isinstance(item, dict):
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
        level = item.get("admin_level")
        if level is None and item.get("admin_only"):
            level = 1
        if level is not None:
            try:
                level = max(1, min(10, int(level)))
            except (TypeError, ValueError):
                level = 1
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
    b = data.get("broadcast") if isinstance(data.get("broadcast"), dict) else {}
    try:
        b_level = max(1, min(10, int(b.get("admin_level", 1))))
    except (TypeError, ValueError):
        b_level = 1
    b_triggers = [_clean_trigger(t) for t in b.get("triggers") or ["post", "publi"]]
    return {
        "version": 2,
        "antispam": antispam.normalize_settings(data.get("antispam")),
        "termux": termux.normalize_settings(data.get("termux")),
        "flows": flows.normalize(data.get("flows"), _message),
        "shop": shop_catalog.normalize(data.get("shop"), _message),
        "auto_menu": bool(data.get("auto_menu", True)),
        "commands": commands,
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
