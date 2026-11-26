"""Agendamentos: mensagens no horário, sequências depois do /start e lembretes.

- ``schedule`` (na lista de comandos, como os outros gatilhos): roda os blocos no horário
  (todo dia, dias da semana, todo mês, a cada X minutos ou numa data) para um destino:
  todos os usuários, grupos, canais, chats escolhidos ou nenhum (só roda os blocos, ex.:
  um script do Termux).
- ``drip``: sequência depois do primeiro /start de cada pessoa ("dia 1, dia 3, dia 7").
  Vale para quem começar depois que o item foi criado (ninguém recebe mensagens atrasadas).
- Lembretes pedidos pela pessoa (``lembrar()`` no Python): ``reminders.json``.

Horários são do relógio do celular (``callbacks.nowLocal``). Se o celular estiver
desligado na hora, o agendamento ainda sai se o bot voltar em até 1 hora; depois disso, é
pulado. Estado em ``schedules_state.json`` (última execução) e ``drip.json``.
"""

import calendar
import json
import os
import threading
import time
from datetime import datetime, timedelta, timezone

from . import broadcast, chats, i18n, template, timeparse

TICK = 20
GRACE = 3600
MODES = ("daily", "weekly", "monthly", "interval", "once")
TARGETS = ("users", "groups", "channels", "all", "chats", "none")
UNITS = {"minutes": 60, "hours": 3600, "days": 86400}
STATE_FILE = "schedules_state.json"
DRIP_FILE = "drip.json"
REMINDERS_FILE = "reminders.json"
MAX_REMINDERS_PER_USER = 20
_lock = threading.RLock()
_EPOCH = datetime(1970, 1, 1)


# -- configuração ---------------------------------------------------------------------------

def _hhmm(value):
    try:
        h, m = str(value or "09:00").split(":")
        h, m = int(h), int(m)
        if 0 <= h <= 23 and 0 <= m <= 59:
            return "%02d:%02d" % (h, m)
    except ValueError:
        pass
    return None


def normalize_schedule(raw):
    raw = raw if isinstance(raw, dict) else {}
    mode = raw.get("mode") if raw.get("mode") in MODES else "daily"
    at = _hhmm(raw.get("time"))
    if at is None:
        return None
    days = sorted({int(d) for d in raw.get("days") or [] if str(d).isdigit() and 0 <= int(d) <= 6})
    try:
        every = max(5, min(int(raw.get("every") or 60), 7 * 24 * 60))
        day = max(1, min(int(raw.get("day") or 1), 31))
    except (TypeError, ValueError):
        every, day = 60, 1
    date = str(raw.get("date") or "")
    if mode == "once":
        try:
            datetime.strptime(date, "%Y-%m-%d")
        except ValueError:
            return None
    if mode == "weekly" and not days:
        return None
    target = raw.get("target") if raw.get("target") in TARGETS else "users"
    ids = []
    for c in raw.get("chats") or []:
        try:
            ids.append(int(c))
        except (TypeError, ValueError):
            pass
    if target == "chats" and not ids:
        return None
    return {"mode": mode, "time": at, "days": days, "every": every, "day": day, "date": date,
            "target": target, "chats": ids[:200]}


def normalize_drip(raw):
    raw = raw if isinstance(raw, dict) else {}
    unit = raw.get("unit") if raw.get("unit") in UNITS else "days"
    try:
        delay = max(0, min(int(raw.get("delay") or 0), 3650))
    except (TypeError, ValueError):
        delay = 0
    return {"delay": delay, "unit": unit}


# -- quando é a vez ---------------------------------------------------------------------------

def last_occurrence(spec, now):
    """Último horário agendado (``datetime`` local) que já chegou, ou ``None``."""
    h, m = map(int, spec["time"].split(":"))
    today = now.replace(hour=h, minute=m, second=0, microsecond=0)
    mode = spec["mode"]
    if mode == "daily":
        return today if today <= now else today - timedelta(days=1)
    if mode == "weekly":
        for back in range(8):
            cand = today - timedelta(days=back)
            if cand.weekday() in spec["days"] and cand <= now:
                return cand
        return None
    if mode == "monthly":
        def in_month(year, month):
            last = calendar.monthrange(year, month)[1]
            return datetime(year, month, min(spec["day"], last), h, m)
        cand = in_month(now.year, now.month)
        if cand <= now:
            return cand
        year, month = (now.year, now.month - 1) if now.month > 1 else (now.year - 1, 12)
        return in_month(year, month)
    if mode == "once":
        cand = datetime.strptime(spec["date"], "%Y-%m-%d").replace(hour=h, minute=m)
        return cand if cand <= now else None
    return None


def next_occurrence(spec, now, last_run=None):
    """Próximo horário (para mostrar no app). ``None`` se não há mais (data única que passou)."""
    if spec["mode"] == "interval":
        return None
    probe = now + timedelta(minutes=1)
    for _ in range(400):
        occ = last_occurrence(spec, probe)
        if occ is not None and occ > now:
            return occ
        probe += timedelta(days=1)
    return None


