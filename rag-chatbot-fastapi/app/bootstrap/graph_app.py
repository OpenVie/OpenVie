"""Composition root for the graph HTTP role."""

from functools import lru_cache

from app.bootstrap.settings import settings
from app.modules.graph.internal.service import KuzuGraphRepository
from app.modules.graph.transport.http import create_graph_app as _create_graph_app


@lru_cache
def repository() -> KuzuGraphRepository:
    return KuzuGraphRepository(settings.KUZU_DATABASE_PATH)


def create_graph_app():  # type: ignore[no-untyped-def]
    return _create_graph_app(
        repository=repository(),
        internal_token=settings.GRAPH_INTERNAL_TOKEN,
        database_path=settings.KUZU_DATABASE_PATH,
    )


app = create_graph_app()
