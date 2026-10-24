"""How the Dell reaches the Mac: Syncthing's view of the shared folder and the connection to the Mac.

Training jobs, datasets and policies travel through Syncthing. When the Dell and the Mac are on the same
network it connects them directly; otherwise it goes through the relay on the Hetzner server (and the
server's discovery service helps them find each other). This module asks the local Syncthing which one
is in use and how far behind the folder is, so the app can show the path a job will take.
"""
from __future__ import annotations

import json
import re
import time
import urllib.request
from pathlib import Path

FOLDER = "mac-dell-shared"


class SyncLink:
    def __init__(self, home: Path):
        self.home = Path(home)
        self._cache = (0.0, None)

    def _config(self) -> tuple[str, str] | None:
        for p in (self.home / ".local/state/syncthing/config.xml", self.home / ".config/syncthing/config.xml"):
            try:
                xml = p.read_text(encoding="utf-8")
            except OSError:
                continue
            key = re.search(r"<apikey>([^<]+)</apikey>", xml)
            gui = re.search(r"<gui[^>]*>.*?<address>([^<]+)</address>", xml, re.S)
            if key:
                return (gui.group(1) if gui else "127.0.0.1:8384"), key.group(1)
        return None

    def _get(self, addr: str, key: str, path: str):
        req = urllib.request.Request(f"http://{addr}{path}", headers={"X-API-Key": key})
        with urllib.request.urlopen(req, timeout=2) as r:
            return json.loads(r.read())

    def _peer(self, addr: str, key: str) -> tuple[str | None, str | None]:
        """The other device sharing the folder (the Mac), found in Syncthing's own config rather than
        written into the code: (device ID, name)."""
        me = self._get(addr, key, "/rest/system/status").get("myID")
        folder = self._get(addr, key, f"/rest/config/folders/{FOLDER}")
        names = {d["deviceID"]: d.get("name", "") for d in self._get(addr, key, "/rest/config/devices")}
        peers = [d["deviceID"] for d in folder.get("devices", []) if d["deviceID"] != me]
        peers.sort(key=lambda d: 0 if "mac" in names.get(d, "").lower() else 1)
        return (peers[0], names.get(peers[0])) if peers else (None, None)

    def status(self) -> dict:
        t, cached = self._cache
        if cached is not None and time.time() - t < 5:
            return cached
        out = {"available": False}
        cfg = self._config()
        if cfg:
            addr, key = cfg
            try:
                peer, peer_name = self._peer(addr, key)
                if peer is None:
                    raise RuntimeError(f"the folder {FOLDER} is not shared with another device")
                conns = self._get(addr, key, "/rest/system/connections")["connections"]
                mac = conns.get(peer, {})
                ctype = mac.get("type", "") or ""
                via = None
                if mac.get("connected"):
                    # Syncthing marks LAN connections isLocal; a relay connection's type says so.
                    via = "server relay" if "relay" in ctype else ("LAN" if mac.get("isLocal") else "internet")
                folder = self._get(addr, key, f"/rest/db/status?folder={FOLDER}")
                comp = self._get(addr, key, f"/rest/db/completion?folder={FOLDER}&device={peer}")
                address = mac.get("address") or None
                if address and (via != "LAN" or address.startswith("relay")):
                    address = None  # a relay or internet address names the server or a public IP: not for the screen
                out = {"available": True, "mac_connected": bool(mac.get("connected")), "via": via, "peer": peer_name,
                       "address": address, "folder_state": folder.get("state"),
                       "need_files": folder.get("needFiles", 0), "need_bytes": folder.get("needBytes", 0),
                       "mac_completion": round(comp.get("completion", 0.0), 1),
                       "mac_need_bytes": comp.get("needBytes", 0)}
            except Exception as e:
                out = {"available": False, "error": str(e)}
        self._cache = (time.time(), out)
        return out
