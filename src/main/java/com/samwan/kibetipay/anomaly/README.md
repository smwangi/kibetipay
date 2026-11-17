# Anomaly detection

The anomaly module listens for `TransferCompleted` and maintains a sender's daily outgoing transfer total. It compares the current day's running total with prior active-day totals in the previous 90 days, using a z-score and a standard-deviation floor. Days with no projection row are not included in the baseline. The default minimum history is seven days and threshold is 3.0, configurable under `app.anomaly`.

`anomaly_processed_transfer` deduplicates event redelivery; `anomaly_daily_outflow` stores the projection and `anomaly_alert` stores raised alerts. Dates use Africa/Nairobi. On a threshold breach the module emits [AnomalyDetected](AnomalyEvents.java). The implementation checks for an existing alert to limit repeated alerts; it is an alerting heuristic, not a payment block or a guarantee of fraud detection.

`GET /api/anomalies/me` returns the caller's alerts; `GET /api/admin/anomalies` is restricted to administrators. Review `BaselineTest` for statistical behavior and the listener for event processing. There is no model-training pipeline, automatic resolution, or SMS alert delivery.
