FROM node:24-alpine
WORKDIR /app
COPY server.mjs ./server.mjs
ENV HOST=0.0.0.0 PORT=8787
EXPOSE 8787
USER node
CMD ["node", "server.mjs"]
