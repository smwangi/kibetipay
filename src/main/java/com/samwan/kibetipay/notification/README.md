# Notifications

This module builds an in-app inbox from lending events. `LendingNotifications` listens for loan disbursement, decline, repayment received and full repayment. It inserts a message in `notification_message` using a unique event key so redelivery does not create a duplicate. Listeners run through the Modulith event publication mechanism; delivery is asynchronous, so messages may appear after the originating operation.

`GET /api/notifications` lists at most the 100 most recent messages for the authenticated recipient. `POST /api/notifications/{id}/read` sets `read_at` once and is scoped to that recipient; an unknown or another user's message returns not found. V4 creates the table and recipient index.

Messages are in-app records. There is no SMS gateway, delivery receipt, scheduled due-date reminder, agent notification or notification preference management. A successful insert means the inbox record exists, not that a user has seen it. `read_at` records an explicit read action.
