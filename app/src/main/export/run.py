#!/usr/bin/env python3
"""Roda este bot no computador (ou em qualquer servidor) com o mesmo motor do app.

Requisitos: Python 3.8 ou mais novo. Nenhum pacote extra.

    python3 run.py

O token é lido, nesta ordem, de: variável de ambiente BOT_TOKEN, arquivo
token.txt ao lado deste script, ou digitado no terminal.

ATENÇÃO: o mesmo bot não pode rodar em dois lugares ao mesmo tempo. Desligue-o
no celular antes de ligar aqui.
"""

import json
import os
import signal
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from telebot_engine.engine import Engine  # noqa: E402


class ConsoleCallbacks:
    def onEvent(self, data):  # noqa: N802 - mesmo nome usado pelo app
        e = json.loads(data)
        kind = e.pop("type")
        stamp = time.strftime("%H:%M:%S")
        if kind == "received":
            print("%s <- %s: %s" % (stamp, e.get("user"), e.get("text")))
        elif kind == "replied":
            print("%s -> resposta para /%s" % (stamp, e.get("command")))
        elif kind == "error":
            print("%s !! erro (%s): %s" % (stamp, e.get("code"), e.get("detail")))
        else:
            print("%s .. %s %s" % (stamp, kind, json.dumps(e, ensure_ascii=False)))
        sys.stdout.flush()

    def nowLocal(self):  # noqa: N802
        return time.strftime("%d/%m/%Y %H:%M")


def read_token():
    token = os.environ.get("BOT_TOKEN", "").strip()
    if token:
        return token
    path = os.path.join(HERE, "token.txt")
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as fh:
            return fh.read().strip()
    return input("Token do bot: ").strip()


def main():
    engine = Engine(read_token(), os.path.join(HERE, "config.json"), HERE, ConsoleCallbacks())

    def stop(*_):
        print("Parando…")
        engine.stop()

    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)
    thread = threading.Thread(target=engine.run)
    thread.start()
    while thread.is_alive():
        thread.join(0.5)


if __name__ == "__main__":
    main()
