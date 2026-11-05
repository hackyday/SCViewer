#!/usr/bin/env python3
"""Trova i contesti di esecuzione (frame) e valuta in quello vixcloud."""
import json, sys, websocket

WS = None
_next_id = 1

def call(method, params=None, ws=None):
    global _next_id
    ws = ws or WS
    msg_id = _next_id; _next_id += 1
    ws.send(json.dumps({"id": msg_id, "method": method, "params": params or {}}))
    while True:
        resp = json.loads(ws.recv())
        if resp.get("id") == msg_id:
            return resp

def main(port=9222):
    global WS
    import urllib.request, websocket
    targets = json.load(urllib.request.urlopen(f"http://localhost:{port}/json/list"))
    page = next(t for t in targets if t["type"] == "page")
    WS = websocket.create_connection(page["webSocketDebuggerUrl"], timeout=25, suppress_origin=True)
    # raccogli i contesti
    contexts = []
    raw = call("Runtime.enable")
    # gli eventi executionContextCreated sono già passati: forzali con una navigazione? No:
    # usa Runtime.evaluate nel main per elencare i frame via DOM
    # alternativa: contexts noti solo via eventi. Fallback: Page.getFrameTree
    ft = call("Page.getFrameTree")
    def walk(node, out):
        f = node["frame"]
        out.append((f["id"], f.get("url", "")[:90]))
        for c in node.get("childFrames", []) or []:
            walk(c, out)
    frames = []
    walk(ft["result"]["frameTree"], frames)
    print("FRAMES:", frames)
    # per ogni frame non-main, attach e valuta
    for fid, url in frames:
        if "vixcloud" not in url: continue
        at = call("Target.attachToTarget", {"targetId": fid, "flatten": True})
        sid = at["result"]["sessionId"]
        r = call("Runtime.evaluate", {
            "expression": "(function(){ var vs = document.querySelectorAll('video'); var out = []; for (var i=0;i<vs.length;i++){ var v = vs[i]; var r = v.getBoundingClientRect(); out.push({dur: Math.round((v.duration||0)*10)/10, ct: Math.round((v.currentTime||0)*10)/10, paused: v.paused, ready: v.readyState, w: Math.round(r.width), h: Math.round(r.height)}); } return JSON.stringify(out); })()",
            "returnByValue": True
        }, ws=WS if False else None)  # con flatten, la sessione va indicata
        print("VIXCLOUD:", r)

if __name__ == "__main__":
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 9222)
