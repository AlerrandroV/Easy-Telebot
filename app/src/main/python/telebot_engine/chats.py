"""Conversas do bot (o "mensageiro" do app), num SQLite local por bot: ``chats.db``.

Guarda toda mensagem recebida, as respostas automáticas e o que o dono envia
pelo app. As mídias ficam só como ``file_id``; o arquivo é baixado sob demanda
(``download``) para ``chat_media/`` e reaproveitado depois.

O motor (gravando) e o app (lendo) usam este mesmo módulo e a mesma trava.
"""

import json
import os
import sqlite3
import threading
import time

from . import errors
from .api import TelegramApi, TelegramError

DB_FILE = "chats.db"
MEDIA_DIR = "chat_media"
MEDIA_KINDS = ("animation", "video", "video_note", "photo", "sticker", "voice", "audio", "document")

_lock = threading.RLock()
_conns = {}

_SCHEMA = """
CREATE TABLE IF NOT EXISTS chats (
    chat_id INTEGER PRIMARY KEY,
    type TEXT, title TEXT, first_name TEXT, last_name TEXT, username TEXT,
    last_at INTEGER DEFAULT 0, last_kind TEXT, last_text TEXT, last_outgoing INTEGER DEFAULT 0,
    unread INTEGER DEFAULT 0, photo TEXT, photo_checked INTEGER DEFAULT 0
);
CREATE TABLE IF NOT EXISTS messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    chat_id INTEGER NOT NULL, message_id INTEGER NOT NULL,
    outgoing INTEGER DEFAULT 0, source TEXT,
    from_id INTEGER, from_name TEXT, date INTEGER, edited INTEGER DEFAULT 0, deleted INTEGER DEFAULT 0,
    kind TEXT, text TEXT, entities TEXT,
    file_id TEXT, file_unique_id TEXT, thumb_id TEXT, file_name TEXT, mime TEXT, file_size INTEGER,
    width INTEGER, height INTEGER, duration INTEGER, emoji TEXT, sticker_format TEXT,
    reply_to INTEGER, buttons TEXT,
    UNIQUE(chat_id, message_id)
);
CREATE INDEX IF NOT EXISTS idx_messages_chat ON messages(chat_id, message_id);
"""


def _db(bot_dir):
    path = os.path.join(bot_dir, DB_FILE)
    conn = _conns.get(path)
    if conn is None:
        os.makedirs(bot_dir, exist_ok=True)
        conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.executescript(_SCHEMA)
        _conns[path] = conn
    return conn


def close(bot_dir):
    with _lock:
        conn = _conns.pop(os.path.join(bot_dir, DB_FILE), None)
        if conn:
            conn.close()


# -- gravação ------------------------------------------------------------------------

def _full_name(user):
    return " ".join(x for x in ((user or {}).get("first_name"), (user or {}).get("last_name")) if x)


def parse_message(msg):
    """Extrai de um ``Message`` do Telegram os campos que a tela usa."""
    out = {
        "kind": "text", "text": msg.get("text") or msg.get("caption") or "",
        "entities": msg.get("entities") or msg.get("caption_entities") or [],
        "file_id": None, "file_unique_id": None, "thumb_id": None, "file_name": None, "mime": None,
        "file_size": None, "width": None, "height": None, "duration": None, "emoji": None,
        "sticker_format": None,
    }
    for kind in MEDIA_KINDS:
        obj = msg.get(kind)
        if not obj:
            continue
        out["kind"] = kind
        if kind == "photo":  # lista de tamanhos: maior para abrir, ~320px para a prévia
            big = obj[-1]
            small = next((p for p in obj if p.get("width", 0) >= 320), big)
            out.update(file_id=big.get("file_id"), file_unique_id=big.get("file_unique_id"),
                       thumb_id=small.get("file_id"), width=big.get("width"), height=big.get("height"),
                       file_size=big.get("file_size"))
        else:
            thumb = obj.get("thumbnail") or obj.get("thumb") or {}
            out.update(file_id=obj.get("file_id"), file_unique_id=obj.get("file_unique_id"),
                       thumb_id=thumb.get("file_id"), file_name=obj.get("file_name"),
                       mime=obj.get("mime_type"), file_size=obj.get("file_size"),
                       width=obj.get("width"), height=obj.get("height"), duration=obj.get("duration"))
            if kind == "sticker":
                out["emoji"] = obj.get("emoji")
                out["sticker_format"] = "tgs" if obj.get("is_animated") else "webm" if obj.get("is_video") else "webp"
        break
    else:
        if msg.get("location"):
            loc = msg["location"]
            out.update(kind="location", text="%s,%s" % (loc.get("latitude"), loc.get("longitude")))
        elif msg.get("contact"):
            c = msg["contact"]
            out.update(kind="contact", text="%s\n%s" % (_full_name(c), c.get("phone_number", "")))
        elif msg.get("poll"):
            out.update(kind="poll", text=msg["poll"].get("question", ""))
        elif msg.get("new_chat_members") or msg.get("left_chat_member") or msg.get("new_chat_title"):
            out.update(kind="service", text=_service_text(msg))
        elif not out["text"]:
            out["kind"] = "other"
    return out


