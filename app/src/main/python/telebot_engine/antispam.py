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

from . import admins, chats, i18n

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


_lock = threading.RLock()


# -- configuração ------------------------------------------------------------------

def normalize_settings(data):
    data = data if isinstance(data, dict) else {}
    level = str(data.get("sensitivity") or DEFAULT_LEVEL).lower()
    admin_level = admins.clamp_level(data.get("admin_level", 1))
    return {
        "enabled": bool(data.get("enabled", True)),
        "sensitivity": level if level in LEVELS else DEFAULT_LEVEL,
        "admin_level": admin_level,
        "warn_user": bool(data.get("warn_user", True)),
    }


# -- detecção (em memória, por pessoa) -----------------------------------------------

_LABELS = ("photo", "video", "animation", "document", "audio", "voice", "video_note", "contact", "location", "poll")


def _sample(message, lang=None):
    p = chats.parse_message(message)
    if p["kind"] == "sticker":
        label = i18n.t("kind.sticker", lang, p["emoji"] or "")
    else:
        label = i18n.t("kind." + p["kind"], lang) if p["kind"] in _LABELS else ""
    return (label + " " + p["text"]).strip()[:200] or i18n.t("kind.message", lang)


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

    def check(self, message, sensitivity=DEFAULT_LEVEL, now=None, lang=None):
        """Registra a mensagem; devolve ``{"reason", "count", "window"}`` se passou do limite."""
        now = now or time.time()
        uid = (message.get("from") or {}).get("id")
        if uid is None:
            return None
        max_msgs, window, max_rep, rep_window = LEVELS.get(sensitivity, LEVELS[DEFAULT_LEVEL])
        self._gc(now)
        self.samples.setdefault(uid, collections.deque(maxlen=6)).append(_sample(message, lang))

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

def reason_text(record, lang=None):
    count, window = record.get("count") or 0, record.get("window") or 0
    if record.get("reason") == "flood":
        return i18n.t("antispam.reason_flood", lang, count, window)
    if record.get("reason") == "repeat":
        span = i18n.t("antispam.one_minute", lang) if window == 60 else i18n.t("antispam.seconds", lang, window)
        return i18n.t("antispam.reason_repeat", lang, count, span)
    return i18n.t("antispam.reason_manual", lang)


def notice_text(record, lang=None):
    e = html.escape
    t = lambda key, *a: i18n.t(key, lang, *a)  # noqa: E731
    stamp = record.get("stamp") or ""
    name = chats._full_name(record) or t("antispam.no_name")
    who = '<a href="tg://user?id=%d">%s</a>' % (int(record["id"]), e(name))
    if record.get("username"):
        who += " (@%s)" % e(record["username"])
    where = t("antispam.private") if record.get("chat_type", "private") == "private" \
        else t("antispam.group", e(record.get("chat_title") or ""))
    lines = [
        t("antispam.notice_title"),
        "",
        "👤 %s · ID <code>%d</code>" % (who, int(record["id"])),
        "📍 %s" % where,
        "⚠️ %s" % e(reason_text(record, lang)),
    ]
    if stamp:
        lines.append("🕒 %s" % e(stamp))
    samples = record.get("samples") or []
    if samples:
        lines += ["", t("antispam.samples")] + ["• " + e(s[:120]) for s in samples]
    lines += ["", t("antispam.notice_footer")]
    return "\n".join(lines)


def decision_line(record, action, lang=None):
    by = record.get("decided_by") or i18n.t("antispam.by_app", lang)
    if action == RELEASE:
        return i18n.t("antispam.released_by", lang, html.escape(by))
    return i18n.t("antispam.kept_by", lang, html.escape(by))


def keyboard(uid, pending=True, lang=None):
    row = [{"text": i18n.t("antispam.btn_release", lang), "callback_data": "%s%s:%d" % (CALLBACK_PREFIX, RELEASE, int(uid))}]
    if pending:
        row.append({"text": i18n.t("antispam.btn_keep", lang), "callback_data": "%s%s:%d" % (CALLBACK_PREFIX, KEEP, int(uid))})
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


def update_notices(api, record, action, lang=None):
    """Edita o aviso de cada admin com a decisão (e tira os botões, se desbloqueou)."""
    text = notice_text(record, lang) + "\n\n" + decision_line(record, action, lang)
    for chat_id, message_id in record.get("notices") or []:
        params = {"chat_id": chat_id, "message_id": message_id, "text": text, "parse_mode": "HTML",
                  "link_preview_options": {"is_disabled": True}}
        if action == KEEP:
            params["reply_markup"] = keyboard(record["id"], pending=False, lang=lang)
        try:
            api.call("editMessageText", params, timeout=20)
        except Exception:  # noqa: BLE001 - aviso apagado pelo admin etc.
            pass


def finish_decision(api, record, action, send_notice, lang=None):
    """Depois de ``decide``: atualiza os avisos dos admins e, se desbloqueou alguém em conversa
    privada, chama ``send_notice(chat_id, texto)``."""
    update_notices(api, record, action, lang)
    if action == RELEASE and record.get("chat_type", "private") == "private":
        send_notice(int(record["id"]), i18n.t("antispam.released_notice", lang))


# -- funções chamadas pelo app (Kotlin) -------------------------------------------------

def get_ban(bot_dir, user_id):
    record = load(bot_dir).get(int(user_id))
    if record is not None:
        record["reason_text"] = reason_text(record)
    return json.dumps(record, ensure_ascii=False)


def count_pending(bot_dir):
    return sum(1 for r in load(bot_dir).values() if r.get("status") == "pending")


def list_bans(bot_dir):
    items = sorted(load(bot_dir).values(), key=lambda r: r.get("at") or 0, reverse=True)
    for r in items:
        r["reason_text"] = reason_text(r)
    return json.dumps(items, ensure_ascii=False)


def decide_app(token, bot_dir, user_id, action):
    """Decisão tomada no app: grava, atualiza os avisos no Telegram e, se desbloqueou, avisa a pessoa."""
    from .api import TelegramApi

    lang = i18n.bot_language(bot_dir)
    record = decide(bot_dir, user_id, action, i18n.t("antispam.by_owner", lang))
    if record is None or not token:
        return json.dumps({"ok": True})
    api = TelegramApi(token)

    def send_notice(chat_id, text):
        try:
            sent = api.call("sendMessage", {"chat_id": chat_id, "text": text}, timeout=20)
            chats.record(bot_dir, sent, outgoing=True, source="auto", fallback_chat_id=chat_id)
        except Exception:  # noqa: BLE001 - a pessoa pode ter bloqueado o bot
            pass

    finish_decision(api, record, action, send_notice, lang)
    return json.dumps({"ok": True})


def block_app(bot_dir, user_json):
    """Bloqueio manual pelo app (ex.: no mensageiro). Já entra como "mantido"."""
    user = json.loads(user_json) if isinstance(user_json, str) else dict(user_json)
    record = build_record(user, {"id": user.get("id"), "type": "private"},
                          {"reason": "manual", "count": 0, "window": 0}, [], source="manual")
    record["decided_by"] = i18n.t("antispam.by_owner", i18n.bot_language(bot_dir))
    add(bot_dir, record)
    return json.dumps({"ok": True})
