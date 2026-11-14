"""Antispam: bloqueia sozinho quem inunda o bot e chama um admin para julgar.

Como funciona:
- O motor conta, por pessoa, as mensagens que chegam (conversa privada e
  comandos em grupo). Rajada demais em poucos segundos, ou a mesma mensagem
  repetida várias vezes, = bloqueio automático.
- Bloqueado, a pessoa é ignorada pelo bot (nenhum comando responde), mas o que
  ela manda continua no mensageiro do app e no banco, para o admin analisar.
- Os admins (da classe configurada) recebem no Telegram um aviso com o motivo,
  as últimas mensagens e dois botões: ✅ Desbloquear / 🚫 Manter bloqueado.
  O app mostra o mesmo caso na tela "Antispam" e numa notificação.
- Admins nunca são bloqueados. Um álbum conta como uma mensagem só.

Arquivo na pasta do bot: ``bans.json`` =
``{"<id>": {"id", "first_name", "last_name", "username", "chat_id", "chat_title",
"reason", "count", "window", "at", "status", "samples", "notices", "decided_by",
"source"}}``. ``status``: ``pending`` (esperando um admin) ou ``kept`` (um admin
decidiu manter). Desbloquear apaga o registro.
"""

import collections
import html
import json
import os
import threading
import time

BANS_FILE = "bans.json"
CALLBACK_PREFIX = "as:"
RELEASE = "release"
KEEP = "keep"

# sensibilidade -> (máx. mensagens, em segundos, máx. repetidas, em segundos)
LEVELS = {
    "baixa": (15, 10, 6, 60),
    "media": (8, 10, 4, 60),
    "alta": (5, 10, 3, 60),
}
DEFAULT_LEVEL = "media"

BLOCK_NOTICE = ("⛔ Você foi bloqueado automaticamente por enviar mensagens demais em pouco tempo.\n\n"
                "Um administrador vai analisar o caso. Até lá, o bot não vai responder.")
RELEASE_NOTICE = "✅ Um administrador analisou o caso e desbloqueou você. Pode voltar a usar o bot."

_lock = threading.RLock()


# -- configuração ------------------------------------------------------------------

def normalize_settings(data):
    data = data if isinstance(data, dict) else {}
    level = str(data.get("sensitivity") or DEFAULT_LEVEL).lower()
    try:
        admin_level = max(1, min(10, int(data.get("admin_level", 1))))
    except (TypeError, ValueError):
        admin_level = 1
    return {
        "enabled": bool(data.get("enabled", True)),
        "sensitivity": level if level in LEVELS else DEFAULT_LEVEL,
        "admin_level": admin_level,
        "warn_user": bool(data.get("warn_user", True)),
    }


# -- detecção (em memória, por pessoa) -----------------------------------------------

def _sample(message):
    text = message.get("text") or message.get("caption") or ""
    kind = next((k for k in ("photo", "video", "animation", "sticker", "document", "audio", "voice",
                             "video_note", "contact", "location", "poll") if message.get(k)), None)
    label = {"photo": "[foto]", "video": "[vídeo]", "animation": "[GIF]", "sticker": "[figurinha]",
             "document": "[documento]", "audio": "[áudio]", "voice": "[mensagem de voz]",
             "video_note": "[vídeo redondo]", "contact": "[contato]", "location": "[localização]",
             "poll": "[enquete]"}.get(kind, "")
    if kind == "sticker":
        label = "[figurinha %s]" % (message["sticker"].get("emoji") or "")
    return (label + " " + text).strip()[:200] or "[mensagem]"


def _key(message):
    """O que conta como "mesma mensagem" para o limite de repetidas."""
    text = " ".join((message.get("text") or message.get("caption") or "").lower().split())
    if text.startswith("/"):
        return None  # repetir um comando (ex.: /start de quem está perdido) não é spam; só a rajada conta
    if text:
        return "t:" + text
    sticker = message.get("sticker") or {}
    if sticker.get("file_unique_id"):
        return "s:" + sticker["file_unique_id"]
    return None


