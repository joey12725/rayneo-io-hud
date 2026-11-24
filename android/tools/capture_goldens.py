"""Reproduce the approved simulator's 540x220 reference PNGs using Playwright Python.

Serve simulator/ with `python -m http.server 8765 --directory simulator`, then run
`python android/tools/capture_goldens.py`. Set CHROME to an installed Chromium path.
The Date/performance init script freezes all time before fixtures are constructed.
Canvas PNG export excludes CSS brightness and browser/device scaling.
SIMULATOR_URL also accepts a file:/// URI; local asset reads are enabled for that mode.
"""
import asyncio
import base64
import io
from PIL import Image
import json
import os
from pathlib import Path
from playwright.async_api import async_playwright

OUTPUT = Path(__file__).resolve().parents[1] / "app/src/test/resources/golden"
ONLY = set(filter(None, os.environ.get("GOLDEN_ONLY", "").split(",")))
UPDATE_COVERAGE = os.environ.get("UPDATE_GOLDEN_COVERAGE") == "1"
if UPDATE_COVERAGE and not ONLY:
    raise ValueError("Coverage changes require an explicit GOLDEN_ONLY list")
INIT = """
const RealDate=Date;
const epoch=new RealDate(2026,10,11,19,45).getTime();
window.Date=class extends RealDate {
  constructor(...args){super(...(args.length?args:[epoch]));}
  static now(){return epoch;}
};
Object.defineProperty(performance,'now',{value:()=>0,configurable:true});
window.setInterval=()=>0;
"""
CALL_CAPTURES = {
    "memo-recording-hud": "S.memo.rec=true;S.memo.t0=nowS()-42",
    "doc-reader": "push(docsV());topV().key('sel')",
    "calendar-event": "push(calV());topV().cur=1;topV().key('sel')",
    "scene-confirm": "push(haV());topV().cur=1;topV().key('sel')",
    "dictation-listening": "push(dictV({label:'Message to Sam'}));topV().listen()",
    "dictation-transcribing": "push(dictV({label:'Message to Sam'}));topV().st='trans'",
    "dictation-enhancing": "push(dictV({label:'Message to Sam'}));topV().st='enh'",
    "dictation-preview": "push(dictV({label:'Message to Sam'}));topV().st='preview';topV().text=\"I'll bring the speaker. Meet you at the park at seven thirty.\"",
    "dictation-pages": "push(dictV({label:'Message to Sam'}));topV().st='preview';topV().text='A long dictated message. '.repeat(20)",

    "apps-page-2": "push(appsV()); topV().i=8",
    "joy-menu": "push(joyMenuV())",
    "beeper-actions": "push(msgActions(chats[0],chats[0].msgs[2]))",
    "beeper-message": "push(msgV(chats[0],chats[0].msgs[2]))",
    "beeper-reply": "push(replyV(chats[0],chats[0].msgs[2]))",
    "beeper-priority": "push(beeperV(PRIORITY_CHATS))",
    "beeper-quiet": "push(beeperV(PRIORITY_CHATS)); topV().cur=5; topV().key('sel'); topV().key('down')",
    "beeper-menu": "push(beeperV(PRIORITY_CHATS)); topV().cur=1; topV().key('act')",
    "t3-choice": "push(t3ThreadV(T3.threads[0]))",
    "t3-approval-deny": "push(t3ThreadV(T3.threads[1])); topV().key('right'); topV().key('right'); topV().cur=2",
    "t3-multi": "push(t3ThreadV(T3.threads[2]))",
    "t3-reader": "push(t3ThreadV(T3.threads[4]))",
    "t3-actions": "push(t3ActionsV(T3.threads[4]))",
    "t3-environments": "push(envV())",
    "t3-new": "push(newThreadV())",
    "t3-usage-more": "push(usageV()); topV().key('down')",
    "audiobooks": "push(absV())",
    "spotify-playing": "M.src='spotify';M.title='Midnight City';M.artist='M83';push(spotV())",
    "media-menu": "push(mediaMenuV())",
    "media-speed": "push(speedV())",
    "media-devices": "push(devV())",
    "people": "push(peopleV())",
    "health-more": "push(healthV()); topV().cur=3",
    "health-breakdown": "push(healthDetailV(HEALTH[1])); topV().key('down')",
    "health-breakdown-more": "push(healthDetailV(HEALTH[1])); topV().key('down'); topV().key('down')",
    "home-assistant": "push(haV())",
    "docs": "push(docsV())",
    "teleprompter": "push(prompterV())",
    "voice-memo": "push(memoV())",
    "status": "push(statusV())",
    "settings": "push(settingsV())",
    "app-order": "push(orderV())",
    "shortcuts": "push(shortcutsV())",
    "shortcuts-last": "push(shortcutsV());topV().cur=5",
    "shortcut-shoulder": "push(shortcutActionsV('r.hold','Hold R'))",
    "shortcut-actions": "push(shortcutActionsV('a.hold','Hold A'))",

    "joy-thinking-reply": "push(joyV()); joy.status='thinking'; joy.anim='thinking'; joy.prompt='New question'",
    "report-continuation": "REPORT[0].b=Array.from({length:80},(_,i)=>'word'+(i+1)).join(' '); push(reportV()); topV().key('down')",
    "reminders-empty": "ARMED_REMINDERS=[]; push(remindersV())",
    "reminders": "ARMED_REMINDERS=[{text:'Ask how the game went',condition:'person: Sam Rivera',expires:'Dec 11, 7:45 PM'}]; push(remindersV())",
    "joy-call-listening": "push(callV()); topV().layout='full'; topV().state='listening'",
    "joy-call-thinking": "push(callV()); topV().layout='full'; topV().state='thinking'",
    "joy-call-speaking": "push(callV()); topV().layout='full'; topV().state='speaking'",
    "joy-call-muted": "push(callV()); topV().layout='full'; topV().state='listening'; topV().muted=true",
    "joy-call-turns": "push(callV()); topV().mode='turns'; topV().state='listening'",
    "joy-call-worker": "push(callV()); topV().layout='full'; topV().state='listening'; topV().worker={state:'tool',tool:'web_search',task:'weather in Springfield'}",
    "joy-call-compact-listening": "push(callV()); topV().state='listening'",
    "joy-call-compact-speaking": "push(callV()); topV().state='speaking'",
    "joy-call-compact-muted": "push(callV()); topV().state='listening'; topV().muted=true",
    "joy-call-compact-worker": "push(callV()); topV().state='listening'; topV().worker={state:'tool',tool:'web_search',task:'weather in Springfield'}",
    "joy-call-compact-reconnecting": "push(callV()); topV().state='reconnecting'",
    "youtube": "push(ytV())",
    "call-incoming": "phoneIncoming()",
    "call-history": "phoneIncoming(); phoneRemoteEnd(); push(phoneHistoryV())",
    "call-active": "phoneIncoming(); topV().key('sel')",
    "call-held": "phoneIncoming(); topV().key('sel'); PHONE.calls[0].phase='Holding'",
    "call-audio": "phoneIncoming(); topV().key('sel'); topV().key('act')",
    "call-waiting": "phoneIncoming(); topV().key('sel'); phoneIncoming('Alex Chen', '(773) 555-0188')",
    "call-dialing": "push(phoneRootV(0)); topV().cur=1; topV().key('sel'); topV().key('sel')",
    "phone-favorites": "push(phoneRootV(0))",
    "phone-recents": "push(phoneRootV(1))",
    "phone-contacts": "push(phoneRootV(2))",
    "phone-numbers": "push(phoneNumbersV(PHONEBOOK.contacts[1]))",
    "phone-search": "push(phoneSearchV([{name:'Dad',number:'(630) 555-0102',label:'Mobile'},{name:'Dana Park',number:'(872) 555-0133',label:'Mobile'}]))",
    "phone-confirm": "push(phoneConfirmV({name:'Mom',number:'(630) 555-0101',label:'Mobile'}))",
    "clock": "clockDemo(); push(clockHubV())",
    "clock-timer": "clockDemo(); push(timerV())",
    "clock-timer-new": "clockReset(); push(timerV())",
    "clock-stopwatch": "clockDemo(); push(stopwatchV())",
    "clock-alarms": "clockDemo(); push(alarmsV())",
    "clock-alarm-set": "clockDemo(); push(alarmSetV('a2'))",
    "clock-ringing": "clockReset(); clockRing('timer')",
    "clock-ringing-alarm": "clockReset(); clockRing('alarm')",
    "clock-settings": "push(clockSettingsV())",
}
CAPTURES = {
    "home": [], "apps": ["s"], "joy": ["s", "Enter"],
    "beeper-list": ["Enter"], "beeper-thread": ["Enter", "Enter"],
    "react-picker": ["Enter", "Enter", "i", "Enter"], "notifications": ["e"],
    "t3-root": ["ArrowDown", "Enter"],
    "t3-working": ["ArrowDown", "Enter"] + ["ArrowDown"] * 6 + ["Enter"],
    "t3-approval": ["ArrowDown", "Enter", "ArrowDown", "Enter"],
    "t3-usage": ["ArrowDown", "Enter"] + ["ArrowDown"] * 10 + ["Enter"],
    "now-playing": ["o"],
    "health": ["s"] + ["ArrowRight"] * 8 + ["Enter"],
    "health-detail": ["s"] + ["ArrowRight"] * 8 + ["Enter", "Enter"],
    "morning-report": ["s"] + ["ArrowRight"] * 7 + ["Enter"],
    "people-card": ["h"], "calendar": ["s"] + ["ArrowRight"] * 4 + ["Enter"],
}

