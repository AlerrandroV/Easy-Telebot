"""Integração com o Termux: scripts em qualquer linguagem e uma API local do bot.

1. Comando de script
   Um comando (ex.: ``/clima``) roda uma linha de comando no Termux pelo app
   (``callbacks.runTermux``). O que o script imprimir vira a resposta:

   - texto simples: uma mensagem, com a mesma formatação dos comandos (*negrito*...);
   - JSON: ``{"text", "photo"|"video"|"document"|"audio"|"animation": url ou file_id,
     "buttons": [[{"text", "url"} | {"text", "data"}]], "html": bool, "edit": bool}``
     ou uma lista desses objetos (várias mensagens);
   - nada: nenhuma resposta (o script pode ter usado a API local sozinho).

   Variáveis de ambiente que o script recebe: ``TELEBOT_UPDATE`` (JSON do update),
   ``TELEBOT_ARGS`` (texto depois do comando), ``TELEBOT_DATA`` (dado do botão
   apertado), ``TELEBOT_CHAT_ID``, ``TELEBOT_USER_ID``, ``TELEBOT_USER_NAME``,
   ``TELEBOT_ADMIN_LEVEL`` (vazio se não for admin) e ``TELEBOT_API`` (endereço da
   API local, se ligada). Botões com ``data`` chamam o mesmo script de novo.

2. API local do bot
   Servidor HTTP só em ``127.0.0.1`` que imita a Bot API oficial. Qualquer
   biblioteca de bot (python-telegram-bot, aiogram, telegraf...) roda no Termux
   apontando para ela, com uma chave local no lugar do token (o token de verdade
   nunca sai do app). ``getUpdates`` entrega o que o app não respondeu (ou tudo);
   os outros métodos são repassados ao Telegram, inclusive envio de arquivos.
"""

import http.client
import http.server
import json
import re
import secrets
import ssl
import threading
import time
import urllib.parse

from . import chats, i18n, template

CALLBACK_PREFIX = "sc:"
DEFAULT_PORT = 8780
DEFAULT_TIMEOUT = 30
MAX_CONCURRENT = 4
QUEUE_LIMIT = 1000
FORWARD_UNHANDLED, FORWARD_ALL = "unhandled", "all"
MEDIA_KEYS = ("photo", "video", "document", "audio", "animation", "voice", "sticker")
ENV_NAME = re.compile(r"^[A-Z][A-Z0-9_]*$")
# Bloqueados na API local: o app é quem recebe as atualizações do Telegram.
BLOCKED = {"setwebhook", "logout", "close"}
ALL_UPDATES = ["message", "edited_message", "channel_post", "edited_channel_post", "my_chat_member",
               "chat_member", "chat_join_request", "callback_query", "inline_query",
               "chosen_inline_result", "pre_checkout_query", "shipping_query", "poll", "poll_answer",
               "message_reaction", "message_reaction_count", "chat_boost", "removed_chat_boost"]

_SSL = ssl.create_default_context()


def normalize_settings(data):
    data = data if isinstance(data, dict) else {}
    try:
        port = int(data.get("port") or DEFAULT_PORT)
    except (TypeError, ValueError):
        port = DEFAULT_PORT
    key = str(data.get("key") or "")
    return {
        "api_enabled": bool(data.get("api_enabled", False)),
        "port": port if 1024 <= port <= 65535 else DEFAULT_PORT,
        "key": key if re.match(r"^\d+:[A-Za-z0-9_-]{20,}$", key) else "",
        "forward": data.get("forward") if data.get("forward") in (FORWARD_UNHANDLED, FORWARD_ALL) else FORWARD_UNHANDLED,
    }


def normalize_script(data):
    """``{"command": "python ~/bot/x.py", "timeout": 30}`` ou ``None``."""
    if not isinstance(data, dict) or not str(data.get("command") or "").strip():
        return None
    try:
        timeout = int(data.get("timeout") or DEFAULT_TIMEOUT)
    except (TypeError, ValueError):
        timeout = DEFAULT_TIMEOUT
    return {"command": str(data["command"]).strip()[:2000], "timeout": max(1, min(300, timeout))}


def new_key(bot_id):
    return "%s:%s" % (bot_id, secrets.token_urlsafe(27))


# -- saída do script -> mensagens -----------------------------------------------------------

def parse_output(stdout):
    """Texto ou JSON impresso pelo script -> lista de mensagens (dicts)."""
    out = (stdout or "").strip()
    if not out:
        return []
    if out[0] in "[{":
        try:
            data = json.loads(out)
        except ValueError:
            data = None
        if isinstance(data, dict):
            return [data]
        if isinstance(data, list) and all(isinstance(x, dict) for x in data):
            return data
    return [{"text": out}]


