package cn.lunadeer.mc.deerfolia.async.path;

import cn.lunadeer.mc.deerfolia.DeerFoliaConfiguration;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import net.minecraft.world.level.pathfinder.Path;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * used to handle the scheduling of async path processing
 */
public class AsyncPathProcessor {
    private static final Executor pathProcessingExecutor = new ThreadPoolExecutor(
            1,
            DeerFoliaConfiguration.asyncPathfinding.asyncPathfindingMaxThreads,
            DeerFoliaConfiguration.asyncPathfinding.asyncPathfindingKeepAlive, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new ThreadFactoryBuilder()
                    .setNameFormat("DeerFolia Async Pathfinding Thread - %d")
                    .setPriority(Thread.NORM_PRIORITY - 2)
                    .build()
    );

    protected static CompletableFuture<Void> queue(@NotNull AsyncPath path) {
        return CompletableFuture.runAsync(path::process, pathProcessingExecutor);
    }

    /**
     * takes a possibly unprocessed path, and waits until it is completed
     * the consumer will be immediately invoked if the path is already processed
     * the consumer will always be called on the main thread
     *
     * @param path            a path to wait on
     * @param afterProcessing a consumer to be called
     */
    public static void awaitProcessing(@Nullable Path path, Consumer<@Nullable Path> afterProcessing) {
        if (path != null && !path.isProcessed() && path instanceof AsyncPath asyncPath) {
            asyncPath.postProcessing(() ->
                    afterProcessing.accept(path)
            );
        } else {
            afterProcessing.accept(path);
        }
    }
}