class Detector:
    def __init__(self):
        self.times = {}     # uid -> deque[timestamps]
        self.repeats = {}   # uid -> deque[(timestamp, key)]
        self.samples = {}   # uid -> deque[texto] (últimas 6, para o relatório)
        self.groups = {}    # uid -> media_group_id já contado

    def reset(self, uid):
        for d in (self.times, self.repeats, self.samples, self.groups):
            d.pop(uid, None)

    def _gc(self, now):
        if len(self.times) < 5000:
            return
        for uid in [u for u, q in self.times.items() if not q or now - q[-1] > 120]:
            self.reset(uid)

    def check(self, message, sensitivity=DEFAULT_LEVEL, now=None):
        """Registra a mensagem; devolve ``{"reason", "count", "window"}`` se passou do limite."""
        now = now or time.time()
        uid = (message.get("from") or {}).get("id")
        if uid is None:
            return None
        max_msgs, window, max_rep, rep_window = LEVELS.get(sensitivity, LEVELS[DEFAULT_LEVEL])
        self._gc(now)
        self.samples.setdefault(uid, collections.deque(maxlen=6)).append(_sample(message))

        group = message.get("media_group_id")
        if group and self.groups.get(uid) == group:
            return None  # resto do mesmo álbum
        self.groups[uid] = group

        q = self.times.setdefault(uid, collections.deque())
        q.append(now)
        while q and now - q[0] > window:
            q.popleft()
        if len(q) > max_msgs:
            return {"reason": "flood", "count": len(q), "window": window}

        key = _key(message)
        if key:
            r = self.repeats.setdefault(uid, collections.deque())
            r.append((now, key))
            while r and now - r[0][0] > rep_window:
                r.popleft()
            same = sum(1 for _, k in r if k == key)
            if same >= max_rep:
                return {"reason": "repeat", "count": same, "window": rep_window}
        return None

    def recent(self, uid):
        return list(self.samples.get(uid) or [])


# -- arquivo bans.json ---------------------------------------------------------------

def _path(bot_dir):
    return os.path.join(bot_dir, BANS_FILE)


def load(bot_dir):
    """``{id(int): registro}``."""
    with _lock:
        try:
            with open(_path(bot_dir), "r", encoding="utf-8") as fh:
                data = json.load(fh)
        except (OSError, ValueError):
            return {}
    out = {}
    for k, v in (data.items() if isinstance(data, dict) else []):
        try:
            out[int(k)] = v
        except (TypeError, ValueError):
            continue
    return out


def _save(bot_dir, bans):
    path = _path(bot_dir)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump({str(k): v for k, v in bans.items()}, fh, ensure_ascii=False, indent=1)
    os.replace(tmp, path)


def add(bot_dir, record):
    with _lock:
        bans = load(bot_dir)
        bans[int(record["id"])] = record
        _save(bot_dir, bans)


def set_notices(bot_dir, uid, notices):
    with _lock:
        bans = load(bot_dir)
        if int(uid) in bans:
            bans[int(uid)]["notices"] = notices
            _save(bot_dir, bans)


def decide(bot_dir, uid, action, by=""):
    """Aplica a decisão de um admin. Devolve o registro (como estava + decisão) ou ``None``
    se não havia bloqueio (ex.: outro admin já desbloqueou)."""
    with _lock:
        bans = load(bot_dir)
        record = bans.get(int(uid))
        if record is None:
            return None
        record = dict(record, decided_by=by, decided_at=int(time.time()))
        if action == RELEASE:
            del bans[int(uid)]
        else:
            record["status"] = "kept"
            bans[int(uid)] = record
        _save(bot_dir, bans)
        return record


def build_record(user, chat, hit, samples, source="auto"):
    return {
        "id": user.get("id"),
        "first_name": user.get("first_name") or "",
        "last_name": user.get("last_name") or "",
        "username": user.get("username") or "",
        "chat_id": chat.get("id"),
        "chat_title": chat.get("title") or "",
        "chat_type": chat.get("type") or "private",
        "reason": hit.get("reason"),
        "count": hit.get("count"),
        "window": hit.get("window"),
        "at": int(time.time()),
        "status": "pending" if source == "auto" else "kept",
        "samples": samples[-6:],
        "notices": [],
        "decided_by": "",
        "source": source,
    }


def public(record):
    """Registro para o banco externo (sem os ids dos avisos: o Firestore não aceita lista de listas)."""
    return {k: v for k, v in record.items() if k != "notices"}


# -- texto do aviso para os admins -----------------------------------------------------

def reason_text(record):
    count, window = record.get("count") or 0, record.get("window") or 0
    if record.get("reason") == "flood":
        return "%d mensagens em %d segundos" % (count, window)
    if record.get("reason") == "repeat":
        return "a mesma mensagem %d vezes em %s" % (count, "1 minuto" if window == 60 else "%d segundos" % window)
    return "bloqueado manualmente"


