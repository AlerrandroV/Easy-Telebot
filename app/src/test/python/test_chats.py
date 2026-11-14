"""Testes do mensageiro (conversas em SQLite)."""

import json
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "main", "python"))
sys.path.insert(0, HERE)

from telebot_engine import admins, chats, engine  # noqa: E402
from test_engine import Callbacks, FakeApi, msg  # noqa: E402


def photo_msg(mid=5):
    return {"message_id": mid, "date": 10, "chat": {"id": 42, "type": "private", "first_name": "Ana", "username": "ana"},
            "from": {"id": 42, "first_name": "Ana"}, "caption": "olha *isso*",
            "caption_entities": [{"type": "bold", "offset": 5, "length": 6}],
            "photo": [{"file_id": "p1", "file_unique_id": "u1", "width": 90},
                      {"file_id": "p2", "file_unique_id": "u2", "width": 320},
                      {"file_id": "p3", "file_unique_id": "u3", "width": 1280}]}


class ChatStoreTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def tearDown(self):
        chats.close(self.dir)

    def test_parse_kinds(self):
        p = chats.parse_message(photo_msg())
        self.assertEqual((p["kind"], p["file_id"], p["thumb_id"], p["text"]), ("photo", "p3", "p2", "olha *isso*"))
        self.assertEqual(p["entities"][0]["type"], "bold")
        anim = chats.parse_message({"message_id": 1, "animation": {"file_id": "a", "thumbnail": {"file_id": "t"}},
                                    "document": {"file_id": "a"}})
        self.assertEqual((anim["kind"], anim["thumb_id"]), ("animation", "t"))
        st = chats.parse_message({"message_id": 1, "sticker": {"file_id": "s", "emoji": "🔥", "is_animated": True}})
        self.assertEqual((st["kind"], st["emoji"], st["sticker_format"]), ("sticker", "🔥", "tgs"))
        loc = chats.parse_message({"message_id": 1, "location": {"latitude": 1.5, "longitude": -2}})
        self.assertEqual((loc["kind"], loc["text"]), ("location", "1.5,-2"))

    def test_record_list_unread_and_edit(self):
        chats.record(self.dir, photo_msg(5))
        m = msg("oi")["message"]
        m.update(message_id=6, date=11, reply_to_message={"message_id": 5})
        chats.record(self.dir, m)
        listed = json.loads(chats.list_chats(self.dir))
        self.assertEqual((listed[0]["chat_id"], listed[0]["unread"], listed[0]["last_text"]), (42, 2, "oi"))
        self.assertEqual(chats.unread_total(self.dir), 2)
        items = json.loads(chats.get_messages(self.dir, 42))
        self.assertEqual([i["message_id"] for i in items], [5, 6])
        self.assertEqual(items[1]["reply"]["kind"], "photo")
        # edição atualiza o texto sem duplicar
        m2 = dict(m, text="oi editado", edit_date=12)
        chats.record(self.dir, m2, edited=True)
        items = json.loads(chats.get_messages(self.dir, 42))
        self.assertEqual((len(items), items[1]["text"], items[1]["edited"]), (2, "oi editado", 1))
        chats.mark_read(self.dir, 42)
        self.assertEqual(chats.unread_total(self.dir), 0)
        # paginação
        older = json.loads(chats.get_messages(self.dir, 42, before=6, limit=10))
        self.assertEqual([i["message_id"] for i in older], [5])
        chats.clear_chat(self.dir, 42)
        self.assertEqual(json.loads(chats.list_chats(self.dir)), [])