async def capture(page, name):
    await page.select_option("#grey-levels", "16")
    encoded = await page.evaluate("render(); cv.toDataURL('image/png').split(',')[1]")
    png = base64.b64decode(encoded)
    frame = Image.open(io.BytesIO(png)).convert("RGB")
    green = [p[1] for p in frame.getdata()]
    palette = await page.evaluate("[0,PRIMARY*17,SECONDARY*17,TERTIARY*17]")
    assert set(green) <= set(palette), (name, set(green))
    coverage_path = OUTPUT.parent / "golden-1bit" / f"{name}.png"
    if UPDATE_COVERAGE:
        coverage = Image.new("RGB", frame.size)
        coverage.putdata([(0, 255 if v else 0, 0) for v in green])
        coverage.save(coverage_path)
    previous = Image.open(coverage_path).convert("RGB")
    assert frame.size == previous.size == (540, 220)
    assert [v > 0 for v in green] == [p[1] >= 100 for p in previous.getdata()], f"{name}: 1-bit coverage changed"
    await page.select_option("#grey-levels", "2")
    mono = await page.evaluate("Array.from(g.getImageData(0,0,W,H).data).filter((v,i)=>i%4===1)")
    assert mono == [255 if v else 0 for v in green], f"{name}: selector lost pixels"
    await page.select_option("#grey-levels", "16")
    (OUTPUT / f"{name}.png").write_bytes(png)


