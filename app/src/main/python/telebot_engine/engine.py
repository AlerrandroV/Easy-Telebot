"""Loop principal do bot (long polling com ``getUpdates``).

Comunicação com o Kotlin:
- O Kotlin cria ``Engine(token, config_path, data_dir, callbacks)`` e chama
  ``run()`` numa thread própria. ``stop()`` e ``wake()`` podem ser chamados de
  outras threads.
- ``callbacks.onEvent(json)`` recebe eventos (status, mensagens, erros).
- ``callbacks.nowLocal()`` devolve ``"dd/MM/aaaa HH:mm"`` no fuso do celular.
"""

import json
import os
import re
import threading
import time

from . import errors, template
from .api import Aborted, NetworkError, TelegramApi, TelegramError

POLL_TIMEOUT = 50  # segundos que o Telegram segura a conexão esperando mensagens
TOKEN_RE = re.compile(r"^\d{5,}:[A-Za-z0-9_-]{30,}$")
COMMAND_RE = re.compile(r"^[a-z0-9_]{1,32}$")
MAX_BACKOFF = 60


def _json(obj):
    return json.dumps(obj, ensure_ascii=False)


def _error_result(exc):
    return _json({"ok": False, "error": errors.classify(exc)})


def _format_error(detail):
    return {"code": errors.TOKEN_FORMAT, "detail": detail, "retry_after": None}


def check_token(token, photo_dir):
    """Valida o token com ``getMe`` e baixa a foto do bot (se houver).

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

    photo = None
    try:
        photos = api.call("getUserProfilePhotos", {"user_id": me["id"], "limit": 1}, timeout=20)
        if photos and photos.get("photos"):
            sizes = photos["photos"][0]
            small = min(sizes, key=lambda s: s.get("width", 0) if s.get("width", 0) >= 160 else 10 ** 6)
            info = api.call("getFile", {"file_id": small["file_id"]}, timeout=20)
            os.makedirs(photo_dir, exist_ok=True)
            photo = api.download(info["file_path"], os.path.join(photo_dir, "bot_photo.jpg"))
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


def load_config(path):
    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        data = {}
    commands = []
    for item in data.get("commands") or []:
        name = str(item.get("command", "")).strip().lstrip("/").lower()
        if not name:
            continue
        commands.append({
            "command": name,
            "description": str(item.get("description") or "").strip(),
            "response": str(item.get("response") or ""),
        })
    data["commands"] = commands
    return data


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


class Engine:
    def __init__(self, token, config_path, data_dir, callbacks, api=None):
        self.token = token.strip()
        self.config_path = config_path
        self.data_dir = data_dir
        self.cb = callbacks
        self.api = api or TelegramApi(self.token)
        self._stop = threading.Event()
        self._wake = threading.Event()
        self._config_mtime = None
        self.config = {"commands": []}
        self.bot = None
        self.offset = None
        self._state = None
        self._last_error_code = None

    # -- controle (chamado de outras threads) ---------------------------------

    def stop(self):
        self._stop.set()
        self._wake.set()
        self.api.abort()

    def wake(self):
        """A internet voltou: tenta de novo agora em vez de esperar o backoff."""
        self._wake.set()

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
        try:
            mtime = os.path.getmtime(self.config_path)
        except OSError:
            mtime = None
        if not force and mtime == self._config_mtime:
            return False
        self._config_mtime = mtime
        self.config = load_config(self.config_path)
        self.emit("config", commands=len(self.config["commands"]))
        self.publish_commands()
        return True

    def publish_commands(self):
        """Registra os comandos no menu "/" do Telegram."""
        menu = []
        for c in self.config["commands"]:
            if not COMMAND_RE.match(c["command"]):
                continue
            desc = c["description"] or c["command"]
            menu.append({"command": c["command"], "description": desc[:256]})
        try:
            self.api.call("setMyCommands", {"commands": menu[:100]})
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
        if not self._connect():
            return
        self.maybe_reload_config(force=True)
        self.set_state("running")
        backoff = 2
        while not self.stopping:
            self.maybe_reload_config()
            try:
                params = {"timeout": POLL_TIMEOUT, "allowed_updates": ["message"]}
                if self.offset is not None:
                    params["offset"] = self.offset
                updates = self.api.call("getUpdates", params, timeout=POLL_TIMEOUT + 15)
            except Aborted:
                return
            except Exception as exc:  # noqa: BLE001
                if self.stopping:
                    return
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

    def handle_message(self, message):
        text = message.get("text") or ""
        user = message.get("from") or {}
        chat = message.get("chat") or {}
        bot_username = (self.bot or {}).get("username", "")
        parsed = parse_command(text, bot_username)
        if parsed is None:
            return
        name, args = parsed
        sender = user.get("username") and "@" + user["username"] or user.get("first_name", "")
        self.emit(
            "received",
            user=sender,
            chat=chat.get("title") or "",
            text=text[:200],
        )
        command = next((c for c in self.config["commands"] if c["command"] == name), None)
        if command is None:
            self.emit("unknown_command", command=name)
            return

        stamp = self._now()
        date, _, hour = stamp.partition(" ")
        context = template.build_context(message, args, bot_username, date, hour)
        body = template.render(command["response"], context)
        if not body.strip():
            return
        params = {
            "chat_id": chat.get("id"),
            "text": body,
            "parse_mode": "HTML",
            "link_preview_options": {"is_disabled": True},
        }
        if chat.get("type") != "private":
            params["reply_parameters"] = {
                "message_id": message.get("message_id"),
                "allow_sending_without_reply": True,
            }
        self._send(params, name)

    def _send(self, params, command_name):
        for attempt in range(2):
            try:
                self.api.call("sendMessage", params)
                self.emit("replied", command=command_name, chat=params["chat_id"])
                return True
            except Aborted:
                raise
            except TelegramError as exc:
                info = errors.classify(exc)
                if info["code"] == errors.TOO_MANY_REQUESTS and attempt == 0:
                    self.sleep((info["retry_after"] or 3) + 1)
                    if self.stopping:
                        return False
                    continue
                if info["code"] == errors.PARSE_ENTITIES:
                    self.emit("debug_text", text=params["text"][:500])
                self.emit("error", context="sendMessage", command=command_name,
                          retry_in=None, fatal=False, **info)
                return False
            except NetworkError as exc:
                self.emit("error", context="sendMessage", command=command_name,
                          retry_in=None, fatal=False, **errors.classify(exc))
                return False
        return False

    def _now(self):
        try:
            return str(self.cb.nowLocal())
        except Exception:  # noqa: BLE001
            return time.strftime("%d/%m/%Y %H:%M")
