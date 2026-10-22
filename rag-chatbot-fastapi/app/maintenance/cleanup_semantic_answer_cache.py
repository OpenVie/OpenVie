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

from __future__ import annotations

from dataclasses import asdict

from app.modules.generation.api import GenerationCacheMaintenanceApi


async def run(
    maintenance: GenerationCacheMaintenanceApi, *, apply: bool, max_batches: int
) -> dict[str, int | bool]:
    return asdict(
        await maintenance.cleanup_expired(max_batches=max_batches, apply=apply)
    )
