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

import pytest

from app.bootstrap.settings import Settings
from app.bootstrap.workers import WorkerManager


def test_environment_parsing() -> None:
    configured = Settings(
        CORS_ORIGINS="http://localhost:3000, http://127.0.0.1:3000",
        WORKER_KINDS="document",
    )

    assert configured.cors_origins == [
        "http://localhost:3000",
        "http://127.0.0.1:3000",
    ]
    assert configured.worker_kinds == ("document",)


@pytest.mark.asyncio
async def test_worker_manager_lifecycle() -> None:
    configured = Settings(WORKER_KINDS="document", WORKER_POLL_INTERVAL_SECONDS=0.01)
    manager = WorkerManager(configured)

    await manager.start()
    assert all(state.running for state in manager.states.values())

    await manager.stop()
    assert not any(state.running for state in manager.states.values())


def test_worker_manager_rejects_unknown_worker() -> None:
    configured = Settings(WORKER_KINDS="ocr")

    with pytest.raises(ValueError, match="Unsupported worker kinds"):
        WorkerManager(configured)
