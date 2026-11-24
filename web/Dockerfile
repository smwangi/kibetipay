# syntax=docker/dockerfile:1
# KibetiPay web image. Build from web/:  docker build -t kibetipay-web web
# Needs at runtime: KIBETIPAY_API_URL and SESSION_SECRET (openssl rand -hex 32).

# ---- deps: install exactly what package-lock.json pins ----
FROM node:22-slim AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci

# ---- build: Next.js standalone output (output: "standalone" in next.config.ts) ----
FROM node:22-slim AS build
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

# ---- runtime: server.js plus the files it traced, as the unprivileged "node" user ----
FROM node:22-slim
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0
COPY --from=build --chown=node:node /app/.next/standalone ./
COPY --from=build --chown=node:node /app/.next/static ./.next/static
USER node
EXPOSE 3000
CMD ["node", "server.js"]
