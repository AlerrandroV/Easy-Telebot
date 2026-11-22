"""Termux: comandos de script (saída em texto/JSON, botões, erros) e a API local do bot."""

import json
import os
import socket
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "main", "python"))
sys.path.insert(0, HERE)

from telebot_engine import chats, engine, termux  # noqa: E402
from test_engine import Callbacks, FakeApi, msg  # noqa: E402


class TermuxCallbacks(Callbacks):
    def __init__(self, outputs):
        super().__init__()
        self.outputs = list(outputs)
        self.runs = []

    def runTermux(self, command, env, timeout):  # noqa: N802
        self.runs.append((command, json.loads(env), timeout))
        out = self.outputs.pop(0)
        return json.dumps(out if isinstance(out, dict) and "ok" in out else {"ok": True, "stdout": out,
                                                                                "stderr": "", "exit_code": 0})


class ScriptApi(FakeApi):
    def call(self, method, params=None, timeout=30, files=None):
        if method in ("editMessageText", "answerCallbackQuery"):
            self.calls.append((method, params, files))
            if method == "editMessageText":
                self.sent.append(dict(params, _method="edit"))
            return True
        return super().call(method, params, timeout, files)


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class ScriptTests(unittest.TestCase):
    dir = None

    def make(self, script, outputs, termux_cfg=None, commands=None):
        self.dir = tempfile.mkdtemp()
        path = os.path.join(self.dir, "config.json")
        cmds = commands or [{"triggers": ["clima"], "messages": [],
                             "script": {"command": "python ~/bot/clima.py", "timeout": 20}}]
        with open(path, "w", encoding="utf-8") as fh:
            json.dump({"version": 2, "commands": cmds, "termux": termux_cfg or {}}, fh)
        cb = TermuxCallbacks(outputs)
        api = ScriptApi(script)
        eng = engine.Engine("1:x", path, self.dir, cb, api=api)
        eng.termux.sync_scripts = True
        eng.termux._sender = lambda: eng  # testes: mesma API falsa
        api.engine = eng
        eng.sleep = lambda s: None
        return eng, api, cb

    def tearDown(self):
        if self.dir:
            chats.close(self.dir)

    def test_text_output_and_env(self):
        eng, api, cb = self.make([[msg("/clima São Paulo", first="Ana")]], ["Hoje: *28 °C* em {nome} <sol>"])
        eng.run()
        command, env, timeout = cb.runs[0]
        self.assertEqual((command, timeout), ("python ~/bot/clima.py", 20))
        self.assertEqual(env["TELEBOT_ARGS"], "São Paulo")
        self.assertEqual(env["TELEBOT_CHAT_ID"], "42")
        self.assertEqual(json.loads(env["TELEBOT_UPDATE"])["message"]["text"], "/clima São Paulo")
        self.assertEqual(env["TELEBOT_API"], "")  # API local desligada
        self.assertEqual(api.sent[0]["text"], "Hoje: <b>28 °C</b> em Ana &lt;sol&gt;")
        self.assertEqual(cb.of("script_done")[0]["messages"], 1)

    def test_json_media_buttons_and_callback_edit(self):
        out1 = json.dumps([{"text": "Previsão", "photo": "https://exemplo.com/sol.jpg",
                            "buttons": [[{"text": "Site", "url": "exemplo.com"}, {"text": "Amanhã", "data": "d+1"}]]},
                           {"text": "<i>fim</i>", "html": True}])
        out2 = json.dumps({"text": "Amanhã: chuva", "edit": True})
        press = {"update_id": 2, "callback_query": {
            "id": "q1", "from": {"id": 42, "first_name": "Ana"}, "data": "sc:clima:d+1",
            "message": {"message_id": 77, "chat": {"id": 42, "type": "private"}}}}
        eng, api, cb = self.make([[msg("/clima")], [press]], [out1, out2])
        eng.run()
        photo, html = api.sent[0], api.sent[1]
        self.assertEqual(photo["photo"], "https://exemplo.com/sol.jpg")
        self.assertEqual(photo["caption"], "Previsão")
        row = photo["reply_markup"]["inline_keyboard"][0]
        self.assertEqual(row[0]["url"], "https://exemplo.com")
        self.assertEqual(row[1]["callback_data"], "sc:clima:d+1")
        self.assertEqual(html["text"], "<i>fim</i>")
        self.assertEqual(cb.runs[1][1]["TELEBOT_DATA"], "d+1")
        edit = api.sent[2]
        self.assertEqual((edit["_method"], edit["message_id"], edit["text"]), ("edit", 77, "Amanhã: chuva"))

    def test_errors_are_explained_and_nothing_is_sent(self):
        eng, api, cb = self.make([[msg("/clima")], [msg("/clima", update_id=2)], [msg("/clima", update_id=3)]], [
            {"ok": True, "stdout": "", "stderr": "Traceback: KeyError 'x'", "exit_code": 1},
            {"ok": True, "stdout": "", "stderr": "", "exit_code": 124},
            {"ok": False, "error": {"code": "termux_permission", "detail": ""}},
        ])
        eng.run()
        self.assertEqual(api.sent, [])
        codes = [(e["code"], e["context"]) for e in cb.of("error")]
        self.assertEqual(codes, [("termux_exit", "script"), ("termux_timeout", "script"), ("termux_permission", "script")])
        self.assertIn("KeyError", cb.of("error")[0]["detail"])

    def test_admin_only_script(self):
        eng, api, cb = self.make([[msg("/painel")]], [], commands=[
            {"triggers": ["painel"], "admin_level": 1, "messages": [], "script": {"command": "x"}}])
        eng.run()
        self.assertEqual(cb.runs, [])
        self.assertEqual(cb.of("denied")[0]["command"], "painel")

    def test_parse_output(self):
        self.assertEqual(termux.parse_output("  oi \n"), [{"text": "oi"}])
        self.assertEqual(termux.parse_output("{quebrado"), [{"text": "{quebrado"}])
        self.assertEqual(termux.parse_output(""), [])
        long = termux.callback_data("clima", "x" * 100)
        self.assertLessEqual(len(long.encode("utf-8")), 64)