def callback_data(trigger, data):
    """Dado de botão que volta para o mesmo script (máx. 64 bytes no Telegram)."""
    prefix = "%s%s:" % (CALLBACK_PREFIX, trigger)
    room = 64 - len(prefix.encode("utf-8"))
    raw = str(data).encode("utf-8")[:max(0, room)].decode("utf-8", "ignore")
    return prefix + raw


def parse_callback(data):
    if not isinstance(data, str) or not data.startswith(CALLBACK_PREFIX):
        return None
    trigger, _, value = data[len(CALLBACK_PREFIX):].partition(":")
    return (trigger, value) if trigger else None


def keyboard(rows, trigger):
    out = []
    for row in rows or []:
        if isinstance(row, dict):
            row = [row]
        clean = []
        for b in row if isinstance(row, list) else []:
            if not isinstance(b, dict) or not b.get("text"):
                continue
            if b.get("url"):
                url = template.normalize_url(b["url"])
                if url:
                    clean.append({"text": str(b["text"])[:64], "url": url})
            elif "data" in b:
                clean.append({"text": str(b["text"])[:64], "callback_data": callback_data(trigger, b["data"])})
        if clean:
            out.append(clean[:8])
    return {"inline_keyboard": out} if out else None


class TermuxBridge:
    """Liga o motor aos scripts do Termux e à API local."""

    def __init__(self, engine):
        self.engine = engine
        self.api = None          # LocalApi em execução
        self._api_conf = None
        self._slots = threading.BoundedSemaphore(MAX_CONCURRENT)
        self.sync_scripts = False  # testes: roda o script na mesma thread

    @property
    def settings(self):
        return self.engine.config.get("termux") or normalize_settings({})

    @property
    def api_url(self):
        s = self.settings
        if self.api is None or not s["key"]:
            return ""
        return "http://127.0.0.1:%d/bot%s" % (s["port"], s["key"])

    def allowed_updates(self, base):
        return ALL_UPDATES if self.api is not None else base

    # -- API local --------------------------------------------------------------------------

    def sync(self):
        """Liga, desliga ou reinicia a API local conforme a configuração."""
        s = self.settings
        wanted = (s["port"], s["key"]) if s["api_enabled"] and s["key"] else None
        if wanted == self._api_conf:
            return
        self.stop()
        self._api_conf = wanted
        if wanted is None:
            return
        api = LocalApi(self.engine, wanted[0], wanted[1])
        try:
            api.start()
        except OSError as exc:
            self.engine.emit("error", context="local_api", command=None, retry_in=None, fatal=False,
                             code="termux_port_busy", detail="porta %d: %s" % (wanted[0], exc), retry_after=None)
            return
        self.api = api
        self.engine.emit("local_api", state="listening", port=wanted[0])

    def stop(self):
        if self.api is not None:
            self.api.stop()
            self.api = None
            self.engine.emit("local_api", state="stopped")
        self._api_conf = None

    def forward(self, update, handled):
        """Entrega o update aos scripts da API local (o que o app não respondeu, ou tudo)."""
        if self.api is None:
            return
        if handled and self.settings["forward"] != FORWARD_ALL:
            return
        self.api.push(update)

    # -- comandos de script -----------------------------------------------------------------

    def run_command(self, command, trigger, update, chat_id, user, args="", data=None, reply_to=None,
                    message_id=None):
        """Roda o script numa thread própria (o bot continua respondendo enquanto isso)."""
        job = (command, trigger, update, chat_id, user, args, data, reply_to, message_id)
        if self.sync_scripts:
            self._run(*job)
            return
        threading.Thread(target=self._run, args=job, name="script", daemon=True).start()

    def _env(self, trigger, update, chat_id, user, args, data):
        admin = self.engine.admins.get(user.get("id"))
        return {
            "TELEBOT_UPDATE": json.dumps(update, ensure_ascii=False),
            "TELEBOT_ARGS": args or "",
            "TELEBOT_DATA": "" if data is None else str(data),
            "TELEBOT_COMMAND": trigger,
            "TELEBOT_CHAT_ID": str(chat_id),
            "TELEBOT_USER_ID": str(user.get("id") or ""),
            "TELEBOT_USER_NAME": " ".join(x for x in (user.get("first_name"), user.get("last_name")) if x),
            "TELEBOT_ADMIN_LEVEL": str(admin.get("level")) if admin else "",
            "TELEBOT_API": self.api_url,
        }

    def _run(self, command, trigger, update, chat_id, user, args, data, reply_to, message_id):
        self.execute(command["script"], trigger, update, chat_id, user, args, data, reply_to, message_id)

    def execute(self, script, trigger, update, chat_id, user, args="", data=None, reply_to=None, message_id=None,
                sender=None):
        """Roda o script e manda a saída (bloqueia). Devolve quantas mensagens saíram (-1 = erro)."""
        eng = self.engine
        with self._slots:
            started = time.time()
            try:
                raw = eng.cb.runTermux(script["command"], json.dumps(self._env(trigger, update, chat_id, user, args, data),
                                                                     ensure_ascii=False), int(script["timeout"]))
                result = json.loads(str(raw))
            except Exception as exc:  # noqa: BLE001
                result = {"ok": False, "error": {"code": "termux_failed", "detail": "%s: %s" % (type(exc).__name__, exc)}}
            elapsed = round(time.time() - started, 1)
            if not result.get("ok"):
                err = result.get("error") or {}
                eng.emit("error", context="script", command=trigger, retry_in=None, fatal=False,
                         code=err.get("code") or "termux_failed", detail=err.get("detail") or "", retry_after=None)
                return -1
            code = int(result.get("exit_code") or 0)
            if code != 0:
                eng.emit("error", context="script", command=trigger, retry_in=None, fatal=False,
                         code="termux_timeout" if code == 124 else "termux_exit",
                         detail=i18n.t("detail.exit_code", None, code, (result.get("stderr") or "").strip()[-600:]), retry_after=None)
                return -1
            sender = sender or self._sender()
            context = None
            sent = 0
            for msg in parse_output(result.get("stdout"))[:10]:
                if eng.stopping:
                    return sent
                if context is None:
                    stamp = eng._now()
                    date, _, hour = stamp.partition(" ")
                    context = template.build_context({"from": user, "chat": {"id": chat_id}}, args,
                                                     (eng.bot or {}).get("username", ""), date, hour)
                if self.send(sender, chat_id, msg, trigger, context, reply_to, message_id):
                    sent += 1
                    reply_to = None
            eng.emit("script_done", command=trigger, seconds=elapsed, messages=sent)
            return sent

    def _sender(self):
        """Cópia do motor com conexão própria (a do motor está presa no long polling)."""
        import copy
        from .api import TelegramApi

        sender = copy.copy(self.engine)
        sender.api = TelegramApi(self.engine.token)
        sender.send_source = "auto"
        return sender

    def send(self, sender, chat_id, msg, trigger, context, reply_to=None, message_id=None):
        """Uma mensagem do script. ``True`` se foi enviada."""
        kb = keyboard(msg.get("buttons"), trigger)
        text = str(msg.get("text") or "")
        if msg.get("edit") and message_id and text:
            params = {"chat_id": chat_id, "message_id": message_id,
                      "text": text if msg.get("html") else template.render(text, context), "parse_mode": "HTML"}
            if kb:
                params["reply_markup"] = kb
            try:
                sender.api.call("editMessageText", params, timeout=30)
                return True
            except Exception as exc:  # noqa: BLE001 - mensagem antiga/igual: manda uma nova
                if "not modified" in str(exc):
                    return True
        media_key = next((k for k in MEDIA_KEYS if msg.get(k)), None)
        base = {"chat_id": chat_id}
        if reply_to:
            base["reply_parameters"] = reply_to
        if media_key:
            params = dict(base, **{media_key: str(msg[media_key])})
            if text and media_key != "sticker":
                params["caption"] = text if msg.get("html") else template.render(text, context)
                params["parse_mode"] = "HTML"
            if kb:
                params["reply_markup"] = kb
            method = "send" + media_key[0].upper() + media_key[1:]
            return bool(sender._call_send(method, params, trigger))
        if not text:
            return False
        params = dict(base, text=text if msg.get("html") else template.render(text, context), parse_mode="HTML",
                      link_preview_options={"is_disabled": not msg.get("preview")})
        if kb:
            params["reply_markup"] = kb
        return bool(sender._call_send("sendMessage", params, trigger))


