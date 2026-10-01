# Contributing to OpenVie

OpenVie accepts contributions to the Apache-2.0 application in this repository. Your rights to
use, modify, and distribute the code come from the [Apache License 2.0](LICENSE), not from any
service or agreement.

## Before submitting

- Read [local development](docs/DEVELOPMENT.md), the [architecture](docs/ARCHITECTURE.md),
  and the relevant language-specific guide in `api/` or `rag-chatbot-fastapi/`.
- Open an issue or discussion for changes to public contracts, storage ownership, tenant
  isolation, or model-data handling before writing a large patch.
- Use data, model weights, assets, and code you have permission to contribute. Never include
  customer documents, personal information, API tokens, local `.env` files, or model checkpoints
  in issues, pull requests, tests, or screenshots. Use invented fixtures.
- By submitting a contribution for inclusion, you agree to the contribution terms in section 5
  of the [Apache License 2.0](LICENSE). Ensure your employer or other rights holders authorize it.
  No separate contributor license agreement is required by this repository.

## Pull requests

1. Keep changes scoped and explain the user-visible behavior and any compatibility impact.
2. Preserve the Spring/PostgreSQL authoritative business boundary and Python-derived index
   boundary. Never trust a tenant ID supplied by an unauthenticated client.
3. Update contracts and both consumers together for wire-format changes; document new or
   removed capabilities without describing proposed work as shipped.
4. Run the checks appropriate to the touched modules in [local development](docs/DEVELOPMENT.md#verification).
   For safety or citation changes, include a test demonstrating the denied and allowed paths.
5. Update relevant documentation and describe any runtime/provider check you could not run.

Report security problems privately, not in a public issue; see [SECURITY.md](SECURITY.md).
