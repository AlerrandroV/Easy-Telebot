"""Publicação em massa pelo Telegram: /post (ou /publi).

1. O admin manda ``/post``: o bot explica e entra no "modo publicação".
2. Tudo o que o admin mandar (texto, foto, vídeo, documento, álbum, figurinha...)
   é acumulado. Linhas ``[Texto | link]`` viram botões.
3. ``/pronto``: o bot mostra a prévia (exatamente como vai chegar), os destinos e
   um código de confirmação.
4. ``/confirmar CÓDIGO [usuarios] [grupos] [canais]``: envia, sem mostrar o autor.
   ``/cancelar`` desiste (ou interrompe um envio em andamento).

Só admins com a classe configurada podem usar; para os outros, o comando não existe.
"""

import html
import threading
import time

from . import admins as admins_mod
from . import broadcast, chats, errors, i18n
from .api import TelegramApi

SESSION_TTL = 60 * 60
CONTROL = ("pronto", "cancelar", "confirmar")
# Os mesmos comandos em inglês (/done, /cancel, /confirm).
CONTROL_ALIASES = {"done": "pronto", "ready": "pronto", "cancel": "cancelar", "confirm": "confirmar"}
WARNINGS = {"album_buttons": "post.album_buttons", "orphan_buttons": "post.orphan_buttons"}


class Session:
    def __init__(self, chat_id):
        self.chat_id = chat_id
        self.items = []
        self.started = time.time()
        self.code = None
        self.plan = None
        self.acked = False