def _service_text(msg):
    if msg.get("new_chat_members"):
        return "+ " + ", ".join(_full_name(u) for u in msg["new_chat_members"])
    if msg.get("left_chat_member"):
        return "- " + _full_name(msg["left_chat_member"])
    return msg.get("new_chat_title", "")


def record(bot_dir, msg, outgoing=False, source="user", edited=False, fallback_chat_id=None):
    """Grava (ou atualiza, se editada) uma mensagem. Devolve o ``chat_id`` ou ``None``."""
    if not isinstance(msg, dict) or "message_id" not in msg:
        return None
    chat = msg.get("chat") or {"id": fallback_chat_id, "type": "private"}
    if chat.get("id") is None:
        return None
    user = msg.get("from") or {}
    p = parse_message(msg)
    reply = (msg.get("reply_to_message") or {}).get("message_id")
    buttons = (msg.get("reply_markup") or {}).get("inline_keyboard")
    date = msg.get("edit_date") if edited else msg.get("date")
    date = int(date or time.time())
    with _lock:
        db = _db(bot_dir)
        db.execute("BEGIN")
        try:
            db.execute(
                """INSERT INTO messages (chat_id, message_id, outgoing, source, from_id, from_name, date, edited,
                       kind, text, entities, file_id, file_unique_id, thumb_id, file_name, mime, file_size,
                       width, height, duration, emoji, sticker_format, reply_to, buttons)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                   ON CONFLICT(chat_id, message_id) DO UPDATE SET
                       text=excluded.text, entities=excluded.entities, buttons=excluded.buttons,
                       edited=excluded.edited OR messages.edited""",
                (chat["id"], msg["message_id"], int(outgoing), source, user.get("id"),
                 _full_name(user) or chat.get("title") or "", int(msg.get("date") or date), int(edited),
                 p["kind"], p["text"], json.dumps(p["entities"]), p["file_id"], p["file_unique_id"],
                 p["thumb_id"], p["file_name"], p["mime"], p["file_size"], p["width"], p["height"],
                 p["duration"], p["emoji"], p["sticker_format"], reply,
                 json.dumps(buttons) if buttons else None),
            )
            db.execute(
                """INSERT INTO chats (chat_id, type, title, first_name, last_name, username)
                   VALUES (?,?,?,?,?,?)
                   ON CONFLICT(chat_id) DO UPDATE SET type=excluded.type,
                       title=COALESCE(excluded.title, chats.title),
                       first_name=COALESCE(excluded.first_name, chats.first_name),
                       last_name=COALESCE(excluded.last_name, chats.last_name),
                       username=COALESCE(excluded.username, chats.username)""",
                (chat["id"], chat.get("type"), chat.get("title"), chat.get("first_name"),
                 chat.get("last_name"), chat.get("username")),
            )
            if not edited:
                db.execute(
                    """UPDATE chats SET last_at=?, last_kind=?, last_text=?, last_outgoing=?,
                           unread = CASE WHEN ? THEN 0 ELSE unread + 1 END
                       WHERE chat_id=?""",
                    (date, p["kind"], (p["emoji"] or "") if p["kind"] == "sticker" else p["text"][:200],
                     int(outgoing), int(outgoing), chat["id"]),
                )
            db.execute("COMMIT")
        except Exception:
            db.execute("ROLLBACK")
            raise
    return chat["id"]


# -- leitura (app) ---------------------------------------------------------------

def _row(r):
    return {k: r[k] for k in r.keys()}


def list_chats(bot_dir):
    with _lock:
        rows = _db(bot_dir).execute("SELECT * FROM chats ORDER BY last_at DESC").fetchall()
    return json.dumps([_row(r) for r in rows], ensure_ascii=False)


