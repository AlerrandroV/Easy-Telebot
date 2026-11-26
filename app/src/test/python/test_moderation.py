"""Moderação de grupos: verificação na entrada, filtros, advertências, comandos e modo lento."""

import json
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "main", "python"))
sys.path.insert(0, HERE)

from telebot_engine import chats, engine, moderation  # noqa: E402
from test_engine import Callbacks, FakeApi  # noqa: E402

GROUP = {"id": -100, "type": "supergroup", "title": "Grupo"}
ADMIN = {"id": 1000, "first_name": "Chefe"}


class Api(FakeApi):
    def call(self, method, params=None, timeout=30, files=None):
        if method == "getChatAdministrators":
            self.calls.append((method, params, files))
            return [{"user": ADMIN, "status": "creator"}]
        if method == "getChat":
            return {"id": params["chat_id"], "permissions": {"can_send_messages": True}}
        if method in ("restrictChatMember", "deleteMessage", "banChatMember", "unbanChatMember"):
            self.calls.append((method, params, files))
            return True
        return super().call(method, params, timeout, files)

    def of(self, method):
        return [c[1] for c in self.calls if c[0] == method]


def gmsg(text, uid=5, mid=10, update_id=1, first="Lu", **extra):
    m = {"message_id": mid, "from": {"id": uid, "first_name": first}, "chat": GROUP, "text": text}
    m.update(extra)
    return {"update_id": update_id, "message": m}


class ModerationTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def tearDown(self):
        chats.close(self.dir)

    def make(self, settings, script):
        path = os.path.join(self.dir, "config.json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump({"version": 2, "commands": [], "moderation": dict({"enabled": True}, **settings)}, fh)
        api = Api(script)
        eng = engine.Engine("1:x", path, self.dir, Callbacks(), api=api)
        eng.scheduler.sync = True
        api.engine = eng
        eng.sleep = lambda s: None
        return eng, api

    def test_link_filter_warns_and_mutes_at_limit(self):
        s = {"filters": {"links": True, "allowed_domains": ["meusite.com"], "action": "warn"},
             "warnings": {"limit": 2, "action": "mute", "mute_hours": 1}}
        link = lambda i, url: gmsg("olha " + url, mid=10 + i, update_id=i,
                                   entities=[{"type": "url", "offset": 5, "length": len(url)}])
        eng, api = self.make(s, [[link(1, "https://spam.com/x")], [link(2, "https://www.meusite.com/a")],
                                 [link(3, "t.me/canal")], [gmsg("tudo certo", mid=40, update_id=4)]])
        eng.run()
        self.assertEqual([p["message_id"] for p in api.of("deleteMessage")], [11, 13])
        texts = [p["text"] for p in api.sent]
        self.assertIn("advertência 1 de 2", texts[0])
        self.assertIn("silenciado(a) depois de 2 advertências", texts[1])
        self.assertEqual(api.of("restrictChatMember")[0]["user_id"], 5)

    def test_admins_are_never_filtered(self):
        s = {"filters": {"words": ["bobo"], "action": "delete"}}
        eng, api = self.make(s, [[gmsg("seu bobo", uid=1000)], [gmsg("que bobo!", update_id=2, mid=11)]])
        eng.run()
        self.assertEqual([p["message_id"] for p in api.of("deleteMessage")], [11])

    def test_commands_by_reply(self):
        target = {"message_id": 3, "from": {"id": 5, "first_name": "Lu"}, "chat": GROUP, "text": "spam"}
        eng, api = self.make({}, [
            [gmsg("/mute 2h", uid=1000, mid=20, reply_to_message=target)],
            [gmsg("/ban", uid=6, mid=21, update_id=2, reply_to_message=target)],  # não é admin: ignora
            [gmsg("/banir", uid=1000, mid=22, update_id=3, reply_to_message=target)],
            [gmsg("/warn", uid=1000, mid=23, update_id=4)],  # sem resposta
        ])
        eng.run()
        mute = api.of("restrictChatMember")[0]
        self.assertEqual(mute["user_id"], 5)
        self.assertGreater(mute["until_date"], 0)
        self.assertEqual([p["user_id"] for p in api.of("banChatMember")], [5])
        self.assertEqual([p["message_id"] for p in api.of("deleteMessage")], [20, 21, 22, 23])
        texts = [p["text"] for p in api.sent]
        self.assertIn("silenciado(a) por 2 h", texts[0])
        self.assertIn("banido", texts[1])
        self.assertIn("Responda à mensagem", texts[2])

    def test_captcha_flow(self):
        join = gmsg("", mid=30)
        del join["message"]["text"]
        join["message"]["new_chat_members"] = [{"id": 7, "first_name": "Novo"}]
        press = lambda uid, data, upd: {"update_id": upd, "callback_query": {
            "id": "q", "from": {"id": uid, "first_name": "x"}, "data": data, "message": {"message_id": 1, "chat": GROUP}}}
        eng, api = self.make({"captcha": {"enabled": True, "mode": "button", "timeout": 2}, "delete_service": True},
                             [[join], [press(9, "mod:cap:-100:7:0", 2)], [press(7, "mod:cap:-100:7:0", 3)]])
        eng.run()
        restricts = api.of("restrictChatMember")
        self.assertEqual(restricts[0]["permissions"]["can_send_messages"], False)
        self.assertEqual(restricts[1]["permissions"], {"can_send_messages": True})  # liberado
        self.assertIn(30, [p["message_id"] for p in api.of("deleteMessage")])  # aviso de entrada apagado
        self.assertIn("não é um robô", api.sent[0]["text"])
        with open(os.path.join(self.dir, moderation.STATE_FILE), encoding="utf-8") as fh:
            self.assertEqual(json.load(fh).get("captcha"), {})

    def test_captcha_timeout_kicks(self):
        eng, api = self.make({"captcha": {"enabled": True, "timeout": 1, "fail": "kick"}}, [])
        eng.maybe_reload_config(force=True)
        eng.mod.start_captcha(GROUP, {"id": 8, "first_name": "Bot?"}, eng.mod.settings["captcha"])
        import time
        eng.mod.tick(time.time() + 120)
        self.assertEqual([p["user_id"] for p in api.of("banChatMember")], [8])
        self.assertEqual([p["user_id"] for p in api.of("unbanChatMember")], [8])

    def test_slowmode_and_rules(self):
        eng, api = self.make({"slowmode": 30, "rules": "1. Respeito"},
                             [[gmsg("a", mid=50)], [gmsg("b", mid=51, update_id=2)], [gmsg("/regras", mid=52, update_id=3, uid=6)]])
        eng.run()
        self.assertEqual([p["message_id"] for p in api.of("deleteMessage")], [51])
        self.assertEqual(api.sent[-1]["text"], "1. Respeito")

    def test_newcomer_cannot_send_media(self):
        join = gmsg("", mid=60)
        del join["message"]["text"]
        join["message"]["new_chat_members"] = [{"id": 5, "first_name": "Lu"}]
        photo = gmsg("", mid=61, update_id=2)
        del photo["message"]["text"]
        photo["message"]["photo"] = [{"file_id": "F", "width": 1, "height": 1}]
        eng, api = self.make({"filters": {"newcomer_hours": 24, "action": "delete"}}, [[join], [photo]])
        eng.run()
        self.assertEqual([p["message_id"] for p in api.of("deleteMessage")], [61])

    def test_parse_duration(self):
        self.assertEqual(moderation.parse_duration("10m", 0), 600)
        self.assertEqual(moderation.parse_duration("2h", 0), 7200)
        self.assertEqual(moderation.parse_duration("1d spam", 0), 86400)
        self.assertEqual(moderation.parse_duration("", 99), 99)


if __name__ == "__main__":
    unittest.main()
