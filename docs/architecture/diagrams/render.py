#!/usr/bin/env python3
"""Render architecture HTML diagrams to PNG using Playwright."""
import asyncio
from playwright.async_api import async_playwright
from pathlib import Path

DIAGRAMS = [
    "01-system-overview.html",
    "02-module-architecture.html",
    "03-event-driven-flow.html",
    "04-cloudflare-deployment.html",
    "05-security-auth.html",
]

async def render(html_file: str):
    async with async_playwright() as p:
        browser = await p.chromium.launch()
        page = await browser.new_page(viewport={"width": 1500, "height": 800}, device_scale_factor=2)
        path = Path(html_file).resolve()
        await page.goto(f"file://{path}")
        # Wait for render
        await page.wait_for_load_state("networkidle")
        # Get actual content size
        canvas = await page.query_selector(".canvas")
        if canvas:
            box = await canvas.bounding_box()
            if box:
                w = int(box["width"]) + 80
                h = int(box["height"]) + 80
                await page.set_viewport_size({"width": w, "height": h})
                await page.wait_for_timeout(300)
        # Screenshot the canvas
        out = html_file.replace(".html", ".png")
        await page.screenshot(path=out, full_page=True, omit_background=False)
        print(f"✅ {out}")
        await browser.close()

async def main():
    for d in DIAGRAMS:
        try:
            await render(d)
        except Exception as e:
            print(f"❌ {d}: {e}")

asyncio.run(main())
