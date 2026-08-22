#!/usr/bin/env bash
set -euo pipefail

zip_path="${1:-glass3_sdk_demo.zip}"
output_dir="${2:-.}"

if [ ! -f "$zip_path" ]; then
  echo "Demo package not found: $zip_path"
  exit 1
fi

unzip "$zip_path" -d "$output_dir"
echo "Demo package extracted to: $output_dir/glass3_sdk_demo"
