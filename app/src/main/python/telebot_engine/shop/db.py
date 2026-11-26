"""Pedidos e assinaturas da loja (``shop.db`` na pasta do bot, SQLite).

A mudança de status de um pedido para ``paid`` é atômica: só uma thread consegue,
então a entrega nunca sai em dobro (ex.: botão "Já paguei" + verificação automática).
"""

import json
import os
import secrets
import sqlite3
import threading
import time

DB_FILE = "shop.db"
_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

_lock = threading.RLock()
_conns = {}

_SCHEMA = """
CREATE TABLE IF NOT EXISTS orders (
    id TEXT PRIMARY KEY,
    user_id INTEGER, chat_id INTEGER, first_name TEXT, last_name TEXT, username TEXT,
    items TEXT, total INTEGER, currency TEXT, stars INTEGER,
    gateway TEXT, gateway_ref TEXT, pay_url TEXT,
    status TEXT, created_at INTEGER, paid_at INTEGER, delivered INTEGER DEFAULT 0, note TEXT
);
CREATE INDEX IF NOT EXISTS orders_status ON orders(status, created_at);
CREATE TABLE IF NOT EXISTS subscriptions (
    user_id INTEGER, chat_id INTEGER, product_id TEXT,
    first_name TEXT, last_name TEXT, username TEXT, chat_title TEXT,
    started_at INTEGER, expires_at INTEGER, status TEXT, reminded INTEGER DEFAULT 0,
    PRIMARY KEY (user_id, chat_id)
);
"""


def _db(bot_dir):
    key = os.path.abspath(bot_dir)
    conn = _conns.get(key)
    if conn is None:
        conn = sqlite3.connect(os.path.join(bot_dir, DB_FILE), check_same_thread=False, isolation_level=None)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.executescript(_SCHEMA)
        for column in ("coupon TEXT", "discount INTEGER DEFAULT 0", "shipping INTEGER DEFAULT 0", "address TEXT",
                       "reminded INTEGER DEFAULT 0", "shipped TEXT"):
            try:  # bancos antigos: colunas novas
                conn.execute("ALTER TABLE orders ADD COLUMN " + column)
            except sqlite3.OperationalError:
                pass
        _conns[key] = conn
    return conn


def close(bot_dir):
    with _lock:
        conn = _conns.pop(os.path.abspath(bot_dir), None)
        if conn is not None:
            conn.close()


def _order(row):
    if row is None:
        return None
    out = dict(row)
    out["items"] = json.loads(out["items"] or "[]")
    return out


# -- pedidos -------------------------------------------------------------------------

def create_order(bot_dir, user, chat_id, items, currency, now=None):
    """``items`` = ``[{"id", "name", "qty", "price", "stars", "type"}]`` (preços em centavos)."""
    total = sum(i["price"] * i["qty"] for i in items)
    stars = sum((i.get("stars") or 0) * i["qty"] for i in items) if all(i.get("stars") for i in items) else None
    with _lock:
        conn = _db(bot_dir)
        while True:
            oid = "".join(secrets.choice(_ALPHABET) for _ in range(8))
            if conn.execute("SELECT 1 FROM orders WHERE id=?", (oid,)).fetchone() is None:
                break
        conn.execute(
            "INSERT INTO orders (id, user_id, chat_id, first_name, last_name, username, items, total, currency,"
            " stars, status, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            (oid, user.get("id"), chat_id, user.get("first_name") or "", user.get("last_name") or "",
             user.get("username") or "", json.dumps(items, ensure_ascii=False), total, currency, stars,
             "pending", int(now or time.time())),
        )
    return get_order(bot_dir, oid)


def get_order(bot_dir, order_id):
    with _lock:
        return _order(_db(bot_dir).execute("SELECT * FROM orders WHERE id=?", (str(order_id),)).fetchone())


def subtotal(order):
    return sum(i["price"] * i["qty"] for i in order["items"])


def _recalc(conn, order_id):
    row = _order(conn.execute("SELECT * FROM orders WHERE id=?", (order_id,)).fetchone())
    if row is None:
        return
    total = max(0, subtotal(row) - int(row.get("discount") or 0)) + int(row.get("shipping") or 0)
    conn.execute("UPDATE orders SET total=? WHERE id=?", (total, order_id))


