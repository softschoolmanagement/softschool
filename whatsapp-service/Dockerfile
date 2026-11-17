FROM node:22-alpine
WORKDIR /app
COPY package*.json ./
RUN npm install --omit=dev
COPY . .
VOLUME /app/sessions
ENV HOST=0.0.0.0
EXPOSE 3001
CMD ["node", "server.js"]
