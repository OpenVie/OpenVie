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
