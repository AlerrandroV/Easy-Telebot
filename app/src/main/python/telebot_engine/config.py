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

MEDIA_TYPES = ("photo", "video", "animation", "audio", "voice", "document", "sticker")


def _clean_trigger(value):
    return str(value or "").strip().lstrip("/").lower()


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
            if isinstance(b, dict) and b.get("text") and b.get("url"):
                clean.append({"text": str(b["text"]), "url": str(b["url"])})
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
            "messages": messages,
        })
    b = data.get("broadcast") if isinstance(data.get("broadcast"), dict) else {}
    try:
        b_level = max(1, min(10, int(b.get("admin_level", 1))))
    except (TypeError, ValueError):
        b_level = 1
    b_triggers = [_clean_trigger(t) for t in b.get("triggers") or ["post", "publi"]]
    return {
        "version": 2,
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
