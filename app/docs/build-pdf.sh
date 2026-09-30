#!/usr/bin/env bash
# Build all documentation PDFs from their Markdown sources.
set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v pandoc >/dev/null 2>&1; then
    echo "Error: pandoc is required to build documentation PDFs" >&2
    exit 1
fi

if ! command -v tectonic >/dev/null 2>&1; then
    echo "Error: tectonic is required to build documentation PDFs" >&2
    exit 1
fi

build_pdf() {
    local source="$1"
    local output="${source%.md}.pdf"
    if [[ "$source" == "lifecycle.md" || "$source" == "ui.md" || "$source" == "README.md" || "$source" == "multi.md" || "$source" == "bt.md" || "$source" == "run.md" ]]; then
        local metadata="docs/lifecycle-meta.yaml"
        if [[ "$source" == "ui.md" ]]; then
            metadata="docs/ui-meta.yaml"
        elif [[ "$source" == "README.md" ]]; then
            metadata="docs/readme-meta.yaml"
        elif [[ "$source" == "multi.md" ]]; then
            metadata="docs/multi-meta.yaml"
        elif [[ "$source" == "bt.md" ]]; then
            metadata="docs/bt-meta.yaml"
        elif [[ "$source" == "run.md" ]]; then
            metadata="docs/run-meta.yaml"
        fi
        pandoc "$source" \
            --metadata-file="$metadata" \
            --template=docs/appnote-template.latex \
            $([[ "$source" == "lifecycle.md" ]] && echo '--lua-filter=docs/mermaid-filter.lua') \
            --pdf-engine=tectonic \
            --toc --toc-depth=2 \
            -o "$output"
    else
        pandoc "$source" \
            --include-in-header=docs/appnote-style.tex \
            --pdf-engine=tectonic \
            --toc --toc-depth=2 \
            -o "$output"
    fi
    echo "Built $output"
}

for source in lifecycle.md ui.md multi.md bt.md README.md run.md design.md; do
    if [[ -f "$source" ]]; then
        build_pdf "$source"
    fi
done
