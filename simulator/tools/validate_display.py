"""Check native display geometry, identity hashes, modes, and pagination.

Run from the repo root: python simulator/tools/validate_display.py
Uses the same frozen browser setup as android/tools/capture_goldens.py.
With --capture, also regenerate the two UI logic regression bitmap fixtures.
"""
import asyncio
import importlib.util
import os
import sys
import re
from hashlib import sha256
from pathlib import Path
from playwright.async_api import async_playwright

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("goldens", ROOT / "android/tools/capture_goldens.py")
goldens = importlib.util.module_from_spec(spec)
spec.loader.exec_module(goldens)


async def validate():
    chrome = os.environ.get("CHROME", "")  # empty: Playwright's bundled Chromium
    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch(args=["--allow-file-access-from-files"],
            **({"executable_path": chrome} if chrome and Path(chrome).exists() else {}))
        try:
            context = await browser.new_context(timezone_id="America/Chicago")
            await context.add_init_script(goldens.INIT)
            page = await context.new_page()
            errors = []
            page.on("pageerror", lambda error: errors.append(str(error)))
            await page.goto((ROOT / "simulator/index.html").as_uri())
            await page.wait_for_function("AVATAR.pack && AVATAR.atlas")
            for name, setup in {"home": "goHome()", "joy": "push(joyV())",
                    "t3-approval": "push(t3ThreadV(T3.threads[1]))"}.items():
                await page.evaluate(setup + ";render()")
                master = await page.evaluate("cv.toDataURL()")
                assert await page.evaluate("[W,H,cv.width,cv.height]") == [540,220,540,220]
                assert await page.locator("#display-mode option").count() == 2
                panel = await page.evaluate("Array.from(g.getImageData(0,0,W,H).data).filter((v,i)=>i%4===1)")
                expected = (ROOT / f"android/app/src/test/resources/rastergolden/{name}.sha256").read_text().splitlines()[0]
                assert sha256(bytes(panel)).hexdigest() == expected, name
                tones = await page.evaluate("[PRIMARY,SECONDARY,TERTIARY]")
                kotlin = (ROOT / "android/app/src/main/java/io/lampyr/glasses/render/Tone.kt").read_text()
                assert tones == [int(re.search(r"const val " + token + r" = (\d+)", kotlin)[1]) for token in ("PRIMARY", "SECONDARY", "TERTIARY")]
                assert set(panel) <= {0, *(t * 17 for t in tones)}
                await page.select_option("#grey-levels", "2")
                mono = await page.evaluate("Array.from(g.getImageData(0,0,W,H).data).filter((v,i)=>i%4===1)")
                assert mono == [255 if v else 0 for v in panel]
                await page.select_option("#grey-levels", "16")
                assert await page.evaluate("Math.abs(parseFloat(cv.style.width)/parseFloat(cv.style.height)-W/H)<.001")
                await page.select_option("#display-mode", "text")
                assert await page.locator("#text-note").is_visible()
                assert await page.locator("#text-note a").get_attribute("href") == "../android/README.md"
                await page.select_option("#display-mode", "design")
                assert await page.locator("#scr").is_visible()
                assert await page.evaluate("cv.toDataURL()") == master, "Mode changed design pixels"
                print(name, "native identity hash, flat tiers, 1-bit fallback, token parity, aspect ratio and controls: PASS")
            # The long report body reaches a second page without changing the card dot.
            await page.evaluate("REPORT[0].b=Array.from({length:80},(_,i)=>'word'+(i+1)).join(' ');goHome();push(reportV())")
            assert await page.evaluate("pages(REPORT[0].b,CW-12,Math.floor((BOTTOM-TOP-46)/LH('l')),'l').length") > 1
            await page.evaluate("topV().key('down');render()")
            assert await page.evaluate("topV().i===0 && topV().page===1")
            await page.evaluate("topV().key('right');render()")
            assert await page.evaluate("topV().i===1 && topV().page===0")
            # Every option remains selectable in the shorter lists; no zero-row focus traps.
            await page.evaluate("goHome();push(t3ThreadV(T3.threads[1]))")
            for _ in range(2):
                await page.evaluate("topV().key('down');render();topV().fix()")
            # The unchanged 68 px approval viewport holds two 34 px rows.
            assert await page.evaluate("topV().cur===2 && topV().top===1")
            await page.evaluate("goHome();push(usageV());topV().key('right');render()")
            assert await page.evaluate("topV().p===0 && topV().wp===1")
            await page.evaluate("topV().key('right');render()")
            assert await page.evaluate("topV().p===1 && topV().wp===0")
            await page.evaluate("goHome();push(healthDetailV(HEALTH[1]));topV().key('down');topV().key('down');render()")
            assert await page.evaluate("topV().page===2")
            # All avatar frames preserve their aspect ratio and produce strictly binary pixels.
            assert await page.evaluate("Object.values(AVATAR.pack.states).every(c=>c.frames.every(f=>{const im=avatarPooled(f.cell,128,192);return im.width===128&&im.height===192}))")
            assert not errors, errors
        finally:
            await browser.close()


if __name__ == "__main__":
    asyncio.run(validate())
    if "--capture" in sys.argv:
        goldens.CALL_CAPTURES.update({
            "report-continuation": "REPORT[0].b=Array.from({length:80},(_,i)=>'word'+(i+1)).join(' ');push(reportV());topV().key('down')",
            "joy-thinking-reply": "joy.prompt='New question';setJoy('thinking');push(joyV())",
        })
        os.environ.setdefault("SIMULATOR_URL", (ROOT / "simulator/index.html").as_uri())
        asyncio.run(goldens.main())
