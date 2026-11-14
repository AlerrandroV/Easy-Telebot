"""Testes do motor em Python puro (rodam no PC e no CI, sem Android).

    python -m unittest discover -s app/src/test/python -t app/src/test/python
"""

import json
import os
import sys
import tempfile
import threading
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "main", "python"))

from telebot_engine import engine, errors, template  # noqa: E402
from telebot_engine.api import NetworkError, TelegramError  # noqa: E402


class Callbacks:
    def __init__(self):
        self.events = []

    def onEvent(self, data):
        self.events.append(json.loads(data))

    def nowLocal(self):
        return "14/11/2026 09:05"

    def of(self, type_):
        return [e for e in self.events if e["type"] == type_]


class FakeApi:
    """Responde getMe/getUpdates/sendMessage a partir de uma fila de roteiros."""

    def __init__(self, updates_script):
        self.script = list(updates_script)
        self.sent = []
        self.calls = []
        self.engine = None
        self.fail_next = {}

    def abort(self):
        pass

    def interrupt(self):
        pass

    def call(self, method, params=None, timeout=30, files=None):
        self.calls.append((method, params, files))
        if self.fail_next.get(method):
            exc = self.fail_next[method].pop(0)
            if exc is not None:
                raise exc
        if method == "getMe":
            return {"id": 1, "username": "MeuBot", "first_name": "Meu Bot"}
        if method in ("setMyCommands", "deleteMyCommands"):
            return True
        if method == "sendMessage":
            self.sent.append(params)
            return {"message_id": 99}
        if method == "sendPhoto":
            self.sent.append(dict(params, _files=files))
            return {"message_id": 100, "photo": [{"file_id": "small"}, {"file_id": "BIG"}]}
        if method == "getUpdates":
            if not self.script:
                self.engine.stop()
                return []
            step = self.script.pop(0)
            if isinstance(step, Exception):
                raise step
            return step
        raise AssertionError(method)


def msg(text, update_id=1, first="Ana", username=None, chat_type="private", title=None):
    user = {"id": 42, "first_name": first}
    if username:
        user["username"] = username
    chat = {"id": 42 if chat_type == "private" else -100, "type": chat_type}
    if title:
        chat["title"] = title
    return {"update_id": update_id, "message": {"message_id": 7, "from": user, "chat": chat, "text": text}}


class TemplateTests(unittest.TestCase):
    def test_escapes_values_and_text(self):
        ctx = {"nome": "<b>Zé & cia</b>"}
        self.assertEqual(
            template.render("Oi, {nome}! 1 < 2", ctx),
            "Oi, &lt;b&gt;Zé &amp; cia&lt;/b&gt;! 1 &lt; 2",
        )

    def test_markdown(self):
        r = lambda t, c=None: template.render(t, c or {})  # noqa: E731
        self.assertEqual(r("*negrito* e _itálico_"), "<b>negrito</b> e <i>itálico</i>")
        self.assertEqual(r("**b** __u__ ~s~ ||sp||"), "<b>b</b> <u>u</u> <s>s</s> <tg-spoiler>sp</tg-spoiler>")
        self.assertEqual(r("`*nada*`"), "<code>*nada*</code>")
        self.assertEqual(r("2 * 3 * 4 e meu_nome_aqui"), "2 * 3 * 4 e meu_nome_aqui")
        self.assertEqual(r("[site](exemplo.com/a_b)"), '<a href="https://exemplo.com/a_b">site</a>')
        self.assertEqual(r("[canal](@meucanal)"), '<a href="https://t.me/meucanal">canal</a>')
        self.assertEqual(r("> citação\n> 2\nfim"), "<blockquote>citação\n2</blockquote>\nfim")
        # valor de variável nunca vira formatação
        self.assertEqual(r("Oi *{nome}*", {"nome": "_x_ <y>"}), "Oi <b>_x_ &lt;y&gt;</b>")

    def test_plain_fallback(self):
        self.assertEqual(template.html_to_plain("<b>a &amp; b</b>"), "a & b")

    def test_unknown_variable_kept(self):
        self.assertEqual(template.render("{xyz} {nome}", {"nome": "A"}), "{xyz} A")
        self.assertEqual(template.unknown_variables("{xyz} {nome} {xyz}"), ["xyz"])

    def test_context(self):
        m = msg("/x", username="ana", chat_type="supergroup", title="Grupo")["message"]
        ctx = template.build_context(m, "olá", "MeuBot", "01/02/2026", "10:00")
        self.assertEqual(ctx["usuario"], "@ana")
        self.assertEqual(ctx["grupo"], "Grupo")
        self.assertEqual(ctx["bot"], "@MeuBot")
        self.assertEqual(ctx["sobrenome"], "")


