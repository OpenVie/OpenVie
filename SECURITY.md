# Security policy

Do not post a vulnerability, customer data, production credentials, or a working exploit in a
public issue. Use GitHub's **Report a vulnerability** link on this repository's Security tab
(private vulnerability reporting) when the repository owner has enabled it. If it is not
available, contact the maintainers privately through a channel published by the repository
owner before disclosing details. Maintainers must enable a private reporting channel before
publishing the repository; this file does not invent an unmonitored security email address.

Include the affected revision, the trust boundary crossed, steps to reproduce with synthetic
data, expected and actual behavior, and whether credentials or tenant data might be exposed.
Do not attach real tenant documents or live keys. There is no guaranteed response time or
supported-version list yet.

For deployments, protect PostgreSQL, Redis, RabbitMQ, Qdrant, SeaweedFS, Kuzu, and inference
services from public access: the delivered Compose file publishes development ports on the host,
and no reverse proxy, TLS termination, or edge filtering is delivered with this release — put
your own ingress in front of the console and API. Review the configured generation provider
before using confidential sources: an external provider may receive document excerpts. The
repository does not certify a deployment for regulated or government data. See
[deployment](docs/DEPLOYMENT.md) and [architecture](docs/ARCHITECTURE.md).
