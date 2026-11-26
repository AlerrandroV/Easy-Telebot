"""Notícias automáticas: posts novos de um site (RSS/Atom) ou canal do YouTube → chats do bot.

Gatilho ``feed`` (na lista de comandos)::

    {"kind": "feed", "trigger": {"url": "https://site.com/feed", "every": 30,
     "target": "chats", "chats": [-1001234]}, "blocks": [...]}

Na resposta, as variáveis do post: ``{titulo}``/``{title}``, ``{link}``, ``{resumo}``/``{summary}``,
``{autor}``/``{author}`` e ``{imagem}``/``{image}``. Na primeira verificação, os posts que já
existem só são marcados como vistos (o canal não recebe uma enxurrada); daí em diante, cada
post novo vira uma mensagem (até 5 por verificação, do mais antigo para o mais novo).

Canal do YouTube: aceita o link do feed (``youtube.com/feeds/videos.xml?channel_id=UC…``) ou
o do canal com o ID (``youtube.com/channel/UC…``).
"""

import html
import json
import os
import re
import threading
import time
import xml.etree.ElementTree as ET

from . import webfetch

STATE_FILE = "feeds_state.json"
MAX_NEW = 5
MAX_SEEN = 300
MIN_EVERY = 10
_lock = threading.RLock()
_TAGS = re.compile(r"<[^>]+>")
_YT_CHANNEL = re.compile(r"youtube\.com/channel/(UC[\w-]{10,})", re.I)


def normalize_url(url):
    url = str(url or "").strip()
    m = _YT_CHANNEL.search(url)
    if m:
        return "https://www.youtube.com/feeds/videos.xml?channel_id=" + m.group(1)
    if url and not re.match(r"^https?://", url, re.I):
        url = "https://" + url
    return url


def normalize_spec(raw):
    from . import scheduler
    raw = raw if isinstance(raw, dict) else {}
    url = normalize_url(raw.get("url"))
    if not re.match(r"^https?://[^\s/]+\.[^\s]+", url):
        return None
    spec = scheduler.normalize_schedule(dict(raw, mode="interval", time="00:00",
                                             every=max(MIN_EVERY, int(raw.get("every") or 30))))
    if spec is None:
        return None
    return {"url": url[:1000], "every": spec["every"], "target": spec["target"], "chats": spec["chats"]}


def _local(tag):
    return tag.rsplit("}", 1)[-1].lower()


def _child(el, *names):
    for c in el:
        if _local(c.tag) in names:
            return c
    return None


def _text(el):
    return (el.text or "").strip() if el is not None else ""


def _clean(text, limit=600):
    text = html.unescape(_TAGS.sub(" ", text or ""))
    text = re.sub(r"\s+", " ", text).strip()
    return text[:limit - 1] + "…" if len(text) > limit else text


def parse(xml_text):
    """Posts do feed, do mais novo para o mais antigo: ``[{"id", "title", "link", "summary", "author", "image"}]``."""
    try:
        root = ET.fromstring(xml_text.encode("utf-8") if isinstance(xml_text, str) else xml_text)
    except ET.ParseError:
        return None
    items = []
    entries = [e for e in root.iter() if _local(e.tag) in ("item", "entry")]
    for e in entries:
        title = _clean(_text(_child(e, "title")), 300)
        link = ""
        for c in e:
            if _local(c.tag) == "link":
                link = c.get("href") or _text(c) or link
                if c.get("rel") in (None, "alternate") and link:
                    break
        summary = ""
        for name in ("description", "summary", "content", "encoded"):
            node = _child(e, name)
            if node is not None and (node.text or "").strip():
                summary = _clean(node.text)
                break
        image = ""
        for c in e.iter():
            tag = _local(c.tag)
            if tag in ("thumbnail", "content") and c.get("url"):
                image = c.get("url")
                break
            if tag == "enclosure" and (c.get("type") or "").startswith("image") and c.get("url"):
                image = c.get("url")
                break
        if not summary:  # YouTube: a descrição fica em media:group/media:description
            for c in e.iter():
                if _local(c.tag) == "description" and (c.text or "").strip():
                    summary = _clean(c.text)
                    break
        author_node = _child(e, "author", "creator")
        author = _text(_child(author_node, "name")) if author_node is not None and len(author_node) else _text(author_node)
        guid = _text(_child(e, "guid", "id")) or link or title
        if guid:
            items.append({"id": guid[:300], "title": title, "link": link, "summary": summary,
                          "author": _clean(author, 100), "image": image})
    return items


def item_vars(item):
    """Variáveis do post para os blocos (português e inglês)."""
    return {"titulo": item["title"], "title": item["title"], "link": item["link"], "resumo": item["summary"],
            "summary": item["summary"], "autor": item["author"], "author": item["author"],
            "imagem": item["image"], "image": item["image"]}


def check(bot_dir, auto, now=None):
    """Verifica um feed (se já deu o tempo). Devolve os posts novos (mais antigo primeiro) ou ``[]``."""
    now = now or time.time()
    spec = auto["trigger"]
    path = os.path.join(bot_dir, STATE_FILE)
    with _lock:
        state = _load(path)
        entry = state.get(auto["id"]) or {}
        if entry.get("url") == spec["url"] and now - entry.get("checked", 0) < spec["every"] * 60 - 5:
            return []
    res = webfetch.fetch(spec["url"], timeout=20)
    items = parse(res["text"]) if res["ok"] else None
    with _lock:
        state = _load(path)
        entry = state.get(auto["id"]) or {}
        first_time = entry.get("url") != spec["url"]
        entry.update({"url": spec["url"], "checked": now})
        if items is None:
            entry["error"] = res["error"] or "HTTP %s" % res["status"]
            state[auto["id"]] = entry
            _save(path, state)
            return None
        seen = entry.get("seen") or []
        new = [] if first_time else [i for i in items if i["id"] not in seen][:MAX_NEW]
        entry["seen"] = ([i["id"] for i in items] + [s for s in seen if s not in {i["id"] for i in items}])[:MAX_SEEN]
        entry.pop("error", None)
        state[auto["id"]] = entry
        _save(path, state)
    return list(reversed(new))


def preview(url):
    """Para o botão "Testar" do app: título e posts mais recentes."""
    url = normalize_url(url)
    res = webfetch.fetch(url, timeout=20)
    items = parse(res["text"]) if res["ok"] else None
    if items is None:
        return json.dumps({"ok": False, "url": url, "error": res["error"] or "HTTP %s" % res["status"], "items": []})
    return json.dumps({"ok": True, "url": url, "error": "", "items": [{"title": i["title"], "link": i["link"]} for i in items[:5]]},
                      ensure_ascii=False)


def _load(path):
    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def _save(path, data):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False)
    os.replace(tmp, path)
