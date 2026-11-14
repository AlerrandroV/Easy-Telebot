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

MEDIA_TYPES = ("photo", "video", "animation", "audio", "voice", "document")


def _clean_trigger(value):
    return str(value or "").strip().lstrip("/").lower()


def _message(item):
    if not isinstance(item, dict):
        return None
    media = item.get("media")
    if isinstance(media, dict) and media.get("type") in MEDIA_TYPES and media.get("file"):
        media = {"type": media["type"], "file": str(media["file"]), "name": str(media.get("name") or "")}
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
        commands.append({
            "id": str(item.get("id") or "cmd%d" % i),
            "triggers": triggers,
            "description": str(item.get("description") or "").strip(),
            "show_in_menu": bool(item.get("show_in_menu", True)),
            "messages": messages,
        })
    return {
        "version": 2,
        "auto_menu": bool(data.get("auto_menu", True)),
        "commands": commands,
    }


def load(path):
    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        data = {}
    return normalize(data)
