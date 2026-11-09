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

import json
import subprocess
import sys

def main():
    if len(sys.argv) < 3:
        print("Usage: python clean_docker_volumes.py <env_file> <compose_file>")
        sys.exit(1)

    env_file = sys.argv[1]
    compose_file = sys.argv[2]
    
    # Get docker compose config to resolve actual volume names
    try:
        output = subprocess.check_output(
            ['docker', 'compose', '--env-file', env_file, '-f', compose_file, 'config', '--format', 'json']
        )
        data = json.loads(output.decode('utf-8'))
    except Exception as e:
        print(f"Failed to get docker compose config: {e}")
        sys.exit(1)

    # The volumes we want to reset
    target_keys = [
        "postgres_data", 
        "qdrant_data", 
        "kuzu_data", 
        "seaweedfs_master", 
        "seaweedfs_volume", 
        "seaweedfs_filer"
    ]

    # Iterate and remove the targeted volumes
    volumes = data.get('volumes', {})
    for key in target_keys:
        if key in volumes:
            vol_name = volumes[key].get('name')
            if vol_name:
                # We suppress stdout/stderr to mimic the behavior of `> /dev/null 2>&1`
                subprocess.call(
                    ['docker', 'volume', 'rm', vol_name],
                    stdout=subprocess.DEVNULL, 
                    stderr=subprocess.DEVNULL
                )

if __name__ == "__main__":
    main()
