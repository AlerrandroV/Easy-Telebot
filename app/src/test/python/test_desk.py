"""Atendimento (suporte, horário, FAQ), agenda de horários e formulários."""

import json
import os
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "main", "python"))
sys.path.insert(0, HERE)

from telebot_engine import admins, booking, chats, desk, engine, forms  # noqa: E402
from test_engine import Callbacks, FakeApi, msg  # noqa: E402

ADMIN = 900


class Api(FakeApi):
    def call(self, method, params=None, timeout=30, files=None):
        if method in ("copyMessage", "editMessageText", "setMessageReaction", "answerCallbackQuery"):
            self.calls.append((method, params, files))
            if method == "copyMessage":
                FakeApi._next_id += 1
                return {"message_id": FakeApi._next_id}
            return True
        return super().call(method, params, timeout, files)

    def of(self, method):
        return [c[1] for c in self.calls if c[0] == method]


def admin_msg(text, update_id, reply_to=None):
    u = msg(text, update_id=update_id, user_id=ADMIN, first="Dono")
    u["message"]["message_id"] = 500 + update_id
    if reply_to:
        u["message"]["reply_to_message"] = {"message_id": reply_to, "chat": {"id": ADMIN}}
    return u


def press(data, update_id, uid=42, mid=77):
    return {"update_id": update_id, "callback_query": {"id": "q", "from": {"id": uid, "first_name": "Ana"}, "data": data,
                                                       "message": {"message_id": mid, "chat": {"id": uid, "type": "private"}}}}