def notice_text(record):
    e = html.escape
    stamp = record.get("stamp") or ""
    name = (record.get("first_name", "") + " " + record.get("last_name", "")).strip() or "Sem nome"
    who = '<a href="tg://user?id=%d">%s</a>' % (int(record["id"]), e(name))
    if record.get("username"):
        who += " (@%s)" % e(record["username"])
    where = "Conversa privada" if record.get("chat_type", "private") == "private" \
        else "Grupo %s" % e(record.get("chat_title") or "")
    lines = [
        "🚨 <b>Bloqueio automático (antispam)</b>",
        "",
        "👤 %s · ID <code>%d</code>" % (who, int(record["id"])),
        "📍 %s" % where,
        "⚠️ %s" % e(reason_text(record)),
    ]
    if stamp:
        lines.append("🕒 %s" % e(stamp))
    samples = record.get("samples") or []
    if samples:
        lines += ["", "<b>Últimas mensagens:</b>"] + ["• " + e(s[:120]) for s in samples]
    lines += ["", "O bot está ignorando esta pessoa até um admin decidir. Se foi engano, desbloqueie."]
    return "\n".join(lines)


def decision_line(record, action):
    by = record.get("decided_by") or "pelo app"
    if action == RELEASE:
        return "✅ <b>Desbloqueado</b> por %s." % html.escape(by)
    return "🚫 <b>Mantido bloqueado</b> por %s. Ainda dá para desbloquear depois." % html.escape(by)


def keyboard(uid, pending=True):
    row = [{"text": "✅ Desbloquear", "callback_data": "%s%s:%d" % (CALLBACK_PREFIX, RELEASE, int(uid))}]
    if pending:
        row.append({"text": "🚫 Manter bloqueado", "callback_data": "%s%s:%d" % (CALLBACK_PREFIX, KEEP, int(uid))})
    return {"inline_keyboard": [row]}


def parse_callback(data):
    """``"as:release:123"`` -> ``("release", 123)``; outra coisa -> ``None``."""
    if not isinstance(data, str) or not data.startswith(CALLBACK_PREFIX):
        return None
    try:
        action, uid = data[len(CALLBACK_PREFIX):].split(":", 1)
        if action not in (RELEASE, KEEP):
            return None
        return action, int(uid)
    except ValueError:
        return None


def update_notices(api, record, action):
    """Edita o aviso de cada admin com a decisão (e tira os botões, se desbloqueou)."""
    text = notice_text(record) + "\n\n" + decision_line(record, action)
    for chat_id, message_id in record.get("notices") or []:
        params = {"chat_id": chat_id, "message_id": message_id, "text": text, "parse_mode": "HTML",
                  "link_preview_options": {"is_disabled": True}}
        if action == KEEP:
            params["reply_markup"] = keyboard(record["id"], pending=False)
        try:
            api.call("editMessageText", params, timeout=20)
        except Exception:  # noqa: BLE001 - aviso apagado pelo admin etc.
            pass


# -- funções chamadas pelo app (Kotlin) -------------------------------------------------

def list_bans(bot_dir):
    items = sorted(load(bot_dir).values(), key=lambda r: r.get("at") or 0, reverse=True)
    for r in items:
        r["reason_text"] = reason_text(r)
    return json.dumps(items, ensure_ascii=False)


def decide_app(token, bot_dir, user_id, action):
    """Decisão tomada no app: grava, atualiza os avisos no Telegram e, se desbloqueou, avisa a pessoa."""
    from . import chats
    from .api import TelegramApi

    record = decide(bot_dir, user_id, action, "o dono do bot (pelo app)")
    if record is None:
        return json.dumps({"ok": True, "missing": True})
    if not token:
        return json.dumps({"ok": True})
    api = TelegramApi(token)
    update_notices(api, record, action)
    if action == RELEASE and record.get("chat_type", "private") == "private":
        try:
            sent = api.call("sendMessage", {"chat_id": int(user_id), "text": RELEASE_NOTICE}, timeout=20)
            chats.record(bot_dir, sent, outgoing=True, source="auto", fallback_chat_id=int(user_id))
        except Exception:  # noqa: BLE001 - a pessoa pode ter bloqueado o bot
            pass
    return json.dumps({"ok": True})


def block_app(bot_dir, user_json):
    """Bloqueio manual pelo app (ex.: no mensageiro). Já entra como "mantido"."""
    user = json.loads(user_json) if isinstance(user_json, str) else dict(user_json)
    record = build_record(user, {"id": user.get("id"), "type": "private"},
                          {"reason": "manual", "count": 0, "window": 0}, [], source="manual")
    record["decided_by"] = "o dono do bot (pelo app)"
    add(bot_dir, record)
    return json.dumps({"ok": True})
