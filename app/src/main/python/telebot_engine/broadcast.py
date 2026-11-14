"""Publicação em massa: mandar as mesmas mensagens para usuários, grupos e canais.

Usado de dois jeitos:
- pelo Telegram (``publish.py``): o admin manda as mensagens para o bot e elas
  são copiadas (``copyMessage``/``copyMessages``) sem mostrar o autor;
- pelo app (``start_app``): mensagens montadas no editor do app.

Botões: como no Telegram comum não dá para mandar mensagem com botão, o texto
(ou a legenda) pode ter linhas assim, que viram fileiras de botões::

    [Site | https://exemplo.com] [Canal | @meucanal]
    [Grupo | t.me/meugrupo]

Só um envio por bot de cada vez. O progresso fica em ``broadcast_job.json``.
"""

import json
import os
import random
import re
import threading
import time

from . import chats, errors, template
from .api import NetworkError, TelegramApi, TelegramError

JOB_FILE = "broadcast_job.json"
PER_MESSAGE_DELAY = 0.045  # ~22 mensagens/s: abaixo do limite de ~30/s do Telegram
UNREACHABLE = (errors.BLOCKED_BY_USER, errors.UNREACHABLE, errors.CHAT_NOT_FOUND)
TARGET_TYPES = {
    "users": ("private",),
    "groups": ("group", "supergroup"),
    "channels": ("channel",),
}
TARGET_WORDS = {
    "todos": ("users", "groups", "channels"), "tudo": ("users", "groups", "channels"),
    "usuarios": ("users",), "usuários": ("users",), "users": ("users",), "pessoas": ("users",),
    "grupos": ("groups",), "groups": ("groups",),
    "canais": ("channels",), "canal": ("channels",), "channels": ("channels",),
}

_TOKEN = re.compile(r"\[([^\[\]|\n]+)\|([^\[\]\n]+)\]")
_LINE = re.compile(r"^\s*(?:\[[^\[\]|\n]+\|[^\[\]\n]+\]\s*)+$")
_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # sem 0/O, 1/I

_jobs = {}
_jobs_lock = threading.Lock()


class SendFailed(Exception):
    def __init__(self, code, detail=""):
        super().__init__(code)
        self.code = code
        self.detail = detail


def new_code(length=6):
    return "".join(random.SystemRandom().choice(_CODE_CHARS) for _ in range(length))


def parse_targets(words):
    """``["usuarios", "grupos"]`` → ``{"users", "groups"}``. Vazio = todos."""
    out = set()
    for w in words:
        out.update(TARGET_WORDS.get(w.strip().lower().strip(","), ()))
    return out or set(TARGET_TYPES)


# -- botões no texto -----------------------------------------------------------------

def _u16(s):
    return len(s.encode("utf-16-le")) // 2


def parse_button_line(line):
    """``"[A | link] [B | @canal]"`` → ``[{"text", "url"}, ...]``; ``None`` se não for linha de botões
    ou se algum link for inválido (aí devolve ``("bad", linha)``)."""
    if not _LINE.match(line):
        return None
    row = []
    for text, url in _TOKEN.findall(line):
        norm = template.normalize_url(url)
        if not norm or not text.strip():
            return ("bad", line.strip())
        row.append({"text": text.strip()[:64], "url": norm})
    return row[:8]


def strip_buttons(text, entities=None):
    """Tira as linhas de botões do texto e ajusta as ``entities`` (offsets em UTF-16).

    Devolve ``(texto, entities, fileiras, linhas_invalidas)``.
    """
    text = text or ""
    entities = entities or []
    rows, bad, removed, kept = [], [], [], []
    pos = 0
    lines = text.split("\n")
    for i, line in enumerate(lines):
        piece = line + ("\n" if i < len(lines) - 1 else "")
        parsed = parse_button_line(line)
        if isinstance(parsed, list):
            rows.append(parsed)
            removed.append((pos, pos + _u16(piece)))
        else:
            if isinstance(parsed, tuple):
                bad.append(parsed[1])
            kept.append(piece)
        pos += _u16(piece)
    if not rows:
        return text, entities, [], bad
    new_text = "".join(kept).rstrip()
    limit = _u16(new_text)

    def shift(x):
        delta = 0
        for start, end in removed:
            if x >= end:
                delta += end - start
            elif x > start:
                delta += x - start
        return x - delta

    new_entities = []
    for e in entities:
        s = shift(e["offset"])
        end = min(shift(e["offset"] + e["length"]), limit)
        if end > s:
            new_entities.append(dict(e, offset=s, length=end - s))
    return new_text, new_entities, rows, bad


# -- plano de envio (fluxo do Telegram) ---------------------------------------------

