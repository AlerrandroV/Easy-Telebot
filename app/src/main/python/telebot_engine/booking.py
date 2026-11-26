"""Agenda de horários: a pessoa escolhe dia e hora em botões (barbearia, consultório, aulas...).

Configuração em ``config["booking"]``::

    {"enabled": true, "command": "agendar", "my_command": "meushorarios", "service": "Corte de cabelo",
     "days": [1, 2, 3, 4, 5], "start": "09:00", "end": "18:00", "slot": 30, "ahead": 14, "capacity": 1,
     "remind_hours": 2, "admin_level": 1, "confirm": "Te esperamos!"}

Horários pelo relógio do celular. Agendamentos em ``bookings.json``. Os admins recebem cada
novo agendamento e cancelamento no Telegram; a pessoa recebe um lembrete antes do horário.
"""

import json
import os
import re
import secrets
import threading
import time
from datetime import datetime, timedelta

from . import admins as admins_mod
from . import i18n, template

CB = "bk:"
STATE_FILE = "bookings.json"
MAX_FUTURE_PER_USER = 5
_lock = threading.RLock()


def _hhmm(value, default):
    m = re.match(r"^([01]?\d|2[0-3]):([0-5]\d)$", str(value or ""))
    return "%02d:%02d" % (int(m.group(1)), int(m.group(2))) if m else default


def _num(value, lo, hi, default):
    try:
        return max(lo, min(hi, int(value)))
    except (TypeError, ValueError):
        return default


def normalize(data):
    data = data if isinstance(data, dict) else {}

    def cmd(value, default):
        return re.sub(r"[^a-z0-9_]", "", str(value or default).lower())[:32] or default
    return {
        "enabled": bool(data.get("enabled", False)),
        "command": cmd(data.get("command"), "agendar"),
        "my_command": cmd(data.get("my_command"), "meushorarios"),
        "service": str(data.get("service") or "")[:100],
        "days": sorted({int(d) for d in data.get("days") or [0, 1, 2, 3, 4] if str(d).isdigit() and 0 <= int(d) <= 6}),
        "start": _hhmm(data.get("start"), "09:00"),
        "end": _hhmm(data.get("end"), "18:00"),
        "slot": _num(data.get("slot"), 5, 480, 30),
        "ahead": _num(data.get("ahead"), 1, 90, 14),
        "capacity": _num(data.get("capacity"), 1, 100, 1),
        "remind_hours": _num(data.get("remind_hours"), 0, 72, 2),
        "admin_level": _num(data.get("admin_level"), 1, 10, 1),
        "confirm": str(data.get("confirm") or "")[:2000],
    }


def load(bot_dir):
    try:
        with open(os.path.join(bot_dir, STATE_FILE), "r", encoding="utf-8") as fh:
            data = json.load(fh)
        return data if isinstance(data, list) else []
    except (OSError, ValueError):
        return []


def _save(bot_dir, items):
    path = os.path.join(bot_dir, STATE_FILE)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(items, fh, ensure_ascii=False)
    os.replace(tmp, path)


