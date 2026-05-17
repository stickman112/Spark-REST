package es.jonaykb.spark_rest;

import net.minecraftforge.common.ForgeConfigSpec;

public class ModConfig {
    public static final ForgeConfigSpec COMMON_CONFIG;

    public static final ForgeConfigSpec.IntValue PORT;
    public static final ForgeConfigSpec.ConfigValue<String> BIND_HOST;
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> ENDPOINT;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("general");

        PORT = builder
                .comment("Port to run the HTTP server on")
                .defineInRange("port", 8080, 0, 65535);
        BIND_HOST = builder
                .comment("Network interface to bind on. Use \"127.0.0.1\" for loopback only (default), or \"0.0.0.0\" to allow external access.")
                .define("bind_host", "127.0.0.1");
        ENDPOINT = builder
                .comment("Endpoint to expose metrics")
                .define("endpoint", "metrics");

        ENABLED = builder
                .comment("Mod is enabled")
                .define("enabled", true);

        builder.pop();

        COMMON_CONFIG = builder.build();
    }
}