def unread_total(bot_dir):
    if not os.path.exists(os.path.join(bot_dir, DB_FILE)):
        return 0
    with _lock:
        return _db(bot_dir).execute("SELECT COALESCE(SUM(unread), 0) FROM chats").fetchone()[0]


def get_messages(bot_dir, chat_id, before=None, limit=50):
    """Mensagens em ordem cronológica. ``before`` = ``message_id`` para carregar as mais antigas."""
    with _lock:
        db = _db(bot_dir)
        if before:
            rows = db.execute("SELECT * FROM messages WHERE chat_id=? AND message_id<? ORDER BY message_id DESC LIMIT ?",
                              (int(chat_id), int(before), int(limit))).fetchall()
        else:
            rows = db.execute("SELECT * FROM messages WHERE chat_id=? ORDER BY message_id DESC LIMIT ?",
                              (int(chat_id), int(limit))).fetchall()
        items = [_row(r) for r in reversed(rows)]
        reply_ids = {m["reply_to"] for m in items if m["reply_to"]}
        replies = {}
        if reply_ids:
            marks = ",".join("?" * len(reply_ids))
            for r in db.execute("SELECT message_id, outgoing, from_name, kind, text, emoji FROM messages "
                                "WHERE chat_id=? AND message_id IN (%s)" % marks, [int(chat_id)] + list(reply_ids)):
                replies[r["message_id"]] = _row(r)
    for m in items:
        m["entities"] = json.loads(m["entities"] or "[]")
        m["buttons"] = json.loads(m["buttons"]) if m["buttons"] else []
        m["reply"] = replies.get(m["reply_to"])
    return json.dumps(items, ensure_ascii=False)


def mark_read(bot_dir, chat_id):
    with _lock:
        _db(bot_dir).execute("UPDATE chats SET unread=0 WHERE chat_id=?", (int(chat_id),))
    return True


def clear_chat(bot_dir, chat_id):
    """Apaga a conversa só do aparelho (no Telegram ela continua)."""
    with _lock:
        db = _db(bot_dir)
        db.execute("DELETE FROM messages WHERE chat_id=?", (int(chat_id),))
        db.execute("DELETE FROM chats WHERE chat_id=?", (int(chat_id),))
    return True


def stickers(bot_dir, limit=60):
    """Figurinhas que já apareceram nas conversas, para reenviar pelo ``file_id``."""
    with _lock:
        rows = _db(bot_dir).execute(
            """SELECT file_id, file_unique_id, thumb_id, emoji, sticker_format, MAX(date) AS d
               FROM messages WHERE kind='sticker' AND file_id IS NOT NULL
               GROUP BY file_unique_id ORDER BY d DESC LIMIT ?""", (int(limit),)).fetchall()
    return json.dumps([_row(r) for r in rows], ensure_ascii=False)


# -- ações no Telegram (app) ---------------------------------------------------------

def _fail(exc):
    return json.dumps({"ok": False, "error": errors.classify(exc)}, ensure_ascii=False)


def download(token, bot_dir, file_id, unique_id, ext=""):
    """Baixa um arquivo do Telegram (até 20 MB, limite da Bot API) e devolve o caminho local."""
    folder = os.path.join(bot_dir, MEDIA_DIR)
    name = "".join(c for c in (unique_id or file_id) if c.isalnum() or c in "-_")[:80]
    ext = "".join(c for c in (ext or "") if c.isalnum())[:5]
    with _lock:
        for f in os.listdir(folder) if os.path.isdir(folder) else []:
            if f.split(".")[0] == name:
                return json.dumps({"ok": True, "path": os.path.join(folder, f)})
    api = TelegramApi(token)
    try:
        info = api.call("getFile", {"file_id": file_id}, timeout=30)
        remote = info.get("file_path") or ""
        if not ext and "." in remote:
            ext = remote.rsplit(".", 1)[1][:5]
        os.makedirs(folder, exist_ok=True)
        dest = os.path.join(folder, name + ("." + ext if ext else ""))
        api.download(remote, dest, timeout=120)
    except Exception as exc:  # noqa: BLE001
        return _fail(exc)
    return json.dumps({"ok": True, "path": dest})


