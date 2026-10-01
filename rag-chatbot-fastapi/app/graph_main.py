"""Compatibility entrypoint for the graph HTTP runtime."""

from app.bootstrap.graph_app import app, create_graph_app

__all__ = ["app", "create_graph_app"]