class PostFlow:
    def __init__(self, engine):
        self.engine = engine
        self.sessions = {}
        self._side_api = None
        self._lock = threading.Lock()

    # -- utilidades ---------------------------------------------------------------

    @property
    def settings(self):
        return self.engine.config.get("broadcast") or {"enabled": False, "admin_level": 1, "triggers": []}

    def is_trigger(self, name):
        s = self.settings
        return bool(s.get("enabled")) and name in s.get("triggers", [])

    def allowed(self, user_id):
        self.engine.maybe_reload_admins()
        admin = self.engine.admins.get(user_id)
        return admins_mod.can_use(admin and admin.get("level"), self.settings.get("admin_level", 1))

    def side_api(self):
        """Conexão separada da do long polling (usada pela thread de envio)."""
        if self._side_api is None:
            self._side_api = TelegramApi(self.engine.token)
        return self._side_api

    def say(self, chat_id, text, reply_to=None):
        params = {"chat_id": chat_id, "text": text, "parse_mode": "HTML",
                  "link_preview_options": {"is_disabled": True}}
        if reply_to:
            params["reply_parameters"] = {"message_id": reply_to, "allow_sending_without_reply": True}
        return self.engine._call_send("sendMessage", params, "post")

    def react(self, chat_id, message_id):
        try:
            self.engine.api.call("setMessageReaction", {
                "chat_id": chat_id, "message_id": message_id,
                "reaction": [{"type": "emoji", "emoji": "👍"}],
            })
        except Exception:  # noqa: BLE001 - reação é só um enfeite
            pass

    def t(self, key, *args):
        return i18n.t(key, self.engine.lang, *args)

    # -- entrada -----------------------------------------------------------------

    def handle(self, message, parsed):
        """Devolve ``True`` se a mensagem foi tratada aqui (o motor não deve responder)."""
        chat = message.get("chat") or {}
        user = message.get("from") or {}
        uid = user.get("id")
        name, args = parsed if parsed else (None, "")
        name = CONTROL_ALIASES.get(name, name)
        t = self.t
        if chat.get("type") != "private":
            return bool(name and self.is_trigger(name))  # em grupo: ignora em silêncio

        session = self.sessions.get(uid)
        if session and time.time() - session.started > SESSION_TTL:
            del self.sessions[uid]
            session = None

        if name and self.is_trigger(name):
            if not self.allowed(uid):
                self.engine.emit("denied", user=user.get("username") or user.get("first_name", ""),
                                 user_id=uid, command=name, required=self.settings.get("admin_level"),
                                 level=None)
                return True
            if session:
                self.say(chat["id"], t("post.already"))
                return True
            self.sessions[uid] = Session(chat["id"])
            self.engine.emit("post_started", user=user.get("username") or user.get("first_name", ""))
            self.say(chat["id"], t("post.intro"))
            return True

        if name == "cancelar" and not session:
            job = broadcast.running(self.engine.base_dir)
            if job and job.owner == uid:
                broadcast.cancel(self.engine.base_dir)
                self.say(chat["id"], t("post.stopped"))
                return True
            return False

        if not session:
            return False

        if name in CONTROL:
            getattr(self, "cmd_" + name)(session, uid, args, message)
            return True

        self.add(session, message)
        return True

    # -- passos ------------------------------------------------------------------

    def add(self, session, message):
        is_text = "text" in message
        session.items.append({
            "message_id": message["message_id"],
            "media_group_id": message.get("media_group_id"),
            "is_text": is_text,
            "text": message.get("text") if is_text else message.get("caption") or "",
            "entities": message.get("entities") if is_text else message.get("caption_entities") or [],
        })
        session.code = None  # mudou: precisa de nova prévia
        self.react(session.chat_id, message["message_id"])
        if not session.acked:
            session.acked = True
            self.say(session.chat_id, self.t("post.received"))

    def cmd_cancelar(self, session, uid, args, message):
        self.sessions.pop(uid, None)
        self.say(session.chat_id, self.t("post.cancelled"))

    def cmd_pronto(self, session, uid, args, message):
        t = self.t
        if not session.items:
            self.say(session.chat_id, t("post.nothing_yet"))
            return
        plan, warnings = broadcast.build_plan(session.items)
        if not plan:
            self.say(session.chat_id, t("post.nothing_left"))
            return
        self.say(session.chat_id, t("post.preview"))
        try:
            broadcast.run_plan(self.engine.api, session.chat_id, session.chat_id, plan)
        except Exception as exc:  # noqa: BLE001
            info = errors.classify(exc)
            self.say(session.chat_id, t("post.preview_failed", html.escape(info["detail"] or info["code"])))
            return
        counts = broadcast.audience_counts(self.engine.base_dir, exclude=(session.chat_id,))
        session.plan = plan
        session.code = broadcast.new_code()
        lines = [t("post.summary", len(plan), counts["users"], counts["groups"], counts["channels"],
                   session.code, session.code)]
        for w in dict.fromkeys(warnings):
            if w.startswith("bad_button:"):
                lines.append("\n" + t("post.bad_button", html.escape(w.split(":", 1)[1])))
            else:
                lines.append("\n" + (t(WARNINGS[w]) if w in WARNINGS else w))
        self.say(session.chat_id, "\n".join(lines))

    def cmd_confirmar(self, session, uid, args, message):
        words = args.split()
        t = self.t
        if not session.code or not session.plan:
            self.say(session.chat_id, t("post.need_preview"))
            return
        if not words or words[0].upper() != session.code:
            self.say(session.chat_id, t("post.wrong_code"))
            return
        targets = broadcast.parse_targets(words[1:])
        recipients = broadcast.audience(self.engine.base_dir, targets, exclude=(session.chat_id,))
        if not recipients:
            self.say(session.chat_id, t("post.no_audience"))
            return
        plan, from_chat = session.plan, session.chat_id
        api = self.side_api()
        status = {}

        def send_one(chat):
            return broadcast.run_plan(api, chat["chat_id"], from_chat, plan)

        def on_progress(state):
            text = t("post.progress", state["sent"] + state["failed"], state["total"], state["failed"])
            if status.get("message_id"):
                try:
                    api.call("editMessageText", {"chat_id": from_chat, "message_id": status["message_id"],
                                                 "text": text})
                except Exception:  # noqa: BLE001
                    pass

        def on_done(state):
            title = {"done": t("post.done_title"), "cancelled": t("post.cancelled_title")}
            parts = [title.get(state["status"], t("post.finished_title")),
                     t("post.delivered", state["sent"], state["total"])]
            if state["failed"]:
                parts.append(t("post.failed", state["failed"]))
                if state["unreachable"]:
                    parts.append(t("post.unreachable", state["unreachable"]))
            try:
                result = api.call("sendMessage", {"chat_id": from_chat, "text": "\n".join(parts),
                                                  "parse_mode": "HTML"})
                chats.record(self.engine.base_dir, result, outgoing=True, source="auto")
            except Exception:  # noqa: BLE001
                pass
            self.engine.emit("broadcast_done", **{k: state[k] for k in ("status", "sent", "failed", "total")})

        try:
            broadcast.start(self.engine.base_dir, recipients, send_one, "telegram", owner=uid,
                            on_progress=on_progress, on_done=on_done)
        except RuntimeError:
            self.say(session.chat_id, t("post.busy"))
            return
        self.sessions.pop(uid, None)
        result = self.say(session.chat_id, t("post.sending", len(recipients)))
        if isinstance(result, dict):
            status["message_id"] = result.get("message_id")
        self.engine.emit("broadcast_started", total=len(recipients), source="telegram")
