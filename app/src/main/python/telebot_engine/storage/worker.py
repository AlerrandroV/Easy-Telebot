"""Fila em segundo plano para gravar no banco sem atrasar as respostas do bot."""

import queue
import threading
import time

from . import http, open_store, parse_settings, record_message, record_user
from .http import StorageError

REDACTED_COMMANDS = ("/newadmin",)


class StorageWorker(threading.Thread):
    def __init__(self, settings, bot_dir, namespace, emit):
        super().__init__(name="storage", daemon=True)
        self.settings = parse_settings(settings)
        self.bot_dir = bot_dir
        self.namespace = namespace
        self.emit = emit
        self.queue = queue.Queue(maxsize=1000)
        self._last_error = (None, 0.0)

    @property
    def enabled(self):
        return bool(self.settings.get("store_users") or self.settings.get("store_history"))

    def record(self, message):
        """Chamado pelo motor a cada mensagem recebida."""
        if not self.enabled:
            return
        text = message.get("text") or message.get("caption") or ""
        for cmd in REDACTED_COMMANDS:  # nunca guardar códigos secretos
            if text.split(" ")[0].split("@")[0].lower() == cmd:
                text = cmd + " ••••"
        try:
            self.queue.put_nowait(("message", message, text))
        except queue.Full:
            pass  # banco lento demais: descarta em vez de travar o bot

    def stop(self):
        try:
            self.queue.put_nowait(None)
        except queue.Full:
            pass

    def _report(self, exc):
        code, at = self._last_error
        if exc.code == code and time.time() - at < 300:
            return  # não repete o mesmo erro a cada mensagem
        self._last_error = (exc.code, time.time())
        self.emit("error", context="storage", retry_in=None, fatal=False, **exc.to_dict())

    def run(self):
        try:
            store = open_store(self.settings, self.bot_dir, self.namespace)
        except StorageError as exc:
            self._report(exc)
            return
        while True:
            item = self.queue.get()
            if item is None:
                return
            _, message, text = item
            try:
                if self.settings.get("store_users"):
                    record_user(store, message.get("from"), message.get("chat"))
                if self.settings.get("store_history"):
                    record_message(store, "%s-%s" % ((message.get("chat") or {}).get("id"), message.get("message_id")),
                                   message, text)
                if self._last_error[0] is not None:
                    self._last_error = (None, 0.0)
                    self.emit("storage_ok")
            except StorageError as exc:
                self._report(exc)
            except Exception as exc:  # noqa: BLE001
                self._report(StorageError(http.UNKNOWN, "%s: %s" % (type(exc).__name__, exc)))
