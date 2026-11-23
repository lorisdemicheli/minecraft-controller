# syntax=docker/dockerfile:1

# ---- build -------------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /src

# Dependencies first, so this layer is cached until the lock file changes.
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund

COPY . .
RUN npm run build

# ---- runtime -----------------------------------------------------------
# The server bundle (Express + Angular SSR) is self-contained: only Node and dist/ui are needed.
FROM node:22-alpine
ENV NODE_ENV=production \
    PORT=4000
WORKDIR /app
COPY --from=build --chown=node:node /src/dist/ui ./

# `node` is UID/GID 1000, the same as the chart's securityContext (runAsNonRoot).
USER node
EXPOSE 4000

# The public URL of the backend is read at start-up (GET /config.js), not baked into the image:
#   docker run -p 4000:4000 -e API_BASE_URL=https://api.example.com <image>
CMD ["node", "server/server.mjs"]
