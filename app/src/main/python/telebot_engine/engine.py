"""Loop principal de um bot (long polling com ``getUpdates``).

Comunicação com o app (Kotlin) ou com o ``run.py`` (computador):
- Crie ``Engine(token, config_path, data_dir, callbacks)`` e chame ``run()``
  numa thread própria. ``stop()``, ``wake()`` e ``request_reload()`` podem ser
  chamados de outras threads.
- ``callbacks.onEvent(json)`` recebe eventos (status, mensagens, erros).
- ``callbacks.nowLocal()`` devolve ``"dd/MM/aaaa HH:mm"`` no fuso local.

Arquivos de mídia citados no ``config.json`` são relativos à pasta dele.
Depois do primeiro envio, o Telegram devolve um ``file_id`` que guardamos em
``media_cache.json`` para não mandar o arquivo de novo.
"""

import json
import os
import re
import threading
import time

from . import admins as admins_mod
from . import antispam
from . import broadcast as broadcast_mod
from . import chats
from .publish import PostFlow
from . import config as config_mod
from . import errors, template
from .api import Aborted, NetworkError, TelegramApi, TelegramError

POLL_TIMEOUT = 50  # segundos que o Telegram segura a conexão esperando mensagens
TOKEN_RE = re.compile(r"^\d{5,}:[A-Za-z0-9_-]{30,}$")
COMMAND_RE = re.compile(r"^[a-z0-9_]{1,32}$")
MAX_BACKOFF = 60
TEXT_LIMIT = 4096
CAPTION_LIMIT = 1024
NEWADMIN_COMMAND = "newadmin"  # comando secreto: só responde com um código válido
NEWADMIN_REPLY = "Parabéns! Agora você é um administrador!"

MEDIA_METHODS = {
    "photo": "sendPhoto",
    "video": "sendVideo",
    "animation": "sendAnimation",
    "audio": "sendAudio",
    "voice": "sendVoice",
    "document": "sendDocument",
    "sticker": "sendSticker",
}
NO_CAPTION = ("sticker",)  # tipos que não aceitam legenda: o texto vai numa mensagem separada


def _json(obj):
    return json.dumps(obj, ensure_ascii=False)


def _error_result(exc):
    return _json({"ok": False, "error": errors.classify(exc)})


def _format_error(detail):
    return {"code": errors.TOKEN_FORMAT, "detail": detail, "retry_after": None}


def download_profile_photo(api, user_id, dest):
    """Baixa a foto de perfil atual do bot para ``dest``. Devolve o caminho ou ``None``."""
    photos = api.call("getUserProfilePhotos", {"user_id": user_id, "limit": 1}, timeout=20)
    if not photos or not photos.get("photos"):
        if os.path.exists(dest):
            os.remove(dest)
        return None
    sizes = photos["photos"][0]
    big = [s for s in sizes if s.get("width", 0) >= 160]
    chosen = min(big, key=lambda s: s.get("width", 0)) if big else sizes[-1]
    info = api.call("getFile", {"file_id": chosen["file_id"]}, timeout=20)
    os.makedirs(os.path.dirname(dest) or ".", exist_ok=True)
    return api.download(info["file_path"], dest)


def check_token(token, photo_path):
    """Valida o token com ``getMe`` e baixa a foto do bot (se houver) para ``photo_path``.

    Devolve JSON: ``{"ok": true, "bot": {...}, "photo": caminho|null}`` ou
    ``{"ok": false, "error": {"code", "detail"}}``.
    """
    token = (token or "").strip()
    if not TOKEN_RE.match(token):
        return _json({"ok": False, "error": _format_error("formato inválido")})
    api = TelegramApi(token)
    try:
        me = api.call("getMe", timeout=20)
    except Exception as exc:  # noqa: BLE001 - tudo vira erro explicado
        return _error_result(exc)
    try:
        photo = download_profile_photo(api, me["id"], photo_path)
    except Exception:  # noqa: BLE001 - foto é opcional
        photo = None
    return _json({"ok": True, "bot": me, "photo": photo})


def delete_webhook(token):
    """Remove o webhook do bot para o long polling funcionar."""
    try:
        TelegramApi(token.strip()).call("deleteWebhook", {"drop_pending_updates": False}, timeout=20)
    except Exception as exc:  # noqa: BLE001
        return _error_result(exc)
    return _json({"ok": True})


