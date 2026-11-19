import type { NextConfig } from "next";
import { networkInterfaces } from "node:os";

/**
 * `next dev` prints a "Network:" URL (this machine's LAN address) but, by default, blocks its own
 * dev assets for any host other than localhost, so pages opened that way never become interactive.
 * Allow this machine's own addresses, plus any extra hosts in KIBETIPAY_DEV_ORIGINS (comma-separated).
 */
const ownAddresses = Object.values(networkInterfaces())
  .flat()
  .filter((net) => net && !net.internal && net.family === "IPv4")
  .map((net) => net!.address);
const extraOrigins = (process.env.KIBETIPAY_DEV_ORIGINS ?? "").split(",").map((h) => h.trim()).filter(Boolean);

const nextConfig: NextConfig = {
  cacheComponents: true,
  partialPrefetching: true,
  allowedDevOrigins: [...ownAddresses, ...extraOrigins],
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default nextConfig;