def set_coupon(bot_dir, order_id, code, discount):
    with _lock:
        conn = _db(bot_dir)
        conn.execute("UPDATE orders SET coupon=?, discount=? WHERE id=? AND status='pending'", (code, int(discount), order_id))
        _recalc(conn, order_id)
    return get_order(bot_dir, order_id)


def set_address(bot_dir, order_id, address, shipping):
    with _lock:
        conn = _db(bot_dir)
        conn.execute("UPDATE orders SET address=?, shipping=? WHERE id=? AND status='pending'",
                     (str(address)[:1000], int(shipping), order_id))
        _recalc(conn, order_id)
    return get_order(bot_dir, order_id)


def coupon_uses(bot_dir, code):
    with _lock:
        return _db(bot_dir).execute("SELECT COUNT(*) FROM orders WHERE coupon=? AND status='paid'", (code,)).fetchone()[0]


def abandoned(bot_dir, older_than, newer_than):
    """Pedidos pendentes criados entre ``newer_than`` e ``older_than`` que ainda não receberam lembrete."""
    with _lock:
        rows = _db(bot_dir).execute(
            "SELECT * FROM orders WHERE status='pending' AND reminded=0 AND created_at<=? AND created_at>=? "
            "AND (gateway IS NULL OR gateway != 'xtr')", (int(older_than), int(newer_than))).fetchall()
    return [_order(r) for r in rows]


def set_reminded(bot_dir, order_id):
    with _lock:
        _db(bot_dir).execute("UPDATE orders SET reminded=1 WHERE id=?", (order_id,))


def set_shipped(bot_dir, order_id, tracking):
    with _lock:
        _db(bot_dir).execute("UPDATE orders SET shipped=? WHERE id=?", (str(tracking or "-")[:200], order_id))


def set_payment(bot_dir, order_id, gateway, ref, url=""):
    with _lock:
        _db(bot_dir).execute("UPDATE orders SET gateway=?, gateway_ref=?, pay_url=? WHERE id=?",
                             (gateway, str(ref), url or "", order_id))


def mark_paid(bot_dir, order_id, gateway=None, ref=None, now=None):
    """``True`` só para quem fez a transição (pedido pendente ou expirado -> pago)."""
    with _lock:
        cur = _db(bot_dir).execute(
            "UPDATE orders SET status='paid', paid_at=?, gateway=COALESCE(?, gateway),"
            " gateway_ref=COALESCE(?, gateway_ref) WHERE id=? AND status IN ('pending', 'expired')",
            (int(now or time.time()), gateway, ref, order_id))
        return cur.rowcount == 1


def set_status(bot_dir, order_id, status, note=None):
    with _lock:
        _db(bot_dir).execute("UPDATE orders SET status=?, note=COALESCE(?, note) WHERE id=?",
                             (status, note, order_id))


def set_delivered(bot_dir, order_id, delivered=True, note=None):
    with _lock:
        _db(bot_dir).execute("UPDATE orders SET delivered=?, note=? WHERE id=?",
                             (1 if delivered else 0, note, order_id))


def pending_with_charge(bot_dir):
    """Pedidos esperando pagamento que já têm cobrança no Mercado Pago ou no Blink."""
    with _lock:
        rows = _db(bot_dir).execute(
            "SELECT * FROM orders WHERE status='pending' AND gateway IN ('mp', 'pix', 'btc', 'stripe', 'paypal')").fetchall()
    return [_order(r) for r in rows]


def expire_old(bot_dir, max_age, now=None):
    """Pedidos pendentes antigos viram ``expired``. Devolve os que expiraram."""
    limit = int(now or time.time()) - max_age
    with _lock:
        conn = _db(bot_dir)
        rows = conn.execute("SELECT * FROM orders WHERE status='pending' AND created_at < ?", (limit,)).fetchall()
        conn.execute("UPDATE orders SET status='expired' WHERE status='pending' AND created_at < ?", (limit,))
    return [_order(r) for r in rows]


def sold_counts(bot_dir):
    """Unidades vendidas (pedidos pagos) por produto, para o estoque."""
    with _lock:
        rows = _db(bot_dir).execute("SELECT items FROM orders WHERE status='paid'").fetchall()
    out = {}
    for r in rows:
        for i in json.loads(r["items"] or "[]"):
            out[i["id"]] = out.get(i["id"], 0) + int(i.get("qty") or 0)
    return out


def list_orders(bot_dir, limit=200):
    with _lock:
        rows = _db(bot_dir).execute("SELECT * FROM orders ORDER BY created_at DESC LIMIT ?", (int(limit),)).fetchall()
    return json.dumps([_order(r) for r in rows], ensure_ascii=False)


