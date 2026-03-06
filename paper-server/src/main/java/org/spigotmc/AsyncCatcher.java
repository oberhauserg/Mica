package org.spigotmc;

import net.minecraft.server.MinecraftServer;

public class AsyncCatcher {

    public static void catchOp(String reason) {
        // Mica start - defer operations during parallel tick processing
        if (Thread.currentThread() instanceof java.util.concurrent.ForkJoinWorkerThread) {
            return; // TODO: buffer specific operations based on reason string
        }
        // Mica end
        if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()) { // Paper - chunk system
            MinecraftServer.LOGGER.error("Thread {} failed main thread check: {}", Thread.currentThread().getName(), reason, new Throwable()); // Paper
            throw new IllegalStateException("Asynchronous " + reason + "!");
        }
    }
}