class LocalApiTests(unittest.TestCase):
    def setUp(self):
        self.port = free_port()
        self.key = termux.new_key(123)
        self.dir = tempfile.mkdtemp()

    def tearDown(self):
        chats.close(self.dir)

    def url(self, method, key=None):
        return "http://127.0.0.1:%d/bot%s/%s" % (self.port, key or self.key, method)

    def post(self, method, data=None, key=None, timeout=5):
        req = urllib.request.Request(self.url(method, key), data=json.dumps(data or {}).encode("utf-8"),
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.status, json.loads(resp.read())
        except urllib.error.HTTPError as exc:
            return exc.code, json.loads(exc.read())

    def test_forwarding_unhandled_updates_and_proxy(self):
        path = os.path.join(self.dir, "config.json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump({"version": 2, "commands": [{"triggers": ["oi"], "messages": [{"text": "olá"}]}],
                       "termux": {"api_enabled": True, "port": self.port, "key": self.key}}, fh)
        got = {}

        def consume():
            # um "bot" no Termux: espera o update, responde pela API local
            deadline, offset, updates = time.time() + 5, None, []
            while time.time() < deadline and len(updates) < 2:
                status, data = self.post("getUpdates", {"timeout": 1, "offset": offset})
                for u in data.get("result") or []:
                    updates.append(u)
                    offset = u["update_id"] + 1
            got["updates"] = updates
            got["after"] = self.post("getUpdates", {"offset": offset, "timeout": 0})[1]["result"]
            got["unauth"] = self.post("getMe", key="999:" + "x" * 30)
            got["webhook"] = self.post("setWebhook", {"url": "https://x"})
            got["delete"] = self.post("deleteWebhook", {"drop_pending_updates": True})
            got["send"] = self.post("sendMessage", {"chat_id": 42, "text": "do script"})

        calls = []

        def fake_upstream(verb, upath, body, ctype):
            calls.append((verb, upath, json.loads(body)))
            return 200, json.dumps({"ok": True, "result": {"message_id": 900, "date": 1700000000, "text": "do script",
                                                           "chat": {"id": 42, "type": "private"}}}).encode(), "application/json"

        def wait_consumer():
            t.join(10)

        eng = None
        api = FakeApi([[msg("/oi"), msg("texto livre", update_id=2), msg("/desconhecido", update_id=3)],
                       wait_consumer])
        cb = Callbacks()
        eng = engine.Engine("1:TOKENREAL", path, self.dir, cb, api=api)
        api.engine = eng
        eng.sleep = lambda s: None
        real_sync = eng.termux.sync

        def sync_and_patch():
            real_sync()
            if eng.termux.api is not None:
                eng.termux.api.upstream_call = fake_upstream
        eng.termux.sync = sync_and_patch
        t = threading.Thread(target=consume)
        t.start()
        eng.run()
        t.join(5)

        texts = [u["message"]["text"] for u in got["updates"]]
        self.assertEqual(texts, ["texto livre", "/desconhecido"])  # /oi o app já respondeu
        self.assertEqual(got["after"], [])
        self.assertEqual(got["unauth"][0], 401)
        self.assertEqual(got["webhook"][0], 400)
        self.assertTrue(got["delete"][1]["result"])
        self.assertEqual(got["send"][1]["result"]["message_id"], 900)
        verb, upath, body = calls[0]
        self.assertEqual((verb, upath, body["text"]), ("POST", "/bot1:TOKENREAL/sendMessage", "do script"))
        history = json.loads(chats.get_messages(self.dir, 42, limit=50))
        self.assertIn(("do script", "script"), [(m["text"], m["source"]) for m in history if m["outgoing"]])
        self.assertEqual(cb.of("local_api")[0]["state"], "listening")
        self.assertEqual(cb.of("unknown_command"), [])  # com a API ligada, comando desconhecido vai ao script
        self.assertIn("inline_query", [p for m, p, f in api.calls if m == "getUpdates"][0]["allowed_updates"])


if __name__ == "__main__":
    unittest.main()
