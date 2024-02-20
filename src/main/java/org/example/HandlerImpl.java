package org.example;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class HandlerImpl implements Handler {

    private final Client client;
    private final ExecutorService executorService;
    private int timeout = 15;

    public HandlerImpl(Client client, int threadCount) {
        this.client = client;
        this.executorService = Executors.newFixedThreadPool(threadCount);
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    @Override
    public ApplicationStatusResponse performOperation(String id) {
        AtomicInteger retriesCount =  new AtomicInteger(0);
        Instant startTime = Instant.now();
        AtomicReference<Instant> lastRequestTime = new AtomicReference<>();

        while (true) {
            try {
                Callable<Response> task1 = () -> {
                    Response response = client.getApplicationStatus1(id);
                    lastRequestTime.set(Instant.now());
                    return response;
                };
                Callable<Response> task2 = () -> {
                    Response response = client.getApplicationStatus2(id);
                    lastRequestTime.set(Instant.now());
                    return response;
                };
                List<Future<Response>> futures = executorService.invokeAll(List.of(task1, task2),  timeout,
                        TimeUnit.SECONDS);

                for (Future<Response> future : futures) {
                    Response response = future.get();
                    if (response instanceof Response.Success successResponse) {
                        return new ApplicationStatusResponse.Success(successResponse.applicationId(),
                                successResponse.applicationStatus());
                    } else if (response instanceof Response.Failure) {
                        retriesCount.getAndIncrement();
                        return new ApplicationStatusResponse.Failure(lastRequestTime.get() != null ?
                                Duration.between(startTime, lastRequestTime.get()) : null, retriesCount.get());
                    } else {
                        Response.RetryAfter retryAfter = (Response.RetryAfter) response;
                        Thread.sleep(retryAfter.delay().toMillis());
                        retriesCount.getAndIncrement();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ApplicationStatusResponse.Failure(lastRequestTime.get() != null ?
                        Duration.between(startTime, lastRequestTime.get()) : null, retriesCount.get());
            } catch (ExecutionException e) {
                return new ApplicationStatusResponse.Failure(lastRequestTime.get() != null ?
                        Duration.between(startTime, lastRequestTime.get()) : null, retriesCount.get());
            }
        }
    }
}
