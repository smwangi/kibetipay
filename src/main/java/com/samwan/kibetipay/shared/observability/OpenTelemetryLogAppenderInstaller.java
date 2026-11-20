package com.samwan.kibetipay.shared.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Sends Logback events to the OpenTelemetry SDK, which exports them over OTLP (to Loki in dev)
 * once an endpoint is configured. Each record keeps its traceId/spanId and the SLF4J fluent
 * key-values ({@code event}, {@code transferId}, ...) as searchable attributes.
 *
 * <p>Attached to the root logger here rather than in a {@code logback-spring.xml} so Spring
 * Boot's console configuration (and {@code logging.structured.format.console}) stays intact.
 * Export is batched and asynchronous: a slow or unavailable Collector never blocks a caller.
 */
@Component
class OpenTelemetryLogAppenderInstaller implements InitializingBean {

    private static final String APPENDER_NAME = "OTEL";

    private final OpenTelemetry openTelemetry;

    OpenTelemetryLogAppenderInstaller(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterPropertiesSet() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return;   // not running on Logback
        }
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(APPENDER_NAME) != null) {
            return;
        }
        var appender = new OpenTelemetryAppender();
        appender.setContext(context);
        appender.setName(APPENDER_NAME);
        appender.setOpenTelemetry(openTelemetry);
        appender.setCaptureKeyValuePairAttributes(true);
        appender.setCaptureCodeAttributes(false);
        appender.start();
        root.addAppender(appender);
    }
}