class EngineChatTests(unittest.TestCase):
    def make(self, script, commands):
        d = tempfile.mkdtemp()
        path = os.path.join(d, "config.json")
        with open(path, "w") as fh:
            json.dump({"version": 2, "commands": commands}, fh)
        cb = Callbacks()
        api = FakeApi(script)
        eng = engine.Engine("1:x", path, d, cb, api=api)
        api.engine = eng
        eng.sleep = lambda s: None
        return eng, api, cb, d

    def test_engine_records_both_sides_and_hides_newadmin_code(self):
        eng, api, cb, d = self.make([], [{"triggers": ["oi"], "messages": [{"text": "Olá *{nome}*"}]}])
        code = json.loads(admins.create_code(d, 1))["code"]
        def with_id(update, mid):
            update["message"]["message_id"] = mid
            return update
        api.script = [[with_id(msg("/oi"), 1)], [with_id(msg("/newadmin " + code, update_id=2), 2)],
                      [{"update_id": 3, "edited_message": dict(msg("/oi")["message"], message_id=1,
                                                               text="/oi!", edit_date=5)}]]
        eng.run()
        items = json.loads(chats.get_messages(d, 42))
        texts = [(i["outgoing"], i["source"], i["text"]) for i in items]
        self.assertIn((0, "user", "/oi!"), texts)  # edição registrada
        self.assertIn((1, "auto", "Olá <b>Ana</b>"), texts)  # o FakeApi devolve o texto enviado
        self.assertIn((0, "user", "/newadmin ••••"), texts)
        self.assertIn((1, "auto", "Parabéns! Agora você é um administrador!"), texts)
        with open(os.path.join(d, chats.DB_FILE), "rb") as fh:
            self.assertNotIn(code.encode(), fh.read())
        newadmin = next(i for i in items if i["text"] == "/newadmin ••••")
        self.assertEqual(newadmin["deleted"], 1)
        self.assertTrue(any(e["type"] == "chat" for e in cb.events))
        chats.close(d)

    def test_sticker_by_file_id_and_text_separate(self):
        eng, api, cb, d = self.make([], [])
        ok = eng.send_message(42, {"text": "legenda", "media": {"type": "sticker", "file_id": "STK", "file": ""},
                                   "buttons": [], "link_preview": False}, {}, "manual")
        self.assertTrue(ok)
        self.assertEqual(api.sent[0]["sticker"], "STK")
        self.assertNotIn("caption", api.sent[0])
        self.assertEqual(api.sent[1]["text"], "legenda")
        chats.close(d)


class ManualSendTests(unittest.TestCase):
    def test_send_as_bot_with_reply_buttons_and_variables(self):
        d = tempfile.mkdtemp()
        chats.record(d, photo_msg(5))
        fake = FakeApi([])
        original = engine.TelegramApi
        engine.TelegramApi = lambda token: fake
        try:
            out = json.loads(chats.send("1:x", d, 42, json.dumps({
                "text": "Oi *{nome}*", "reply_to": 5,
                "buttons": [[{"text": "Canal", "url": "@meucanal"}]],
            })))
        finally:
            engine.TelegramApi = original
        self.assertTrue(out["ok"])
        sent = fake.sent[0]
        self.assertEqual(sent["text"], "Oi <b>Ana</b>")
        self.assertEqual(sent["reply_parameters"]["message_id"], 5)
        self.assertEqual(sent["reply_markup"]["inline_keyboard"][0][0]["url"], "https://t.me/meucanal")
        items = json.loads(chats.get_messages(d, 42))
        self.assertEqual((items[-1]["outgoing"], items[-1]["source"]), (1, "manual"))
        self.assertEqual(json.loads(chats.list_chats(d))[0]["unread"], 0)  # enviar zera não lidas
        chats.close(d)

    def test_send_error_is_explained(self):
        d = tempfile.mkdtemp()
        fake = FakeApi([])
        from telebot_engine.api import TelegramError
        fake.fail_next["sendMessage"] = [TelegramError("sendMessage", 403, "Forbidden: bot was blocked by the user")]
        original = engine.TelegramApi
        engine.TelegramApi = lambda token: fake
        try:
            out = json.loads(chats.send("1:x", d, 42, json.dumps({"text": "oi"})))
        finally:
            engine.TelegramApi = original
        self.assertEqual(out["error"]["code"], "blocked_by_user")
        chats.close(d)


if __name__ == "__main__":
    unittest.main()