def parse_command(text, bot_username):
    """``"/Diga@meubot olá"`` → ``("diga", "olá")``. ``None`` se não for comando para nós."""
    if not text or not text.startswith("/"):
        return None
    head, _, args = text.partition(" ")
    if "\n" in head:
        head, _, rest = head.partition("\n")
        args = rest + (" " + args if args else "")
    name = head[1:]
    if "@" in name:
        name, _, target = name.partition("@")
        if bot_username and target.lower() != bot_username.lower():
            return None
    name = name.lower()
    if not name:
        return None
    return name, args.strip()


def _display_name(user):
    if user.get("username"):
        return "@" + user["username"]
    return chats._full_name(user) or str(user.get("id", ""))


def build_keyboard(rows):
    keyboard = []
    for row in rows or []:
        clean = []
        for b in row:
            url = template.normalize_url(b.get("url"))
            if url and b.get("text"):
                clean.append({"text": b["text"][:64], "url": url})
        if clean:
            keyboard.append(clean[:8])
    return {"inline_keyboard": keyboard} if keyboard else None


class Engine:
    def __init__(self, token, config_path, data_dir, callbacks, api=None, storage=None):
        self.token = token.strip()
        self.config_path = config_path
        self.base_dir = os.path.dirname(os.path.abspath(config_path))
        self.data_dir = data_dir
        self.cb = callbacks
        self.api = api or TelegramApi(self.token)
        self._stop = threading.Event()
        self._wake = threading.Event()
        self._reload = threading.Event()
        self._config_mtime = None
        self.config = config_mod.normalize({})
        self.bot = None
        self.offset = None
        self._state = None
        self._last_error_code = None
        self.admins = {}
        self._admins_mtime = None
        self._menu_scopes = set()  # chats de admins que receberam um menu próprio
        self._cache_path = os.path.join(self.base_dir, "media_cache.json")
        self.spam = antispam.Detector()
        self.bans = {}
        self._bans_mtime = None
        # Banco de dados (usuários/histórico). ``storage`` = configuração em JSON.
        self.post = PostFlow(self)
        self.send_source = "auto"  # "auto" = resposta de comando; "manual" = enviada pelo app
        self.storage = None
        if storage:
            from .storage.worker import StorageWorker
            self.storage = StorageWorker(storage, self.base_dir, self.token.split(":")[0], self.emit)
        self._media_cache = self._load_cache()

    # -- controle (chamado de outras threads) ---------------------------------

    def stop(self):
        if self.storage:
            self.storage.stop()
        job = broadcast_mod.running(self.base_dir)
        if job and job.state.get("source") == "telegram":
            job.cancelled.set()
        self._stop.set()
        self._wake.set()
        self.api.abort()

    def wake(self):
        """A internet voltou: tenta de novo agora em vez de esperar o backoff."""
        self._wake.set()

    def request_reload(self):
        """A configuração mudou: recarrega na hora (interrompe o long polling atual)."""
        self._reload.set()
        self._wake.set()
        interrupt = getattr(self.api, "interrupt", None)
        if interrupt:
            interrupt()

    @property
    def stopping(self):
        return self._stop.is_set()

    # -- eventos ---------------------------------------------------------------

    def emit(self, type_, **data):
        data["type"] = type_
        try:
            self.cb.onEvent(_json(data))
        except Exception:  # noqa: BLE001 - nunca derrubar o motor por causa do log
            pass

    def set_state(self, state, error=None):
        if state == self._state and error is None:
            return
        self._state = state
        self.emit("status", state=state, error=error)

    def report_error(self, exc, context, fatal=False, retry_in=None):
        info = errors.classify(exc)
        # Não repete o mesmo erro de rede a cada tentativa.
        repeated = info["code"] == self._last_error_code and not fatal
        self._last_error_code = info["code"]
        if not repeated:
            self.emit("error", context=context, retry_in=retry_in, fatal=fatal, **info)
        return info

    def sleep(self, seconds):
        """Espera ``seconds`` ou até ``stop()``/``wake()``."""
        self._wake.clear()
        self._wake.wait(seconds)

    # -- configuração ----------------------------------------------------------

    def maybe_reload_config(self, force=False):
        if self._reload.is_set():
            self._reload.clear()
            force = True
        try:
            mtime = os.path.getmtime(self.config_path)
        except OSError:
            mtime = None
        admins_changed = self.maybe_reload_admins(force)
        if not force and mtime == self._config_mtime:
            if admins_changed:
                self.publish_commands()
            return admins_changed
        self._config_mtime = mtime
        self.config = config_mod.load(self.config_path)
        self.emit("config", commands=len(self.config["commands"]), admins=len(self.admins))
        self.publish_commands()
        return True

    def maybe_reload_admins(self, force=False):
        try:
            mtime = os.path.getmtime(os.path.join(self.base_dir, admins_mod.ADMINS_FILE))
        except OSError:
            mtime = None
        if not force and mtime == self._admins_mtime:
            return False
        self._admins_mtime = mtime
        self.admins = admins_mod.load(self.base_dir)
        return True

    def maybe_reload_bans(self):
        try:
            mtime = os.path.getmtime(os.path.join(self.base_dir, antispam.BANS_FILE))
        except OSError:
            mtime = None
        if mtime == self._bans_mtime:
            return
        self._bans_mtime = mtime
        old = self.bans
        self.bans = antispam.load(self.base_dir)
        # Único ponto que vê toda mudança (motor ou app): espelha no banco externo.
        for uid in set(old) - set(self.bans):
            self.spam.reset(uid)  # desbloqueado: começa a contar do zero
            if self.storage:
                self.storage.delete_doc("bloqueios", uid)
        for uid, record in self.bans.items():
            if self.storage and old.get(uid) != record:
                self.storage.put_doc("bloqueios", uid, antispam.public(record))

    def menu(self, admin_level=None):
        """Comandos do menu: os públicos + os de admin que ``admin_level`` pode usar."""
        items = []
        for c in self.config["commands"]:
            if not c["show_in_menu"]:
                continue
            required = c.get("admin_level")
            if required is not None and not admins_mod.can_use(admin_level, required):
                continue
            name = next((t for t in c["triggers"] if COMMAND_RE.match(t)), None)
            if name:
                items.append({"command": name, "description": (c["description"] or name)[:256]})
        b = self.config.get("broadcast") or {}
        if admin_level is not None and b.get("enabled") and admins_mod.can_use(admin_level, b.get("admin_level", 1)):
            items.append({"command": b["triggers"][0], "description": "Publicação em massa"})
        return items[:100]

    def publish_commands(self):
        """Mantém o menu "/" do Telegram igual aos comandos do app.

        Todo mundo vê os comandos públicos. Cada admin, na conversa privada com
        o bot, vê também os comandos de admin que a classe dele permite.
        """
        try:
            auto = self.config["auto_menu"]
            if auto:
                self.api.call("setMyCommands", {"commands": self.menu()})
            else:
                self.api.call("deleteMyCommands", {})
            wanted = set(self.admins) if auto else set()
            has_admin_cmds = any(c.get("admin_level") is not None for c in self.config["commands"]) \
                or bool((self.config.get("broadcast") or {}).get("enabled"))
            for admin_id in wanted:
                if has_admin_cmds:
                    self.api.call("setMyCommands", {
                        "commands": self.menu(self.admins[admin_id].get("level")),
                        "scope": {"type": "chat", "chat_id": admin_id},
                    })
                elif admin_id in self._menu_scopes:
                    self.api.call("deleteMyCommands", {"scope": {"type": "chat", "chat_id": admin_id}})
            for old in self._menu_scopes - wanted:
                self.api.call("deleteMyCommands", {"scope": {"type": "chat", "chat_id": old}})
            self._menu_scopes = wanted if has_admin_cmds else set()
        except Aborted:
            raise
        except Exception as exc:  # noqa: BLE001
            self.emit("error", context="setMyCommands", retry_in=None, fatal=False,
                      **errors.classify(exc))

    # -- loop ------------------------------------------------------------------

    def run(self):
        try:
            self._run()
        except Aborted:
            pass
        except Exception as exc:  # noqa: BLE001 - erro inesperado: avisa e para
            self.report_error(exc, "engine", fatal=True)
            self.set_state("fatal", errors.classify(exc)["code"])
            return
        if self._state != "fatal":
            self.set_state("stopped")

    def _run(self):
        self.set_state("starting")
        if self.storage and self.storage.enabled:
            self.storage.start()
        if not self._connect():
            return
        self.maybe_reload_config(force=True)
        self.set_state("running")
        backoff = 2
        while not self.stopping:
            self.maybe_reload_config()
            self.maybe_reload_bans()
            try:
                params = {"timeout": POLL_TIMEOUT, "allowed_updates": ["message", "edited_message", "channel_post", "my_chat_member",
                                                                  "callback_query"]}
                if self.offset is not None:
                    params["offset"] = self.offset
                updates = self.api.call("getUpdates", params, timeout=POLL_TIMEOUT + 15)
            except Aborted:
                return
            except Exception as exc:  # noqa: BLE001
                if self.stopping:
                    return
                if self._reload.is_set():
                    continue  # conexão fechada de propósito para recarregar
                wait = self._handle_poll_error(exc, backoff)
                if wait is None:
                    return
                backoff = min(backoff * 2, MAX_BACKOFF)
                self.sleep(wait)
                continue

            if self._last_error_code is not None:
                self.emit("recovered", code=self._last_error_code)
                self._last_error_code = None
            backoff = 2
            self.set_state("running")
            for update in updates or []:
                self.offset = update["update_id"] + 1
                message = update.get("message")
                if message and not self.stopping:
                    self.handle_message(message)
                elif update.get("edited_message"):
                    self.record_chat(update["edited_message"], edited=True)
                elif update.get("channel_post"):
                    self.record_chat(update["channel_post"])
                elif update.get("my_chat_member"):
                    self.handle_membership(update["my_chat_member"])
                elif update.get("callback_query") and not self.stopping:
                    self.handle_callback(update["callback_query"])

    def handle_membership(self, change):
        """Bot adicionado/removido de grupo ou canal, ou bloqueado/desbloqueado por um usuário."""
        chat = change.get("chat") or {}
        status = (change.get("new_chat_member") or {}).get("status")
        gone = status in ("left", "kicked")
        try:
            chats.set_blocked(self.base_dir, chat.get("id"), gone, chat=chat)
            self.emit("membership", chat=chat.get("title") or chat.get("first_name") or "",
                      chat_type=chat.get("type"), status=status)
            self.emit("chat", chat_id=chat.get("id"), incoming=False)
        except Exception:  # noqa: BLE001
            pass

    def record_chat(self, message, edited=False):
        """Guarda a mensagem recebida nas conversas do app (sem nunca derrubar o bot)."""
        try:
            text = message.get("text") or ""
            if text.split(" ")[0].split("@")[0].lower() == "/" + NEWADMIN_COMMAND:
                message = dict(message, text="/" + NEWADMIN_COMMAND + " ••••", entities=[])
            chat_id = chats.record(self.base_dir, message, outgoing=False, source="user", edited=edited)
            if chat_id is not None:
                self.emit("chat", chat_id=chat_id, incoming=not edited)
        except Exception:  # noqa: BLE001
            pass

    def record_sent(self, result, chat_id):
        if not isinstance(result, dict) or "message_id" not in result:
            return
        if self.send_source == "broadcast":
            return  # publicação em massa não lota o mensageiro de cada pessoa
        if self.storage:
            self.storage.record_out(result, self.send_source)
        try:
            cid = chats.record(self.base_dir, result, outgoing=True, source=self.send_source,
                               fallback_chat_id=chat_id)
            if cid is not None:
                self.emit("chat", chat_id=cid, incoming=False)
        except Exception:  # noqa: BLE001
            pass

    def _connect(self):
        """``getMe`` com novas tentativas. ``False`` se o bot não pode rodar."""
        backoff = 2
        while not self.stopping:
            try:
                self.bot = self.api.call("getMe", timeout=20)
                self.emit("bot", bot=self.bot)
                return True
            except Aborted:
                return False
            except Exception as exc:  # noqa: BLE001
                wait = self._handle_poll_error(exc, backoff)
                if wait is None:
                    return False
                backoff = min(backoff * 2, MAX_BACKOFF)
                self.sleep(wait)
        return False

    def _handle_poll_error(self, exc, backoff):
        """Decide quanto esperar antes de tentar de novo. ``None`` = parar o bot."""
        info = errors.classify(exc)
        code = info["code"]
        if code == errors.INVALID_TOKEN:
            self.report_error(exc, "getUpdates", fatal=True)
            self.set_state("fatal", code)
            return None
        if code == errors.TOO_MANY_REQUESTS:
            wait = (info["retry_after"] or 5) + 1
        elif code in (errors.CONFLICT, errors.WEBHOOK_ACTIVE):
            wait = 30
        else:
            wait = backoff
        self.report_error(exc, "getUpdates", retry_in=wait)
        self.set_state("retrying", code)
        return wait

    # -- mensagens -------------------------------------------------------------

    def find_command(self, name):
        return next((c for c in self.config["commands"] if name in c["triggers"]), None)

    def handle_message(self, message):
        if self.storage:
            self.storage.record(message)
        self.record_chat(message)
        text = message.get("text") or ""
        user = message.get("from") or {}
        chat = message.get("chat") or {}
        bot_username = (self.bot or {}).get("username", "")
        parsed = parse_command(text, bot_username)
        if self.check_spam(message, parsed):
            return
        if self.post.handle(message, parsed):
            return
        if parsed is None:
            return
        name, args = parsed
        sender = _display_name(user)
        if name == NEWADMIN_COMMAND:
            self.handle_newadmin(message, args, sender)
            return
        self.emit("received", user=sender, chat=chat.get("title") or "", text=text[:200])
        command = self.find_command(name)
        if command is None:
            self.emit("unknown_command", command=name)
            return
        required = command.get("admin_level")
        if required is not None:
            self.maybe_reload_admins()
            admin = self.admins.get(user.get("id"))
            if not admins_mod.can_use(admin and admin.get("level"), required):
                # Para quem não pode, o comando "não existe": nenhuma resposta.
                self.emit("denied", user=sender, user_id=user.get("id"), command=name,
                          required=required, level=admin and admin.get("level"))
                return

        stamp = self._now()
        date, _, hour = stamp.partition(" ")
        context = template.build_context(message, args, bot_username, date, hour)
        reply_to = None
        if chat.get("type") != "private":
            reply_to = {"message_id": message.get("message_id"), "allow_sending_without_reply": True}

        sent = 0
        for msg in command["messages"]:
            if self.stopping:
                return
            if self.send_message(chat.get("id"), msg, context, name, reply_to):
                sent += 1
                reply_to = None  # só a primeira mensagem responde à do usuário
        if sent:
            self.emit("replied", command=name, chat=chat.get("id"), messages=sent)

    # -- antispam ----------------------------------------------------------------

    def check_spam(self, message, parsed):
        """``True`` = a pessoa está (ou acabou de ficar) bloqueada: o bot não responde nada."""
        user = message.get("from") or {}
        chat = message.get("chat") or {}
        uid = user.get("id")
        # Em grupo, só conta (e só importa bloquear) o que é dirigido ao bot: comandos.
        if uid is None or user.get("is_bot") or (chat.get("type") != "private" and parsed is None):
            return False
        self.maybe_reload_admins()
        if uid in self.admins:
            return False  # admin nunca é bloqueado
        self.maybe_reload_bans()
        if uid in self.bans:
            return True
        settings = self.config.get("antispam") or {}
        if not settings.get("enabled", True):
            return False
        hit = self.spam.check(message, settings.get("sensitivity", antispam.DEFAULT_LEVEL))
        if not hit:
            return False
        self.auto_block(message, hit, settings)
        return True

    def auto_block(self, message, hit, settings):
        user = message.get("from") or {}
        chat = message.get("chat") or {}
        uid = user["id"]
        record = antispam.build_record(user, chat, hit, self.spam.recent(uid))
        record["stamp"] = self._now()

        if settings.get("warn_user", True) and chat.get("type") == "private":
            self._call_send("sendMessage", {"chat_id": chat.get("id"), "text": antispam.BLOCK_NOTICE}, "antispam")

        # Avisa os admins com classe para julgar o caso.
        required = settings.get("admin_level", 1)
        text = antispam.notice_text(record)
        for admin_id, admin in sorted(self.admins.items()):
            if not admins_mod.can_use(admin.get("level"), required):
                continue
            sent = self._call_send("sendMessage", {
                "chat_id": admin_id, "text": text, "parse_mode": "HTML",
                "link_preview_options": {"is_disabled": True},
                "reply_markup": antispam.keyboard(uid),
            }, "antispam")
            if isinstance(sent, dict) and sent.get("message_id"):
                record["notices"].append([admin_id, sent["message_id"]])
        antispam.add(self.base_dir, record)  # uma gravação só, já com os avisos
        self.maybe_reload_bans()
        self.emit("spam_blocked", user_id=uid, name=_display_name(user), reason=record["reason"],
                  reason_text=antispam.reason_text(record), admins_notified=len(record["notices"]))

    def _try_call(self, method, params):
        """Chamada opcional: só ``Aborted`` (parada do bot) interessa."""
        try:
            self.api.call(method, params, timeout=20)
        except Aborted:
            raise
        except Exception:  # noqa: BLE001
            pass

    def handle_callback(self, query):
        """Botões do aviso de antispam (✅ Desbloquear / 🚫 Manter bloqueado)."""
        parsed = antispam.parse_callback(query.get("data"))
        if parsed is None:
            self._try_call("answerCallbackQuery", {"callback_query_id": query.get("id")})
            return
        action, target = parsed
        user = query.get("from") or {}
        self.maybe_reload_admins()
        admin = self.admins.get(user.get("id"))
        required = (self.config.get("antispam") or {}).get("admin_level", 1)
        if admin is None or not admins_mod.can_use(admin.get("level"), required):
            self._try_call("answerCallbackQuery", {"callback_query_id": query.get("id"),
                                                   "text": "Só admins podem decidir bloqueios."})
            return
        by = _display_name(user)
        record = antispam.decide(self.base_dir, target, action, by)
        if record is None:
            self._try_call("answerCallbackQuery", {"callback_query_id": query.get("id"),
                                                   "text": "Esse caso já foi resolvido por outro admin."})
            msg = query.get("message") or {}  # tira os botões do aviso antigo
            self._try_call("editMessageReplyMarkup", {"chat_id": (msg.get("chat") or {}).get("id"),
                                                      "message_id": msg.get("message_id")})
            return
        # Responde ao toque antes de editar os avisos de todos os admins.
        released = action == antispam.RELEASE
        self._try_call("answerCallbackQuery", {"callback_query_id": query.get("id"),
                                               "text": "Desbloqueado." if released else "Mantido bloqueado."})
        self.maybe_reload_bans()
        antispam.finish_decision(self.api, record, action, lambda chat_id, text: self._call_send(
            "sendMessage", {"chat_id": chat_id, "text": text}, "antispam"))
        self.emit("spam_decided", user_id=target, action=action, by=by, name=_display_name(record))

    def handle_newadmin(self, message, args, sender):
        """``/newadmin CÓDIGO``: comando secreto. Sem código válido, não responde nada."""
        user = message.get("from") or {}
        chat = message.get("chat") or {}
        code = args.split()[0] if args else ""
        # Só em conversa privada: em grupo o código ficaria visível para todos.
        admin = None
        if code and chat.get("type") == "private":
            admin = admins_mod.redeem_code(self.base_dir, code, user)
        if admin is None:
            self.emit("newadmin_invalid", user=sender, user_id=user.get("id"),
                      private=chat.get("type") == "private", had_code=bool(code))
            return
        self.admins[admin["id"]] = admin
        self._call_send("sendMessage", {"chat_id": chat.get("id"), "text": NEWADMIN_REPLY}, NEWADMIN_COMMAND)
        # Tira o código da conversa (se o Telegram deixar).
        try:
            self.api.call("deleteMessage", {"chat_id": chat.get("id"), "message_id": message.get("message_id")})
            chats.mark_deleted(self.base_dir, chat.get("id"), message.get("message_id"))
        except Aborted:
            raise
        except Exception:  # noqa: BLE001
            pass
        self.emit("admin_added", admin=admin, user=sender)
        self.maybe_reload_admins(force=True)
        self.publish_commands()

    def send_message(self, chat_id, msg, context, command_name, reply_to=None):
        """Envia uma mensagem configurada (texto e/ou mídia + botões). ``True`` se deu certo."""
        body = template.render(msg["text"], context) if msg["text"].strip() else ""
        keyboard = build_keyboard(msg["buttons"])
        base = {"chat_id": chat_id}
        if reply_to:
            base["reply_parameters"] = reply_to

        media = msg.get("media")
        if media:
            fits = template.plain_length(body) <= CAPTION_LIMIT and media.get("type") not in NO_CAPTION
            caption = body if fits else ""
            extra = body if body and not caption else ""
            params = dict(base)
            if caption:
                params["caption"] = caption
                params["parse_mode"] = "HTML"
            if keyboard and not extra:
                params["reply_markup"] = keyboard
            if not self._send_media(media, params, command_name):
                return False
            if not extra:
                return True
            base.pop("reply_parameters", None)
            body = extra

        if not body:
            return False
        params = dict(base, text=body, parse_mode="HTML",
                      link_preview_options={"is_disabled": not msg.get("link_preview")})
        if keyboard:
            params["reply_markup"] = keyboard
        return self._call_send("sendMessage", params, command_name)

    def _media_path(self, media):
        path = media["file"]
        if not os.path.isabs(path):
            path = os.path.join(self.base_dir, path)
        return path

    def _send_media(self, media, params, command_name):
        kind = media["type"]
        method = MEDIA_METHODS[kind]
        if media.get("file_id") and not media.get("file"):
            # Reenvio de algo que já está no Telegram (ex.: figurinha de uma conversa).
            return bool(self._call_send(method, dict(params, **{kind: media["file_id"]}), command_name))
        key = media["file"]
        cached = self._media_cache.get(key)
        if cached:
            p = dict(params)
            p[kind] = cached
            result = self._call_send(method, p, command_name, quiet_media_error=True)
            if result:
                return True
            if result is None:  # file_id não vale mais: manda o arquivo de novo
                self._media_cache.pop(key, None)
                self._save_cache()
            else:
                return False
        path = self._media_path(media)
        if not os.path.isfile(path):
            self.emit("error", context="media", command=command_name, retry_in=None, fatal=False,
                      code=errors.MEDIA_MISSING, detail=media.get("name") or key, retry_after=None)
            return False
        result = self._call_send(method, params, command_name, files={kind: path})
        if isinstance(result, dict):
            file_id = self._extract_file_id(result, kind)
            if file_id:
                self._media_cache[key] = file_id
                self._save_cache()
        return bool(result)

    @staticmethod
    def _extract_file_id(message, kind):
        obj = message.get(kind)
        if kind == "photo" and isinstance(obj, list) and obj:
            return obj[-1].get("file_id")
        if isinstance(obj, dict):
            return obj.get("file_id")
        # O Telegram às vezes converte (ex.: GIF enviado como documento).
        for other in ("animation", "video", "document", "audio", "voice"):
            if isinstance(message.get(other), dict):
                return message[other].get("file_id")
        return None

    def _call_send(self, method, params, command_name, files=None, quiet_media_error=False):
        """Envia e trata os erros. Devolve o resultado (truthy), ``False`` ou ``None``
        (``None`` = erro de mídia silencioso, para tentar de novo com upload)."""
        tried_plain = False
        for attempt in range(3):
            try:
                result = self.api.call(method, params, timeout=120 if files else 30, files=files)
                self.record_sent(result, params.get("chat_id"))
                return result if result else True
            except Aborted:
                raise
            except TelegramError as exc:
                info = errors.classify(exc)
                if info["code"] == errors.TOO_MANY_REQUESTS and attempt < 2:
                    self.sleep((info["retry_after"] or 3) + 1)
                    if self.stopping:
                        return False
                    continue
                if info["code"] == errors.PARSE_ENTITIES and not tried_plain:
                    # Plano B: manda sem formatação, para a pessoa não ficar sem resposta.
                    tried_plain = True
                    field = "caption" if "caption" in params else "text"
                    self.emit("debug_text", text=params.get(field, "")[:500])
                    params = dict(params)
                    params[field] = template.html_to_plain(params[field])
                    params.pop("parse_mode", None)
                    continue
                if quiet_media_error and info["code"] in (errors.MEDIA_ERROR, errors.UNKNOWN):
                    return None
                self.emit("error", context=method, command=command_name,
                          retry_in=None, fatal=False, **info)
                return False
            except NetworkError as exc:
                self.emit("error", context=method, command=command_name,
                          retry_in=None, fatal=False, **errors.classify(exc))
                return False
            except OSError as exc:
                self.emit("error", context=method, command=command_name,
                          retry_in=None, fatal=False, **errors.classify(exc))
                return False
        return False

    # -- cache de mídia --------------------------------------------------------

    def _load_cache(self):
        try:
            with open(self._cache_path, "r", encoding="utf-8") as fh:
                data = json.load(fh)
            return data if isinstance(data, dict) else {}
        except (OSError, ValueError):
            return {}

    def _save_cache(self):
        try:
            tmp = self._cache_path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as fh:
                json.dump(self._media_cache, fh)
            os.replace(tmp, self._cache_path)
        except OSError:
            pass

    def _now(self):
        try:
            return str(self.cb.nowLocal())
        except Exception:  # noqa: BLE001
            return time.strftime("%d/%m/%Y %H:%M")
