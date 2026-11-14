"""Antispam: detecção, bloqueio automático, aviso aos admins e decisão pelos botões/app."""

import json
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "main", "python"))
sys.path.insert(0, HERE)

from telebot_engine import admins, antispam, chats, engine  # noqa: E402
from test_engine import Callbacks, FakeApi, msg  # noqa: E402


def m(text, uid=42, mid=1, **extra):
    out = {"message_id": mid, "from": {"id": uid, "first_name": "Troll"}, "chat": {"id": uid, "type": "private"},
           "text": text}
    out.update(extra)
    return out


class DetectorTests(unittest.TestCase):
    def test_flood(self):
        d = antispam.Detector()
        hits = [d.check(m("oi %d" % i), "media", now=100 + i * 0.5) for i in range(9)]
        self.assertTrue(all(h is None for h in hits[:8]))
        self.assertEqual(hits[8]["reason"], "flood")

    def test_slow_messages_are_fine(self):
        d = antispam.Detector()
        self.assertTrue(all(d.check(m("oi %d" % i), "alta", now=100 + i * 3) is None for i in range(50)))

    def test_repeat(self):
        d = antispam.Detector()
        hits = [d.check(m("  COMPRE  já "), "media", now=100 + i * 5) for i in range(4)]
        self.assertIsNone(hits[2])
        self.assertEqual(hits[3]["reason"], "repeat")
        self.assertEqual(hits[3]["count"], 4)

    def test_album_counts_once(self):
        d = antispam.Detector()
        album = [m("", photo=[{"file_id": "x"}], media_group_id="g1") for _ in range(10)]
        self.assertTrue(all(d.check(x, "alta", now=100) is None for x in album))
        self.assertEqual(d.recent(42)[-1], "[foto]")


class EngineSpamTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        # Admin classe I (recebe o aviso) e classe 5 (não pode julgar com admin_level 1).
        admins.redeem_code(self.dir, json.loads(admins.create_code(self.dir, 1))["code"],
                           {"id": 500, "first_name": "Chefe", "username": "chefe"})
        admins.redeem_code(self.dir, json.loads(admins.create_code(self.dir, 5))["code"],
                           {"id": 600, "first_name": "Ajudante"})

    def make(self, script, antispam_cfg=None):
        path = os.path.join(self.dir, "config.json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump({"version": 2, "commands": [{"triggers": ["oi"], "messages": [{"text": "olá"}]}],
                       "antispam": antispam_cfg or {}}, fh)
        cb = Callbacks()
        api = FakeApi(script)
        eng = engine.Engine("1:x", path, self.dir, cb, api=api)
        api.engine = eng
        eng.sleep = lambda s: None
        return eng, api, cb

    def flood(self, uid=42, n=10, start=1):
        out = []
        for i in range(n):
            u = msg("/oi", update_id=start + i, user_id=uid)
            u["message"]["message_id"] = start + i
            out.append(u)
        return out

    def one(self, n, uid=42):
        u = msg("/oi", update_id=n, user_id=uid)
        u["message"]["message_id"] = n
        return u

    def callback(self, data, who, update_id=100, qid="q1"):
        return {"update_id": update_id, "callback_query": {
            "id": qid, "from": {"id": who, "first_name": "X", "username": "chefe" if who == 500 else None},
            "data": data, "message": {"message_id": 1, "chat": {"id": who}}}}

    def test_flood_blocks_notifies_and_admin_releases(self):
        eng, api, cb = self.make([
            self.flood(),
            [self.one(50)],                                                    # bloqueado: silêncio
            [self.callback("as:release:42", 600, 60, "nope")],                  # classe 5: não pode
            [self.callback("as:keep:42", 500, 61)],
            [self.callback("as:release:42", 500, 62)],
            [self.one(70)],                                                    # liberado
            [self.callback("as:release:42", 500, 63, "q9")],                    # já resolvido
        ])
        eng.run()
        to_user = [p["text"] for p in api.sent if p["chat_id"] == 42]
        self.assertEqual(to_user.count("olá"), 9)  # 8 respostas antes do bloqueio + 1 depois de liberado
        self.assertIn(antispam.BLOCK_NOTICE, to_user)
        self.assertIn(antispam.RELEASE_NOTICE, to_user)
        notices = [p for p in api.sent if p["chat_id"] in (500, 600)]
        self.assertEqual([p["chat_id"] for p in notices], [500])  # só a classe I recebe
        self.assertIn("9 mensagens em 10 segundos", notices[0]["text"])
        self.assertIn("as:release:42", json.dumps(notices[0]["reply_markup"]))
        answers = [p for meth, p, f in api.calls if meth == "answerCallbackQuery"]
        self.assertEqual([a.get("text") for a in answers], [
            "Só admins podem decidir bloqueios.", "Mantido bloqueado.", "Desbloqueado.",
            "Esse caso já foi resolvido por outro admin."])
        edits = [p for meth, p, f in api.calls if meth == "editMessageText"]
        self.assertIn("Mantido bloqueado", edits[0]["text"])
        self.assertIn("Desbloqueado</b> por @chefe", edits[1]["text"])
        self.assertNotIn("reply_markup", edits[1])
        self.assertEqual(antispam.load(self.dir), {})
        blocked = cb.of("spam_blocked")
        self.assertEqual((blocked[0]["user_id"], blocked[0]["admins_notified"]), (42, 1))
        self.assertEqual([e["action"] for e in cb.of("spam_decided")], ["keep", "release"])
        # o que o troll mandou continua no mensageiro para análise
        texts = [x["text"] for x in json.loads(chats.get_messages(self.dir, 42, limit=100))
                 if not x["outgoing"]]
        self.assertEqual(len(texts), 12)
        chats.close(self.dir)

    def test_admins_are_never_blocked_and_can_disable(self):
        eng, api, cb = self.make([self.flood(uid=500)])
        eng.run()
        self.assertEqual(cb.of("spam_blocked"), [])
        eng, api, cb = self.make([self.flood(uid=42, start=200)], {"enabled": False})
        eng.run()
        self.assertEqual(cb.of("spam_blocked"), [])
        chats.close(self.dir)

    def test_group_chatter_does_not_count(self):
        script = [[msg("conversa %d" % i, update_id=i + 1, chat_type="group") for i in range(20)]]
        eng, api, cb = self.make(script, {"sensitivity": "alta"})
        eng.run()
        self.assertEqual(cb.of("spam_blocked"), [])
        chats.close(self.dir)

    def test_app_decision_and_manual_block(self):
        antispam.block_app(self.dir, json.dumps({"id": 77, "first_name": "Zé"}))
        items = json.loads(antispam.list_bans(self.dir))
        self.assertEqual((items[0]["id"], items[0]["status"], items[0]["reason_text"]),
                         (77, "kept", "bloqueado manualmente"))
        eng, api, cb = self.make([[msg("/oi", update_id=1, user_id=77)]])
        eng.run()
        self.assertEqual([p for p in api.sent if p["chat_id"] == 77], [])  # ignorado
        out = json.loads(antispam.decide_app("", self.dir, 77, antispam.RELEASE))
        self.assertTrue(out["ok"])
        self.assertEqual(antispam.load(self.dir), {})
        chats.close(self.dir)


if __name__ == "__main__":
    unittest.main()
