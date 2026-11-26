"""Liga o Python facilitado (``pyfacil``) ao motor: envio pelo Telegram, Termux, variáveis."""

import json
import os
import secrets
import time

from . import flows, pyfacil, template

FILES_DIR = "pyfacil_files"
FILES_TTL = 3600


class EngineHost:
    """O que o código do dono do bot pode fazer "lá fora"."""

    callback_prefix = flows.CUSTOM_CALLBACK

    def __init__(self, engine, sender, name, update, chat_id, user, args):
        self.engine = engine
        self.sender = sender
        self.name = name
        self.update = update or {}
        self.chat_id = chat_id
        self.user = user or {}
        self.args = args or ""

    @property
    def stopping(self):
        return self.engine.stopping

    def send(self, chat_id, method, params, files=None):
        params = dict(params, chat_id=chat_id if chat_id is not None else self.chat_id)
        for key in ("text", "caption"):
            if params.get(key):
                params[key] = template.markdown_to_html(params[key])
                params["parse_mode"] = "HTML"
        if method == "sendMessage":
            params.setdefault("link_preview_options", {"is_disabled": True})
        return self.sender._call_send(method, params, self.name, files=files)

    def download(self, info):
        api = self.sender.api
        meta = api.call("getFile", {"file_id": info["file_id"]}, timeout=30) or {}
        remote = meta.get("file_path") or ""
        if not remote:
            raise RuntimeError("getFile")
        folder = os.path.join(self.engine.base_dir, FILES_DIR)
        os.makedirs(folder, exist_ok=True)
        _cleanup(folder)
        ext = os.path.splitext(remote)[1][:8]
        dest = os.path.join(folder, "%s%s" % (info.get("file_unique_id") or secrets.token_hex(6), ext))
        api.download(remote, dest, timeout=90)
        return dest

    def termux(self, command, env, timeout, files):
        eng = self.engine
        full = eng.termux._env(self.name, self.update, self.chat_id, self.user, self.args, None)
        full.update(env or {})
        if files:
            full["TELEBOT_FILES"] = json.dumps(files, ensure_ascii=False)
        with eng.termux._slots:
            try:
                raw = eng.cb.runTermux(command, json.dumps(full, ensure_ascii=False), int(timeout))
                result = json.loads(str(raw))
            except Exception as exc:  # noqa: BLE001
                result = {"ok": False, "error": {"code": "termux_failed", "detail": "%s: %s" % (type(exc).__name__, exc)}}
        if not result.get("ok"):
            err = result.get("error") or {}
            eng.emit("error", context="script", command=self.name, retry_in=None, fatal=False,
                     code=err.get("code") or "termux_failed", detail=err.get("detail") or "", retry_after=None)
        return result

    def remind(self, when, text=None):
        out = self.engine.scheduler.remind(self.chat_id, self.user, when, text)
        if out:
            self.engine.emit("reminder_created", user=self.user.get("first_name") or "", when=out[0])
        return out

    def cancel_reminders(self):
        return self.engine.scheduler.cancel_reminders(self.user.get("id"))

    def log(self, text):
        self.engine.emit("code_log", command=self.name, text=text)

    def sleep(self, seconds):
        self.engine._stop.wait(seconds)

    def now(self):
        return self.engine._now()


def _cleanup(folder):
    limit = time.time() - FILES_TTL
    try:
        for entry in os.scandir(folder):
            if entry.is_file() and entry.stat().st_mtime < limit:
                os.remove(entry.path)
    except OSError:
        pass


def run(engine, code, name, chat_id, user, args="", update=None, sender=None, message=None):
    """Roda o código de um bloco. Devolve o resultado de ``pyfacil.run`` (``stopped`` = ``parar()``)."""
    update = update or {}
    user = user or {}
    if message is None:
        message = update.get("message")
        query = update.get("callback_query")
        if message is None and query:
            qmsg = query.get("message") or {}
            message = {"_button": (query.get("data") or "")[len(flows.CUSTOM_CALLBACK):], "from": user,
                       "chat": qmsg.get("chat") or {"id": chat_id}, "message_id": qmsg.get("message_id")}
    message = message or {"from": user, "chat": {"id": chat_id}}
    chat = message.get("chat") or {"id": chat_id}
    uid = user.get("id")
    stored = flows.user_state(engine.base_dir, uid).get("vars") or {} if uid is not None else {}
    before = json.dumps(stored, sort_keys=True, ensure_ascii=False)
    host = EngineHost(engine, sender or engine, name, update, chat_id, user, args)
    out = pyfacil.run(code, host, message=message, user=user, chat=chat, args=args, variables=dict(stored),
                      default_lang=engine.lang, is_admin=uid in engine.admins)
    if uid is not None:
        clean = pyfacil.storable(out["variables"])
        if clean is None:
            err = pyfacil.ScriptError("variables", 0, n=pyfacil.MAX_VARIABLES // 1024)
            out["ok"], out["error"] = False, err.to_dict(out["lang"])
        elif json.dumps(clean, sort_keys=True, ensure_ascii=False) != before:
            flows._update(engine.base_dir, uid, lambda st: st.__setitem__("vars", clean))
    if out["error"]:
        engine.emit("code_error", command=name, line=out["error"]["line"], code=out["error"]["code"],
                    message=out["error"]["message"])
    return out


def check(code, lang=None, default_lang="pt"):
    """Para o editor do app: JSON com ``ok``, ``lang`` e ``errors``."""
    return json.dumps(pyfacil.check(code, lang, default_lang), ensure_ascii=False)
