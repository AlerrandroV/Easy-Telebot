"""Agendamentos: horários, sequência depois do /start, lembretes e o leitor de datas."""

import json
import os
import sys
import tempfile
import unittest
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "main", "python"))
sys.path.insert(0, HERE)

from telebot_engine import chats, engine, scheduler, timeparse  # noqa: E402
from test_engine import Callbacks, FakeApi, msg  # noqa: E402


def read_json(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def write_json(path, data):
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(data, fh)


def text(t):
    return [{"type": "message", "text": t}]


class ParseTests(unittest.TestCase):
    NOW = datetime(2026, 11, 26, 14, 30)  # quinta-feira

    def p(self, t):
        return timeparse.parse(t, self.NOW)

    def test_portuguese_and_english(self):
        self.assertEqual(self.p("amanhã às 9h comprar pão"), (datetime(2026, 11, 27, 9, 0), "comprar pão"))
        self.assertEqual(self.p("em 30 minutos tirar o bolo"), (datetime(2026, 11, 26, 15, 0), "tirar o bolo"))
        self.assertEqual(self.p("tomorrow at 9am gym"), (datetime(2026, 11, 27, 9, 0), "gym"))
        self.assertEqual(self.p("in 2 hours call mom")[0], datetime(2026, 11, 26, 16, 30))
        self.assertEqual(self.p("25/12 10:00 natal"), (datetime(2026, 12, 25, 10, 0), "natal"))
        self.assertEqual(self.p("sexta 8h feira"), (datetime(2026, 11, 27, 8, 0), "feira"))

    def test_bare_hour_is_a_time_not_a_duration(self):
        self.assertEqual(self.p("8h acordar")[0], datetime(2026, 11, 27, 8, 0))
        self.assertEqual(self.p("em 8h acordar")[0], datetime(2026, 11, 26, 22, 30))

    def test_nothing(self):
        self.assertIsNone(self.p("comprar pão"))
        self.assertIsNone(self.p("31/02 10h"))


class OccurrenceTests(unittest.TestCase):
    def spec(self, **kw):
        return scheduler.normalize_schedule(dict({"time": "09:00", "target": "users"}, **kw))

    def test_modes(self):
        now = datetime(2026, 11, 26, 10, 0)  # quinta
        self.assertEqual(scheduler.last_occurrence(self.spec(mode="daily"), now), datetime(2026, 11, 26, 9, 0))
        self.assertEqual(scheduler.last_occurrence(self.spec(mode="daily", time="11:00"), now),
                         datetime(2026, 11, 25, 11, 0))
        self.assertEqual(scheduler.last_occurrence(self.spec(mode="weekly", days=[0]), now),
                         datetime(2026, 11, 23, 9, 0))
        self.assertEqual(scheduler.last_occurrence(self.spec(mode="monthly", day=31), datetime(2026, 2, 28, 10, 0)),
                         datetime(2026, 2, 28, 9, 0))
        self.assertIsNone(scheduler.last_occurrence(self.spec(mode="once", date="2026-12-01"), now))
        self.assertEqual(scheduler.next_occurrence(self.spec(mode="weekly", days=[4]), now), datetime(2026, 11, 27, 9, 0))

    def test_invalid(self):
        self.assertIsNone(self.spec(mode="weekly", days=[]))
        self.assertIsNone(self.spec(mode="once", date="amanhã"))
        self.assertIsNone(self.spec(target="chats", chats=[]))
        self.assertIsNone(self.spec(time="25:00"))


class EngineTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def tearDown(self):
        chats.close(self.dir)

    def make(self, commands, script=None):
        path = os.path.join(self.dir, "config.json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump({"version": 2, "commands": commands}, fh)
        api = FakeApi(script or [])
        eng = engine.Engine("1:x", path, self.dir, Callbacks(), api=api)
        api.engine = eng
        eng.flows.sync = True
        eng.termux.sync_scripts = True
        eng.scheduler.sync = True
        eng.scheduler._offset, eng.scheduler._offset_at = 0, 10 ** 12  # relógio = UTC nos testes
        eng.maybe_reload_config(force=True)
        return eng, api

    @staticmethod
    def ts(*args):
        return (datetime(*args) - datetime(1970, 1, 1)).total_seconds()

    def test_daily_schedule_to_chosen_chat(self):
        sched = {"kind": "schedule", "id": "s1", "description": "Bom dia",
                 "trigger": {"mode": "daily", "time": "09:00", "target": "chats", "chats": [-100]},
                 "blocks": text("Bom dia, grupo!")}
        eng, api = self.make([sched])
        s = eng.scheduler
        s.tick(self.ts(2026, 11, 26, 8, 50))  # primeira vez: só começa a contar
        s.tick(self.ts(2026, 11, 26, 8, 59))
        self.assertEqual(api.sent, [])
        s.tick(self.ts(2026, 11, 26, 9, 0, 10))
        s.tick(self.ts(2026, 11, 26, 9, 5))  # não repete no mesmo dia
        self.assertEqual([(p["chat_id"], p["text"]) for p in api.sent], [(-100, "Bom dia, grupo!")])
        s.tick(self.ts(2026, 11, 27, 11, 0))  # celular desligado às 9h e de volta só às 11h: passou de 1 h, pula
        self.assertEqual(len(api.sent), 1)
        s.tick(self.ts(2026, 11, 28, 9, 30))
        self.assertEqual(len(api.sent), 2)

    def test_interval_to_all_users_with_names(self):
        for uid, name in ((5, "Ana"), (6, "Bia")):
            chats.record(self.dir, {"message_id": 1, "date": 1, "chat": {"id": uid, "type": "private", "first_name": name},
                                    "from": {"id": uid, "first_name": name}, "text": "oi"})
        sched = {"kind": "schedule", "id": "s2", "trigger": {"mode": "interval", "every": 30, "time": "00:00",
                                                             "target": "users"}, "blocks": text("Oi, {nome}")}
        eng, api = self.make([sched])
        eng.scheduler.tick(1000)
        eng.scheduler.tick(1000 + 29 * 60)
        self.assertEqual(api.sent, [])
        eng.scheduler.tick(1000 + 30 * 60)
        self.assertEqual(sorted(p["text"] for p in api.sent), ["Oi, Ana", "Oi, Bia"])

    def test_drip_after_start_only_for_new_people(self):
        drips = [{"kind": "drip", "id": "d1", "trigger": {"delay": 0, "unit": "minutes"}, "blocks": text("Passo 1")},
                 {"kind": "drip", "id": "d2", "trigger": {"delay": 1, "unit": "days"}, "blocks": text("Passo 2, {nome}")}]
        eng, api = self.make(drips)
        s = eng.scheduler
        s.record_start({"id": 8, "first_name": "Velho"})
        import time as _t
        old = _t.time()
        data = read_json(os.path.join(self.dir, scheduler.DRIP_FILE))
        data["users"]["8"]["start"] = old - 10 * 86400  # começou antes de a sequência existir
        write_json(os.path.join(self.dir, scheduler.DRIP_FILE), data)
        s.tick(old)  # a sequência "nasce" agora
        s.record_start({"id": 9, "first_name": "Nova"})
        s.tick(old + 60)
        s.tick(old + 2 * 86400)
        s.tick(old + 3 * 86400)
        self.assertEqual([(p["chat_id"], p["text"]) for p in api.sent], [(9, "Passo 1"), (9, "Passo 2, Nova")])

    def test_start_command_records_drip(self):
        eng, api = self.make([{"triggers": ["start"], "blocks": text("oi")}], script=[[msg("/start")]])
        eng.sleep = lambda s: None
        eng.run()
        data = read_json(os.path.join(self.dir, scheduler.DRIP_FILE))
        self.assertIn("42", data["users"])

    def test_reminder_from_python(self):
        cmd = {"triggers": ["lembrar"], "blocks": [{"type": "code", "code":
               'r = lembrar(argumentos)\nenviar_texto("Te lembro " + r if r else "Não entendi")\n'}]}
        eng, api = self.make([cmd], script=[[msg("/lembrar em 10 minutos tomar remédio")], [msg("/lembrar nada", update_id=2)]])
        eng.sleep = lambda s: None
        eng.scheduler._offset_at = 10 ** 12
        eng.run()
        self.assertTrue(api.sent[0]["text"].startswith("Te lembro "))
        self.assertEqual(api.sent[1]["text"], "Não entendi")
        items = eng.scheduler.reminders()
        self.assertEqual(items[0]["text"], "tomar remédio")
        eng.scheduler.tick(items[0]["at"] + 1)
        self.assertEqual(api.sent[-1]["text"], "⏰ Lembrete: tomar remédio")
        self.assertEqual(eng.scheduler.reminders(), [])

    def test_python_send_to_other_chat_and_no_chat_error(self):
        sched = {"kind": "schedule", "id": "s3", "trigger": {"mode": "interval", "every": 5, "time": "00:00",
                                                             "target": "none"},
                 "blocks": [{"type": "code", "code": 'enviar_texto("relatório", para=-100)\nenviar_texto("x")\n'}]}
        eng, api = self.make([sched])
        eng.scheduler.tick(1000)
        eng.scheduler.tick(1000 + 300)
        self.assertEqual([(p["chat_id"], p["text"]) for p in api.sent], [(-100, "relatório")])
        err = [e for e in eng.cb.events if e["type"] == "code_error"][0]
        self.assertEqual(err["code"], "no_chat")


if __name__ == "__main__":
    unittest.main()