def chat_photo(token, bot_dir, chat_id):
    """Foto do usuário/grupo (pequena), baixada uma vez por dia no máximo."""
    with _lock:
        row = _db(bot_dir).execute("SELECT type, photo, photo_checked FROM chats WHERE chat_id=?",
                                   (int(chat_id),)).fetchone()
    if row is None:
        return json.dumps({"ok": True, "path": None})
    if row["photo"] and os.path.exists(row["photo"]):
        return json.dumps({"ok": True, "path": row["photo"]})
    if time.time() - (row["photo_checked"] or 0) < 86400:
        return json.dumps({"ok": True, "path": None})
    api = TelegramApi(token)
    path = None
    try:
        info = api.call("getChat", {"chat_id": int(chat_id)}, timeout=20)
        small = (info.get("photo") or {}).get("small_file_id")
        if small:
            remote = api.call("getFile", {"file_id": small}, timeout=20).get("file_path")
            folder = os.path.join(bot_dir, MEDIA_DIR)
            os.makedirs(folder, exist_ok=True)
            path = api.download(remote, os.path.join(folder, "avatar_%s.jpg" % str(chat_id).replace("-", "g")))
    except Exception:  # noqa: BLE001 - foto é opcional
        path = None
    with _lock:
        _db(bot_dir).execute("UPDATE chats SET photo=?, photo_checked=? WHERE chat_id=?",
                             (path, int(time.time()), int(chat_id)))
    return json.dumps({"ok": True, "path": path})


class _SendCallbacks:
    """Recolhe os erros do envio manual (o Engine avisa por eventos)."""

    def __init__(self):
        self.errors = []

    def onEvent(self, data):  # noqa: N802
        e = json.loads(data)
        if e.get("type") == "error":
            self.errors.append({k: e.get(k) for k in ("code", "detail", "retry_after")})

    def nowLocal(self):  # noqa: N802
        return time.strftime("%d/%m/%Y %H:%M")


def send(token, bot_dir, chat_id, payload):
    """Envia uma mensagem em nome do bot, pelo app.

    ``payload`` (JSON): ``{"text", "media": {"type", "file"|"file_id", "name"}, "buttons": [[...]],
    "reply_to": message_id, "link_preview": bool}``. O texto aceita a mesma marcação e as mesmas
    variáveis dos comandos (``{nome}`` vira o nome da pessoa da conversa).
    """
    from . import template
    from .engine import Engine

    data = json.loads(payload) if isinstance(payload, str) else dict(payload)
    with _lock:
        row = _db(bot_dir).execute("SELECT * FROM chats WHERE chat_id=?", (int(chat_id),)).fetchone()
    chat = {"id": int(chat_id), "type": (row and row["type"]) or "private", "title": row and row["title"]}
    user = {"id": int(chat_id), "first_name": (row and row["first_name"]) or "",
            "last_name": (row and row["last_name"]) or "", "username": row and row["username"]}
    cb = _SendCallbacks()
    eng = Engine(token, os.path.join(bot_dir, "config.json"), bot_dir, cb)
    eng.send_source = "manual"
    try:
        me = eng.api.call("getMe", timeout=20)
    except Exception as exc:  # noqa: BLE001
        return _fail(exc)
    stamp = cb.nowLocal()
    context = template.build_context({"from": user, "chat": chat}, "", me.get("username", ""),
                                     *stamp.split(" "))
    msg = {
        "text": data.get("text") or "",
        "media": data.get("media"),
        "buttons": data.get("buttons") or [],
        "link_preview": bool(data.get("link_preview")),
    }
    reply_to = None
    if data.get("reply_to"):
        reply_to = {"message_id": int(data["reply_to"]), "allow_sending_without_reply": True}
    ok = eng.send_message(int(chat_id), msg, context, "manual", reply_to)
    if not ok:
        error = cb.errors[-1] if cb.errors else {"code": "unknown", "detail": "", "retry_after": None}
        return json.dumps({"ok": False, "error": error}, ensure_ascii=False)
    return json.dumps({"ok": True})


def mark_deleted(bot_dir, chat_id, message_id):
    with _lock:
        _db(bot_dir).execute("UPDATE messages SET deleted=1 WHERE chat_id=? AND message_id=?",
                             (int(chat_id), int(message_id)))


def delete_message(token, bot_dir, chat_id, message_id):
    """Apaga no Telegram (para todos) e marca como apagada no app."""
    try:
        TelegramApi(token).call("deleteMessage", {"chat_id": int(chat_id), "message_id": int(message_id)}, timeout=20)
    except TelegramError as exc:
        return _fail(exc)
    except Exception as exc:  # noqa: BLE001
        return _fail(exc)
    mark_deleted(bot_dir, chat_id, message_id)
    return json.dumps({"ok": True})