def build_plan(items):
    """Transforma as mensagens recebidas do admin num plano de envio.

    ``items``: ``[{"message_id", "media_group_id", "is_text", "text", "entities"}]``.
    Devolve ``(plano, avisos)``.
    """
    plan, warnings = [], []
    i = 0
    while i < len(items):
        it = items[i]
        group = it.get("media_group_id")
        if group:
            ids = []
            while i < len(items) and items[i].get("media_group_id") == group:
                if strip_buttons(items[i].get("text"))[2]:
                    warnings.append("album_buttons")
                ids.append(items[i]["message_id"])
                i += 1
            plan.append({"type": "album", "ids": sorted(ids)})
            continue
        i += 1
        text, ents, rows, bad = strip_buttons(it.get("text"), it.get("entities"))
        for line in bad:
            warnings.append("bad_button:" + line)
        if not rows:
            plan.append({"type": "copy", "id": it["message_id"]})
        elif it.get("is_text") and not text.strip():
            # Mensagem só com botões: vão para a mensagem anterior.
            prev = plan[-1] if plan else None
            if prev is None or prev["type"] == "album":
                warnings.append("orphan_buttons")
            else:
                prev["buttons"] = prev.get("buttons", []) + rows
        elif it.get("is_text"):
            plan.append({"type": "text", "text": text, "entities": ents, "buttons": rows})
        else:
            plan.append({"type": "copy", "id": it["message_id"], "caption": text,
                         "caption_entities": ents, "buttons": rows})
    return plan, warnings


def call_retry(api, method, params, attempts=4):
    """Chama a API esperando o tempo pedido pelo Telegram em caso de 429."""
    for n in range(attempts):
        try:
            return api.call(method, params, timeout=60)
        except TelegramError as exc:
            if exc.error_code == 429 and n < attempts - 1:
                time.sleep((exc.retry_after or 3) + 1)
                continue
            raise


def run_plan(api, chat_id, from_chat_id, plan):
    """Envia o plano para um chat. Devolve quantas chamadas fez."""
    calls = 0
    for item in plan:
        markup = {"inline_keyboard": item["buttons"]} if item.get("buttons") else None
        if item["type"] == "album":
            call_retry(api, "copyMessages", {"chat_id": chat_id, "from_chat_id": from_chat_id,
                                             "message_ids": item["ids"]})
        elif item["type"] == "text":
            params = {"chat_id": chat_id, "text": item["text"], "entities": item["entities"]}
            if markup:
                params["reply_markup"] = markup
            call_retry(api, "sendMessage", params)
        else:
            params = {"chat_id": chat_id, "from_chat_id": from_chat_id, "message_id": item["id"]}
            if "caption" in item:
                params["caption"] = item["caption"]
                params["caption_entities"] = item["caption_entities"]
            if markup:
                params["reply_markup"] = markup
            call_retry(api, "copyMessage", params)
        calls += 1
    return calls


# -- destinos ------------------------------------------------------------------------

def audience(bot_dir, targets=None, exclude=()):
    targets = targets or set(TARGET_TYPES)
    types = [t for key in targets for t in TARGET_TYPES.get(key, ())]
    rows = json.loads(chats.list_chats(bot_dir))
    return [r for r in rows if r.get("type") in types and not r.get("blocked") and r["chat_id"] not in exclude]


def audience_counts(bot_dir, exclude=()):
    rows = audience(bot_dir, exclude=exclude)
    count = {k: sum(1 for r in rows if r.get("type") in v) for k, v in TARGET_TYPES.items()}
    return count


def audience_counts_json(bot_dir):
    return json.dumps(audience_counts(bot_dir))


# -- envio em segundo plano --------------------------------------------------------

class Job(threading.Thread):
    def __init__(self, bot_dir, recipients, send_one, source, owner=None, on_progress=None, on_done=None):
        super().__init__(name="broadcast", daemon=True)
        self.bot_dir = bot_dir
        self.recipients = recipients
        self.send_one = send_one
        self.owner = owner
        self.on_progress = on_progress
        self.on_done = on_done
        self.cancelled = threading.Event()
        self.state = {
            "id": int(time.time() * 1000), "source": source, "status": "running",
            "total": len(recipients), "sent": 0, "failed": 0, "unreachable": 0, "errors": {},
            "started_at": int(time.time()), "finished_at": None,
        }

    def _save(self):
        try:
            path = os.path.join(self.bot_dir, JOB_FILE)
            with open(path + ".tmp", "w", encoding="utf-8") as fh:
                json.dump(self.state, fh)
            os.replace(path + ".tmp", path)
        except OSError:
            pass

    def run(self):
        last_save = last_progress = 0
        try:
            for chat in self.recipients:
                if self.cancelled.is_set():
                    self.state["status"] = "cancelled"
                    break
                calls = 1
                try:
                    calls = self.send_one(chat) or 1
                    self.state["sent"] += 1
                except (TelegramError, NetworkError, SendFailed) as exc:
                    code = exc.code if isinstance(exc, SendFailed) else errors.classify(exc)["code"]
                    self.state["failed"] += 1
                    self.state["errors"][code] = self.state["errors"].get(code, 0) + 1
                    if code in UNREACHABLE:
                        self.state["unreachable"] += 1
                        chats.set_blocked(self.bot_dir, chat["chat_id"], True)
                except Exception as exc:  # noqa: BLE001
                    self.state["failed"] += 1
                    key = "unknown"
                    self.state["errors"][key] = self.state["errors"].get(key, 0) + 1
                    self.state["last_error"] = "%s: %s" % (type(exc).__name__, exc)
                now = time.time()
                if now - last_save > 2:
                    self._save()
                    last_save = now
                if self.on_progress and now - last_progress > 4:
                    last_progress = now
                    self._safe(self.on_progress)
                time.sleep(PER_MESSAGE_DELAY * calls)
            if self.state["status"] == "running":
                self.state["status"] = "done"
        finally:
            self.state["finished_at"] = int(time.time())
            self._save()
            with _jobs_lock:
                if _jobs.get(self.bot_dir) is self:
                    del _jobs[self.bot_dir]
            if self.on_done:
                self._safe(self.on_done)

    def _safe(self, fn):
        try:
            fn(dict(self.state))
        except Exception:  # noqa: BLE001
            pass