class Base(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        with open(os.path.join(self.dir, admins.ADMINS_FILE), "w", encoding="utf-8") as fh:
            json.dump([{"id": ADMIN, "level": 1, "first_name": "Dono"}], fh)

    def tearDown(self):
        chats.close(self.dir)

    def make(self, cfg, script, now=None):
        path = os.path.join(self.dir, "config.json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(dict({"version": 2, "commands": []}, **cfg), fh)
        api = Api(script)
        eng = engine.Engine("1:x", path, self.dir, Callbacks(), api=api)
        api.engine = eng
        eng.flows.sync = True
        eng.termux.sync_scripts = True
        eng.scheduler.sync = True
        eng.scheduler._offset, eng.scheduler._offset_at = 0, 10 ** 12
        eng.sleep = lambda s: None
        return eng, api


class FaqTests(unittest.TestCase):
    ITEMS = desk.normalize({"faq": {"enabled": True, "items": [
        {"q": "Qual o horário de funcionamento?", "a": "8h às 18h", "keywords": ["que horas abre"]},
        {"q": "Vocês entregam?", "a": "Sim, na cidade toda"},
        {"q": "Aceitam Pix?", "a": "Sim"}]}})["faq"]["items"]

    def test_matching(self):
        m = lambda t: desk.faq_match(self.ITEMS, t)
        self.assertEqual(m("oi, qual é o horario de funcionamento de vcs?"), 0)
        self.assertEqual(m("que horas abre amanhã"), 0)
        self.assertEqual(m("vocês fazem entrega? entregam aqui?"), 1)
        self.assertEqual(m("aceita pix"), 2)
        self.assertIsNone(m("quero falar com um atendente"))
        self.assertIsNone(m("oi"))


class DeskTests(Base):
    def test_support_round_trip(self):
        cfg = {"desk": {"support": {"enabled": True, "ack": "Recebemos, {nome}!"}}}
        eng, api = self.make(cfg, [[msg("preciso de ajuda")], [msg("alô?", update_id=2)]])
        eng.run()
        to_admin = [p for p in api.sent if p["chat_id"] == ADMIN]
        self.assertEqual(len(to_admin), 2)
        self.assertIn("preciso de ajuda", to_admin[0]["text"])
        self.assertEqual([p["text"] for p in api.sent if p["chat_id"] == 42], ["Recebemos, Ana!"])  # aviso só uma vez
        # o admin responde à primeira mensagem encaminhada
        with open(os.path.join(self.dir, desk.STATE_FILE), encoding="utf-8") as fh:
            data = json.load(fh)
        key = next(k for k in data["map"])
        reply_to = int(key.split(":")[1])
        eng2, api2 = self.make(cfg, [[admin_msg("Oi Ana, posso ajudar!", 3, reply_to=reply_to)]])
        eng2.run()
        copy = api2.of("copyMessage")[0]
        self.assertEqual((copy["chat_id"], copy["from_chat_id"]), (42, ADMIN))

    def test_faq_answers_before_support_and_command_menu(self):
        cfg = {"desk": {"support": {"enabled": True}, "faq": {"enabled": True, "command": "faq", "items": [
            {"q": "Aceitam Pix?", "a": "*Sim*"}]}}}
        eng, api = self.make(cfg, [[msg("vocês aceitam pix?")], [msg("/faq", update_id=2)], [press("dk:faq:0", 3)]])
        eng.run()
        texts = [p["text"] for p in api.sent]
        self.assertIn("<b>Sim</b>", texts[0])
        self.assertFalse(any(p["chat_id"] == ADMIN for p in api.sent))  # FAQ resolveu: não incomoda o admin
        self.assertIn("Perguntas frequentes", texts[1])
        self.assertEqual(api.sent[1]["reply_markup"]["inline_keyboard"][0][0]["callback_data"], "dk:faq:0")
        self.assertIn("<b>Sim</b>", texts[2])

    def test_away_message_outside_hours(self):
        now = datetime.now(timezone.utc).replace(tzinfo=None)
        closed_days = [d for d in range(7) if d != now.weekday()]
        cfg = {"desk": {"hours": {"enabled": True, "days": closed_days, "start": "00:00", "end": "23:59",
                                  "away": "Voltamos amanhã!"}}}
        eng, api = self.make(cfg, [[msg("oi")], [msg("alguém?", update_id=2)]])
        eng.run()
        self.assertEqual([p["text"] for p in api.sent], ["Voltamos amanhã!"])


class BookingTests(Base):
    def cfg(self):
        today = datetime.now(timezone.utc).replace(tzinfo=None)
        return {"booking": {"enabled": True, "service": "Corte", "days": list(range(7)), "start": "00:00", "end": "23:59",
                            "slot": 60, "ahead": 2, "capacity": 1, "remind_hours": 0}}, today + timedelta(days=1)

    def test_book_and_cancel(self):
        cfg, tomorrow = self.cfg()
        day = tomorrow.strftime("%Y%m%d")
        slot = day + "1000"
        eng, api = self.make(cfg, [[msg("/agendar")], [press("bk:d:" + day, 2)], [press("bk:t:" + slot, 3)],
                                   [press("bk:c:" + slot, 4)], [press("bk:c:" + slot, 5, uid=43)],
                                   [msg("/meushorarios", update_id=6)]])
        eng.run()
        self.assertIn("Corte", api.sent[0]["text"])
        edits = [p["text"] for p in api.of("editMessageText")]
        self.assertIn("Horários livres", edits[0])
        self.assertIn("Confirmar", edits[1])
        self.assertIn("Agendado", edits[2])
        items = booking.load(self.dir)
        self.assertEqual(len(items), 1)
        alerts = [p for p in api.of("answerCallbackQuery") if p.get("show_alert")]
        self.assertIn("ocupado", alerts[0]["text"])  # o outro usuário perdeu o horário
        self.assertTrue(any(p["chat_id"] == ADMIN and "Novo agendamento" in p["text"] for p in api.sent))
        mine = api.sent[-1]
        cancel = mine["reply_markup"]["inline_keyboard"][0][0]["callback_data"]
        eng2, api2 = self.make(cfg, [[press(cancel, 7)]])
        eng2.run()
        self.assertEqual(booking.load(self.dir), [])
        self.assertTrue(any("Cancelado" in p["text"] for p in api2.sent if p["chat_id"] == ADMIN))

    def test_slots_respect_capacity_and_days(self):
        cfg, tomorrow = self.cfg()
        cfg["booking"]["days"] = [tomorrow.weekday()]
        eng, api = self.make(cfg, [])
        eng.maybe_reload_config(force=True)
        self.assertEqual(eng.booking.open_days(), [tomorrow.date()])
        self.assertEqual(len(eng.booking.slots(tomorrow.date())), 23)


class FormTests(Base):
    def test_form_flow_and_csv(self):
        cmd = {"triggers": ["cadastro"], "blocks": [
            {"type": "form", "id": "cad", "title": "Cadastro", "done": "Valeu, {nome}! Plano {plano}.",
             "questions": [{"text": "Seu e-mail?", "kind": "email"},
                           {"text": "Plano?", "kind": "choice", "options": ["Básico", "Premium"], "var": "plano"}]},
            {"type": "message", "text": "não aparece agora"}]}
        eng, api = self.make({"commands": [cmd]}, [[msg("/cadastro")], [msg("nao-e-email", update_id=2)],
                                                   [msg("ana@x.com", update_id=3)], [msg("premium", update_id=4)]])
        eng.run()
        texts = [p["text"] for p in api.sent if p["chat_id"] == 42]
        self.assertEqual(texts[0], "Seu e-mail?")
        self.assertIn("não parece válido", texts[1])
        self.assertEqual(texts[2], "Plano?")
        self.assertEqual(api.sent[[p.get("text") for p in api.sent].index("Plano?")]["reply_markup"]["keyboard"],
                         [[{"text": "Básico"}], [{"text": "Premium"}]])
        self.assertEqual(texts[3], "Valeu, Ana! Plano Premium.")
        self.assertTrue(any(p["chat_id"] == ADMIN and "ana@x.com" in p["text"] for p in api.sent))
        self.assertEqual(forms.count(self.dir, "cad"), 1)
        csv_text = forms.export_csv(self.dir, "cad", json.dumps(["Seu e-mail?", "Plano?"]), "pt")
        self.assertIn("ana@x.com,Premium", csv_text)
        self.assertTrue(csv_text.startswith("Data,Nome,@,ID,Seu e-mail?,Plano?"))


if __name__ == "__main__":
    unittest.main()
