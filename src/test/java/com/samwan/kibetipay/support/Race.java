package com.samwan.kibetipay.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * Runs tasks that are released at the same instant from a start gate, so they genuinely contend
 * for the same rows. Each result slot holds what the task returned or the exception it threw.
 */
public final class Race {

    private Race() {}

    public static List<Object> run(int tasks, int threads, IntFunction<Callable<?>> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            var gate = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                Callable<?> body = task.apply(i);
                futures.add(pool.submit(() -> {
                    gate.await();
                    try {
                        return body.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            gate.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (var f : futures) {
                outcomes.add(f.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    public static <T> List<T> successes(List<Object> outcomes, Class<T> type) {
        return outcomes.stream().filter(type::isInstance).map(type::cast).toList();
    }

    public static List<Throwable> failures(List<Object> outcomes) {
        return outcomes.stream().filter(Throwable.class::isInstance).map(Throwable.class::cast).toList();
    }
}
