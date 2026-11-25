"""Cliente mínimo da Bot API do Telegram.

Usa ``http.client`` diretamente (e não ``urllib``) para podermos fechar a
conexão de outra thread: assim, parar o bot interrompe o long polling na hora,
sem esperar os ~50 s do timeout.
"""

import http.client
import json
import mimetypes
import os
import socket
import ssl
import threading
import urllib.parse
import uuid

from . import i18n

HOST = "api.telegram.org"


class TelegramError(Exception):
    """O Telegram respondeu, mas recusou o pedido (ok=false)."""

    def __init__(self, method, error_code, description, retry_after=None):
        super().__init__("%s: %s %s" % (method, error_code, description))
        self.method = method
        self.error_code = error_code
        self.description = description or ""
        self.retry_after = retry_after


class NetworkError(Exception):
    """Não foi possível falar com o Telegram (sem internet, DNS, timeout...)."""

    def __init__(self, method, reason):
        super().__init__("%s: %s" % (method, reason))
        self.method = method
        self.reason = str(reason)


class Aborted(Exception):
    """A conexão foi fechada de propósito porque o bot está parando."""


class TelegramApi:
    def __init__(self, token, host=HOST):
        self.token = token
        self.host = host
        self._lock = threading.Lock()
        self._conn = None
        self._aborted = False
        self._ssl = ssl.create_default_context()

    # -- controle de conexão -------------------------------------------------

    def abort(self):
        """Fecha a conexão atual (chamado de outra thread ao parar o bot)."""
        with self._lock:
            self._aborted = True
            conn = self._conn
        if conn is not None:
            try:
                if conn.sock is not None:
                    conn.sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                conn.close()
            except OSError:
                pass

    def interrupt(self):
        """Fecha a conexão atual sem parar o cliente (o long polling volta a ser feito).

        Usado para o motor recarregar a configuração na hora, sem esperar os ~50 s.
        """
        with self._lock:
            conn = self._conn
            self._conn = None
        if conn is not None:
            try:
                if conn.sock is not None:
                    conn.sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                conn.close()
            except OSError:
                pass

    def _open(self, timeout):
        with self._lock:
            if self._aborted:
                raise Aborted()
            if self._conn is None:
                self._conn = http.client.HTTPSConnection(
                    self.host, timeout=timeout, context=self._ssl
                )
            else:
                self._conn.timeout = timeout
                if self._conn.sock is not None:
                    self._conn.sock.settimeout(timeout)
            return self._conn

    def _drop(self):
        with self._lock:
            conn, self._conn = self._conn, None
        if conn is not None:
            try:
                conn.close()
            except OSError:
                pass

    # -- chamadas --------------------------------------------------------------

    def call(self, method, params=None, timeout=30, files=None):
        """Chama um método da Bot API.

        ``files`` = ``{"campo": caminho_do_arquivo}`` envia como multipart (upload).
        """
        if files:
            body, ctype = _multipart(params or {}, files)
        else:
            body = json.dumps(params or {}).encode("utf-8")
            ctype = "application/json"
        path = "/bot%s/%s" % (self.token, method)
        headers = {"Content-Type": ctype, "Connection": "keep-alive"}
        try:
            conn = self._open(timeout)
            conn.request("POST", path, body=body, headers=headers)
            resp = conn.getresponse()
            raw = resp.read()
        except Aborted:
            raise
        except (OSError, http.client.HTTPException) as exc:
            self._drop()
            if self._aborted:
                raise Aborted()
            raise NetworkError(method, exc) from None

        try:
            data = json.loads(raw.decode("utf-8"))
        except ValueError:
            self._drop()
            raise TelegramError(method, resp.status, i18n.t("detail.bad_response", None, resp.status))

        if not data.get("ok"):
            params_ = data.get("parameters") or {}
            raise TelegramError(
                method,
                data.get("error_code", resp.status),
                data.get("description", ""),
                params_.get("retry_after"),
            )
        return data.get("result")

    def download(self, file_path, dest, timeout=30):
        """Baixa um arquivo de ``getFile`` para ``dest``."""
        quoted = urllib.parse.quote(file_path)
        conn = http.client.HTTPSConnection(self.host, timeout=timeout, context=self._ssl)
        try:
            conn.request("GET", "/file/bot%s/%s" % (self.token, quoted))
            resp = conn.getresponse()
            data = resp.read()
            if resp.status != 200:
                raise TelegramError("download", resp.status, "falha ao baixar arquivo")
        except (OSError, http.client.HTTPException) as exc:
            raise NetworkError("download", exc) from None
        finally:
            conn.close()
        with open(dest, "wb") as fh:
            fh.write(data)
        return dest


def _safe_filename(name):
    base = "".join(c if c.isalnum() or c in "._-" else "_" for c in os.path.basename(name))
    return base or "arquivo"


def _multipart(params, files):
    boundary = "----easytelebot" + uuid.uuid4().hex
    parts = []
    for key, value in params.items():
        if value is None:
            continue
        if not isinstance(value, str):
            value = json.dumps(value, ensure_ascii=False)
        parts.append(
            ("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n" % (boundary, key)).encode("utf-8")
            + value.encode("utf-8") + b"\r\n"
        )
    for key, path in files.items():
        filename = _safe_filename(path)
        mime = mimetypes.guess_type(filename)[0] or "application/octet-stream"
        with open(path, "rb") as fh:
            data = fh.read()
        parts.append(
            ("--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\n"
             "Content-Type: %s\r\n\r\n" % (boundary, key, filename, mime)).encode("utf-8")
            + data + b"\r\n"
        )
    parts.append(("--%s--\r\n" % boundary).encode("utf-8"))
    return b"".join(parts), "multipart/form-data; boundary=%s" % boundary