# -- arquivos -----------------------------------------------------------------------------------

def _load(path, default):
    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        return data if isinstance(data, type(default)) else default
    except (OSError, ValueError):
        return default


def _save(path, data):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False)
    os.replace(tmp, path)


class Scheduler:
    def __init__(self, engine):
        self.engine = engine
        self._thread = None
        self._offset = None
        self._offset_at = 0
        self.sync = False  # testes: roda os envios na mesma thread

    @property
    def base_dir(self):
        return self.engine.base_dir

    def _path(self, name):
        return os.path.join(self.base_dir, name)

    # -- relógio local ------------------------------------------------------------------------

    def offset(self, now_ts=None):
        """Diferença (s) entre o relógio do celular e o UTC, arredondada em 15 min."""
        now_ts = now_ts or time.time()
        if self._offset is None or now_ts - self._offset_at > 300:
            try:
                local = datetime.strptime(self.engine._now(), "%d/%m/%Y %H:%M")
                utc = datetime.fromtimestamp(now_ts, timezone.utc).replace(tzinfo=None, second=0, microsecond=0)
                self._offset = round((local - utc).total_seconds() / 900) * 900
            except (ValueError, TypeError):
                self._offset = 0
            self._offset_at = now_ts
        return self._offset

    def to_local(self, ts):
        return datetime.fromtimestamp(ts, timezone.utc).replace(tzinfo=None) + timedelta(seconds=self.offset(ts))

    def to_ts(self, local):
        return (local - timedelta(seconds=self.offset()) - _EPOCH).total_seconds()

    # -- laço ------------------------------------------------------------------------------------

    def start(self):
        if self._thread is None:
            self._thread = threading.Thread(target=self._loop, name="scheduler", daemon=True)
            self._thread.start()

    def _loop(self):
        eng = self.engine
        while not eng.stopping:
            try:
                self.tick()
            except Exception as exc:  # noqa: BLE001 - agendamento nunca derruba o bot
                eng.emit("error", context="schedule", command=None, retry_in=None, fatal=False,
                         code="unknown", detail="%s: %s" % (type(exc).__name__, exc), retry_after=None)
            eng._stop.wait(TICK)

    def tick(self, now_ts=None):
        now_ts = now_ts or time.time()
        autos = self.engine.config.get("automations") or []
        schedules = [a for a in autos if a["kind"] == "schedule"]
        drips = [a for a in autos if a["kind"] == "drip"]
        if schedules:
            self._schedules(schedules, now_ts)
        if drips:
            self._drips(drips, now_ts)
        self._reminders(now_ts)

    # -- mensagens agendadas -------------------------------------------------------------------

    def _schedules(self, items, now_ts):
        with _lock:
            state = _load(self._path(STATE_FILE), {})
            now = self.to_local(now_ts)
            due = []
            for a in items:
                spec, last = a["trigger"], state.get(a["id"])
                if last is None:
                    state[a["id"]] = now_ts  # agendamento novo: começa a contar agora
                    continue
                if spec["mode"] == "interval":
                    if now_ts - last >= spec["every"] * 60 - 5:
                        due.append(a)
                        state[a["id"]] = now_ts
                    continue
                occ = last_occurrence(spec, now)
                if occ is None:
                    continue
                occ_ts = self.to_ts(occ)
                if occ_ts > last:
                    state[a["id"]] = now_ts
                    if now_ts - occ_ts <= GRACE:
                        due.append(a)
            for gone in set(state) - {a["id"] for a in items}:
                state.pop(gone, None)
            _save(self._path(STATE_FILE), state)
        for a in due:
            self._run(self.fire, a)

    def recipients(self, spec):
        target = spec["target"]
        if target == "none":
            return [None]
        if target == "chats":
            known = {r["chat_id"]: r for r in json.loads(chats.list_chats(self.base_dir))}
            return [known.get(c) or {"chat_id": c, "type": "private" if c > 0 else "group"} for c in spec["chats"]]
        keys = {"users", "groups", "channels"} if target == "all" else {target}
        return broadcast.audience(self.base_dir, keys)

    def fire(self, auto):
        eng = self.engine
        sender = self._sender()
        rows = self.recipients(auto["trigger"])
        label = auto.get("description") or i18n.t("label.schedule")
        eng.emit("schedule_fired", label=label, recipients=len([r for r in rows if r]))
        for row in rows:
            if eng.stopping:
                return
            chat_id = row["chat_id"] if row else None
            user = _user(row) if row and row.get("type") == "private" else {}
            eng.run_blocks(auto["blocks"], label, chat_id, user, "", update={}, sender=sender, kind="schedule")
            if len(rows) > 1:
                eng._stop.wait(0.05)  # ~20 por segundo, dentro do limite do Telegram

    # -- sequência depois do /start --------------------------------------------------------------

    def record_start(self, user):
        """Primeiro /start de alguém (conversa privada): começa a sequência dessa pessoa."""
        uid = (user or {}).get("id")
        if uid is None:
            return
        with _lock:
            data = _load(self._path(DRIP_FILE), {})
            users = data.setdefault("users", {})
            if str(uid) in users:
                return
            users[str(uid)] = {"start": time.time(), "first_name": user.get("first_name") or "",
                               "last_name": user.get("last_name") or "", "username": user.get("username") or "",
                               "sent": []}
            _save(self._path(DRIP_FILE), data)

    def _drips(self, items, now_ts):
        jobs = []
        with _lock:
            path = self._path(DRIP_FILE)
            data = _load(path, {})
            seen = data.setdefault("items", {})
            users = data.setdefault("users", {})
            changed = False
            for a in items:
                if a["id"] not in seen:
                    seen[a["id"]] = now_ts  # só vale para quem começar a partir de agora
                    changed = True
            for a in sorted(items, key=lambda x: x["trigger"]["delay"] * UNITS[x["trigger"]["unit"]]):
                wait = a["trigger"]["delay"] * UNITS[a["trigger"]["unit"]]
                for uid, u in users.items():
                    if u.get("stopped") or a["id"] in u["sent"] or u["start"] < seen[a["id"]] - 1:
                        continue
                    if now_ts >= u["start"] + wait:
                        u["sent"].append(a["id"])
                        jobs.append((a, int(uid), u))
                        changed = True
                    if len(jobs) >= 200:
                        break
            if changed:
                _save(path, data)
        for a, uid, u in jobs:
            self._run(self.send_drip, a, uid, u)

    def send_drip(self, auto, uid, u):
        user = {"id": uid, "first_name": u.get("first_name"), "last_name": u.get("last_name"),
                "username": u.get("username")}
        label = auto.get("description") or i18n.t("label.drip")
        self.engine.emit("drip_sent", label=label, user=u.get("first_name") or str(uid))
        self.engine.run_blocks(auto["blocks"], label, uid, user, "", update={}, sender=self._sender(), kind="drip")

    # -- lembretes ----------------------------------------------------------------------------

    def remind(self, chat_id, user, when, text, lang=None):
        """Agenda um lembrete. ``when`` = minutos (número) ou texto ("amanhã 9h ...").
        Devolve ``(descrição, texto)`` ou ``None`` se não entendeu / passou do limite."""
        now = self.to_local(time.time())
        if isinstance(when, (int, float)):
            at = now + timedelta(minutes=max(1, float(when)))
            rest = text or ""
        else:
            parsed = timeparse.parse(str(when), now)
            if parsed is None:
                return None
            at, rest = parsed
            if text:
                rest = text
        at = at.replace(second=0, microsecond=0)
        uid = (user or {}).get("id")
        with _lock:
            path = self._path(REMINDERS_FILE)
            items = _load(path, [])
            if sum(1 for r in items if r.get("user_id") == uid) >= MAX_REMINDERS_PER_USER:
                return None
            items.append({"chat_id": chat_id, "user_id": uid, "at": self.to_ts(at), "text": str(rest)[:1000],
                          "lang": lang or self.engine.lang})
            _save(path, items)
        desc = i18n.t("reminder.when", lang or self.engine.lang, at.strftime(i18n.t("fmt.date_short", lang)),
                      at.strftime("%H:%M"))
        return desc, rest

    def reminders(self, user_id=None):
        items = _load(self._path(REMINDERS_FILE), [])
        return [r for r in items if user_id is None or r.get("user_id") == user_id]

    def cancel_reminders(self, user_id):
        with _lock:
            path = self._path(REMINDERS_FILE)
            items = _load(path, [])
            keep = [r for r in items if r.get("user_id") != user_id]
            _save(path, keep)
            return len(items) - len(keep)

    def _reminders(self, now_ts):
        path = self._path(REMINDERS_FILE)
        if not os.path.exists(path):
            return
        with _lock:
            items = _load(path, [])
            due = [r for r in items if r.get("at", 0) <= now_ts]
            if not due:
                return
            _save(path, [r for r in items if r.get("at", 0) > now_ts])
        sender = self._sender()
        for r in due:
            text = i18n.t("reminder.fire", r.get("lang"), template.escape(r.get("text") or "")) if r.get("text") \
                else i18n.t("reminder.fire_empty", r.get("lang"))
            sender._call_send("sendMessage", {"chat_id": r["chat_id"], "text": text, "parse_mode": "HTML"},
                              i18n.t("label.reminder"))
            self.engine.emit("reminder_sent", chat=r["chat_id"])

    # -- auxiliares -----------------------------------------------------------------------------

    def _sender(self):
        return self.engine if self.sync else self.engine.termux._sender()

    def _run(self, fn, *args):
        if self.sync:
            fn(*args)
        else:
            threading.Thread(target=fn, args=args, name="schedule-send", daemon=True).start()


def _user(row):
    return {"id": row.get("chat_id"), "first_name": row.get("first_name") or "", "last_name": row.get("last_name") or "",
            "username": row.get("username") or ""}
