package es.jonaykb.spark_rest;

import com.mojang.logging.LogUtils;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;

import me.lucko.spark.api.Spark;
import me.lucko.spark.api.gc.GarbageCollector;
import me.lucko.spark.api.statistic.StatisticWindow;
import me.lucko.spark.api.statistic.misc.DoubleAverageInfo;
import me.lucko.spark.api.statistic.types.DoubleStatistic;
import me.lucko.spark.api.statistic.types.GenericStatistic;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;

import com.google.gson.JsonObject;

@Mod("spark_rest")
public class SparkRest {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "::1", "localhost");

    private Spark spark;
    private HttpServer httpServer = null;
    private MinecraftServer server = null;

    public SparkRest() {
        ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.COMMON,
                ModConfig.COMMON_CONFIG);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarting);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
    }

    private void onServerStarting(ServerStartingEvent event) {
        if (!isEnabled()) {
            LOGGER.info("spark_rest is DISABLED via config.");
            return;
        }

        spark = SparkCompat.tryLoad();

        if (spark == null) {
            LOGGER.error("Spark not found! spark_rest will run in DISABLED mode.");
            return;
        }

        server = event.getServer();
        startHttpServer();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        server = null;
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
            LOGGER.info("Spark REST API stopped");
        }
    }

    private void startHttpServer() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(getBindHost(), getPort()), 0);
            httpServer.createContext("/" + getEndpoint(), new MetricsHandler());
            httpServer.setExecutor(null);
            httpServer.start();
            LOGGER.info("Spark REST API started on {}:{}/{}", getBindHost(), getPort(), getEndpoint());

            if (!LOOPBACK_HOSTS.contains(getBindHost().toLowerCase(Locale.ROOT))) {
                LOGGER.warn("Spark REST API is bound to non-loopback address {}:{}.", getBindHost(), getPort());
                LOGGER.warn("This endpoint has no authentication. Anyone able to reach this");
                LOGGER.warn("address can read server performance metrics and player counts.");
                LOGGER.warn("Place it behind a reverse proxy, firewall, or VPN before exposing it.");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to start Spark REST API", e);
        }
    }

    class MetricsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            try {
                if (spark == null) {
                    String response = "Spark not found or not loaded. Wait until the server is fully started.";
                    exchange.sendResponseHeaders(500, response.length());
                    OutputStream os = exchange.getResponseBody();
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                    os.close();
                    return;
                }

                DoubleStatistic<StatisticWindow.TicksPerSecond> tps = spark.tps();
                GenericStatistic<DoubleAverageInfo, StatisticWindow.MillisPerTick> mspt = spark.mspt();
                DoubleStatistic<StatisticWindow.CpuUsage> cpuSystem = spark.cpuSystem();
                DoubleStatistic<StatisticWindow.CpuUsage> cpuProcess = spark.cpuProcess();

                double tpsLast10Secs = tps.poll(StatisticWindow.TicksPerSecond.SECONDS_10);
                double tpsLast1Mins  = tps.poll(StatisticWindow.TicksPerSecond.MINUTES_1);
                double tpsLast5Mins  = tps.poll(StatisticWindow.TicksPerSecond.MINUTES_5);
                double tpsLast15Mins = tps.poll(StatisticWindow.TicksPerSecond.MINUTES_15);

                DoubleAverageInfo msptLastMin = mspt.poll(StatisticWindow.MillisPerTick.MINUTES_1);

                double cpuSystemLastMin  = cpuSystem.poll(StatisticWindow.CpuUsage.MINUTES_1);
                double cpuProcessLastMin = cpuProcess.poll(StatisticWindow.CpuUsage.MINUTES_1);

                MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();

                JsonObject json = new JsonObject();
                json.addProperty("tps_10s", tpsLast10Secs);
                json.addProperty("tps_1m", tpsLast1Mins);
                json.addProperty("tps_5m", tpsLast5Mins);
                json.addProperty("tps_15m", tpsLast15Mins);
                json.addProperty("mspt_1m", msptLastMin.mean());
                json.addProperty("mspt_p95_1m", msptLastMin.percentile95th());
                json.addProperty("mspt_max_1m", msptLastMin.max());
                json.addProperty("cpu", cpuSystemLastMin);
                json.addProperty("cpu_process", cpuProcessLastMin);
                json.addProperty("heap_used_bytes", heap.getUsed());
                json.addProperty("heap_max_bytes", heap.getMax());

                MinecraftServer s = server;
                if (s != null) {
                    try {
                        json.addProperty("players_online", s.getPlayerCount());
                        json.addProperty("players_max", s.getMaxPlayers());
                    } catch (Exception e) {
                        // server.getPlayerList() can return null during the
                        // initialization window between ServerStartingEvent firing
                        // and the player list being fully constructed; getPlayerCount()
                        // then NPEs. Omit the fields rather than 500 the request.
                        LOGGER.debug("Player fields omitted from response: {}", e.toString());
                    }
                }

                JsonObject gcJson = new JsonObject();
                try {
                    for (Map.Entry<String, GarbageCollector> entry : spark.gc().entrySet()) {
                        GarbageCollector gc = entry.getValue();
                        JsonObject gcInfo = new JsonObject();
                        gcInfo.addProperty("count", gc.totalCollections());
                        gcInfo.addProperty("avg_time_ms", gc.avgTime());
                        gcJson.add(entry.getKey(), gcInfo);
                    }
                } catch (Exception e) {
                    // spark-api does not document whether gc() returns a snapshot or a live view;
                    // isolate any iteration failure so it doesn't 500 the whole response.
                    LOGGER.debug("GC fields omitted from response: {}", e.toString());
                }
                json.add("gc", gcJson);

                byte[] response = json.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                OutputStream os = exchange.getResponseBody();
                os.write(response);
                os.close();

            } catch (Exception e) {
                LOGGER.error("Error handling /{} request", getEndpoint(), e);
            }
        }
    }

    private int getPort() {
        return ModConfig.PORT.get();
    }

    private String getBindHost() {
        return ModConfig.BIND_HOST.get();
    }

    private String getEndpoint() {
        return ModConfig.ENDPOINT.get();
    }

    private boolean isEnabled() {
        return ModConfig.ENABLED.get();
    }
}
