#!/usr/bin/env bash
# Build all documentation PDFs from their Markdown sources.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

if ! command -v pandoc >/dev/null 2>&1; then
    echo "Error: pandoc is required to build documentation PDFs" >&2
    exit 1
fi

if ! command -v tectonic >/dev/null 2>&1; then
    echo "Error: tectonic is required to build documentation PDFs" >&2
    exit 1
fi

cd "$PROJECT_ROOT"

build_pdf() {
    local source="$1"
    local output="${source%.md}.pdf"
    local metadata="app/docs/readme-meta.yaml"

    if [[ "$source" == *"lifecycle"* ]]; then
        metadata="app/docs/lifecycle-meta.yaml"
    elif [[ "$source" == *"ui"* ]]; then
        metadata="app/docs/ui-meta.yaml"
    elif [[ "$source" == *"multi"* ]]; then
        metadata="app/docs/multi-meta.yaml"
    elif [[ "$source" == *"bt"* ]]; then
        metadata="app/docs/bt-meta.yaml"
    elif [[ "$source" == *"run"* ]]; then
        metadata="app/docs/run-meta.yaml"
    fi

    pandoc "$source" \
        --metadata-file="$metadata" \
        --template=app/docs/appnote-template.latex \
        $([[ "$source" == *"lifecycle"* ]] && echo '--lua-filter=app/docs/mermaid-filter.lua') \
        --pdf-engine=tectonic \
        --toc --toc-depth=2 \
        -o "$output"

    echo "Built $output"
}

if [[ -f "README.md" ]]; then
    build_pdf "README.md"
fi

for source in app/docs/*.md; do
    if [[ -f "$source" ]]; then
        build_pdf "$source"
    fi
done