async def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    chrome = os.environ.get("CHROME", "")  # empty: Playwright's bundled Chromium
    async with async_playwright() as p:
        browser = await p.chromium.launch(args=(["--allow-file-access-from-files"] if os.environ.get("SIMULATOR_URL", "").startswith("file:") else []), **({"executable_path": chrome} if chrome and Path(chrome).exists() else {}))
        context = await browser.new_context(viewport={"width": 1500, "height": 1000}, timezone_id="America/Chicago", device_scale_factor=1)
        await context.add_init_script(INIT)
        page = await context.new_page()
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        await page.goto(os.environ.get("SIMULATOR_URL", "http://localhost:8765/index.html"))
        await page.wait_for_function("AVATAR.pack && AVATAR.atlas")
        await page.evaluate("""() => {
            window.railAudit = {};
            const original = frame;
            frame = function(title, ic, t, opts = {}) {
                for (const text of [].concat(opts.railText || [])) {
                    const short = railCaption(text);
                    const lines = layout(short, RAIL - 4, 's');
                    if (String(text).split(/\\s+/).some(word => tw(word, 's') > RAIL - 4 && !railCaption(word)))
                        throw new Error('Missing short rail label: ' + text);
                    railAudit[text] = {short, lines: lines.map(l => l.map(t => t.w || t.e).join(' '))};
                }
                return original(title, ic, t, opts);
            };
        }""")
        await page.evaluate("CFG.zoom='1'; sizeScreen(); M.pos=.34;")
        for name, keys in CAPTURES.items():
            if ONLY and name not in ONLY:
                continue
            await page.evaluate("goHome()")
            for key in keys:
                await page.keyboard.down(key)
                await page.keyboard.up(key)
            await capture(page, name)
            print(name, await page.evaluate("topV().id"))
        media = await page.evaluate("({...M})")
        for name, setup in CALL_CAPTURES.items():
            if ONLY and name not in ONLY:
                continue
            await page.evaluate("value=>Object.assign(M,value)", media)
            await page.evaluate("PHONE.calls=[]; PHONE.saved=null; S.notifs=S.notifs.filter(n=>n.ic!=='phone'); S.locked=false; S.memo.rec=false; goHome(); " + setup)
            await capture(page, name)
            print(name, await page.evaluate("topV().id"))
        # Audit captions beyond the photographed first pages and playback states.
        await page.evaluate("""() => {
            for (let i = 0; i < REPORT.length; i++) { const v = reportV(); v.i = i; v.draw(0); }
            for (const m of HEALTH) healthDetailV(m).draw(0);
            const memo = memoV(); S.memo.rec = true; memo.draw(0); S.memo.rec = false;
            const prompter = prompterV(); prompter.auto = true; prompter.draw(0);
            for (const src of ['abs', 'spotify', 'yt']) {
                M.src = src; M.playing = false; nowPlayingV().draw(0);
            }
        }""")
        audit_path = OUTPUT.parents[3] / "build/rail-caption-audit.json"
        audit_path.parent.mkdir(parents=True, exist_ok=True)
        audit_path.write_text(json.dumps(await page.evaluate("railAudit"), indent=2), encoding="utf-8")
        print("Rail captions audited:", audit_path)
        await browser.close()
        if errors:
            raise RuntimeError(errors)

if __name__ == "__main__":
    asyncio.run(main())