class ParseTests(unittest.TestCase):
    def test_parse(self):
        self.assertEqual(engine.parse_command("/Diga olá mundo", "bot"), ("diga", "olá mundo"))
        self.assertEqual(engine.parse_command("/diga@MeuBot oi", "meubot"), ("diga", "oi"))
        self.assertIsNone(engine.parse_command("/diga@outro oi", "meubot"))
        self.assertIsNone(engine.parse_command("oi", "meubot"))
        self.assertEqual(engine.parse_command("/start", "b"), ("start", ""))


class ErrorTests(unittest.TestCase):
    def test_classify(self):
        c = lambda e: errors.classify(e)["code"]  # noqa: E731
        self.assertEqual(c(TelegramError("getMe", 401, "Unauthorized")), errors.INVALID_TOKEN)
        self.assertEqual(c(TelegramError("getMe", 404, "Not Found")), errors.INVALID_TOKEN)
        self.assertEqual(c(TelegramError("getUpdates", 409, "Conflict: terminated by other getUpdates request")), errors.CONFLICT)
        self.assertEqual(c(TelegramError("getUpdates", 409, "Conflict: can't use getUpdates method while webhook is active")), errors.WEBHOOK_ACTIVE)
        self.assertEqual(c(TelegramError("sendMessage", 400, "Bad Request: chat not found")), errors.CHAT_NOT_FOUND)
        self.assertEqual(c(TelegramError("sendMessage", 403, "Forbidden: bot can't initiate conversation with a user")), errors.CANT_INITIATE)
        self.assertEqual(c(TelegramError("sendMessage", 400, "Bad Request: not enough rights to send text messages to the chat")), errors.NOT_ENOUGH_RIGHTS)
        self.assertEqual(c(TelegramError("sendMessage", 429, "Too Many Requests: retry after 5", 5)), errors.TOO_MANY_REQUESTS)
        self.assertEqual(c(NetworkError("getUpdates", "timed out")), errors.NETWORK)

    def test_token_format(self):
        out = json.loads(engine.check_token("abc", "/tmp"))
        self.assertFalse(out["ok"])
        self.assertEqual(out["error"]["code"], errors.TOKEN_FORMAT)


class MultipartTests(unittest.TestCase):
    def test_multipart_body(self):
        from telebot_engine.api import _multipart
        tmp = tempfile.mkdtemp()
        path = os.path.join(tmp, "foto loja.jpg")
        with open(path, "wb") as fh:
            fh.write(b"\xff\xd8DATA")
        body, ctype = _multipart({"chat_id": 5, "caption": "Olá", "reply_markup": {"a": 1}}, {"photo": path})
        boundary = ctype.split("boundary=")[1].encode()
        self.assertTrue(body.startswith(b"--" + boundary))
        self.assertIn(b'name="chat_id"\r\n\r\n5\r\n', body)
        self.assertIn("Olá".encode(), body)
        self.assertIn(b'{"a": 1}', body)
        self.assertIn(b'filename="foto_loja.jpg"', body)
        self.assertIn(b"Content-Type: image/jpeg", body)
        self.assertIn(b"\xff\xd8DATA\r\n", body)
        self.assertTrue(body.endswith(b"--" + boundary + b"--\r\n"))