def stats(bot_dir, since):
    """Vendas pagas desde ``since``: quantidade e soma por moeda (Stars à parte)."""
    with _lock:
        rows = _db(bot_dir).execute(
            "SELECT currency, gateway, COUNT(*) n, SUM(total) total, SUM(stars) stars FROM orders"
            " WHERE status='paid' AND paid_at >= ? GROUP BY currency, gateway", (int(since),)).fetchall()
    out = {"count": 0, "totals": {}, "stars": 0}
    for r in rows:
        out["count"] += r["n"]
        if r["gateway"] == "xtr":
            out["stars"] += r["stars"] or 0
        else:
            out["totals"][r["currency"]] = out["totals"].get(r["currency"], 0) + (r["total"] or 0)
    return json.dumps(out)


# -- assinaturas -----------------------------------------------------------------------

def extend_subscription(bot_dir, user, chat_id, chat_title, product_id, days, now=None):
    """Cria ou renova. Renovação antes de vencer soma os dias ao vencimento atual."""
    now = int(now or time.time())
    with _lock:
        conn = _db(bot_dir)
        row = conn.execute("SELECT * FROM subscriptions WHERE user_id=? AND chat_id=?",
                           (user.get("id"), chat_id)).fetchone()
        base = row["expires_at"] if row and row["status"] == "active" and row["expires_at"] > now else now
        expires = base + int(days) * 86400
        conn.execute(
            "INSERT INTO subscriptions (user_id, chat_id, product_id, first_name, last_name, username, chat_title,"
            " started_at, expires_at, status, reminded) VALUES (?,?,?,?,?,?,?,?,?,'active',0)"
            " ON CONFLICT(user_id, chat_id) DO UPDATE SET product_id=excluded.product_id,"
            " first_name=excluded.first_name, last_name=excluded.last_name, username=excluded.username,"
            " chat_title=excluded.chat_title, expires_at=excluded.expires_at, status='active', reminded=0,"
            " started_at=CASE WHEN subscriptions.status='active' THEN subscriptions.started_at"
            " ELSE excluded.started_at END",
            (user.get("id"), chat_id, product_id, user.get("first_name") or "", user.get("last_name") or "",
             user.get("username") or "", chat_title or "", now, expires))
        was_active = bool(row and row["status"] == "active" and row["expires_at"] > now)
    return expires, was_active


def get_subscription(bot_dir, user_id, chat_id):
    with _lock:
        row = _db(bot_dir).execute("SELECT * FROM subscriptions WHERE user_id=? AND chat_id=?",
                                   (int(user_id), int(chat_id))).fetchone()
    return dict(row) if row else None


def user_subscriptions(bot_dir, user_id):
    with _lock:
        rows = _db(bot_dir).execute("SELECT * FROM subscriptions WHERE user_id=? ORDER BY expires_at DESC",
                                    (int(user_id),)).fetchall()
    return [dict(r) for r in rows]


def due_subscriptions(bot_dir, now=None):
    now = int(now or time.time())
    with _lock:
        rows = _db(bot_dir).execute("SELECT * FROM subscriptions WHERE status='active' AND expires_at <= ?",
                                    (now,)).fetchall()
    return [dict(r) for r in rows]


def reminders_due(bot_dir, days, now=None):
    now = int(now or time.time())
    with _lock:
        rows = _db(bot_dir).execute(
            "SELECT * FROM subscriptions WHERE status='active' AND reminded=0 AND expires_at > ? AND expires_at <= ?",
            (now, now + int(days) * 86400)).fetchall()
    return [dict(r) for r in rows]


def set_subscription(bot_dir, user_id, chat_id, **fields):
    allowed = {k: v for k, v in fields.items() if k in ("status", "reminded", "expires_at")}
    if not allowed:
        return
    sets = ", ".join("%s=?" % k for k in allowed)
    with _lock:
        _db(bot_dir).execute("UPDATE subscriptions SET %s WHERE user_id=? AND chat_id=?" % sets,
                             tuple(allowed.values()) + (int(user_id), int(chat_id)))


def list_subscriptions(bot_dir):
    with _lock:
        rows = _db(bot_dir).execute(
            "SELECT * FROM subscriptions ORDER BY status='active' DESC, expires_at ASC").fetchall()
    return json.dumps([dict(r) for r in rows], ensure_ascii=False)
