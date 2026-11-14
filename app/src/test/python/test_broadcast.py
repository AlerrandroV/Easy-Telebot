"""Testes da publicação em massa (pelo Telegram e pelo app)."""

import json
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "main", "python"))
sys.path.insert(0, HERE)

from telebot_engine import admins, broadcast, chats, engine, publish  # noqa: E402
from telebot_engine.api import TelegramError  # noqa: E402
from test_engine import Callbacks, FakeApi  # noqa: E402


def u16(s):
    return len(s.encode("utf-16-le")) // 2


class ButtonSyntaxTests(unittest.TestCase):
    def test_strip_buttons_and_fix_entities(self):
        text = "🔥 *Promo* hoje\n[Site | exemplo.com] [Canal | @meucanal]\n[Grupo | t.me/grupo]"
        # "Promo" em negrito: offset em UTF-16 (o emoji ocupa 2 unidades)
        bold = {"type": "bold", "offset": u16("🔥 *"), "length": 5}
        # entidade que cobre tudo (inclusive as linhas de botão) deve ser cortada
        whole = {"type": "italic", "offset": 0, "length": u16(text)}
        new, ents, rows, bad = broadcast.strip_buttons(text, [bold, whole])
        self.assertEqual(new, "🔥 *Promo* hoje")
        self.assertEqual(rows, [[{"text": "Site", "url": "https://exemplo.com"},
                                 {"text": "Canal", "url": "https://t.me/meucanal"}],
                                [{"text": "Grupo", "url": "https://t.me/grupo"}]])
        self.assertEqual(ents[0], bold)
        self.assertEqual(ents[1]["length"], u16(new))
        self.assertEqual(bad, [])

    def test_button_line_in_middle_and_invalid(self):
        text = "a\n[X | https://x.com]\nb\n[Ruim | nada disso]"
        ent = {"type": "bold", "offset": u16("a\n[X | https://x.com]\n"), "length": 1}  # o "b"
        new, ents, rows, bad = broadcast.strip_buttons(text, [ent])
        self.assertEqual(new, "a\nb\n[Ruim | nada disso]")
        self.assertEqual(ents[0]["offset"], 2)
        self.assertEqual(len(rows), 1)
        self.assertEqual(bad, ["[Ruim | nada disso]"])

    def test_markdown_links_are_not_buttons(self):
        new, _, rows, _ = broadcast.strip_buttons("[texto](https://x.com)\n[a] [b]")
        self.assertEqual(rows, [])

    def test_plan(self):
        items = [
            {"message_id": 1, "is_text": True, "text": "Olá", "entities": []},
            {"message_id": 2, "is_text": False, "text": "", "entities": []},                       # foto sem legenda
            {"message_id": 3, "is_text": True, "text": "[Ver | x.com]", "entities": []},          # botões p/ a foto
            {"message_id": 4, "is_text": False, "text": "legenda\n[A | a.com]", "entities": []},  # legenda + botão
            {"message_id": 6, "media_group_id": "g", "is_text": False, "text": "", "entities": []},
            {"message_id": 5, "media_group_id": "g", "is_text": False, "text": "", "entities": []},
            {"message_id": 7, "is_text": True, "text": "fim\n[B | b.com]", "entities": []},
        ]
        plan, warnings = broadcast.build_plan(items)
        self.assertEqual([p["type"] for p in plan], ["copy", "copy", "copy", "album", "text"])
        self.assertEqual(plan[1]["buttons"][0][0]["url"], "https://x.com")
        self.assertEqual(plan[2]["caption"], "legenda")
        self.assertEqual(plan[3]["ids"], [5, 6])
        self.assertEqual(plan[4]["text"], "fim")
        self.assertEqual(warnings, [])

    def test_parse_targets(self):
        self.assertEqual(broadcast.parse_targets([]), {"users", "groups", "channels"})
        self.assertEqual(broadcast.parse_targets(["usuários", "canais"]), {"users", "channels"})


def private(uid, mid, text=None, **extra):
    m = {"message_id": mid, "date": 1, "chat": {"id": uid, "type": "private", "first_name": "U%d" % uid},
         "from": {"id": uid, "first_name": "U%d" % uid}}
    if text is not None:
        m["text"] = text
    m.update(extra)
    return m


class TelegramFlowTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self._orig = publish.TelegramApi

    def tearDown(self):
        publish.TelegramApi = self._orig
        chats.close(self.dir)

    def test_full_post_flow(self):
        d = self.dir
        # audiência: 2 usuários, 1 grupo, 1 canal (adicionado via my_chat_member), 1 usuário que bloqueou
        for uid in (101, 102):
            chats.record(d, private(uid, 1, "oi"))
        chats.record(d, {"message_id": 1, "date": 1, "chat": {"id": -5, "type": "group", "title": "G"},
                         "from": {"id": 101}, "text": "oi"})
        admin_id = 42
        admins.redeem_code(d, json.loads(admins.create_code(d, 1))["code"], {"id": admin_id, "first_name": "Adm"})
        path = os.path.join(d, "config.json")
        with open(path, "w") as fh:
            json.dump({"version": 2, "commands": []}, fh)
        cb = Callbacks()
        api = FakeApi([])
        side = FakeApi([])
        publish.TelegramApi = lambda token: side
        eng = engine.Engine("1:x", path, d, cb, api=api)
        api.engine = eng
        eng.sleep = lambda s: None
        photo = {"photo": [{"file_id": "p", "file_unique_id": "u"}]}
        api.script = [
            [{"update_id": 1, "my_chat_member": {"chat": {"id": -100, "type": "channel", "title": "Canal"},
                                                 "new_chat_member": {"status": "administrator"}}},
             {"update_id": 2, "my_chat_member": {"chat": {"id": 103, "type": "private", "first_name": "X"},
                                                 "new_chat_member": {"status": "kicked"}}}],
            [{"update_id": 3, "message": private(999, 10, "/post")}],         # não é admin: silêncio
            [{"update_id": 4, "message": private(admin_id, 11, "/post")}],
            [{"update_id": 5, "message": private(admin_id, 12, "Novidade!\n[Site | exemplo.com]")}],
            [{"update_id": 6, "message": private(admin_id, 13, **photo)}],
            [{"update_id": 7, "message": private(admin_id, 14, "/confirmar XXXXXX")}],  # sem prévia
            [{"update_id": 8, "message": private(admin_id, 15, "/pronto")}],
        ]
        eng.run()
        flow_session = eng.post.sessions[admin_id]
        code = flow_session.code
        self.assertTrue(code and len(code) == 6)
        replies = [m.get("text", "") for m in api.sent if m.get("chat_id") == admin_id and "text" in m]
        self.assertTrue(any("Modo publicação" in t for t in replies))
        self.assertTrue(any("Mande /pronto primeiro" in t for t in replies))
        summary = next(t for t in replies if "Destinos" in t)
        # 101, 102 e 999 (falou com o bot); o 103 bloqueou e o próprio admin fica de fora
        self.assertIn("3 usuário(s) · 👥 1 grupo(s) · 📢 1 canal(is)", summary)
        # a prévia foi enviada ao próprio admin: texto com botão + cópia da foto
        preview = [m for m in api.sent if m.get("chat_id") == admin_id and
                   (m.get("_method") == "copyMessage" or m.get("reply_markup"))]
        self.assertEqual(preview[0]["text"], "Novidade!")
        self.assertEqual(preview[0]["reply_markup"]["inline_keyboard"][0][0]["url"], "https://exemplo.com")
        self.assertFalse(any(m.get("chat_id") == 999 for m in api.sent))

        # confirmação: código errado e depois o certo (só usuários)
        api.script = [
            [{"update_id": 9, "message": private(admin_id, 16, "/confirmar ERRADO")}],
            [{"update_id": 10, "message": private(admin_id, 17, "/confirmar %s usuarios" % code.lower())}],
            lambda: broadcast.running(d) and broadcast.running(d).join(5),  # bot ligado até terminar
        ]
        side.fail_next["sendMessage"] = [TelegramError("sendMessage", 403, "Forbidden: bot was blocked by the user")]
        eng._stop.clear()
        eng.run()
        broadcast.running(d) and broadcast.running(d).join(5)
        replies = [m.get("text", "") for m in api.sent if m.get("chat_id") == admin_id and "text" in m]
        self.assertTrue(any("Código incorreto" in t for t in replies))
        delivered = [m for m in side.sent if m.get("chat_id") in (101, 102, 999)]
        self.assertTrue(all(m.get("from_chat_id", admin_id) == admin_id for m in delivered))
        state = json.loads(broadcast.job_status(d))
        self.assertEqual((state["status"], state["total"], state["sent"], state["failed"]), ("done", 3, 2, 1))
        self.assertEqual(state["unreachable"], 1)
        # quem bloqueou durante o envio sai da audiência: sobram 2 dos 3 + o admin
        self.assertEqual(broadcast.audience_counts(d)["users"], 3)
        self.assertNotIn(-5, [m.get("chat_id") for m in side.sent])  # grupos não foram escolhidos
        self.assertTrue(any(e["type"] == "broadcast_done" for e in cb.events))


    def test_stopping_bot_interrupts_telegram_broadcast(self):
        d = self.dir
        chats.record(d, private(101, 1, "oi"))
        import threading
        gate = threading.Event()

        def slow(chat):
            gate.wait(2)
            return 1
        job = broadcast.start(d, broadcast.audience(d), slow, "telegram")
        path = os.path.join(d, "config.json")
        with open(path, "w") as fh:
            json.dump({"version": 2, "commands": []}, fh)
        eng = engine.Engine("1:x", path, d, Callbacks(), api=FakeApi([]))
        eng.stop()
        gate.set()
        job.join(3)
        self.assertEqual(json.loads(broadcast.job_status(d))["status"], "done")  # 1 só chat: já estava enviando
        self.assertTrue(job.cancelled.is_set())


class AppBroadcastTests(unittest.TestCase):
    def test_start_app_with_variables(self):
        d = tempfile.mkdtemp()
        chats.record(d, private(101, 1, "oi"))
        chats.record(d, private(102, 1, "oi"))
        fake = FakeApi([])
        original = engine.TelegramApi
        engine.TelegramApi = lambda token: fake
        try:
            out = json.loads(broadcast.start_app("1:x", d, json.dumps({
                "messages": [{"text": "Oi *{nome}*", "buttons": [[{"text": "Site", "url": "x.com"}]]}],
                "targets": ["users"],
            })))
            self.assertTrue(out["ok"])
            self.assertEqual(out["total"], 2)
            broadcast.running(d) and broadcast.running(d).join(5)
        finally:
            engine.TelegramApi = original
        texts = sorted(m["text"] for m in fake.sent)
        self.assertEqual(texts, ["Oi <b>U101</b>", "Oi <b>U102</b>"])
        self.assertEqual(json.loads(broadcast.job_status(d))["sent"], 2)
        # envios em massa não lotam o mensageiro
        self.assertEqual(len(json.loads(chats.get_messages(d, 101))), 1)
        empty = json.loads(broadcast.start_app("1:x", d, json.dumps({"messages": []})))
        self.assertEqual(empty["error"]["code"], "broadcast_empty")
        chats.close(d)


if __name__ == "__main__":
    unittest.main()