class Booking:
    def __init__(self, engine):
        self.engine = engine

    @property
    def s(self):
        return self.engine.config.get("booking") or normalize({})

    def t(self, key, *args):
        return i18n.t(key, self.engine.lang, *args)

    def now(self):
        return self.engine.scheduler.to_local(time.time()).replace(second=0, microsecond=0)

    def label(self, dt):
        wd = self.t("wd.%d" % dt.weekday())
        return self.t("bk.when", wd, dt.strftime(self.t("fmt.date_short")), dt.strftime("%H:%M"))

    # -- horários livres ----------------------------------------------------------------------

    def slots(self, day, items=None):
        """Horários livres (``datetime``) num dia."""
        s = self.s
        if day.weekday() not in s["days"]:
            return []
        items = load(self.engine.base_dir) if items is None else items
        taken = {}
        for b in items:
            taken[b["at"]] = taken.get(b["at"], 0) + 1
        h, m = map(int, s["start"].split(":"))
        eh, em = map(int, s["end"].split(":"))
        cur = datetime(day.year, day.month, day.day, h, m)
        end = datetime(day.year, day.month, day.day, eh, em)
        now = self.now()
        out = []
        while cur + timedelta(minutes=s["slot"]) <= end:
            if cur > now and taken.get(cur.strftime("%Y-%m-%dT%H:%M"), 0) < s["capacity"]:
                out.append(cur)
            cur += timedelta(minutes=s["slot"])
        return out

    def open_days(self):
        items = load(self.engine.base_dir)
        today = self.now().date()
        out = []
        for i in range(self.s["ahead"] + 1):
            day = today + timedelta(days=i)
            if self.slots(day, items):
                out.append(day)
        return out

    # -- Telegram -------------------------------------------------------------------------------

    def handle_command(self, message, parsed):
        s = self.s
        if not s["enabled"] or not parsed:
            return False
        chat = message.get("chat") or {}
        if parsed[0] == s["command"]:
            if chat.get("type") != "private":
                self._send(chat.get("id"), self.t("bk.private_only"))
            else:
                self._send(chat["id"], *self.days_view())
            return True
        if parsed[0] == s["my_command"]:
            self._send(chat.get("id"), *self.mine_view((message.get("from") or {}).get("id")))
            return True
        return False

    def days_view(self):
        days = self.open_days()
        title = self.t("bk.pick_day", template.escape(self.s["service"])) if self.s["service"] else self.t("bk.pick_day_plain")
        if not days:
            return self.t("bk.no_days"), None
        buttons = [{"text": "%s %s" % (self.t("wd.%d" % d.weekday()), d.strftime(self.t("fmt.date_short"))),
                    "callback_data": "%sd:%s" % (CB, d.strftime("%Y%m%d"))} for d in days[:28]]
        rows = [buttons[i:i + 3] for i in range(0, len(buttons), 3)]
        return title, {"inline_keyboard": rows}

    def times_view(self, day):
        free = self.slots(day)
        if not free:
            return self.t("bk.day_full"), {"inline_keyboard": [[{"text": self.t("bk.back"), "callback_data": CB + "back"}]]}
        buttons = [{"text": t.strftime("%H:%M"), "callback_data": "%st:%s" % (CB, t.strftime("%Y%m%d%H%M"))} for t in free[:60]]
        rows = [buttons[i:i + 4] for i in range(0, len(buttons), 4)]
        rows.append([{"text": self.t("bk.back"), "callback_data": CB + "back"}])
        title = self.t("bk.pick_time", "%s %s" % (self.t("wd.%d" % day.weekday()), day.strftime(self.t("fmt.date_short"))))
        return title, {"inline_keyboard": rows}

    def mine_view(self, uid):
        now_key = self.now().strftime("%Y-%m-%dT%H:%M")
        mine = sorted((b for b in load(self.engine.base_dir) if b["user_id"] == uid and b["at"] >= now_key), key=lambda b: b["at"])
        if not mine:
            return self.t("bk.none_yet", "/" + self.s["command"]), None
        lines = [self.t("bk.mine_title")]
        rows = []
        for b in mine:
            when = self.label(datetime.strptime(b["at"], "%Y-%m-%dT%H:%M"))
            lines.append("• " + when)
            rows.append([{"text": self.t("bk.cancel_btn", when), "callback_data": "%sx:%s" % (CB, b["id"])}])
        return "\n".join(lines), {"inline_keyboard": rows}

    def _send(self, chat_id, html, keyboard=None):
        params = {"chat_id": chat_id, "text": html, "parse_mode": "HTML"}
        if keyboard:
            params["reply_markup"] = keyboard
        return self.engine._call_send("sendMessage", params, self.t("label.booking"))

    def _edit(self, msg, html, keyboard=None):
        params = {"chat_id": msg["chat"]["id"], "message_id": msg["message_id"], "text": html, "parse_mode": "HTML"}
        if keyboard:
            params["reply_markup"] = keyboard
        try:
            self.engine.api.call("editMessageText", params, timeout=20)
        except Exception:  # noqa: BLE001 - mensagem antiga: manda outra
            self._send(msg["chat"]["id"], html, keyboard)

    def handle_callback(self, query):
        data = query.get("data") or ""
        if not data.startswith(CB):
            return False
        eng = self.engine
        msg = query.get("message") or {}
        user = query.get("from") or {}
        action, _, arg = data[len(CB):].partition(":")
        answer = None
        try:
            if action == "back":
                self._edit(msg, *self.days_view())
            elif action == "d":
                self._edit(msg, *self.times_view(datetime.strptime(arg, "%Y%m%d").date()))
            elif action == "t":
                at = datetime.strptime(arg, "%Y%m%d%H%M")
                kb = {"inline_keyboard": [[{"text": self.t("bk.confirm_btn"), "callback_data": "%sc:%s" % (CB, arg)},
                                           {"text": self.t("bk.back"), "callback_data": "%sd:%s" % (CB, arg[:8])}]]}
                self._edit(msg, self.t("bk.confirm_q", self.label(at)), kb)
            elif action == "c":
                answer = self.confirm(msg, user, datetime.strptime(arg, "%Y%m%d%H%M"))
            elif action == "x":
                answer = self.cancel(arg, user, msg)
        except ValueError:
            pass
        params = {"callback_query_id": query.get("id")}
        if answer:
            params.update(text=answer, show_alert=True)
        eng._try_call("answerCallbackQuery", params)
        return True

    def confirm(self, msg, user, at):
        s = self.s
        key = at.strftime("%Y-%m-%dT%H:%M")
        uid = user.get("id")
        with _lock:
            items = load(self.engine.base_dir)
            if at not in self.slots(at.date(), items):
                return self.t("bk.taken")
            now_key = self.now().strftime("%Y-%m-%dT%H:%M")
            if sum(1 for b in items if b["user_id"] == uid and b["at"] >= now_key) >= MAX_FUTURE_PER_USER:
                return self.t("bk.too_many", MAX_FUTURE_PER_USER)
            booking = {"id": secrets.token_hex(4), "user_id": uid, "chat_id": msg["chat"]["id"],
                       "name": " ".join(x for x in (user.get("first_name"), user.get("last_name")) if x),
                       "username": user.get("username") or "", "at": key, "created": int(time.time()), "reminded": False}
            items.append(booking)
            _save(self.engine.base_dir, items)
        when = self.label(at)
        text = self.t("bk.confirmed", when)
        if s["confirm"]:
            ctx = self.engine.flows._context(user, msg["chat"]["id"], "")
            text += "\n\n" + template.render(s["confirm"], ctx, self.engine.lang)
        text += "\n\n" + self.t("bk.how_cancel", "/" + s["my_command"])
        self._edit(msg, text)
        self.notify(self.t("bk.admin_new", template.escape(booking["name"] or str(uid)), when,
                           template.escape(s["service"]) or "—"))
        self.engine.emit("booking_created", user=booking["name"], when=when)
        return None

    def cancel(self, booking_id, user=None, msg=None, by_admin=False):
        with _lock:
            items = load(self.engine.base_dir)
            booking = next((b for b in items if b["id"] == booking_id), None)
            if booking is None or (user is not None and not by_admin and booking["user_id"] != user.get("id")):
                return self.t("bk.not_found")
            items.remove(booking)
            _save(self.engine.base_dir, items)
        when = self.label(datetime.strptime(booking["at"], "%Y-%m-%dT%H:%M"))
        if msg is not None:
            self._edit(msg, self.t("bk.cancelled", when))
        if by_admin:
            self._send(booking["chat_id"], self.t("bk.cancelled_by_admin", when))
        else:
            self.notify(self.t("bk.admin_cancel", template.escape(booking["name"] or str(booking["user_id"])), when))
        self.engine.emit("booking_cancelled", user=booking["name"], when=when)
        return None

    def notify(self, html):
        eng = self.engine
        eng.maybe_reload_admins()
        for admin_id, admin in sorted(eng.admins.items()):
            if admins_mod.can_use(admin.get("level"), self.s["admin_level"]):
                self._send(admin_id, html)

    # -- lembretes (agendador) -------------------------------------------------------------------

    def tick(self, sender_factory=None):
        s = self.s
        if not s["enabled"] or not s["remind_hours"]:
            return
        now = self.now()
        due = []
        with _lock:
            items = load(self.engine.base_dir)
            changed = False
            for b in items:
                at = datetime.strptime(b["at"], "%Y-%m-%dT%H:%M")
                if not b.get("reminded") and now < at <= now + timedelta(hours=s["remind_hours"]):
                    b["reminded"] = True
                    due.append(b)
                    changed = True
            # agendamentos que já passaram há mais de 30 dias saem do arquivo
            old = (now - timedelta(days=30)).strftime("%Y-%m-%dT%H:%M")
            keep = [b for b in items if b["at"] >= old]
            if changed or len(keep) != len(items):
                _save(self.engine.base_dir, keep)
        sender = sender_factory() if (sender_factory and due) else self.engine
        for b in due:
            when = self.label(datetime.strptime(b["at"], "%Y-%m-%dT%H:%M"))
            sender._call_send("sendMessage", {"chat_id": b["chat_id"], "parse_mode": "HTML",
                                              "text": self.t("bk.reminder", when, template.escape(s["service"]) or "")},
                              self.t("label.booking"))


# -- funções para o app ----------------------------------------------------------------------------

def list_bookings(bot_dir):
    return json.dumps(sorted(load(bot_dir), key=lambda b: b["at"]), ensure_ascii=False)


def app_cancel(token, config_path, booking_id):
    """Cancelamento pelo app: avisa a pessoa no Telegram."""
    from .api import TelegramApi
    from .engine import Engine

    class _Quiet:
        def onEvent(self, data):  # noqa: N802
            pass

        def nowLocal(self):  # noqa: N802
            return time.strftime("%d/%m/%Y %H:%M")

    eng = Engine(token, config_path, os.path.dirname(config_path), _Quiet(), api=TelegramApi(token))
    eng.maybe_reload_config(force=True)
    out = eng.booking.cancel(booking_id, by_admin=True)
    return json.dumps({"ok": out is None})
