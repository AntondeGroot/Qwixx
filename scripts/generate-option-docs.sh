#!/bin/bash
# Regenerates the game-option preview images (docs/option-previews/*.png) and the auto-generated
# options block in README.md by running the OptionPreviewGeneratorIT against the real Spring-served
# SPA. Because it screenshots the live components, the images always track the current styling.
#
# Previews are rendered with a fixed random seed (see ConfigurableGameStyleFactory.deterministic),
# so the images are reproducible run-to-run on the same machine — which is what lets the pre-push
# hook regenerate and diff them. Run this whenever you change an option's look or add an option,
# then commit the result. (Screenshots still differ across machines/OS, so generation is local:
# CI does not regenerate or commit these files.)
set -e

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT/server"

echo "🖼  Generating option previews + README block and the mini-sheet doc (builds the Angular bundle, boots Spring, screenshots)…"
./mvnw -B test-compile failsafe:integration-test failsafe:verify \
  -Dit.test=OptionPreviewGeneratorIT,MiniSheetPreviewGeneratorIT \
  -Dgenerate.option.previews=true \
  -DfailIfNoTests=false

echo "✅ Done. Review the changes:"
echo "   git status docs/option-previews docs/mini-sheet-previews docs/MINI_SHEETS.md README.md"
