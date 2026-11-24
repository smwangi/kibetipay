import { registerOTel } from "@vercel/otel";

/**
 * OpenTelemetry for the Next.js server (see node_modules/next/dist/docs/01-app/02-guides/open-telemetry.md).
 *
 * Next.js already creates a root span per request plus route-handler and fetch spans. This adds:
 * - export over OTLP when OTEL_EXPORTER_OTLP_ENDPOINT is set (http://localhost:4318 for the compose
 *   otel-lgtm container); without it spans are created but not exported, as in the backend;
 * - W3C `traceparent` on calls to the KibetiPay API only, so a page or /api/kp request and the
 *   Spring Boot work it triggers form one trace. Other hosts never receive our trace context.
 *
 * No request headers are recorded as attributes: calls to the API carry the session's Basic
 * credentials. Sampling follows OTEL_TRACES_SAMPLER / OTEL_TRACES_SAMPLER_ARG (default: all);
 * the backend's parent-based sampler then follows whatever this service decided.
 */
export function register() {
  const apiUrl = (process.env.KIBETIPAY_API_URL ?? "http://localhost:8080").replace(/\/$/, "");
  registerOTel({
    serviceName: "kibetipay-web",
    instrumentationConfig: {
      fetch: {
        propagateContextUrls: [`${apiUrl}/`],
      },
    },
  });
}
