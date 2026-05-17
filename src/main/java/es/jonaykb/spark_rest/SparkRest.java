package es.jonaykb.spark_rest;

import com.mojang.logging.LogUtils;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;

import me.lucko.spark.api.Spark;
import me.lucko.spark.api.statistic.StatisticWindow;
import me.lucko.spark.api.statistic.misc.DoubleAverageInfo;
import me.lucko.spark.api.statistic.types.DoubleStatistic;
import me.lucko.spark.api.statistic.types.GenericStatistic;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;

import com.google.gson.JsonObject;

@Mod("spark_rest")
public class SparkRest {

    private Spark spark;
    private static final Logger LOGGER = LogUtils.getLogger();
    private HttpServer server = null;

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

        startHttpServer();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (server != null) {
            server.stop(0);
            server = null;
            LOGGER.info("Spark REST API stopped");
        }
    }

    private void startHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(getBindHost(), getPort()), 0);
            server.createContext("/" + getEndpoint(), new MetricsHandler());
            server.setExecutor(null);
            server.start();
            LOGGER.info("Spark REST API started on {}:{}/{}", getBindHost(), getPort(), getEndpoint());
        } catch (Exception e) {
            LOGGER.error("Failed to start Spark REST API", e);
        }
    }

    class MetricsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            try {
                if(spark == null) {
                    String response = "Spark not found or not loaded. Wait until the server is fully started.";
                    exchange.sendResponseHeaders(500, response.length());
                    OutputStream os = exchange.getResponseBody();
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                    os.close();
                    return;
                }
                // Get the TPS statistic (will be null on platforms that don't have ticks!)
                DoubleStatistic<StatisticWindow.TicksPerSecond> tps = spark.tps();
                GenericStatistic<DoubleAverageInfo, StatisticWindow.MillisPerTick> mspt = spark.mspt();
                DoubleStatistic<StatisticWindow.CpuUsage> cpuUsage = spark.cpuSystem();

                // Retrieve the average TPS in the last 10 seconds / 5 minutes
                double tpsLast10Secs = tps.poll(StatisticWindow.TicksPerSecond.SECONDS_10);
                double tpsLast1Mins = tps.poll(StatisticWindow.TicksPerSecond.MINUTES_1);
                double tpsLast5Mins = tps.poll(StatisticWindow.TicksPerSecond.MINUTES_5);
                double tpsLast15Mins = tps.poll(StatisticWindow.TicksPerSecond.MINUTES_15);

                DoubleAverageInfo msptLastMin = mspt.poll(StatisticWindow.MillisPerTick.MINUTES_1);

                double usageLastMin = cpuUsage.poll(StatisticWindow.CpuUsage.MINUTES_1);

                JsonObject json = new JsonObject();
                json.addProperty("tps_10s", tpsLast10Secs);
                json.addProperty("tps_1m", tpsLast1Mins);
                json.addProperty("tps_5m", tpsLast5Mins);
                json.addProperty("tps_15m", tpsLast15Mins);
                json.addProperty("mspt_1m", msptLastMin.mean());
                json.addProperty("cpu", usageLastMin);

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