class EngineTests(unittest.TestCase):
    def make(self, script, commands, version=1, **extra):
        tmp = tempfile.mkdtemp()
        path = os.path.join(tmp, "config.json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(dict({"version": version, "commands": commands}, **extra), fh)
        cb = Callbacks()
        api = FakeApi(script)
        eng = engine.Engine("1:x", path, tmp, cb, api=api)
        api.engine = eng
        eng.sleep = lambda s: None  # sem esperas nos testes
        return eng, api, cb

    def test_replies_with_variables(self):
        eng, api, cb = self.make(
            [[msg("/horario", first="Zé <3")], [msg("/nada", update_id=2)]],
            [{"command": "horario", "description": "Horário", "response": "Olá, {nome}! Hoje é {data}."}],
        )
        eng.run()
        self.assertEqual(len(api.sent), 1)
        self.assertEqual(api.sent[0]["text"], "Olá, Zé &lt;3! Hoje é 14/11/2026.")
        self.assertEqual(api.sent[0]["parse_mode"], "HTML")
        self.assertEqual(cb.of("unknown_command")[0]["command"], "nada")
        menu = [p for m, p, f in api.calls if m == "setMyCommands"][0]
        self.assertEqual(menu["commands"], [{"command": "horario", "description": "Horário"}])
        self.assertEqual(cb.of("status")[-1]["state"], "stopped")
        # o offset confirma as mensagens já lidas
        self.assertEqual([p for m, p, f in api.calls if m == "getUpdates"][-1]["offset"], 3)

    def test_group_reply_and_mention(self):
        eng, api, cb = self.make(
            [[msg("/oi@meubot", chat_type="group", title="G"), msg("/oi@outrobot", update_id=2, chat_type="group")]],
            [{"command": "oi", "description": "", "response": "Oi {grupo}"}],
        )
        eng.run()
        self.assertEqual(len(api.sent), 1)
        self.assertEqual(api.sent[0]["text"], "Oi G")
        self.assertEqual(api.sent[0]["reply_parameters"]["message_id"], 7)

    def test_network_error_is_reported_once_then_recovers(self):
        net = NetworkError("getUpdates", "no route")
        eng, api, cb = self.make([net, net, net, [msg("/oi")]], [{"command": "oi", "response": "oi"}])
        eng.run()
        self.assertEqual(len(cb.of("error")), 1)
        self.assertEqual(len(cb.of("recovered")), 1)
        self.assertEqual(len(api.sent), 1)

    def test_invalid_token_is_fatal(self):
        eng, api, cb = self.make([TelegramError("getUpdates", 401, "Unauthorized")], [])
        eng.run()
        self.assertEqual(cb.of("status")[-1]["state"], "fatal")

    def test_v2_triggers_messages_media_and_cache(self):
        cmds = [{
            "id": "a", "triggers": ["start", "iniciar"], "description": "Começar",
            "messages": [
                {"text": "Oi *{nome}*", "buttons": [[{"text": "Canal", "url": "@meucanal"}]]},
                {"text": "legenda", "media": {"type": "photo", "file": "media/f.jpg", "name": "f.jpg"}},
            ],
        }]
        eng, api, cb = self.make([[msg("/iniciar")], [msg("/start", update_id=2)]], cmds, version=2)
        os.makedirs(os.path.join(eng.base_dir, "media"))
        with open(os.path.join(eng.base_dir, "media", "f.jpg"), "wb") as fh:
            fh.write(b"jpeg")
        eng.run()
        texts = [m for m in api.sent if "text" in m]
        photos = [m for m in api.sent if "caption" in m]
        self.assertEqual(texts[0]["text"], "Oi <b>Ana</b>")
        self.assertEqual(texts[0]["reply_markup"]["inline_keyboard"][0][0]["url"], "https://t.me/meucanal")
        # 1º envio: upload do arquivo; 2º: file_id guardado em cache
        self.assertTrue(photos[0]["_files"]["photo"].endswith("f.jpg"))
        self.assertEqual(photos[1]["photo"], "BIG")
        self.assertIsNone(photos[1]["_files"])
        menu = [p for m, p, f in api.calls if m == "setMyCommands"][0]
        self.assertEqual(menu["commands"], [{"command": "start", "description": "Começar"}])

    def test_auto_menu_off_and_parse_fallback(self):
        cmds = [{"triggers": ["oi"], "messages": [{"text": "*a _b* c_"}]}]
        eng, api, cb = self.make([[msg("/oi")]], cmds, version=2, auto_menu=False)
        api.fail_next["sendMessage"] = [TelegramError("sendMessage", 400, "Bad Request: can't parse entities")]
        eng.run()
        self.assertIn("deleteMyCommands", [m for m, p, f in api.calls])
        self.assertEqual(len(api.sent), 1)
        self.assertNotIn("parse_mode", api.sent[0])
        self.assertNotIn("<", api.sent[0]["text"])

    def test_v1_config_is_converted(self):
        from telebot_engine import config
        c = config.normalize({"commands": [{"command": "/Horario", "response": "x"}]})
        self.assertEqual(c["commands"][0]["triggers"], ["horario"])
        self.assertEqual(c["commands"][0]["messages"][0]["text"], "x")
        self.assertTrue(c["auto_menu"])

    def test_stop_from_other_thread(self):
        eng, api, cb = self.make([], [])
        api.script = None  # getUpdates bloquearia; usamos stop() antes
        eng.stop()
        t = threading.Thread(target=eng.run)
        t.start()
        t.join(5)
        self.assertFalse(t.is_alive())


if __name__ == "__main__":
    unittest.main()