def running(bot_dir):
    with _jobs_lock:
        job = _jobs.get(bot_dir)
    return job if job and job.is_alive() else None


def start(bot_dir, recipients, send_one, source, owner=None, on_progress=None, on_done=None):
    with _jobs_lock:
        current = _jobs.get(bot_dir)
        if current and current.is_alive():
            raise RuntimeError("busy")
        job = Job(bot_dir, recipients, send_one, source, owner, on_progress, on_done)
        _jobs[bot_dir] = job
    job._save()
    job.start()
    return job


def cancel(bot_dir):
    job = running(bot_dir)
    if job:
        job.cancelled.set()
    return bool(job)


def job_status(bot_dir):
    job = running(bot_dir)
    if job:
        return json.dumps(job.state)
    try:
        with open(os.path.join(bot_dir, JOB_FILE), "r", encoding="utf-8") as fh:
            state = json.load(fh)
        if state.get("status") == "running":  # o app foi fechado no meio do envio
            state["status"] = "interrupted"
        return json.dumps(state)
    except (OSError, ValueError):
        return "null"


# -- envio montado no app ------------------------------------------------------------

def start_app(token, bot_dir, payload):
    """Começa uma publicação montada no app.

    ``payload``: ``{"messages": [{"text", "media", "buttons", "link_preview"}], "targets": ["users", ...]}``.
    O texto aceita a marcação e as variáveis dos comandos ({nome} vira o nome de cada pessoa).
    """
    from .chats import _SendCallbacks
    from .engine import Engine

    data = json.loads(payload) if isinstance(payload, str) else dict(payload)
    messages = [m for m in data.get("messages") or [] if (m.get("text") or "").strip() or m.get("media")]
    if not messages:
        return json.dumps({"ok": False, "error": {"code": "broadcast_empty", "detail": ""}})
    recipients = audience(bot_dir, set(data.get("targets") or TARGET_TYPES))
    if not recipients:
        return json.dumps({"ok": False, "error": {"code": "broadcast_no_audience", "detail": ""}})
    if running(bot_dir):
        return json.dumps({"ok": False, "error": {"code": "broadcast_busy", "detail": ""}})

    cb = _SendCallbacks()
    eng = Engine(token, os.path.join(bot_dir, "config.json"), bot_dir, cb)
    eng.send_source = "broadcast"
    try:
        me = eng.api.call("getMe", timeout=20)
    except Exception as exc:  # noqa: BLE001
        return json.dumps({"ok": False, "error": errors.classify(exc)})
    for m in messages:
        m.setdefault("buttons", [])
        m.setdefault("link_preview", False)
        m.setdefault("media", None)

    def send_one(chat):
        user = {"id": chat["chat_id"], "first_name": chat.get("first_name") or "",
                "last_name": chat.get("last_name") or "", "username": chat.get("username")}
        stamp = cb.nowLocal().split(" ")
        context = template.build_context({"from": user if chat.get("type") == "private" else {},
                                          "chat": {"type": chat.get("type"), "title": chat.get("title")}},
                                         "", me.get("username", ""), *stamp)
        for m in messages:
            cb.errors.clear()
            if not eng.send_message(chat["chat_id"], m, context, "broadcast"):
                err = cb.errors[-1] if cb.errors else {"code": "unknown", "detail": ""}
                raise SendFailed(err.get("code") or "unknown", err.get("detail") or "")
        return len(messages)

    try:
        start(bot_dir, recipients, send_one, "app")
    except RuntimeError:
        return json.dumps({"ok": False, "error": {"code": "broadcast_busy", "detail": ""}})
    return json.dumps({"ok": True, "total": len(recipients)})