# -- API local --------------------------------------------------------------------------------

class LocalApi:
    def __init__(self, engine, port, key, upstream="api.telegram.org"):
        self.engine = engine
        self.port = port
        self.key = key
        self.upstream = upstream
        self.updates = []
        self.cond = threading.Condition()
        self.server = None
        self.thread = None
        self.stopping = False

    def start(self):
        api = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args):  # sem log no console
                pass

            def do_GET(self):  # noqa: N802
                try:
                    api.handle(self)
                except (BrokenPipeError, ConnectionResetError):
                    pass  # o script fechou a conexão (ex.: parou no meio do getUpdates)

            do_POST = do_GET  # noqa: N815

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", self.port), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, name="local-api", daemon=True)
        self.thread.start()

    def stop(self):
        self.stopping = True
        with self.cond:
            self.cond.notify_all()
        if self.server is not None:
            self.server.shutdown()
            self.server.server_close()

    # -- fila de updates --------------------------------------------------------------------

    def push(self, update):
        with self.cond:
            self.updates.append(update)
            del self.updates[:-QUEUE_LIMIT]
            self.cond.notify_all()

    def get_updates(self, offset=None, limit=100, timeout=0):
        deadline = time.time() + max(0, min(int(timeout or 0), 50))
        with self.cond:
            if offset:
                self.updates = [u for u in self.updates if u["update_id"] >= int(offset)]
            while not self.updates and not self.stopping:
                left = deadline - time.time()
                if left <= 0:
                    break
                self.cond.wait(left)
            return self.updates[:max(1, min(int(limit or 100), 100))]

    # -- HTTP -------------------------------------------------------------------------------

    @staticmethod
    def _reply(handler, status, body, ctype="application/json"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False).encode("utf-8")
        handler.send_response(status)
        handler.send_header("Content-Type", ctype)
        handler.send_header("Content-Length", str(len(body)))
        handler.end_headers()
        handler.wfile.write(body)

    @staticmethod
    def _params(query, body, ctype):
        params = dict(urllib.parse.parse_qsl(query))
        if body and "application/json" in ctype:
            try:
                data = json.loads(body.decode("utf-8"))
                if isinstance(data, dict):
                    params.update(data)
            except ValueError:
                pass
        elif body and "application/x-www-form-urlencoded" in ctype:
            params.update(urllib.parse.parse_qsl(body.decode("utf-8")))
        return params

    def handle(self, h):
        parts = urllib.parse.urlsplit(h.path)
        length = int(h.headers.get("Content-Length") or 0)
        body = h.rfile.read(length) if length else b""
        ctype = h.headers.get("Content-Type") or ""
        m = re.match(r"^/(file/)?bot([^/]+)/(.+)$", parts.path)
        if not m or not secrets.compare_digest(m.group(2), self.key):
            self._reply(h, 401, {"ok": False, "error_code": 401, "description": "Unauthorized"})
            return
        is_file, method = bool(m.group(1)), m.group(3)
        if is_file:
            self.forward(h, "GET", "/file/bot%s/%s" % (self.engine.token, method), b"", "")
            return
        name = method.lower()
        if name == "getupdates":
            p = self._params(parts.query, body, ctype)
            result = self.get_updates(p.get("offset"), p.get("limit") or 100, p.get("timeout") or 0)
            self._reply(h, 200, {"ok": True, "result": result})
        elif name == "deletewebhook":
            p = self._params(parts.query, body, ctype)
            if str(p.get("drop_pending_updates")).lower() in ("true", "1"):
                with self.cond:
                    self.updates = []
            self._reply(h, 200, {"ok": True, "result": True, "description": "Easy Telebot: no webhook"})
        elif name in BLOCKED:
            self._reply(h, 400, {"ok": False, "error_code": 400,
                                 "description": "Bad Request: %s is not allowed by the Easy Telebot local API "
                                                "(the app receives the Telegram updates)" % method})
        else:
            path = "/bot%s/%s" % (self.engine.token, method) + ("?" + parts.query if parts.query else "")
            self.forward(h, h.command, path, body, ctype)

    def upstream_call(self, verb, path, body, ctype):
        """Repassa ao Telegram. Devolve ``(status, bytes, content_type)``."""
        conn = http.client.HTTPSConnection(self.upstream, 443, timeout=120, context=_SSL)
        try:
            headers = {"Content-Type": ctype} if ctype else {}
            conn.request(verb, path, body=body or None, headers=headers)
            resp = conn.getresponse()
            return resp.status, resp.read(), resp.getheader("Content-Type") or "application/json"
        finally:
            conn.close()

    def forward(self, h, verb, path, body, ctype):
        try:
            status, raw, rtype = self.upstream_call(verb, path, body, ctype)
        except (OSError, http.client.HTTPException) as exc:
            self._reply(h, 502, {"ok": False, "error_code": 502, "description": "Easy Telebot: no connection to "
                                                                                 "Telegram (%s)" % exc})
            return
        self._reply(h, status, raw, rtype)
        if "json" in rtype and status == 200:
            self.record(raw)

    def record(self, raw):
        """Mensagens enviadas pelos scripts também aparecem no mensageiro e no banco."""
        try:
            result = json.loads(raw.decode("utf-8")).get("result")
        except (ValueError, AttributeError):
            return
        for msg in result if isinstance(result, list) else [result]:
            if not isinstance(msg, dict) or "message_id" not in msg or "chat" not in msg:
                continue
            try:
                if self.engine.storage:
                    self.engine.storage.record_out(msg, "script")
                cid = chats.record(self.engine.base_dir, msg, outgoing=True, source="script")
                if cid is not None:
                    self.engine.emit("chat", chat_id=cid, incoming=False)
            except Exception:  # noqa: BLE001
                pass

