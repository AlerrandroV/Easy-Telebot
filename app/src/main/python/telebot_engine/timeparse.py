"""Entende quando lembrar: "amanhã às 9h comprar pão", "em 30 minutos", "in 2 hours", "25/12 10:00".

``parse(texto, agora)`` devolve ``(quando, resto)``: ``quando`` é um ``datetime`` local (sem
fuso) e ``resto`` é o texto do lembrete. ``None`` se não achou um momento no começo do texto.

Aceita português e inglês, e também formatos só com números (valem em qualquer idioma):
"10:30", "25/12", "25/12/2026 8h". Sem hora, o horário padrão é 9h.
"""

import re
from datetime import datetime, timedelta

from .pyfacil_names import fold

DEFAULT_HOUR = 9

_UNITS = {
    "min": 60, "mins": 60, "minuto": 60, "minutos": 60, "minute": 60, "minutes": 60, "m": 60,
    "h": 3600, "hr": 3600, "hrs": 3600, "hora": 3600, "horas": 3600, "hour": 3600, "hours": 3600,
    "dia": 86400, "dias": 86400, "day": 86400, "days": 86400, "d": 86400,
    "semana": 604800, "semanas": 604800, "week": 604800, "weeks": 604800,
}
_WEEKDAYS = {
    "segunda": 0, "terca": 1, "quarta": 2, "quinta": 3, "sexta": 4, "sabado": 5, "domingo": 6,
    "monday": 0, "tuesday": 1, "wednesday": 2, "thursday": 3, "friday": 4, "saturday": 5, "sunday": 6,
}
_RELATIVE = re.compile(
    r"^\s*(em |daqui a |daqui |dentro de |in |after )?\s*(\d{1,4})\s*(%s)\b" % "|".join(
        sorted(map(re.escape, _UNITS), key=len, reverse=True)))
_DAY = re.compile(
    r"(?:(hoje|today)|(depois de amanha|day after tomorrow)|(amanha|tomorrow)"
    r"|(?:na |no |on |next )?(%s)(?:-feira| feira)?"
    r"|(?:dia |on )?(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?)\b" % "|".join(_WEEKDAYS))
_TIME = re.compile(
    r"(?:(?:as|a|at|@|ao meio|por volta das)\s*)?(\d{1,2})(?:(?::|h)(\d{2})?)\s*(am|pm)?"
    r"|(?:(?:as|at|@)\s*)(\d{1,2})\s*(am|pm)?"
    r"|(\d{1,2})\s*(am|pm)\b")
_GLUE = re.compile(r"^[\s,:;.\-–—]*(?:(?:de|do|da|que|para|pra|to|that|about|sobre|:)\s+)?")


def _fold(text):
    # Um caractere por caractere: as posições continuam valendo no texto original.
    return "".join((fold(c) or c)[:1] for c in text)


def _time_at(m):
    """``(hora, minuto, fim)`` de um match de ``_TIME`` (ou ``None`` se inválido)."""
    if m.group(1) is not None:
        hour, minute, ampm = int(m.group(1)), int(m.group(2) or 0), m.group(3)
    elif m.group(4) is not None:
        hour, minute, ampm = int(m.group(4)), 0, m.group(5)
    else:
        hour, minute, ampm = int(m.group(6)), 0, m.group(7)
    if ampm:
        if not 1 <= hour <= 12:
            return None
        hour = hour % 12 + (12 if ampm == "pm" else 0)
    if hour > 23 or minute > 59:
        return None
    return hour, minute, m.end()


def _day_at(m, now):
    """Data (``date``) de um match de ``_DAY``."""
    today = now.date()
    if m.group(1):
        return today
    if m.group(2):
        return today + timedelta(days=2)
    if m.group(3):
        return today + timedelta(days=1)
    if m.group(4):
        ahead = (_WEEKDAYS[m.group(4)] - today.weekday()) % 7  # hoje mesmo, se ainda der tempo
        return today + timedelta(days=ahead)
    day, month = int(m.group(5)), int(m.group(6))
    year = int(m.group(7)) if m.group(7) else today.year
    if year < 100:
        year += 2000
    try:
        d = datetime(year, month, day).date()
    except ValueError:
        return None
    if not m.group(7) and d < today:
        d = d.replace(year=year + 1)
    return d


def parse(text, now):
    raw = text or ""
    low = _fold(raw).lower()
    m = _RELATIVE.match(low)
    # "8h" sozinho é horário; "em 8h" ou "8 horas" é daqui a 8 horas.
    if m and (m.group(1) or len(m.group(3)) > 1):
        amount = int(m.group(2)) * _UNITS[m.group(3)]
        return now + timedelta(seconds=amount), _rest(raw, m.end())

    pos = len(low) - len(low.lstrip())
    day = when_time = None
    for _ in range(2):  # dia e hora, em qualquer ordem
        chunk = low[pos:]
        dm = _DAY.match(chunk) if day is None else None
        if dm and dm.group(0).strip():
            day = _day_at(dm, now)
            if day is None:
                return None
            pos += dm.end()
            pos += len(low[pos:]) - len(low[pos:].lstrip())
            continue
        tm = _TIME.match(chunk) if when_time is None else None
        if tm and tm.group(0).strip():
            t = _time_at(tm)
            if t is None:
                return None
            when_time = t[:2]
            pos += t[2]
            pos += len(low[pos:]) - len(low[pos:].lstrip())
            continue
        break
    if day is None and when_time is None:
        return None
    hour, minute = when_time or (DEFAULT_HOUR, 0)
    if day is None:
        day = now.date()
        if (hour, minute) <= (now.hour, now.minute):
            day += timedelta(days=1)  # "às 8h" quando já passou das 8: amanhã
    when = datetime(day.year, day.month, day.day, hour, minute)
    if when <= now and day == now.date():
        when += timedelta(days=7 if _is_weekday(low) else 1)  # "quinta 8h" numa quinta às 10h
    if when <= now:
        return None
    return when, _rest(raw, pos)


def _is_weekday(low):
    return any(w in low for w in _WEEKDAYS)


def _rest(raw, pos):
    rest = raw[pos:]
    rest = rest[_GLUE.match(_fold(rest).lower()).end():]
    return rest.strip()
