"""Headless screen tour of the simulator (Playwright + Chrome).

Serve the simulator first (it fetches avatar/default/manifest.json, so file:// will not work):
  cd simulator && python -m http.server 8765
Then run:
  python simulator/tools/screen_tour.py
Screenshots of the 540x220 display are written to /tmp/w_*.png. Page errors are printed.
Set CHROME to your Chrome/Chromium path if Playwright's bundled browser is not installed.
"""
import os
import asyncio
from playwright.async_api import async_playwright
async def main():
    async with async_playwright() as p:
        b=await p.chromium.launch(**({'executable_path':os.environ['CHROME']} if os.environ.get('CHROME') else {}))
        pg=await b.new_page(viewport={'width':1500,'height':1000})
        errs=[]; pg.on('pageerror',lambda e:errs.append(str(e)))
        await pg.goto('http://localhost:8765/index.html'); await pg.wait_for_timeout(2000)
        n=[0]
        async def shot(name):
            n[0]+=1; el=await pg.query_selector('#scr'); await el.screenshot(path=f'/tmp/w_{n[0]:02d}_{name}.png')
        async def key(k,hold=60):
            await pg.keyboard.down(k); await pg.wait_for_timeout(hold); await pg.keyboard.up(k); await pg.wait_for_timeout(160)
        async def home(): await key('Backspace',700)
        await shot('home')
        await key('ArrowDown'); await key('ArrowDown'); await pg.wait_for_timeout(1500); await shot('home_np')
        # talk to the assistant from home via L2
        await pg.keyboard.down(' '); await pg.wait_for_timeout(500); await shot('joy_listen'); await pg.keyboard.up(' '); await pg.wait_for_timeout(3600); await shot('joy_ans')
        await key('u'); await pg.keyboard.down(' '); await pg.wait_for_timeout(400); await shot('quiet_listen'); await pg.keyboard.up(' '); await pg.wait_for_timeout(800); await shot('quiet_wait'); await pg.wait_for_timeout(2600); await shot('quiet_back')
        await key('i'); await shot('joy_menu'); await home()
        # beeper thread actions
        await key('s'); await key('ArrowRight'); await key('Enter'); await key('Enter'); await shot('thread'); await key('i'); await shot('msgact'); await key('ArrowDown'); await key('Enter'); await shot('reply')
        await pg.keyboard.down(' '); await pg.wait_for_timeout(400); await pg.keyboard.up(' '); await pg.wait_for_timeout(1300); await shot('reply_prev'); await key('Enter'); await shot('reply_sent')
        await key('ArrowUp'); await key('i'); await key('Enter'); await shot('react'); await home()
        # t3
        await key('s'); for_=None
        for k in ['ArrowRight','ArrowRight','ArrowRight']: await key(k)
        await key('Enter'); await key('Enter'); await shot('t3_q')
        await key('ArrowRight'); await shot('t3_q_p2'); await key('Backspace')
        for _ in range(4): await key('ArrowDown')
        await key('Enter'); await shot('t3_done'); await key('i'); await shot('t3_hist'); await key('ArrowUp'); await key('ArrowUp'); await key('Enter'); await shot('t3_entry'); await key('Backspace'); await key('Backspace')
        await pg.keyboard.down(' '); await pg.wait_for_timeout(400); await pg.keyboard.up(' '); await pg.wait_for_timeout(1300); await key('Enter'); await pg.wait_for_timeout(200); await shot('t3_after_send'); await home()
        # notifications
        await pg.click('#sims button:nth-child(2)'); await key('e'); await shot('notifs'); await key('i'); await shot('notif_dismiss'); await home()
        # media spotify
        await key('o'); await key('i'); await shot('media_menu'); await key('ArrowDown'); await key('ArrowDown'); await key('ArrowDown'); await key('Enter'); await key('Enter'); await shot('spotify_np'); await key('i'); await shot('media_menu_spot'); await home()
        await key('o'); await key('i'); for_=None
        for _ in range(4): await key('ArrowDown')
        await key('Enter'); await key('Enter'); await shot('yt_list'); await home()
        # people quick
        await key('h'); await shot('pq'); await key('ArrowRight'); await shot('pq2'); await home()
        # health
        await key('s'); 
        for k in ['ArrowDown','ArrowRight','ArrowRight','ArrowRight','ArrowRight']: await key(k)
        await key('Enter'); await shot('health'); await key('ArrowDown'); await key('Enter'); await shot('hd'); await key('ArrowRight'); await shot('hd30'); await key('ArrowDown'); await shot('hd_bd'); await home()
        # memo
        await key('s'); await key('ArrowRight'); await key('ArrowRight'); await key('ArrowRight'); await key('ArrowRight'); await key('ArrowRight'); await key('ArrowRight'); await key('ArrowRight'); await key('ArrowRight'); await key('ArrowRight'); await key('ArrowRight'); await key('ArrowRight'); await key('ArrowRight'); await key('Enter'); await key(' '); await pg.wait_for_timeout(1200); await shot('memo_rec'); await key(' '); await pg.wait_for_timeout(1800); await shot('memo_saved'); await home()
        # ha + lock
        await key('s'); 
        for k in ['ArrowDown','ArrowRight','ArrowRight','ArrowRight','ArrowRight','ArrowRight']: await key(k)
        await key('Enter'); 
        for _ in range(7): await key('ArrowDown')
        await shot('ha'); await key('-'); await key('Enter'); await shot('locked'); await key('-'); await shot('unlocked')
        await pg.click('#bg button[data-v=video]'); await pg.wait_for_timeout(300); el=await pg.query_selector('#view'); await el.screenshot(path='/tmp/w_videohint.png')
        print('ERR',errs[:8]); await b.close()
asyncio.run(main())
