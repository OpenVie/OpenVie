# Copyright 2026 LinkedNodeDigital
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

FROM node:22-alpine AS dependencies
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM node:22-alpine AS build
WORKDIR /app
COPY --from=dependencies /app/node_modules node_modules
COPY . .
ARG NEXT_PUBLIC_API_BASE_URL=http://localhost/api/v1
ARG NEXT_PUBLIC_WIDGET_URL=http://localhost/widget/v1/openvie-chat.js
ARG NEXT_PUBLIC_DEFAULT_LOCALE=vi-VN
ARG NEXT_PUBLIC_SITE_URL=http://localhost:3000
ARG NEXT_PUBLIC_OIDC_ISSUER=http://localhost:18080/realms/openvie
ARG NEXT_PUBLIC_OIDC_CLIENT_ID=openvie-frontend
ARG NEXT_PUBLIC_TURNSTILE_SITE_KEY=
ARG NEXT_PUBLIC_RECRUITMENT_ENABLED=false
ARG NEXT_PUBLIC_PLATFORM_ADMINISTRATION_ENABLED=false
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL
ENV NEXT_PUBLIC_WIDGET_URL=$NEXT_PUBLIC_WIDGET_URL
ENV NEXT_PUBLIC_DEFAULT_LOCALE=$NEXT_PUBLIC_DEFAULT_LOCALE
ENV NEXT_PUBLIC_SITE_URL=$NEXT_PUBLIC_SITE_URL
ENV NEXT_PUBLIC_OIDC_ISSUER=$NEXT_PUBLIC_OIDC_ISSUER
ENV NEXT_PUBLIC_OIDC_CLIENT_ID=$NEXT_PUBLIC_OIDC_CLIENT_ID
ENV NEXT_PUBLIC_TURNSTILE_SITE_KEY=$NEXT_PUBLIC_TURNSTILE_SITE_KEY
ENV NEXT_PUBLIC_RECRUITMENT_ENABLED=$NEXT_PUBLIC_RECRUITMENT_ENABLED
ENV NEXT_PUBLIC_PLATFORM_ADMINISTRATION_ENABLED=$NEXT_PUBLIC_PLATFORM_ADMINISTRATION_ENABLED
RUN npm run build

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app/package.json /app/package-lock.json ./
COPY --from=build /app/.next .next
COPY --from=build /app/public public
COPY --from=dependencies /app/node_modules node_modules
COPY LICENSE NOTICE /licenses/
CMD ["npm", "run", "start"]
