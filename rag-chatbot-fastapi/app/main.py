"""Compatibility entrypoint for the AI API runtime."""

from app.bootstrap.ai_app import app, create_app, lifespan

__all__ = ["app", "create_app", "lifespan"]
