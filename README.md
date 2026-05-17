# Spark-REST

A lightweight Forge mod for **Minecraft 1.20.1**, created by **JonayKB**, that exposes server performance metrics from the **Spark** profiler through a simple REST API.

This mod is intended for server administrators who want to monitor TPS, MSPT, CPU usage, and more using external dashboards or monitoring tools.

---

## 📌 Features

* Exposes Spark performance data over HTTP.
* Returns TPS (10s, 1m, 5m, 15m) and MSPT (mean, p95, max over 1m).
* Returns system and JVM process CPU usage.
* Returns JVM heap used / max in bytes.
* Returns live player count and server slot limit.
* Returns per-collector GC stats (collection count and average pause time).
* Configurable bind interface, port, and endpoint path.
* Optional enable/disable through configuration.
* Lightweight and safe to use.
* Defaults to loopback-only deployment; see [Security](#-security--threat-model) before exposing it off-host.

---

## 📦 Requirements

* **Minecraft Forge 1.20.1**
* **Spark mod** installed on the server
* Java 17+

> ⚠️ Spark-REST does *not* include Spark. You must install Spark separately.

---

## ⚙️ Installation

1. Download `spark-rest.jar`.
2. Place it in your server's `/mods/` folder.
3. Ensure that **Spark** is also installed.
4. Start the server to generate the configuration file.

---

## 📝 Configuration

After the first launch, a config file is generated at:

```
config/spark_rest-common.toml
```

### Example:

```
[general]
port = 8080
bind_host = "127.0.0.1"
endpoint = "metrics"
enabled = true
```

### Options

| Setting     | Description                                                                                                | Default     |
| ----------- | ---------------------------------------------------------------------------------------------------------- | ----------- |
| `port`      | TCP port on which the HTTP server runs.                                                                    | 8080        |
| `bind_host` | Network interface to bind on. Use `127.0.0.1` for loopback-only (default), or `0.0.0.0` for any interface. | "127.0.0.1" |
| `endpoint`  | URL path used for exposing the metrics (no leading slash).                                                 | "metrics"   |
| `enabled`   | Enables or disables the REST API.                                                                          | true        |

> **Note:** `bind_host` defaults to `127.0.0.1`. Set to `0.0.0.0` only after reading the [Security](#-security--threat-model) section.

---

## 🔗 REST API Usage

Once the server is running, Spark-REST exposes metrics at:

```
http://<bind_host>:<port>/<endpoint>
```

### Example:

```
http://127.0.0.1:8080/metrics
```

### Example JSON Response:

```json
{
  "tps_10s": 20.0,
  "tps_1m": 19.95,
  "tps_5m": 19.87,
  "tps_15m": 19.76,
  "mspt_1m": 12.4,
  "mspt_p95_1m": 18.2,
  "mspt_max_1m": 47.1,
  "cpu": 0.452,
  "cpu_process": 0.231,
  "heap_used_bytes": 4831838208,
  "heap_max_bytes": 17179869184,
  "players_online": 3,
  "players_max": 20,
  "gc": {
    "G1 Young Generation": { "count": 142, "avg_time_ms": 8.4 },
    "G1 Old Generation":   { "count": 1,   "avg_time_ms": 31.0 }
  }
}
```

### Field semantics

| Field             | Meaning                                                                                                                                                                                              |
| ----------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `tps_10s`         | Ticks per second averaged over the last 10 seconds. Healthy is 20.0 (Minecraft's target tick rate); lower means the server is missing ticks.                                                         |
| `tps_1m`          | Same, 1-minute window. Smoother and slower to react than `tps_10s`.                                                                                                                                  |
| `tps_5m`          | Same, 5-minute window.                                                                                                                                                                               |
| `tps_15m`         | Same, 15-minute window.                                                                                                                                                                              |
| `mspt_1m`         | Mean milliseconds per tick (MSPT) over the last minute. Minecraft runs at 20 ticks per second, so each tick has a 50ms budget; sustained values above 50 mean TPS will fall.                         |
| `mspt_p95_1m`     | 95th-percentile MSPT over the last minute. Most ticks finish faster than this; catches spikes the mean smooths away.                                                                                 |
| `mspt_max_1m`     | Slowest single-tick MSPT in the last minute. Worst-case latency for that window; use to size your alerting threshold.                                                                                |
| `cpu`             | Host machine's overall CPU usage averaged over the last minute, as a fraction (0.0 = idle, 1.0 = all cores saturated). Includes everything on the box, not just the JVM.                             |
| `cpu_process`     | This JVM process's share of CPU averaged over the last minute (0.0 to 1.0). When close to `cpu`, Minecraft is the dominant load; when much smaller, something else on the host is competing.         |
| `heap_used_bytes` | Current JVM heap usage in bytes. Compare against `heap_max_bytes` for headroom.                                                                                                                      |
| `heap_max_bytes`  | JVM heap ceiling in bytes (typically what `-Xmx` sets).                                                                                                                                              |
| `players_online`  | Players currently connected. **Omitted** from the response if the server has not finished initialising.                                                                                              |
| `players_max`     | Server slot limit (from `server.properties`). Omitted under the same conditions as `players_online`.                                                                                                 |
| `gc`              | Object keyed by garbage collector name (e.g. `"G1 Young Generation"`). Each value has `count` (total collections since JVM start) and `avg_time_ms` (average wall-clock time per collection). Counters reset on JVM restart, so plot `avg_time_ms` as an absolute rather than computing rates from `count`. |

---

## 🔒 Security / Threat model

### Why is there no authentication?

The intended deployment is a local collector process running on the same host as the Minecraft server, scraping `127.0.0.1:8080`. A shared secret in `spark_rest-common.toml` would protect nothing in that topology: anything on the host that can read the config file can already curl the loopback endpoint. Adding a bearer token would be ceremony, not security. If your deployment looks different, see the options below.

### Deployment assumptions that make "no auth" safe

* `bind_host` defaults to `127.0.0.1`, so the endpoint is unreachable from outside the host.
* The only intended consumer is a process running on the same host as the server.
* The response contains performance metrics and player counts, not credentials, chat, or world data.
* The host is trusted infrastructure that you control. If unprivileged users can run code on the host, the threat model is already broken in larger ways than this endpoint.

### If you want to expose the endpoint beyond the host

Pick the option that matches your topology. Do not just flip `bind_host` to `0.0.0.0` and call it done.

* **Reverse proxy with auth and TLS.** Run Caddy, nginx, or Traefik on the same host, point it at `127.0.0.1:8080`, terminate TLS, and enforce auth (basic auth, an API key header, mTLS, OAuth proxy, whatever fits). The mod stays loopback-only; the proxy is the only thing reachable from the network.
* **VPN-only access.** Bind to the IP of a VPN interface (WireGuard, Tailscale) and rely on the VPN for both transport encryption and access control. The endpoint is invisible to the public internet.
* **Host firewall scoped to known collectors.** Bind to `0.0.0.0` and use the OS firewall (Windows Defender Firewall, iptables, ufw) to allow inbound traffic on the port only from specific source IPs. Lowest-effort option when the collector hosts are static.
* **Fork and add auth.** If none of the above fit, add bearer-token, API-key, or mTLS auth to `MetricsHandler`. The handler has the obvious extension point at the top of `handle()`. If you'd like the mod to grow this natively, open an issue in this repo first so we can compare notes on the threat model you're protecting against.

If you do change `bind_host` to a non-loopback address, the mod logs a startup WARN to flag that the endpoint is reachable from off-host without authentication. The warning is informational, not a gate; the mod will still start.

---

## ❓ FAQ

### **Q: Spark-REST returns an error / no metrics**

A: Ensure the **Spark** mod is installed. Spark-REST depends on Spark's API.

### **Q: The port is already in use**

A: Change the `port` value in the config file.

---

## 🧑‍💻 Author

**JonayKB**

If you enjoy the mod, feel free to report issues or suggest improvements!

**Fork maintained by stickman112.** See LICENSE for original authorship.

---

## 📄 License
[License](LICENSE)
